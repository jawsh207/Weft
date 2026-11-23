#!/usr/bin/env python3
"""
Writes a tiny random next-word model and what the NumPy reader (wlm.py)
computes with it, for NextWordModelTest: the app's Kotlin version must give
the same numbers.

    python3 tools/nextword/make_fixture.py

Writes app/src/test/resources/next_word_fixture.wlm and next_word_fixture.txt
(three lines per case: the context words, every word's log-probability, the top five words).
Needs only NumPy.
"""
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import wlm  # noqa: E402

OUT = HERE.parents[1] / "app/src/test/resources"


def main():
    rng = np.random.default_rng(7)
    # Real words first (they're in the test dictionary, so SuggesterTest can see them suggested).
    real = ["you", "i", "the", "to", "see", "later", "home", "go", "want", "okay", "yeah", "night",
            "monday", "end", "hello", "so", "much", "what", "do", "mean"]
    words = ["<s>", "<unk>"] + real + [f"w{i}" for i in range(35)] + ["don't", "café", "zebra"]
    V, E, H = len(words), 8, 32
    cutoffs = [20, 40]
    sizes = [40 - 20, V - 40]
    tails = [(rng.normal(0, 0.5, (H // 4 ** (k + 1), H)), rng.normal(0, 0.8, (n, H // 4 ** (k + 1))))
             for k, n in enumerate(sizes)]
    path = OUT / "next_word_fixture.wlm"
    wlm.write(path, words, rng.normal(0, 1, (V, E)), rng.normal(0, 0.4, (4 * H, E)),
              rng.normal(0, 0.3, (4 * H, H)), rng.normal(0, 0.2, 4 * H), rng.normal(0, 0.6, (cutoffs[0] + 2, H)),
              tails, cutoffs, weight=1.5, offset=2.0, unk_penalty=3.0, sources="fixture")
    m = wlm.Model(path)
    lines = []
    for ctx in ([], ["w3"], ["w3", "w7", "don't"], ["see", "you", "unknownword", "zebra"], ["café", "w30", "w1"]):
        lp = m.log_probs(m.state_for(ctx))
        top = [m.words[i] for i in np.argsort(-lp) if i > 1][:5]
        lines += [" ".join(ctx), " ".join(f"{v:.5f}" for v in lp), " ".join(top)]
    (OUT / "next_word_fixture.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("fixture written:", path.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
