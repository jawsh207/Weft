#!/usr/bin/env python3
"""Compares two word lists written by build_dict.py --words-out. Usage: compare.py BASE NEW OUT.md"""
import sys


def read(path):
    out = {}
    for line in open(path, encoding="utf-8"):
        w, f, flags = line.rstrip("\n").split("\t")
        out[w] = int(f)
    return out


base, new = read(sys.argv[1]), read(sys.argv[2])
dropped = sorted((w for w in base if w not in new), key=lambda w: (-base[w], w))
gained = sorted((w for w in new if w not in base), key=lambda w: (-new[w], w))
common = [w for w in base if w in new]
fell = sorted(common, key=lambda w: (new[w] - base[w], w))[:150]
rose = sorted(common, key=lambda w: (base[w] - new[w], w))[:150]
spot = """gonna wanna gotta kinda sorta yeah yep nope nah hey hi ok okay lol omg idk btw haha hmm ugh wow dude bro
guys awesome cool crap damn hell stuff thing really pretty totally literally basically actually whatever anyway
mom dad kid kids texted texting selfie emoji wifi app apps google email phone tonight tomorrow weekend pizza coffee
beer movie cute funny weird sorry thanks please love hate miss
Layla Tom Mary Berber Kabyle Esperanto Algerians hijab coefficients unincorporated redesignated topological
don uh um huh whoa outta ain mm hiya meh""".split()


def cells(words, table, other=None):
    return ", ".join(f"{w} ({table[w]}" + (f"→{other[w]})" if other else ")") for w in words) or "none"


def band(table, lo, hi):
    return sum(1 for f in table.values() if lo <= f < hi)


lines = [
    "# Dictionary comparison: today's (with OpenSubtitles) vs replacement",
    "",
    "Frequencies are on the dictionary's 1–255 scale.",
    "",
    f"- Words: {len(base):,} today, {len(new):,} replacement, {len(common):,} in both",
    f"- Only in today's (dropped): {len(dropped):,}",
    f"- Only in the replacement (gained): {len(gained):,}",
    f"- Dropped words by today's frequency: {sum(1 for w in dropped if base[w] >= 150)} at 150+, "
    f"{sum(1 for w in dropped if 100 <= base[w] < 150)} at 100–149, "
    f"{sum(1 for w in dropped if 50 <= base[w] < 100)} at 50–99, {sum(1 for w in dropped if base[w] < 50)} below 50",
    "",
    "## How many words sit in each frequency band",
    "",
    "| Band | Today | Replacement |",
    "|---|---|---|",
] + [f"| {lo}–{hi - 1} | {band(base, lo, hi):,} | {band(new, lo, hi):,} |"
     for lo, hi in ((200, 256), (150, 200), (135, 150), (100, 135), (50, 100), (1, 50))] + [
    "",
    "## Dropped: the 400 most frequent (today's frequency)",
    "", cells(dropped[:400], base), "",
    "## Dropped: a spread across the rest (every 25th)",
    "", cells(dropped[400::25], base), "",
    "## Gained: the 300 most frequent (replacement frequency)",
    "", cells(gained[:300], new), "",
    "## Biggest falls among shared words (today→replacement)",
    "", cells(fell, base, new), "",
    "## Biggest rises among shared words (today→replacement)",
    "", cells(rose, base, new), "",
    "## Spot check of everyday and casual words (today→replacement; — means missing)",
    "", ", ".join(f"{w} ({base.get(w, '—')}→{new.get(w, '—')})" for w in spot), "",
]
open(sys.argv[3], "w", encoding="utf-8").write("\n".join(lines))
print("\n".join(lines[:9]))
