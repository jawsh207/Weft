#!/usr/bin/env python3
"""
Build an Weft dictionary (.ekd) from open word-frequency sources.

Inputs (all optional, but at least one frequency source is required):

  --aosp FILE        AOSP LatinIME ".combined" word list (.gz ok). Base frequencies,
                     offensive-word flags and bigrams. Apache 2.0.
  --subtitles FILE   "word count" list, e.g. Hermit Dave's OpenSubtitles
                     FrequencyWords (en_full.txt). Casual-language tuning.
                     Data: CC BY-SA 4.0.
  --leipzig FILE     Leipzig Corpora Collection archive (.tar.gz) or extracted
                     folder. Its -words.txt and -co_n.txt files feed next-word
                     (bigram) data.
  --allow FILE       Allow list (repeatable): one word per line, or a Hunspell
                     .dic file. Words that come only from --subtitles must appear
                     here (or in AOSP) to be kept, which filters out junk.
                     SCOWL or Wiktionary-derived lists work well.
  --block FILE       Words to drop entirely (repeatable).

Only the Python standard library is used. Output is deterministic for the same
inputs, which keeps F-Droid builds reproducible.

Binary format (little endian), version 1:
  "EKD1" | u32 version | u32 nodeCount | u32 bigramWords | u32 bigramEntries
  | u32 metaLength | meta (UTF-8 JSON, padded to 4 bytes)
  | i32 chars[nodeCount] | i32 firstChild[nodeCount] | i32 parent[nodeCount]
  | i32 bigramWord[bigramWords] | i32 bigramStart[bigramWords + 1]
  | i32 bigramNext[bigramEntries]
  | u16 childCount[nodeCount]
  | u8 freq[nodeCount] | u8 maxSub[nodeCount] | u8 flags[nodeCount]
  | u8 bigramFreq[bigramEntries]

Nodes are laid out breadth-first, so each node's children are contiguous and
sorted by code point. Node 0 is the root. freq > 0 marks the end of a word;
a word's id is its terminal node index.
"""

from __future__ import annotations

import argparse
import array
import gzip
import io
import json
import math
import sys
import tarfile
import time
import unicodedata
from collections import deque
from pathlib import Path

FORMAT_VERSION = 1
FLAG_OFFENSIVE = 0x01
MAX_WORD_LEN = 32
APOSTROPHES = {"’": "'", "ʼ": "'", "‘": "'"}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def open_text(path: Path):
    """Open a UTF-8 text file, transparently handling .gz."""
    if path.suffix == ".gz":
        return io.TextIOWrapper(gzip.open(path, "rb"), encoding="utf-8", errors="replace")
    return open(path, encoding="utf-8", errors="replace")


def normalize(word: str) -> str:
    word = unicodedata.normalize("NFC", word.strip())
    for k, v in APOSTROPHES.items():
        word = word.replace(k, v)
    return word


def is_valid_word(word: str) -> bool:
    if not word or len(word) > MAX_WORD_LEN:
        return False
    if word[0] in "'-" or word[-1] == "-":
        return False
    has_letter = False
    for ch in word:
        cat = unicodedata.category(ch)
        if cat[0] == "L":
            has_letter = True
        elif cat[0] == "M" or ch in "'-":
            continue
        else:
            return False
    return has_letter


def log_scale(count: float, max_count: float) -> int:
    """Map a raw count to 1..255 on a log scale (13 steps per doubling)."""
    if count <= 0 or max_count <= 0:
        return 0
    value = 255 + 13 * math.log2(count / max_count)
    return max(1, min(255, round(value)))


class Entry:
    __slots__ = ("word", "aosp", "sub", "offensive", "not_a_word", "bigrams")

    def __init__(self, word: str):
        self.word = word
        self.aosp: int | None = None
        self.sub: int | None = None
        self.offensive = False
        self.not_a_word = False
        self.bigrams: dict[str, int] = {}


# ---------------------------------------------------------------------------
# Source readers
# ---------------------------------------------------------------------------


def parse_attrs(line: str) -> dict[str, str]:
    attrs = {}
    for part in line.strip().split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            attrs[k.strip()] = v
    return attrs


def read_aosp(path: Path, entries: dict[str, Entry]) -> dict:
    """Read an AOSP .combined word list. Returns header attributes."""
    header: dict[str, str] = {}
    current: Entry | None = None
    raw_bigrams: list[tuple[Entry, str, int]] = []
    with open_text(path) as f:
        for line in f:
            if not line.strip():
                continue
            if line.startswith("dictionary="):
                header = parse_attrs(line)
                continue
            if line.startswith(" word="):
                attrs = parse_attrs(line)
                word = normalize(attrs.get("word", ""))
                current = None
                if not is_valid_word(word):
                    continue
                freq = int(attrs.get("f", "0") or 0)
                e = entries.get(word) or Entry(word)
                entries[word] = e
                e.aosp = max(e.aosp or 0, freq)
                e.offensive |= attrs.get("possibly_offensive") == "true"
                e.not_a_word |= attrs.get("not_a_word") == "true"
                current = e
            elif line.startswith("  bigram=") and current is not None:
                attrs = parse_attrs(line)
                nxt = normalize(attrs.get("bigram", ""))
                if is_valid_word(nxt):
                    raw_bigrams.append((current, nxt, int(attrs.get("f", "0") or 0)))
    # AOSP bigram frequencies are either 0..15 or 0..255 depending on vintage.
    scale = 17 if raw_bigrams and max(b[2] for b in raw_bigrams) <= 15 else 1
    for e, nxt, f in raw_bigrams:
        e.bigrams[nxt] = max(e.bigrams.get(nxt, 0), min(255, f * scale))
    return header


def read_counts(path: Path) -> dict[str, int]:
    """Read a 'word count' list (FrequencyWords format)."""
    counts: dict[str, int] = {}
    with open_text(path) as f:
        for line in f:
            parts = line.rsplit(None, 1)
            if len(parts) != 2:
                continue
            word, count = normalize(parts[0]), parts[1]
            if not count.isdigit() or not is_valid_word(word):
                continue
            counts[word] = counts.get(word, 0) + int(count)
    return counts


def read_word_set(path: Path) -> set[str]:
    """One word per line, or a Hunspell .dic (first line is a count, 'word/FLAGS')."""
    words = set()
    with open_text(path) as f:
        for i, line in enumerate(f):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if i == 0 and line.isdigit():
                continue
            word = normalize(line.split("/", 1)[0].split("\t", 1)[0])
            if is_valid_word(word):
                words.add(word)
    return words


def read_leipzig(path: Path) -> tuple[dict[int, tuple[str, int]], list[tuple[int, int, int]]]:
    """Read Leipzig -words.txt (id, word, freq) and -co_n.txt (id1, id2, freq, sig)."""

    def lines_of(name_suffix: str):
        if path.is_dir():
            files = sorted(path.glob(f"*{name_suffix}"))
            if not files:
                raise SystemExit(f"No *{name_suffix} in {path}")
            with open_text(files[0]) as f:
                yield from f
        else:
            with tarfile.open(path) as tar:
                member = next((m for m in tar.getmembers() if m.name.endswith(name_suffix)), None)
                if member is None:
                    raise SystemExit(f"No *{name_suffix} in {path}")
                with io.TextIOWrapper(tar.extractfile(member), encoding="utf-8", errors="replace") as f:
                    yield from f

    words: dict[int, tuple[str, int]] = {}
    for line in lines_of("-words.txt"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3 and parts[0].isdigit() and parts[2].isdigit():
            words[int(parts[0])] = (normalize(parts[1]), int(parts[2]))
    pairs: list[tuple[int, int, int]] = []
    for line in lines_of("-co_n.txt"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3 and parts[0].isdigit() and parts[1].isdigit() and parts[2].isdigit():
            pairs.append((int(parts[0]), int(parts[1]), int(parts[2])))
    return words, pairs


# ---------------------------------------------------------------------------
# Merge
# ---------------------------------------------------------------------------


class Resolver:
    """Maps any spelling to the entry it should merge into (case-insensitively)."""

    def __init__(self, entries: dict[str, Entry]):
        self.entries = entries
        self.by_lower: dict[str, list[str]] = {}
        for w in entries:
            self.by_lower.setdefault(w.lower(), []).append(w)

    def resolve(self, word: str) -> str | None:
        if word in self.entries:
            return word
        options = self.by_lower.get(word.lower())
        if not options:
            return None
        lower = word.lower()
        if lower in options:
            return lower
        return max(options, key=lambda w: (self.entries[w].aosp or 0, w))

    def add(self, word: str) -> Entry:
        e = Entry(word)
        self.entries[word] = e
        self.by_lower.setdefault(word.lower(), []).append(word)
        return e


def merge(args) -> tuple[dict[str, Entry], list[dict], dict]:
    entries: dict[str, Entry] = {}
    sources: list[dict] = []
    header: dict = {}

    if args.aosp:
        header = read_aosp(Path(args.aosp), entries)
        sources.append({
            "name": "AOSP LatinIME word list",
            "license": "Apache-2.0",
            "url": "https://android.googlesource.com/platform/packages/inputmethods/LatinIME/",
        })
        print(f"  AOSP: {len(entries):,} words", file=sys.stderr)

    allow: set[str] = set()
    for a in args.allow or []:
        allow |= read_word_set(Path(a))
    allow_lower = {w.lower() for w in allow}
    block_lower: set[str] = set()
    for b in args.block or []:
        block_lower |= {w.lower() for w in read_word_set(Path(b))}

    resolver = Resolver(entries)

    if args.subtitles:
        counts = read_counts(Path(args.subtitles))
        max_count = max(counts.values(), default=1)
        added = 0
        for word, count in counts.items():
            if count < args.min_count:
                continue
            target = resolver.resolve(word)
            if target is None:
                low = word.lower()
                allowed = low in allow_lower if allow else True
                if not allowed or (len(word) == 1 and not allow):
                    continue
                # Prefer the allow list's casing (e.g. "Monday"), else as given.
                cased = next((w for w in (word, word.capitalize(), word.upper()) if w in allow), word)
                e = resolver.add(cased)
                added += 1
            else:
                e = entries[target]
            e.sub = max(e.sub or 0, log_scale(count, max_count))
        sources.append({
            "name": "OpenSubtitles word frequencies (Hermit Dave, FrequencyWords)",
            "license": "CC-BY-SA-4.0",
            "url": "https://github.com/hermitdave/FrequencyWords",
        })
        print(f"  Subtitles: {len(counts):,} words read, {added:,} new", file=sys.stderr)

    if args.leipzig:
        words, pairs = read_leipzig(Path(args.leipzig))
        per_first: dict[str, dict[str, int]] = {}
        first_totals: dict[str, int] = {}
        for id1, id2, count in pairs:
            a, b = words.get(id1), words.get(id2)
            if not a or not b:
                continue
            ra, rb = resolver.resolve(a[0]), resolver.resolve(b[0])
            if ra and rb:
                d = per_first.setdefault(ra, {})
                d[rb] = d.get(rb, 0) + count
                first_totals.setdefault(ra, 0)
        # Strength = P(next | first) on the same log scale as word frequencies,
        # so Leipzig pairs and AOSP pairs are comparable.
        for id_, (w, c) in words.items():
            r = resolver.resolve(w)
            if r in first_totals:
                first_totals[r] += c
        for first, nexts in per_first.items():
            total = max(first_totals.get(first, 0), sum(nexts.values()))
            e = entries[first]
            for nxt, count in nexts.items():
                bf = log_scale(count, total)
                e.bigrams[nxt] = max(e.bigrams.get(nxt, 0), bf)
        sources.append({
            "name": "Leipzig Corpora Collection",
            "license": "CC-BY-4.0",
            "url": "https://wortschatz.uni-leipzig.de/en/download",
        })
        print(f"  Leipzig: {len(per_first):,} words with next-word data", file=sys.stderr)

    if allow:
        sources.append({"name": "Allow list(s): " + ", ".join(Path(a).name for a in args.allow),
                        "license": "see source", "url": ""})

    # Drop blocked and shortcut-only entries.
    for w in [w for w, e in entries.items() if e.not_a_word or w.lower() in block_lower]:
        del entries[w]

    return entries, sources, header


def final_frequency(e: Entry, casual_weight: float) -> int:
    if e.aosp is not None and e.sub is not None:
        f = casual_weight * e.sub + (1 - casual_weight) * e.aosp
    elif e.aosp is not None:
        f = e.aosp
    elif e.sub is not None:
        f = e.sub * 0.85  # unvetted by AOSP: rank a little lower
    else:
        f = 0
    return max(1, min(255, round(f)))


# ---------------------------------------------------------------------------
# Trie + binary writer
# ---------------------------------------------------------------------------


class Node:
    __slots__ = ("ch", "children", "freq", "flags", "id")

    def __init__(self, ch: int):
        self.ch = ch
        self.children: dict[int, Node] = {}
        self.freq = 0
        self.flags = 0
        self.id = -1


def i32(values) -> bytes:
    a = array.array("i", values)
    if sys.byteorder != "little":
        a.byteswap()
    return a.tobytes()


def u16(values) -> bytes:
    a = array.array("H", values)
    if sys.byteorder != "little":
        a.byteswap()
    return a.tobytes()


def write_dictionary(path: Path, words: dict[str, tuple[int, int]],
                     bigrams: dict[str, list[tuple[str, int]]], meta: dict) -> int:
    root = Node(0)
    for word in sorted(words):
        node = root
        for ch in word:
            cp = ord(ch)
            node = node.children.setdefault(cp, Node(cp))
        node.freq, node.flags = words[word]

    # Breadth-first layout: a node's children get consecutive ids.
    order: list[Node] = [root]
    root.id = 0
    first_child = [0]
    parent = [-1]
    queue = deque([root])
    while queue:
        node = queue.popleft()
        kids = [node.children[k] for k in sorted(node.children)]
        first_child[node.id] = len(order) if kids else 0
        for kid in kids:
            kid.id = len(order)
            order.append(kid)
            first_child.append(0)
            parent.append(node.id)
            queue.append(kid)

    n = len(order)
    chars = [nd.ch for nd in order]
    child_count = [len(nd.children) for nd in order]
    freq = bytes(nd.freq for nd in order)
    flags = bytes(nd.flags for nd in order)

    # Max frequency anywhere in each subtree, for best-first completion search.
    max_sub = [nd.freq for nd in order]
    for i in range(n - 1, 0, -1):
        p = parent[i]
        if max_sub[i] > max_sub[p]:
            max_sub[p] = max_sub[i]

    def node_of(word: str) -> int:
        node = root
        for ch in word:
            node = node.children[ord(ch)]
        return node.id

    bigram_word, bigram_start, bigram_next, bigram_freq = [], [], [], []
    for first_id, nexts in sorted((node_of(w), lst) for w, lst in bigrams.items() if lst):
        bigram_word.append(first_id)
        bigram_start.append(len(bigram_next))
        for nxt, bf in nexts:
            bigram_next.append(node_of(nxt))
            bigram_freq.append(bf)
    bigram_start.append(len(bigram_next))

    meta_bytes = json.dumps(meta, ensure_ascii=False, sort_keys=True).encode("utf-8")
    meta_bytes += b" " * (-len(meta_bytes) % 4)

    with open(path, "wb") as f:
        f.write(b"EKD1")
        f.write(i32([FORMAT_VERSION, n, len(bigram_word), len(bigram_next), len(meta_bytes)]))
        f.write(meta_bytes)
        f.write(i32(chars))
        f.write(i32(first_child))
        f.write(i32(parent))
        f.write(i32(bigram_word))
        f.write(i32(bigram_start))
        f.write(i32(bigram_next))
        f.write(u16(child_count))
        f.write(freq)
        f.write(bytes(max_sub))
        f.write(flags)
        f.write(bytes(bigram_freq))
    return n


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--locale", required=True, help="e.g. en_US")
    p.add_argument("--description", default=None, help="Human-readable name, e.g. 'English (US)'")
    p.add_argument("--aosp")
    p.add_argument("--subtitles")
    p.add_argument("--leipzig")
    p.add_argument("--allow", action="append")
    p.add_argument("--block", action="append")
    p.add_argument("--out", required=True, help="Output .ekd path")
    p.add_argument("--max-words", type=int, default=150_000)
    p.add_argument("--min-count", type=int, default=3, help="Ignore subtitle words seen fewer times")
    p.add_argument("--casual-weight", type=float, default=0.4,
                   help="0..1: how much subtitle (casual) frequency counts vs AOSP")
    p.add_argument("--bigrams-per-word", type=int, default=12)
    p.add_argument("--date", type=int, default=None,
                   help="Version stamp (default: SOURCE_DATE_EPOCH or now)")
    args = p.parse_args()

    if not (args.aosp or args.subtitles):
        p.error("need at least one of --aosp or --subtitles")

    print(f"Building {args.locale}…", file=sys.stderr)
    entries, sources, header = merge(args)

    ranked = sorted(entries.values(), key=lambda e: (-final_frequency(e, args.casual_weight), e.word))
    kept = ranked[: args.max_words]
    words = {
        e.word: (final_frequency(e, args.casual_weight), FLAG_OFFENSIVE if e.offensive else 0)
        for e in kept
    }

    bigrams: dict[str, list[tuple[str, int]]] = {}
    for e in kept:
        nexts = sorted(((w, f) for w, f in e.bigrams.items() if w in words and f > 0),
                       key=lambda x: (-x[1], x[0]))
        if nexts:
            bigrams[e.word] = nexts[: args.bigrams_per_word]

    import os
    date = args.date or int(os.environ.get("SOURCE_DATE_EPOCH", time.time()))
    meta = {
        "locale": args.locale,
        "description": args.description or header.get("description") or args.locale,
        "date": date,
        "wordCount": len(words),
        "sources": sources,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    nodes = write_dictionary(out, words, bigrams, meta)
    size = out.stat().st_size
    print(f"Wrote {out}: {len(words):,} words, {nodes:,} nodes, "
          f"{sum(len(v) for v in bigrams.values()):,} bigrams, {size / 1e6:.1f} MB", file=sys.stderr)


if __name__ == "__main__":
    main()
