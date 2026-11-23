# Next-word model

Weft's next-word predictions start from the dictionary's word pairs and triples
("going to" → "the", "be", "do"). Those only see the last one or two words. The
next-word model reads the whole sentence so far, so it can tell that "I'm
taking the dog to the" wants a place and "Can you pass me the" wants a thing.

In the app (`predict/NextWordModel.kt`, `predict/Suggester.kt`):

1. The dictionary proposes its 30 best next words, as before.
2. The model scores each of them; that score, times a weight, is added to the
   dictionary's.
3. The model's own likeliest words (dictionary words only, never offensive ones)
   can join, starting a little below the weakest dictionary word.

The weight and that starting point are tuned here and stored in the model file.
The model only affects predictions when nothing has been typed yet; it doesn't
touch corrections or autocorrect.

## The model

A word-level LSTM: 30,000-word vocabulary, 128-wide word vectors, one
256-wide recurrent layer, and an adaptive softmax (the 2,000 commonest words
scored in full detail, rarer ones in two cheaper groups). About 5.6 million
weights, stored as 8-bit integers with one scale per row: a 6 MB file, about
5 MB gzipped in the APK. It runs in plain Kotlin (no ONNX or other native
library) at a few milliseconds per word, and only the newest word is read
each time.

An earlier comparison picked this size: small pretrained models such as
SmolLM2-360M were only slightly more accurate at 70 times the size and far too
slow on a phone.

## Training text

| Text | License | Why |
|---|---|---|
| Tatoeba sentences (minus the benchmark's held-out ones) | CC BY 2.0 FR | everyday sentences |
| Mozilla Common Voice sentences | CC0 | short, spoken-style |
| Leipzig news, 300,000 sentences | CC BY 4.0 | breadth |
| Google Taskmaster-1 to -4 dialogs | CC BY 4.0 | real back-and-forth conversation, typed and spoken |

Taskmaster is people ordering food and coffee, booking tables, flights and
hotels, and asking about films, music and sports. Its movie-ticket part (TM-3)
is cut to a quarter so it doesn't skew predictions toward tickets. Its sports
and music dialogs are never trained on: they are the conversation test.

None of it is share-alike, and every source is credited in `NOTICE` and under
Settings › About.

## How it is chosen and measured

The Next-word model workflow (`.github/workflows/next-word-model.yml`) runs
when this folder changes on main, or by hand:

1. `prepare.py` writes the training text.
2. `NextWordDumpTest` writes the dictionary's 30 candidates for every position
   of the typing benchmark (`tools/bench/data/sentences_en.txt`) and of the
   conversation test (`conversation_en.txt`).
3. `train.py` trains the variants in the workflow's matrix in parallel (the
   list is `VARIANTS` in train.py: which text, how many passes, how fast the
   learning rate falls). Each uses a little dropout and keeps whichever pass
   did best on sentences set aside from training.
4. Each is written as the int8 file and scored by `evaluate.py` through
   `wlm.py`, a NumPy copy of the app's arithmetic, so the numbers are what the
   keyboard gets. The weight and offset are tuned on half of each test set's
   sentences; the other half is reported.
5. `evaluate.py` measures the model already in the app the same way, and
   `choose.py` ships whichever is best on average top-3 over both tests (the
   current one wins ties, so a run never makes things worse), and writes
   [RESULTS.md](RESULTS.md).

The typing benchmark in the Build workflow then reports next-word accuracy with
the model and with the dictionary alone, on both test sets.

`make_fixture.py` writes the tiny random model `NextWordModelTest` uses to
check that the Kotlin code matches `wlm.py` exactly.

## Running it yourself

```sh
python3 tools/nextword/prepare.py data
WEFT_NEXT_WORD_DUMP=$PWD/data/bench.jsonl ./gradlew testDebugUnitTest --tests '*NextWordDumpTest'
WEFT_NEXT_WORD_SENTENCES=conversation_en.txt WEFT_NEXT_WORD_DUMP=$PWD/data/conversation.jsonl \
  ./gradlew testDebugUnitTest --tests '*NextWordDumpTest' --rerun
pip install numpy torch   # CPU is fine; a run takes two to four hours
python3 tools/nextword/train.py conversation data out.wlm out.json \
  --dump data/bench.jsonl --conversation-dump data/conversation.jsonl
python3 tools/nextword/evaluate.py out.wlm --dump data/bench.jsonl --conversation-dump data/conversation.jsonl
```

## Ideas not done yet

- **More conversational text.** Taskmaster is task-focused (ordering, booking).
  Casual chat between friends is the gap. OpenAssistant's messages (people
  typing to a chatbot, Apache-2.0) were tried and didn't help: too few, and
  not how people text each other. Openly licensed options are rare: most chat corpora
  (DailyDialog, EmpatheticDialogues, Topical-Chat) are non-commercial or
  share-alike, which Weft can't ship. MultiWOZ (Apache-2.0) adds more task
  dialogs.
- **Generated text.** Casual messages written by an openly licensed language
  model (Apache-2.0 weights) would have no licensing problem, but small models
  repeat themselves; it would need careful filtering and its own test.
- **Distillation.** Training this small model to copy a larger one's
  predictions (SmolLM2-360M) usually beats training on text alone, but costs
  many hours of CPU time per run.
- **Predicting at the start of a sentence or after a comma.** The model can
  do it; the dictionary can't, so those places get no predictions today.
  It needs its own benchmark first.
