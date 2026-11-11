#!/usr/bin/env python3
"""
Build an Weft dictionary (.ekd) from open word-frequency sources.

Inputs (all optional, but at least one frequency source is required):

  --aosp FILE        AOSP LatinIME ".combined" word list (.gz ok). Base frequencies,
                     offensive-word flags and bigrams. Apache 2.0.
  --subtitles FILE   "word count" list, e.g. Hermit Dave's OpenSubtitles
                     FrequencyWords (en_full.txt). Casual-language tuning.
                     Data: CC BY-SA 4.0, so the shipped dictionaries don't use
                     it; they use the next option instead.
  --casual-from-sentences
                     Count casual frequencies from --tatoeba and --sentences
                     (see README, "Casual frequencies without subtitles").
  --spoken FILE      Words written sentences under-count (fillers); boosted.
  --leipzig FILE     Leipzig Corpora Collection archive (.tar.gz) or extracted
                     folder. Its -words.txt and -co_n.txt files feed next-word
                     (bigram) data.
  --allow FILE       Allow list (repeatable): one word per line, or a Hunspell
                     .dic file. Words that come only from --subtitles must appear
                     here (or in AOSP) to be kept, which filters out junk.
                     SCOWL or Wiktionary-derived lists work well.
  --casual FILE      Words to include exactly as written even if spell-check
                     lists lack them: texting words ("lol", "idk"), lowercase
                     "ok". Ranked by subtitle frequency when available.
  --block FILE       Words to drop entirely (repeatable).
  --tatoeba FILE     Tatoeba sentence export (id, lang, text; .bz2 ok). Everyday,
                     conversational sentences for next-word pairs and three-word
                     context. Sentences whose id is a multiple of 50 are held
                     out for the benchmark (tools/bench). CC BY 2.0 FR.
                     The Leipzig archive's -sentences.txt is used the same way.
  --sentences NAME|LICENSE|URL|FILE
                     More plain-text sentences, one per line, for word pairs and
                     three-word context (repeatable), e.g. Common Voice (CC0).
                     Any that match a held-out Tatoeba sentence are skipped, so
                     the benchmark never sees its own test data.
  --names FILE       Names and places (tools/dictgen/fetch_names.py, from
                     Wikidata, CC0): label, kind, rank. Added with their
                     capitals, without displacing common lowercase words.
  --variants FILE    US → regional spellings (sources/en_variants.tsv, from ESDB).
  --variant-out R=PATH
                     Also write the dictionary for region R (GB, CA, AU) to PATH,
                     respelled with --variants (repeatable). PATH may end in .gz.

Only the Python standard library is used. Output is deterministic for the same
inputs, which keeps F-Droid builds reproducible.

Binary format (little endian), version 3:
  "EKD1" | u32 version | u32 nodeCount | u32 bigramWords | u32 bigramEntries
  | u32 trigramWords | u32 trigramContexts | u32 trigramEntries | u32 layout
  | u32 metaLength | meta (UTF-8 JSON, padded to 4 bytes)
  then sections, each padded to 4 bytes:
  i32 firstChild[nodeCount]                 (non-decreasing; see below)
  i32 bigramWord[bigramWords] | i32 bigramStart[bigramWords + 1] | i32 bigramNext[bigramEntries]
  i32 trigramWord[trigramWords] | i32 trigramWordStart[trigramWords + 1]   (first words)
  i32 trigramSecond[trigramContexts] | i32 trigramStart[trigramContexts + 1]
  i32 trigramNext[trigramEntries]
  chars[nodeCount]       u8, u16 or i32 (layout bits 0-1: 0, 1, 2)
  childCount[nodeCount]  u8 or u16 (layout bit 2)
  u8 freq[nodeCount] | u8 maxSub[nodeCount] | u8 flags[nodeCount]
  u8 bigramFreq[bigramEntries] | u8 trigramFreq[trigramEntries]

There's no parent array: a leaf's firstChild is where its children would have
started, so firstChild never decreases and a node's parent is the last node
whose firstChild is <= it (binary search). Versions 1 and 2 (an i32 parent
array, i32 chars, u16 child counts, ungrouped trigrams) are still readable.

Nodes are laid out breadth-first, so each node's children are contiguous and
sorted by code point. Node 0 is the root. freq > 0 marks the end of a word;
a word's id is its terminal node index.
"""

from __future__ import annotations

import argparse
import array
import bz2
import gzip
import io
import json
import math
import os
import re
import sys
import tarfile
import time
import unicodedata
from collections import deque
from pathlib import Path

FORMAT_VERSION = 3
TATOEBA_HOLDOUT = 50  # keep in sync with tools/bench/make_bench.py
TOKEN = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")
# Context doesn't carry across punctuation (like the keyboard's TextContext).
CHUNK_BREAK = re.compile(r"[^\w\s'’-]+|\s-\s|_")
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
    __slots__ = ("word", "aosp", "sub", "name", "offensive", "not_a_word", "bigrams")

    def __init__(self, word: str):
        self.word = word
        self.aosp: int | None = None
        self.sub: int | None = None
        self.name: int | None = None  # frequency given to a name or place
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


def leipzig_sentences(path: Path):
    """Sentences from a Leipzig archive's -sentences.txt (id, sentence)."""
    def lines():
        if path.is_dir():
            files = sorted(path.glob("*-sentences.txt"))
            if files:
                with open_text(files[0]) as f:
                    yield from f
            return
        with tarfile.open(path) as tar:
            member = next((m for m in tar.getmembers() if m.name.endswith("-sentences.txt")), None)
            if member is None:
                return
            with io.TextIOWrapper(tar.extractfile(member), encoding="utf-8", errors="replace") as f:
                yield from f
    for line in lines():
        parts = line.rstrip("\n").split("\t", 1)
        if len(parts) == 2:
            yield parts[1]


def tatoeba_sentences(path: Path):
    """Training sentences from a Tatoeba export (id, lang, text), minus the benchmark's."""
    opener = bz2.open if path.suffix == ".bz2" else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[0].isdigit() and int(parts[0]) % TATOEBA_HOLDOUT != 0:
                yield parts[2]


def tatoeba_held_out(path: Path) -> set[str]:
    """Normalized text of the benchmark's held-out Tatoeba sentences."""
    opener = bz2.open if path.suffix == ".bz2" else open
    out = set()
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[0].isdigit() and int(parts[0]) % TATOEBA_HOLDOUT == 0:
                out.add(sentence_key(parts[2]))
    return out


def sentence_key(text: str) -> str:
    return " ".join(normalize(text).lower().split())


def plain_sentences(path: Path, exclude: set[str]):
    with open_text(path) as f:
        for line in f:
            text = line.strip()
            if text and sentence_key(text) not in exclude:
                yield text


def chunks_of(sentence: str):
    """Runs of words with no punctuation between them."""
    for part in CHUNK_BREAK.split(sentence):
        tokens = [normalize(t) for t in TOKEN.findall(part)]
        if tokens:
            yield tokens


def names_sources(path: Path) -> list[dict]:
    """The "# source<TAB>name<TAB>license<TAB>url" lines of a names file, for attribution."""
    out = []
    with open_text(path) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "# source" and len(parts) == 4:
                out.append({"name": parts[1], "license": parts[2], "url": parts[3]})
    return out


def read_names(path: Path) -> list[tuple[str, str, int]]:
    """label, kind, rank (1 = most notable within its kind)."""
    out = []
    with open_text(path) as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3 and parts[2].isdigit():
                out.append((normalize(parts[0]), parts[1], int(parts[2])))
    return out


def read_variants(path: Path) -> dict[str, dict[str, tuple[str, int, bool]]]:
    """region → {us spelling: (regional spelling, us level in region, keep us)}."""
    out: dict[str, dict[str, tuple[str, int, bool]]] = {}
    with open_text(path) as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) == 5:
                out.setdefault(parts[0], {})[parts[1]] = (parts[2], int(parts[3]), parts[4] == "1")
    return out


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


def allow_source(path: Path) -> dict:
    """How an allow list is credited in the dictionary (and so in the app's About screen)."""
    name = path.name.lower()
    if name.endswith(".dic"):
        return {"name": "Hunspell English word list (from SCOWL), allow list",
                "license": "MIT-like", "url": "http://wordlist.aspell.net"}
    if "scowl" in name:
        return {"name": "SCOWL word list, allow list",
                "license": "MIT-like", "url": "http://wordlist.aspell.net"}
    return {"name": f"Allow list: {path.name}", "license": "see source", "url": ""}


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
    sub_by_lower: dict[str, int] = {}

    counts: dict[str, int] = {}
    if args.subtitles:
        counts = read_counts(Path(args.subtitles))
    elif args.casual_from_sentences:
        counts = sentence_word_counts(args)
    if counts:
        max_count = max(counts.values(), default=1)
        for word, count in counts.items():
            low = word.lower()
            sub_by_lower[low] = max(sub_by_lower.get(low, 0), log_scale(count, max_count))
        added = 0
        for word, count in counts.items():
            target = resolver.resolve(word)
            # Too rare to add as a new word. (From sentences, a known word seen once or
            # twice still gets its low count: the collection is small.)
            if count < args.min_count and (args.subtitles or target is None):
                continue
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
        if args.subtitles:
            sources.append({
                "name": "OpenSubtitles word frequencies (Hermit Dave, FrequencyWords)",
                "license": "CC-BY-SA-4.0",
                "url": "https://github.com/hermitdave/FrequencyWords",
            })
            print(f"  Subtitles: {len(counts):,} words read, {added:,} new", file=sys.stderr)
        else:
            # The sentence sources are credited where they're read for word pairs.
            # Words the sentences never use: a subtitle list would have given them a low
            # count rather than none, so rank them as rarely used, not on AOSP alone.
            unseen = 0
            for e in entries.values():
                # (Not capitalized twins of counted words, "Best" beside "best": those keep AOSP's rank.)
                if e.aosp is not None and e.sub is None and e.word.lower() not in sub_by_lower:
                    e.sub = log_scale(0.5, max_count)
                    unseen += 1
            print(f"  Casual frequencies from sentences: {len(counts):,} words counted, {added:,} new, "
                  f"{unseen:,} AOSP words unseen", file=sys.stderr)

    if args.casual:
        added = 0
        for path in args.casual:
            for word in sorted(read_word_set(Path(path))):
                e = entries.get(word)
                if e is None:
                    e = resolver.add(word)
                    added += 1
                e.not_a_word = False
                if args.subtitles:
                    e.sub = max(e.sub or 0, sub_by_lower.get(word.lower(), 110))
                else:
                    # Sentence collections barely contain texting words: don't rank them by that.
                    e.sub = max(e.sub or 0, sub_by_lower.get(word.lower(), 0), 110)
        print(f"  Casual list: {added:,} words added", file=sys.stderr)

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

    if args.names:
        add_names(read_names(Path(args.names)), entries, resolver, args)
        sources += names_sources(Path(args.names)) or [{
            "name": "Wikidata (names and places)",
            "license": "CC0-1.0",
            "url": "https://www.wikidata.org",
        }]

    if allow:
        for a in args.allow:
            sources.append(allow_source(Path(a)))

    # Drop blocked and shortcut-only entries.
    for w in [w for w, e in entries.items() if e.not_a_word or w.lower() in block_lower]:
        del entries[w]

    return entries, sources, header


def sentence_word_counts(args) -> dict[str, int]:
    """
    Casual word frequencies counted from the conversational sentence sources
    (Tatoeba minus the benchmark's sentences, and any --sentences files), for
    builds without a subtitle frequency list. Lowercased, like that list.

    Tatoeba has favourite subjects (a few prolific contributors, stock names):
    a word's Tatoeba count is capped at TATOEBA_CAP times what its rate in the
    other sentences (the --sentences files, or the Leipzig news text) predicts.
    Words in the --spoken lists are multiplied by --spoken-boost.
    """
    def count(sentences) -> tuple[dict[str, int], int]:
        counts: dict[str, int] = {}
        total = 0
        for sentence in sentences:
            for chunk in chunks_of(sentence):
                for token in chunk:
                    word = token.lower()
                    if is_valid_word(word):
                        counts[word] = counts.get(word, 0) + 1
                        total += 1
        return counts, total

    held_out: set[str] = set()
    tatoeba: dict[str, int] = {}
    tatoeba_total = 0
    if args.tatoeba:
        tatoeba, tatoeba_total = count(tatoeba_sentences(Path(args.tatoeba)))
        held_out = tatoeba_held_out(Path(args.tatoeba))
    other: dict[str, int] = {}
    other_total = 0
    for spec in args.sentences or []:
        c, n = count(plain_sentences(Path(spec.split("|", 3)[3]), held_out))
        other_total += n
        for w, k in c.items():
            other[w] = other.get(w, 0) + k
    news, news_total = count(leipzig_sentences(Path(args.leipzig))) if args.leipzig else ({}, 0)

    counts = dict(other)
    capped = 0
    for w, k in tatoeba.items():
        if other_total or news_total:
            rate = max((other.get(w, 0) + 0.5) / other_total if other_total else 0,
                       (news.get(w, 0) + 0.5) / news_total if news_total else 0)
            limit = max(1, round(TATOEBA_CAP * rate * tatoeba_total))
            if k > limit:
                k = limit
                capped += 1
        counts[w] = counts.get(w, 0) + k
    spoken: set[str] = set()
    for path in args.spoken or []:
        spoken |= {w.lower() for w in read_word_set(Path(path))}
    for w in spoken:
        if w in counts:
            counts[w] = round(counts[w] * args.spoken_boost)
    print(f"  Sentence counts: Tatoeba {tatoeba_total:,} words ({capped:,} capped), "
          f"other {other_total:,}, news reference {news_total:,}, {len(spoken)} spoken words boosted",
          file=sys.stderr)
    return counts


TATOEBA_CAP = 5

NAME_KINDS_WITH_PAIRS = {"place", "country", "region", "org"}


def add_names(names: list[tuple[str, str, int]], entries: dict[str, Entry], resolver: Resolver, args) -> None:
    """
    Names and places with their capitals. Rank within each kind sets the
    frequency (the most notable ~170, tapering to ~80). A name never displaces
    a common lowercase word: "Will", "Rose" and "May" stay "will", "rose", "may".
    Two-word places ("New York", "Los Angeles") add both words and the pair.
    """
    # A new name one edit away from a very common word would hijack its typo
    # ("Teh" the surname vs "teh" → "the"), so those are left out.
    common = {w.lower() for w, e in entries.items() if final_frequency(e, args.casual_weight) >= NAME_NEAR_COMMON}
    by_kind: dict[str, int] = {}
    for _, kind, rank in names:
        by_kind[kind] = max(by_kind.get(kind, 0), rank)
    added = boosted = skipped = 0
    for label, kind, rank in names:
        parts = label.split(" ")
        if len(parts) > 2 or not all(is_valid_word(p) for p in parts):
            continue
        # Notability rank → frequency: the best known rank like everyday words,
        # the long tail like rare ones, so obscure names can't win corrections.
        freq = round(NAME_TOP - (NAME_TOP - NAME_TAIL) * math.sqrt((rank - 1) / max(1, by_kind[kind] - 1)))
        for i, word in enumerate(parts):
            if not word[0].isupper():
                break
            # A second word gets less on its own ("Angeles" alone is rare).
            f = freq if len(parts) == 1 else freq - 25
            e = entries.get(word)
            if e is None:
                low = entries.get(word.lower())
                if low is not None and final_frequency(low, args.casual_weight) >= NAME_COMMON_WORD:
                    continue
                if any(x in common for x in edits1(word.lower())):
                    skipped += 1
                    continue
                # New names must be plain letters on an English keyboard: "Kálmán"
                # would otherwise turn a typed "Kalman" into it.
                if not word.isascii():
                    skipped += 1
                    continue
                e = resolver.add(word)
                added += 1
            else:
                # Already known ("Rose"): never lift it above its lowercase twin ("rose").
                low = entries.get(word.lower()) if word != word.lower() else None
                if low is not None:
                    f = min(f, final_frequency(low, args.casual_weight) - 13)
                boosted += 1
            e.name = max(e.name or 0, f)
        if len(parts) == 2 and kind in NAME_KINDS_WITH_PAIRS and parts[0] in entries and parts[1] in entries:
            entries[parts[0]].bigrams[parts[1]] = 230
    print(f"  Names: {added:,} added, {boosted:,} already known, {skipped:,} too close to common words",
          file=sys.stderr)


NAME_COMMON_WORD = 100
NAME_NEAR_COMMON = 150
NAME_TOP = 160
NAME_TAIL = 50
LETTERS = "abcdefghijklmnopqrstuvwxyz'"


def edits1(word: str) -> set[str]:
    """Everything one deletion, swap, change or insertion away."""
    splits = [(word[:i], word[i:]) for i in range(len(word) + 1)]
    out = {a + b[1:] for a, b in splits if b}
    out |= {a + b[1] + b[0] + b[2:] for a, b in splits if len(b) > 1}
    out |= {a + c + b[1:] for a, b in splits if b for c in LETTERS}
    out |= {a + c + b for a, b in splits for c in LETTERS}
    out.discard(word)
    return out


def final_frequency(e: Entry, casual_weight: float) -> int:
    f = base_frequency(e, casual_weight)
    if e.name is not None:
        f = max(f, e.name)
    return max(1, min(255, round(f)))


def base_frequency(e: Entry, casual_weight: float) -> float:
    if e.aosp is None and e.sub is None:
        return 0
    if e.aosp is not None and e.sub is not None:
        f = casual_weight * e.sub + (1 - casual_weight) * e.aosp
    elif e.aosp is not None:
        f = e.aosp
    else:
        f = e.sub * 0.85  # unvetted by AOSP: rank a little lower
    return f


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
                     bigrams: dict[str, list[tuple[str, int]]], meta: dict,
                     trigrams: dict[tuple[str, str], list[tuple[str, int]]] | None = None) -> int:
    root = Node(0)
    for word in sorted(words):
        node = root
        for ch in word:
            cp = ord(ch)
            node = node.children.setdefault(cp, Node(cp))
        node.freq, node.flags = words[word]

    # Breadth-first layout: a node's children get consecutive ids. A leaf's
    # firstChild is where its children would start, so the array never decreases
    # and a node's parent can be found by binary search (no parent array).
    order: list[Node] = [root]
    root.id = 0
    first_child = [0]
    parent = [-1]
    queue = deque([root])
    while queue:
        node = queue.popleft()
        kids = [node.children[k] for k in sorted(node.children)]
        first_child[node.id] = len(order)
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

    # Three-word contexts, grouped by their first word (stored once per group).
    tri_word, tri_word_start, tri_second, tri_start, tri_next, tri_freq = [], [], [], [], [], []
    for (a, b), nexts in sorted(((node_of(a), node_of(b)), lst) for (a, b), lst in (trigrams or {}).items() if lst):
        if not tri_word or tri_word[-1] != a:
            tri_word.append(a)
            tri_word_start.append(len(tri_second))
        tri_second.append(b)
        tri_start.append(len(tri_next))
        for nxt, tf in nexts:
            tri_next.append(node_of(nxt))
            tri_freq.append(tf)
    tri_word_start.append(len(tri_second))
    tri_start.append(len(tri_next))

    # Narrowest widths that fit: English letters fit in one byte.
    top = max(chars)
    char_width = 1 if top < 0x100 else 2 if top < 0x10000 else 4
    count_width = 1 if max(child_count) < 0x100 else 2
    layout_flags = {1: 0, 2: 1, 4: 2}[char_width] | (4 if count_width == 2 else 0)

    def sized(values, width) -> bytes:
        return bytes(values) if width == 1 else u16(values) if width == 2 else i32(values)

    meta_bytes = json.dumps(meta, ensure_ascii=False, sort_keys=True).encode("utf-8")
    meta_bytes += b" " * (-len(meta_bytes) % 4)

    out = io.BytesIO()
    with out as f:
        def section(data: bytes) -> None:
            f.write(data)
            f.write(b"\0" * (-f.tell() % 4))  # every section starts 4-byte aligned

        f.write(b"EKD1")
        f.write(i32([FORMAT_VERSION, n, len(bigram_word), len(bigram_next),
                     len(tri_word), len(tri_second), len(tri_next), layout_flags, len(meta_bytes)]))
        f.write(meta_bytes)
        for part in (first_child, bigram_word, bigram_start, bigram_next,
                     tri_word, tri_word_start, tri_second, tri_start, tri_next):
            section(i32(part))
        section(sized(chars, char_width))
        section(sized(child_count, count_width))
        for part in (freq, bytes(max_sub), flags, bytes(bigram_freq), bytes(tri_freq)):
            section(part)
        data = f.getvalue()
    if path.suffix in (".gz", ".ekdz"):
        # mtime=0 and no file name keep the output byte-identical between builds.
        with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
            gz.write(data)
    else:
        path.write_bytes(data)
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
    p.add_argument("--casual-from-sentences", action="store_true",
                   help="Without --subtitles: count casual word frequencies from --tatoeba and --sentences")
    p.add_argument("--spoken", action="append",
                   help="With --casual-from-sentences: words under-counted in written sentences")
    p.add_argument("--spoken-boost", type=float, default=32)
    p.add_argument("--leipzig")
    p.add_argument("--allow", action="append")
    p.add_argument("--casual", action="append")
    p.add_argument("--block", action="append")
    p.add_argument("--tatoeba")
    p.add_argument("--sentences", action="append", metavar="NAME|LICENSE|URL|FILE")
    p.add_argument("--names")
    p.add_argument("--variants")
    p.add_argument("--variant-out", action="append", metavar="REGION=PATH")
    p.add_argument("--out", required=True, help="Output .ekd path")
    p.add_argument("--words-out", help="Also write the kept words as 'word<TAB>frequency<TAB>flags'")
    p.add_argument("--max-words", type=int, default=150_000)
    p.add_argument("--min-count", type=int, default=3, help="Ignore subtitle words seen fewer times")
    p.add_argument("--casual-weight", type=float, default=0.4,
                   help="0..1: how much subtitle (casual) frequency counts vs AOSP")
    p.add_argument("--bigrams-per-word", type=int, default=12)
    p.add_argument("--trigrams-per-context", type=int, default=6)
    p.add_argument("--trigram-min-count", type=int, default=3)
    p.add_argument("--trigram-min-context", type=int, default=8)
    p.add_argument("--max-trigram-contexts", type=int, default=120_000)
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

    if args.words_out:
        with open(args.words_out, "w", encoding="utf-8") as f:
            for e in kept:
                f.write(f"{e.word}\t{words[e.word][0]}\t{words[e.word][1]}\n")

    # Next-word data from sentences (Leipzig news, Tatoeba conversation).
    context = SentenceCounts(words)
    if args.leipzig:
        context.add(leipzig_sentences(Path(args.leipzig)))
    held_out: set[str] = set()
    if args.tatoeba:
        context.add(tatoeba_sentences(Path(args.tatoeba)))
        held_out = tatoeba_held_out(Path(args.tatoeba))
        sources.append({
            "name": "Tatoeba sentences",
            "license": "CC-BY-2.0-FR",
            "url": "https://tatoeba.org",
        })
    for spec in args.sentences or []:
        name, license_, url, path = spec.split("|", 3)
        context.add(plain_sentences(Path(path), held_out))
        sources.append({"name": name, "license": license_, "url": url})
    if context.tokens:
        print(f"  Sentences: {context.tokens:,} words, {len(context.pairs):,} pairs, "
              f"{len(context.triples):,} triples", file=sys.stderr)
        for first, nexts in context.bigram_strengths().items():
            e = entries[first]
            for nxt, strength in nexts.items():
                e.bigrams[nxt] = max(e.bigrams.get(nxt, 0), strength)

    bigrams: dict[str, list[tuple[str, int]]] = {}
    for e in kept:
        nexts = sorted(((w, f) for w, f in e.bigrams.items() if w in words and f > 0),
                       key=lambda x: (-x[1], x[0]))
        if nexts:
            bigrams[e.word] = nexts[: args.bigrams_per_word]
    trigrams = context.trigrams(bigrams, args) if context.tokens else {}

    date = args.date or int(os.environ.get("SOURCE_DATE_EPOCH", time.time()))
    meta = {
        "locale": args.locale,
        "description": args.description or header.get("description") or args.locale,
        "date": date,
        "wordCount": len(words),
        "sources": sources,
    }
    write(Path(args.out), words, bigrams, trigrams, meta)

    if args.variant_out:
        if not args.variants:
            p.error("--variant-out needs --variants")
        variants = read_variants(Path(args.variants))
        for spec in args.variant_out:
            region, _, path = spec.partition("=")
            locale, description = REGION_LOCALES[region]
            w2, b2, t2 = respell(words, bigrams, trigrams, variants[region])
            meta2 = dict(meta, locale=locale, description=description, wordCount=len(w2),
                         sources=sources + [{
                             "name": "English Speller Database (ESDB, formerly SCOWL), regional spellings",
                             "license": "MIT-like",
                             "url": "https://github.com/en-wl/wordlist",
                         }])
            write(Path(path), w2, b2, t2, meta2)


REGION_LOCALES = {
    "GB": ("en_GB", "English (UK)"),
    "CA": ("en_CA", "English (Canada)"),
    "AU": ("en_AU", "English (Australia)"),
}


def write(out: Path, words, bigrams, trigrams, meta) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    nodes = write_dictionary(out, words, bigrams, meta, trigrams)
    size = out.stat().st_size
    print(f"Wrote {out}: {len(words):,} words, {nodes:,} nodes, "
          f"{sum(len(v) for v in bigrams.values()):,} bigrams, "
          f"{sum(len(v) for v in trigrams.values()):,} trigrams in {len(trigrams):,} contexts, "
          f"{size / 1e6:.1f} MB", file=sys.stderr)


# ---------------------------------------------------------------------------
# Sentence statistics: word pairs and three-word context
# ---------------------------------------------------------------------------


class SentenceCounts:
    """Counts word pairs and triples over the dictionary's words, as integer ids."""

    def __init__(self, words: dict[str, tuple[int, int]]):
        assert len(words) < 1 << 18, "word ids must fit in 18 bits"
        self.names = sorted(words)
        self.ids = {w: i for i, w in enumerate(self.names)}
        self.lower: dict[str, int] = {}
        # Lowercase lookups prefer the lowercase word ("The" → "the"), else the most common casing.
        for w in sorted(words, key=lambda w: (w != w.lower(), -words[w][0], w)):
            self.lower.setdefault(w.lower(), self.ids[w])
        self.unigrams: dict[int, int] = {}
        self.pairs: dict[int, int] = {}
        self.triples: dict[int, int] = {}
        self.tokens = 0

    def _id(self, token: str) -> int | None:
        i = self.ids.get(token)
        return i if i is not None else self.lower.get(token.lower())

    def add(self, sentences) -> None:
        uni, pairs, triples = self.unigrams, self.pairs, self.triples
        for sentence in sentences:
            for chunk in chunks_of(sentence):
                prev2 = prev = None
                for token in chunk:
                    i = self._id(token)
                    self.tokens += 1
                    if i is None:
                        prev2 = prev = None
                        continue
                    if prev is not None:
                        k2 = (prev << 18) | i
                        pairs[k2] = pairs.get(k2, 0) + 1
                        if prev2 is not None:
                            k3 = (prev2 << 36) | k2
                            triples[k3] = triples.get(k3, 0) + 1
                        uni[prev] = uni.get(prev, 0) + 1  # times seen as a context
                    prev2, prev = prev, i

    def bigram_strengths(self) -> dict[str, dict[str, int]]:
        """P(next | first) on the dictionary's log scale, for pairs seen often enough."""
        out: dict[str, dict[str, int]] = {}
        for k, count in self.pairs.items():
            if count < MIN_PAIR_COUNT:
                continue
            a, b = k >> 18, k & 0x3FFFF
            out.setdefault(self.names[a], {})[self.names[b]] = log_scale(count, self.unigrams[a])
        return out

    def trigrams(self, bigrams: dict[str, list[tuple[str, int]]], args) -> dict[tuple[str, str], list[tuple[str, int]]]:
        """
        Third words worth knowing given the two before: seen often enough, and
        clearly likelier than the pair alone predicts (otherwise the bigram
        already does the job and the entry only costs space).
        """
        pair_strength = {(a, w): f for a, lst in bigrams.items() for w, f in lst}
        per_context: dict[int, list[tuple[int, int]]] = {}
        for k3, count in self.triples.items():
            if count < args.trigram_min_count:
                continue
            k2 = k3 >> 18
            per_context.setdefault(k2 >> 0, []).append((k3 & 0x3FFFF, count))
        contexts = []
        for k2, nexts in per_context.items():
            total = self.pairs.get(k2 & ((1 << 36) - 1), 0)
            if total < args.trigram_min_context:
                continue
            a, b = self.names[(k2 >> 18) & 0x3FFFF], self.names[k2 & 0x3FFFF]
            kept = []
            for c, count in nexts:
                strength = log_scale(count, total)
                word = self.names[c]
                if strength >= pair_strength.get((b, word), 0) + TRIGRAM_GAIN:
                    kept.append((word, strength))
            if kept:
                kept.sort(key=lambda x: (-x[1], x[0]))
                contexts.append((total, a, b, kept[: args.trigrams_per_context]))
        contexts.sort(key=lambda x: (-x[0], x[1], x[2]))
        return {(a, b): kept for _, a, b, kept in contexts[: args.max_trigram_contexts]}


MIN_PAIR_COUNT = 3
TRIGRAM_GAIN = 13  # at least twice as likely as the pair alone suggests


# ---------------------------------------------------------------------------
# Regional spellings
# ---------------------------------------------------------------------------


def respell(words, bigrams, trigrams, mapping: dict[str, tuple[str, int, bool]]):
    """
    The same dictionary with a region's spellings: "colour" takes "color"'s
    frequency and context. The US spelling stays known (so it isn't
    "corrected") but ranks lower, a little if the region also uses it
    (Canadian "color"), a lot if not.
    """
    def demoted(f: int, level: int) -> int:
        return max(1, f - (13 if level <= 4 else 39 if level <= 6 else 65))

    def spell(w: str) -> str:
        m = mapping.get(w)
        return m[0] if m else w

    w2 = dict(words)
    for u, (freq, flags) in words.items():
        m = mapping.get(u)
        if not m:
            continue
        v, level, keep = m
        old = w2.get(v)
        w2[v] = (max(freq, old[0] if old else 0), flags | (old[1] if old else 0))
        if not keep:
            w2[u] = (demoted(freq, level), flags)

    b2: dict[str, list[tuple[str, int]]] = {}
    for first, nexts in bigrams.items():
        for f in {first, spell(first)}:
            merged = dict(b2.get(f, []))
            for nxt, strength in nexts:
                merged[spell(nxt)] = max(merged.get(spell(nxt), 0), strength)
            b2[f] = sorted(merged.items(), key=lambda x: (-x[1], x[0]))
    t2: dict[tuple[str, str], list[tuple[str, int]]] = {}
    for (a, b), nexts in trigrams.items():
        key = (spell(a), spell(b))
        merged = dict(t2.get(key, []))
        for nxt, strength in nexts:
            merged[spell(nxt)] = max(merged.get(spell(nxt), 0), strength)
        t2[key] = sorted(merged.items(), key=lambda x: (-x[1], x[0]))
    return w2, b2, t2


if __name__ == "__main__":
    main()
