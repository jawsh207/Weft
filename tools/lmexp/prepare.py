#!/usr/bin/env python3
"""
Experiment (see README.md): writes the training text for Weft's own small
language models, from the same openly licensed sentences the dictionary uses:
Tatoeba (minus the benchmark's held-out sentences), Common Voice, and part of
the Leipzig news corpus. Downloads go to tools/dictgen/cache like the
dictionary build's.

    python3 tools/lmexp/prepare.py OUT.txt [--news 300000]
"""
import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "dictgen"))
import build_dict as bd  # noqa: E402
import make_dict as md  # noqa: E402
import json  # noqa: E402


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("out")
    p.add_argument("--news", type=int, default=300_000, help="Leipzig news sentences to include")
    a = p.parse_args()
    config = json.loads((HERE.parent / "dictgen" / "sources" / "en_US.json").read_text())
    cache = HERE.parent / "dictgen" / "cache" / "en_US"
    cache.mkdir(parents=True, exist_ok=True)
    paths = {k: md.fetch(k, config["sources"][k], cache) for k in ("tatoeba", "commonvoice", "leipzig")}
    held_out = bd.tatoeba_held_out(paths["tatoeba"]) if paths["tatoeba"] else set()
    counts = {}
    with open(a.out, "w", encoding="utf-8") as f:
        def put(name, sentences, limit=None):
            n = 0
            for s in sentences:
                s = s.strip()
                if s:
                    f.write(s.replace("\n", " ") + "\n")
                    n += 1
                    if limit and n >= limit:
                        break
            counts[name] = n
        if paths["tatoeba"]:
            put("tatoeba", bd.tatoeba_sentences(paths["tatoeba"]))
        if paths["commonvoice"]:
            put("commonvoice", bd.plain_sentences(paths["commonvoice"], held_out))
        if paths["leipzig"]:
            put("leipzig", bd.leipzig_sentences(paths["leipzig"]), a.news)
    print("Training sentences:", counts)


if __name__ == "__main__":
    main()
