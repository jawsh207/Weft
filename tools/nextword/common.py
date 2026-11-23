"""
Shared by the next-word model scripts (see README.md): splitting text into
words the way the keyboard does, reading the dictionary's candidate dumps, and
the rule that mixes the dictionary's ranking with the model's. Suggester.kt
applies the same rule in the app, with the weight and offset tuned here.
"""
import json
import re

WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")

# Mixing weights tried; 1e6 means "the model alone".
WEIGHTS = [0, 0.1, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 5, 8]
# How far below the dictionary's weakest candidate a model-only guess starts.
OFFSETS = [0, 0.5, 1, 2, 3, 5, 8]
# The model's own guesses: its top TOP_GUESSES words, keeping at most MAX_GUESSES dictionary words.
TOP_GUESSES = 40
MAX_GUESSES = 20


CAPITAL_I = re.compile(r"I(?:['’](?:m|ll|ve|d))?")


def is_name(word):
    """Only ever written with a capital ("Tom"), apart from "I", "I'm" etc.: never a model guess (Suggester.isName)."""
    return word[:1].isupper() and not CAPITAL_I.fullmatch(word)


def words(text):
    """Lowercased words of [text], with curly apostrophes made straight."""
    return [w.replace("’", "'").lower() for w in WORD.findall(text)]


SENTENCE_END = re.compile(r"[.!?\n…]")


def context(prefix):
    """The words the keyboard gives the model: the sentence so far, from the last . ! ? (TextContext.sentenceWords)."""
    return words(SENTENCE_END.split(prefix)[-1])


def load_dump(path, every=1):
    """
    Positions to score: every [every]th sentence of a dump written by NextWordDumpTest,
    alternately for tuning (dev) and for reporting (test).
    """
    rows = [json.loads(line) for line in open(path, encoding="utf-8")]
    rows = [r for r in rows if r["i"] % every == 0]
    for r in rows:
        r["dev"] = (r["i"] // every) % 2 == 0
    return rows


def load_words(path):
    """
    The dictionary's suggestible words: lowercase → how it is written, its commonest spelling
    when there are several ("Tom" over "tom"), as BinaryDictionary.findIgnoreCase picks.
    """
    out, best = {}, {}
    for line in open(path, encoding="utf-8"):
        w, f = line.rstrip("\n").split("\t")
        k, f = w.lower(), int(f)
        if k not in out or f > best[k]:
            out[k], best[k] = w, f
    return out


def top3(row, logps, guesses, weight, offset):
    """
    The three words the keyboard would offer: each dictionary candidate's score plus
    weight × the model's log-probability, and the model's own guesses starting [offset]
    below the weakest candidate.
    """
    cands = row["cands"]
    ranked = [(c[0].lower(), c[1] + weight * lp) for c, lp in zip(cands, logps)]
    base = min((c[1] for c in cands), default=0.0) - offset
    have = {w for w, _ in ranked}
    ranked += [(w.lower(), base + weight * lp) for w, lp in guesses if w.lower() not in have]
    ranked.sort(key=lambda x: -x[1])
    return [w for w, _ in ranked[:3]]


def hits(rows, scored, weight, offset, dev):
    """(top-1 %, top-3 %, positions) over the dev or test rows."""
    h1 = h3 = n = 0
    for r, (logps, guesses) in zip(rows, scored):
        if r["dev"] != dev:
            continue
        top = top3(r, logps, guesses, weight, offset)
        t = r["target"].lower()
        n += 1
        h1 += bool(top) and top[0] == t
        h3 += t in top
    return (100 * h1 / max(n, 1), 100 * h3 / max(n, 1), n)


def tune(sets):
    """
    Picks the weight and offset with the best average dev top-3 over every set in
    [sets] (a list of (rows, scored)), so no single kind of text decides alone.
    """
    def score(wo):
        return sum(hits(rows, scored, wo[0], wo[1], True)[1] for rows, scored in sets)
    return max(((w, o) for w in WEIGHTS for o in OFFSETS), key=score)
