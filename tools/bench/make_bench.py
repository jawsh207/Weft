#!/usr/bin/env python3
"""
Builds the typing benchmark data.

  tools/bench/data/sentences_en.txt (committed)
      Held-out English sentences from Tatoeba (every sentence whose id is a
      multiple of 50). The dictionary builder never trains on these, so
      prediction scores are honest.

        python3 tools/bench/make_bench.py --tatoeba tools/dictgen/cache/en_US/eng_sentences.tsv.bz2

  tools/bench/cache/misspellings_en.tsv (downloaded, never committed)
      "typo<TAB>correct" pairs from Wikipedia's lists of common misspellings.
      That list is CC BY-SA 4.0, so it isn't stored in this Apache-2.0 repo:
      CI downloads it before the tests, and so can you:

        python3 tools/bench/make_bench.py --misspellings

Sources and licenses: see tools/bench/data/README.md.
"""

from __future__ import annotations

import argparse
import bz2
import re
import sys
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent / "data"
CACHE = Path(__file__).resolve().parent / "cache"
MISSPELLINGS_URL = (
    "https://en.wikipedia.org/w/index.php?title=Wikipedia:Lists_of_common_misspellings/"
    "For_machines&action=raw"
)
HOLDOUT = 50  # must match build_dict.py's TATOEBA_HOLDOUT
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")


def held_out_sentences(path: Path, count: int) -> list[str]:
    picked = []
    opener = bz2.open if path.suffix == ".bz2" else open
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3 or not parts[0].isdigit() or int(parts[0]) % HOLDOUT != 0:
                continue
            text = parts[2].strip()
            n = len(WORD.findall(text))
            if 5 <= n <= 25 and "\t" not in text:
                picked.append((int(parts[0]), text))
    picked.sort()
    step = max(1, len(picked) // count)
    return [t for _, t in picked[::step][:count]]


def misspellings() -> list[tuple[str, str]]:
    req = urllib.request.Request(MISSPELLINGS_URL, headers={"User-Agent": "weft-bench/1.0 (https://github.com/jawsh207/Weft)"})
    with urllib.request.urlopen(req, timeout=120) as r:
        text = r.read().decode("utf-8", "replace")
    pairs = []
    for line in text.splitlines():
        if "->" not in line:
            continue
        typo, _, right = line.strip().partition("->")
        typo, right = typo.strip(), right.strip()
        if "," in right or " " in right or " " in typo or not typo or not right:
            continue  # several possible fixes, or phrases: skip
        if not (WORD.fullmatch(typo) and WORD.fullmatch(right)) or typo.lower() == right.lower():
            continue
        pairs.append((typo, right))
    return sorted(set(pairs))


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--tatoeba", help="Tatoeba export: write the held-out sentences")
    p.add_argument("--sentences", type=int, default=1500)
    p.add_argument("--misspellings", action="store_true",
                   help="Download Wikipedia's misspellings into tools/bench/cache/ (git-ignored)")
    a = p.parse_args()
    if not (a.tatoeba or a.misspellings):
        p.error("nothing to do: pass --tatoeba FILE and/or --misspellings")

    if a.tatoeba:
        OUT.mkdir(parents=True, exist_ok=True)
        sentences = held_out_sentences(Path(a.tatoeba), a.sentences)
        (OUT / "sentences_en.txt").write_text("\n".join(sentences) + "\n", encoding="utf-8")
        print(f"sentences_en.txt: {len(sentences):,} held-out sentences", file=sys.stderr)

    if a.misspellings:
        pairs = misspellings()
        if len(pairs) < 1000:
            sys.exit(f"only {len(pairs)} misspellings parsed: the page may have changed")
        CACHE.mkdir(parents=True, exist_ok=True)
        with open(CACHE / "misspellings_en.tsv", "w", encoding="utf-8") as f:
            for typo, right in pairs:
                f.write(f"{typo}\t{right}\n")
        print(f"cache/misspellings_en.tsv: {len(pairs):,} pairs", file=sys.stderr)


if __name__ == "__main__":
    main()
