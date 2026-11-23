# Experiment: would a small language model improve next-word prediction?

Not part of the app. This compares Weft's dictionary-based next-word
predictions with the same predictions re-ranked by small language models,
of several sizes, run on the same benchmark positions.

Two families are compared:

- **Weft's own models** (`own.py`, sizes S, M, L): word-level LSTMs trained
  here on the same openly licensed sentences the dictionary uses (Tatoeba
  minus the benchmark's sentences, Common Voice, part of Leipzig). Fully
  rebuildable and Apache-2.0 compatible.
- **Small pretrained models** (`pretrained.py`): published models from
  Hugging Face whose licenses allow use in an Apache-2.0 app. They have read
  far more text, but are much larger and trained on web-scale data.

For each: next-word top-3 and top-1 on held-out sentences, its size as an
int8 ONNX file (the format a slimmed ONNX Runtime would load on the phone),
and its speed on one CPU thread.

How it runs (`.github/workflows/experiment-lm.yml`, on `experiment/**`):

1. `NextWordDumpTest` writes the dictionary's top 30 candidates for every
   next-word position of the typing benchmark (`WEFT_NEXT_WORD_DUMP`), and
   `prepare.py` writes the training text.
2. One job per model trains (own) or downloads (pretrained) it and scores it
   (`common.py` tunes how much weight the model gets on half the sentences and
   reports on the other half).
3. `report.py` writes `RESULTS.md`.

Caveat: the test sentences are Tatoeba's, like most of the own models'
training text, which favours the own models a little. Real typing is the
final judge.
