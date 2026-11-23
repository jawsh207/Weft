#!/usr/bin/env python3
"""
Measures a next-word model file the way the keyboard will use it (see README.md):
the dictionary's candidates for every benchmark position (dumps written by
NextWordDumpTest), re-ranked with the model through the NumPy twin of
NextWordModel.kt, joined by the model's own guesses. Tunes the mixing weight
and offset on half the sentences and reports the other half.

    python3 tools/nextword/evaluate.py MODEL.wlm --dump bench.jsonl --conversation-dump conversation.jsonl

train.py calls measure() after training. Needs only NumPy.
"""
import argparse
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common  # noqa: E402
import wlm  # noqa: E402

MAX_CONTEXT = 30  # NextWordModel.MAX_CONTEXT


def score_rows(m, rows, dict_words):
    """For each dump row: the model's log-probability of every candidate, and its own guesses."""
    cache = {}
    out = []
    for r in rows:
        ctx = tuple(common.context(r["prefix"])[-MAX_CONTEXT:])
        if ctx not in cache:
            cache[ctx] = m.log_probs(m.state_for(ctx))
        lp = cache[ctx]
        logps = []
        for c in r["cands"]:
            i = m.ids.get(c[0].replace("’", "'").lower())
            logps.append(float(lp[i]) if i is not None else float(lp[1]) - m.unk_penalty)
        guesses = []
        looked = 0
        for i in np.argsort(-lp):
            if i <= 1:  # "<s>" and "<unk>" aren't words to offer
                continue
            looked += 1
            if looked > common.TOP_GUESSES:
                break
            w = m.words[i]
            if w in dict_words:
                guesses.append((dict_words[w], float(lp[i])))
            if len(guesses) >= common.MAX_GUESSES:
                break
        out.append((logps, guesses))
    return out


def measure(model_path, dump, conversation_dump):
    """(weight, offset, {set name: test results}) for the model at [model_path]."""
    m = wlm.Model(model_path)
    dict_words = common.load_words(dump + ".words.tsv")
    sets = {}
    for name, path, every in (("sentences", dump, 3), ("conversation", conversation_dump, 1)):
        rows = common.load_dump(path, every)
        sets[name] = (rows, score_rows(m, rows, dict_words))
    weight, offset = common.tune(list(sets.values()))
    out = {}
    for name, (rows, scored) in sets.items():
        b1, b3, n = common.hits(rows, [([0.0] * len(r["cands"]), []) for r in rows], 0, 0, False)
        t1, t3, _ = common.hits(rows, scored, weight, offset, False)
        out[name] = {"top1": round(t1, 1), "top3": round(t3, 1),
                     "dictionary_top1": round(b1, 1), "dictionary_top3": round(b3, 1), "positions": n}
    return weight, offset, out


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("model")
    p.add_argument("--dump", required=True)
    p.add_argument("--conversation-dump", required=True)
    a = p.parse_args()
    weight, offset, sets = measure(a.model, a.dump, a.conversation_dump)
    print(json.dumps({"weight": weight, "offset": offset, **sets}, indent=1))


if __name__ == "__main__":
    main()
