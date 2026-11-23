#!/usr/bin/env python3
"""
Turns the English dictionaries into one shared base and a small patch per
country, so the APK carries the words once instead of four times.

    python3 tools/dictgen/share_dicts.py [ASSETS_DIR]

Reads main_en_US.ekdz, main_en_GB.ekdz, main_en_CA.ekdz and main_en_AU.ekdz
from ASSETS_DIR (default: the app's assets/dictionaries) and replaces them with:

  main_en.ekdz        the US dictionary, laid out over every English word and
                      word pair any of the four has (the extra entries have
                      frequency 0, which the app treats as "not a word here")
  patch_en_XX.ekdp    for each country: its description and sources, and the
                      bytes where its dictionary differs from the base (word
                      frequencies, regional spellings, word-pair strengths)

The UK, Canadian and Australian dictionaries are the US one re-spelled, so the
four line up almost exactly and each patch is a few tens of kilobytes. On the
phone, DictionaryLoader rebuilds a country's dictionary from the base and its
patch the first time it's used (DictionaryPatch.kt), then uses it as before.

Before writing anything this checks that every rebuilt dictionary holds exactly
the words, frequencies, flags and word pairs of the original. make_dict.py runs
it after each English build.

Patch layout (little-endian, gzipped): "EKP1", int32 meta length, the meta
bytes (padded to 4), int32 run count, then per run: int32 offset into the body
(the bytes after the header and meta), int32 length, the new bytes (padded to 4).
"""
import gzip
import io
import json
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build_dict as bd  # noqa: E402

ASSETS = HERE.parent.parent / "app" / "src" / "main" / "assets" / "dictionaries"
BASE_LOCALE = "en_US"
LOCALES = ["en_US", "en_GB", "en_CA", "en_AU"]
HEADER = 40  # "EKD1" and nine int32s, the last being the meta length
# Differing bytes closer together than this become one run (a run costs 8 bytes plus padding).
MERGE_GAP = 12


def read_dictionary(data: bytes):
    """
    The inverse of build_dict.encode_dictionary: (words, bigrams, trigrams, meta), keeping only
    real entries (frequency or flags set; word-pair strength above 0).
    """
    magic = data[:4]
    assert magic == b"EKD1"
    version, nodes, bw, be, tw, tc, te, layout, ml = struct.unpack_from("<9i", data, 4)
    assert version == 3, "only format 3 is supported"
    meta = json.loads(data[HEADER:HEADER + ml].decode("utf-8"))
    p = HEADER + ml
    cw = {0: 1, 1: 2}.get(layout & 3, 4)
    cnt = 2 if layout & 4 else 1

    def sec(n, fmt, width):
        nonlocal p
        size = n * width
        vals = struct.unpack_from(f"<{n}{fmt}", data, p) if n else ()
        p += size + (-size % 4)
        return vals
    first_child = sec(nodes, "i", 4)
    bigram_word = sec(bw, "i", 4)
    bigram_start = sec(bw + 1, "i", 4)
    bigram_next = sec(be, "i", 4)
    tri_word = sec(tw, "i", 4)
    tri_word_start = sec(tw + 1, "i", 4)
    tri_second = sec(tc, "i", 4)
    tri_start = sec(tc + 1, "i", 4)
    tri_next = sec(te, "i", 4)
    chars = sec(nodes, {1: "B", 2: "H", 4: "I"}[cw], cw)
    counts = sec(nodes, {1: "B", 2: "H"}[cnt], cnt)
    freq = sec(nodes, "B", 1)
    sec(nodes, "B", 1)  # max_sub: recomputed when encoding
    flags = sec(nodes, "B", 1)
    bigram_freq = sec(be, "B", 1)
    tri_freq = sec(te, "B", 1)

    parent = [-1] * nodes
    for i in range(nodes):
        for c in range(first_child[i], first_child[i] + counts[i]):
            parent[c] = i
    text = [""] * nodes
    for i in range(1, nodes):  # breadth-first: a parent always comes before its children
        text[i] = text[parent[i]] + chr(chars[i])
    words = {text[i]: (freq[i], flags[i]) for i in range(1, nodes) if freq[i] or flags[i]}
    bigrams = {}
    for k in range(bw):
        lst = [(text[bigram_next[e]], bigram_freq[e]) for e in range(bigram_start[k], bigram_start[k + 1])
               if bigram_freq[e]]
        if lst:
            bigrams[text[bigram_word[k]]] = lst
    trigrams = {}
    for g in range(tw):
        a = text[tri_word[g]]
        for c in range(tri_word_start[g], tri_word_start[g + 1]):
            lst = [(text[tri_next[e]], tri_freq[e]) for e in range(tri_start[c], tri_start[c + 1]) if tri_freq[e]]
            if lst:
                trigrams[(a, text[tri_second[c]])] = lst
    return words, bigrams, trigrams, meta


def union_layout(dicts):
    """
    Every locale's (words, bigrams, trigrams) over the same keys and lists, so their encodings
    line up byte for byte except where values differ. Missing entries get 0.
    """
    all_words = set().union(*(d[0] for d in dicts.values()))
    firsts = set().union(*(d[1] for d in dicts.values()))
    nexts = {f: sorted(set().union(*({w for w, _ in d[1].get(f, ())} for d in dicts.values()))) for f in firsts}
    contexts = set().union(*(d[2] for d in dicts.values()))
    tri_nexts = {c: sorted(set().union(*({w for w, _ in d[2].get(c, ())} for d in dicts.values()))) for c in contexts}
    out = {}
    for loc, (words, bigrams, trigrams, meta) in dicts.items():
        w = {k: words.get(k, (0, 0)) for k in all_words}
        bd_ = {f: dict(v) for f, v in bigrams.items()}
        td = {c: dict(v) for c, v in trigrams.items()}
        b = {f: [(n, bd_.get(f, {}).get(n, 0)) for n in lst] for f, lst in nexts.items()}
        t = {c: [(n, td.get(c, {}).get(n, 0)) for n in lst] for c, lst in tri_nexts.items()}
        out[loc] = (w, b, t, meta)
    return out


def make_patch(base: bytes, target: bytes) -> bytes:
    """A patch turning [base] into [target]: both laid out alike, only meta and values differ."""
    bml = struct.unpack_from("<i", base, 36)[0]
    tml = struct.unpack_from("<i", target, 36)[0]
    assert base[:36] == target[:36], "dictionaries don't share a layout"
    body_b, body_t = base[HEADER + bml:], target[HEADER + tml:]
    assert len(body_b) == len(body_t)
    runs = []
    i, n = 0, len(body_b)
    while i < n:
        if body_b[i] == body_t[i]:
            i += 1
            continue
        start = end = i
        while end < n:
            if body_b[end] != body_t[end]:
                end += 1
            elif body_b[end:end + MERGE_GAP] != body_t[end:end + MERGE_GAP]:
                end += 1
            else:
                break
        runs.append((start, body_t[start:end]))
        i = end
    out = [b"EKP1", struct.pack("<i", tml), target[HEADER:HEADER + tml], struct.pack("<i", len(runs))]
    for off, data in runs:
        out.append(struct.pack("<2i", off, len(data)) + data + b"\0" * (-len(data) % 4))
    return b"".join(out)


def apply_patch(base: bytes, patch: bytes) -> bytes:
    """What DictionaryPatch.kt does on the phone, for checking here."""
    assert patch[:4] == b"EKP1"
    ml = struct.unpack_from("<i", patch, 4)[0]
    meta = patch[8:8 + ml]
    p = 8 + ml
    count = struct.unpack_from("<i", patch, p)[0]
    p += 4
    bml = struct.unpack_from("<i", base, 36)[0]
    body = bytearray(base[HEADER + bml:])
    for _ in range(count):
        off, length = struct.unpack_from("<2i", patch, p)
        p += 8
        body[off:off + length] = patch[p:p + length]
        p += length + (-length % 4)
    return base[:36] + struct.pack("<i", ml) + meta + bytes(body)


def gz(data: bytes) -> bytes:
    """Gzipped the same way every build (no time stamp or file name)."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0, filename="") as f:
        f.write(data)
    return buf.getvalue()


def main():
    assets = Path(sys.argv[1]) if len(sys.argv) > 1 else ASSETS
    sources = {loc: assets / f"main_{loc}.ekdz" for loc in LOCALES}
    missing = [str(p) for p in sources.values() if not p.exists()]
    if missing:
        sys.exit(f"missing: {', '.join(missing)}")
    originals = {loc: read_dictionary(gzip.decompress(p.read_bytes())) for loc, p in sources.items()}
    laid = union_layout(originals)
    encoded = {loc: bd.encode_dictionary(w, b, m, t)[0] for loc, (w, b, t, m) in laid.items()}
    base = encoded[BASE_LOCALE]
    patches = {loc: make_patch(base, data) for loc, data in encoded.items()}

    def comparable(d):
        # Word pairs are a set: their order in the file doesn't matter to the app.
        words, bigrams, trigrams, meta = d
        return (words, {k: sorted(v) for k, v in bigrams.items()}, {k: sorted(v) for k, v in trigrams.items()}, meta)
    for loc, patch in patches.items():
        rebuilt = read_dictionary(apply_patch(base, patch))
        assert comparable(rebuilt) == comparable(originals[loc]), f"{loc}: rebuilt dictionary differs from the original"

    before = sum(p.stat().st_size for p in sources.values())
    (assets / "main_en.ekdz").write_bytes(gz(base))
    for loc, patch in patches.items():
        (assets / f"patch_{loc}.ekdp").write_bytes(gz(patch))
    for p in sources.values():
        p.unlink()
    after = (assets / "main_en.ekdz").stat().st_size + sum((assets / f"patch_{l}.ekdp").stat().st_size for l in LOCALES)
    print(f"Shared English dictionaries: {before / 1e6:.2f} MB → {after / 1e6:.2f} MB "
          + ", ".join(f"{l} patch {(assets / f'patch_{l}.ekdp').stat().st_size / 1e3:.0f} kB" for l in LOCALES))


if __name__ == "__main__":
    main()
