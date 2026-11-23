"""
Shared by the language-model experiment's scripts (see README.md): reading the
dictionary's candidate dump, splitting sentences into words the way the
keyboard does, and scoring a model's re-ranking against the benchmark.
"""
import json
import math
import re
import time

WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")

# Weights tried when combining the dictionary's score with a model's log-probability.
WEIGHTS = [0, 0.1, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 5, 8, 1e6]
# For "open" mode: how far below the dictionary's lowest candidate a model's own guess starts.
OFFSETS = [0, 1, 2, 3, 5, 8]


def words(text):
    """Lowercased words of [text], with curly apostrophes made straight."""
    return [w.replace("’", "'").lower() for w in WORD.findall(text)]


def load_dump(path, every=3):
    """The positions to evaluate: every [every]th benchmark sentence, split into dev (tuning) and test."""
    rows = [json.loads(l) for l in open(path, encoding="utf-8")]
    rows = [r for r in rows if r["i"] % every == 0]
    for r in rows:
        r["dev"] = (r["i"] // every) % 2 == 0
    return rows


def load_words(path):
    """The dictionary's suggestible words: lowercase → how it is written."""
    out = {}
    for line in open(path, encoding="utf-8"):
        w, f = line.rstrip("\n").split("\t")
        k = w.lower()
        if k not in out or w == k:
            out[k] = w
    return out


def evaluate(rows, scores, extra=None):
    """
    [scores][n] is the model's log-probability for each of row n's dictionary
    candidates; [extra][n] (optional) the model's own guesses as (word, logp).
    Tunes the combination on dev rows and reports top-1/top-3 on test rows.
    """
    def hits(sel, w, off, use_extra):
        h1 = h3 = 0
        for r, s, e in zip(rows, scores, extra or [None] * len(rows)):
            if r["dev"] != sel:
                continue
            ranked = [(c[0], c[1] + w * lp if w < 1e5 else lp) for c, lp in zip(r["cands"], s)]
            if use_extra and e:
                base = min((c[1] for c in r["cands"]), default=0) - off
                have = {c[0].lower() for c in r["cands"]}
                ranked += [(word, base + w * lp if w < 1e5 else lp) for word, lp in e if word.lower() not in have]
            ranked.sort(key=lambda x: -x[1])
            top = [x[0].lower() for x in ranked[:3]]
            t = r["target"].lower()
            h1 += bool(top) and top[0] == t
            h3 += t in top
        n = sum(1 for r in rows if r["dev"] == sel)
        return 100 * h1 / n, 100 * h3 / n

    out = {}
    for mode, use_extra in (("rerank", False), ("open", True)):
        if use_extra and not extra:
            continue
        best = max(((w, o) for w in WEIGHTS for o in (OFFSETS if use_extra else [0])),
                   key=lambda wo: hits(True, wo[0], wo[1], use_extra)[1])
        t1, t3 = hits(False, best[0], best[1], use_extra)
        out[mode] = {"top1": round(t1, 1), "top3": round(t3, 1), "weight": best[0], "offset": best[1]}
    b1, b3 = hits(False, 0, 0, False)
    out["baseline"] = {"top1": round(b1, 1), "top3": round(b3, 1)}
    test = [r for r in rows if not r["dev"]]
    out["ceiling_top30"] = round(100 * sum(any(c[0].lower() == r["target"].lower() for c in r["cands"]) for r in test) / len(test), 1)
    out["test_positions"] = len(test)
    return out


def timed(fn, items, limit=150):
    """Median milliseconds [fn] takes per item, over up to [limit] items."""
    times = []
    for it in items[:limit]:
        t = time.perf_counter()
        fn(it)
        times.append((time.perf_counter() - t) * 1000)
    times.sort()
    return round(times[len(times) // 2], 1) if times else None


def logsumexp_safe(x):
    return x if math.isfinite(x) else -1e9
