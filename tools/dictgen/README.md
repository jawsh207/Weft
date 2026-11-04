# Dictionary pipeline

Builds the `.ekd` dictionaries the keyboard uses for suggestions, autocorrect
and next-word prediction. Python 3.10+, standard library only.

## Quick start

```sh
python3 tools/dictgen/make_dict.py en_US
```

This downloads the sources in `sources/en_US.json` into `cache/en_US/`
(git-ignored), builds `app/src/main/assets/dictionaries/main_en_US.ekdz` plus
the UK, Canadian and Australian variants (`main_en_GB.ekdz`, …), and prints a
summary. `.ekdz` is a gzipped `.ekd`; the app unpacks each once, on first use.
(Not `.ekd.gz`: Android's build tools unpack `.gz` assets, which undoes the
saving.) Rebuild the app afterwards. The Dictionary workflow does all of this on
GitHub and commits the results.

Extra options are passed through to the builder, for example:

```sh
python3 tools/dictgen/make_dict.py en_US --max-words 120000 --casual-weight 0.5
```

## Sources

| Source | Role | License |
|---|---|---|
| AOSP LatinIME word lists | Base frequencies, offensive flags, some next-word pairs | Apache 2.0 |
| OpenSubtitles frequencies (Hermit Dave's FrequencyWords) | Tunes rankings toward casual, conversational language | CC BY-SA 4.0 |
| Leipzig Corpora Collection | Next-word pairs (`-co_n.txt`) | CC BY 4.0 |
| SCOWL (or a Hunspell `.dic`) | Allow list that filters junk from subtitle-only words | MIT-like |
| `sources/en_US.casual.txt` | Texting words spell-check lists omit ("lol", "idk", lowercase "ok") | part of this repo |
| Tatoeba sentences | Everyday sentences for word pairs and three-word context (every 50th sentence is held out for the benchmark) | CC BY 2.0 FR |
| Mozilla Common Voice sentences (hand-written ones; the Wikipedia extracts are left out as too formal) | More everyday sentences for word pairs and three-word context; any matching a held-out benchmark sentence are skipped | CC0 |
| Leipzig sentences (same archive) | Three-word context from news text | CC BY 4.0 |
| Wikidata and GeoNames (`sources/en.names.tsv`, from `fetch_names.py`) | Names, countries, demonyms, regions, brands (Wikidata); cities (GeoNames) | CC0; CC BY 4.0 |
| ESDB, formerly SCOWL (`sources/en_variants.tsv`, from `make_variants.py`) | US → UK / Canadian / Australian spellings | MIT-like, see `sources/LICENSE-ESDB.txt` |

All are compatible with the app's GPL-3.0 license. The CC BY / CC BY-SA sources
require attribution: the builder records every source in the dictionary's
metadata, and the app's settings screen lists them.

If a download fails, `make_dict.py` prints where else to get the file. Put it in
`cache/<locale>/` under the file name listed in the JSON and rerun.

- **AOSP fallback:** HeliBoard maintains copies at
  https://codeberg.org/Helium314/aosp-dictionaries (wordlists folder).
- **SCOWL:** generate a list at http://app.aspell.net/create (size 70, US
  spelling) and save it as `cache/en_US/scowl_en_US_70.txt`. Without it, a
  system Hunspell dictionary is used if found.
- **Leipzig:** any corpus from https://wortschatz.uni-leipzig.de/en/download
  works; the builder reads the `.tar.gz` directly.

## How the sources are merged

1. AOSP words and frequencies are the base. Entries marked `not_a_word`
   (shortcuts) are dropped. Offensive words are kept but flagged: they're
   recognized so they don't get "corrected", but never suggested unless typed
   in full.
2. Subtitle counts are converted to the same 0–255 log scale and blended in
   (`--casual-weight`, default 0.4). They're matched case-insensitively, so
   "monday" boosts AOSP's "Monday".
3. Words found only in subtitles are kept only if the allow list contains them,
   and rank slightly lower. Words in the casual list are always kept, exactly as
   written.
4. Names and places get capitals and a frequency from their notability rank.
   A name never replaces a common lowercase word ("Will" stays "will"), and a
   new name one edit away from a very common word is skipped, so it can't
   hijack that word's typos ("Teh" the surname vs "teh" → "the").
5. The top `--max-words` (default 150,000) are kept.
6. Next-word strength is the conditional probability of word B following word A,
   on the same log scale, merged from AOSP, Leipzig and sentence counts. The top
   12 per word are kept.
7. Three-word context: from sentences (never across punctuation), a third word
   is kept for a pair when it's seen often enough and at least twice as likely
   as the second word alone predicts ("see you" → "later"); up to 6 per pair,
   for the 120,000 most common pairs.
8. Regional dictionaries are the US one respelled: "colour" takes "color"'s
   frequency and context, and "color" stays known but ranks lower.

Output is deterministic for the same inputs. Set `SOURCE_DATE_EPOCH` or
`--date` for byte-identical rebuilds.

## Adding a language

Copy `sources/en_US.json` to `sources/<locale>.json` and point it at that
language's files (AOSP has many; FrequencyWords covers about 60 languages;
Leipzig covers hundreds). Add a matching subtype in `res/xml/method.xml`.

## File format (`.ekd`, version 3)

A breadth-first trie stored as flat little-endian arrays, memory-mapped by the
app with no parsing. Version 3 stores letters and child counts in the narrowest
width that fits (one byte each for English), drops the parent array (a node's
parent is found by binary search, since firstChild never decreases) and groups
the three-word table by first word: about 30% smaller than version 2 with
identical results and speed. The app still reads versions 1 and 2.

Trimming content was measured too (Nov 2026) and not adopted: fewer words (120k
or 100k) would make about a third of the dropped real words ("forbearing",
"avow") get autocorrected into something else, and trimming word pairs or
three-word entries saves only about 0.1 MB compressed each, at a small cost in
predictions. See the docstring at the top of `build_dict.py` for the
exact layout, and `predict/BinaryDictionary.kt` for the reader.

## Tests

`testdata/` holds tiny inputs in each source's real format. The unit-test
fixture is built from them:

```sh
python3 tools/dictgen/build_dict.py --locale en_US \
  --aosp tools/dictgen/testdata/aosp.combined.gz \
  --subtitles tools/dictgen/testdata/subs.txt \
  --allow tools/dictgen/testdata/allow.txt \
  --leipzig tools/dictgen/testdata/leipzig \
  --casual tools/dictgen/testdata/casual.txt \
  --tatoeba tools/dictgen/testdata/tatoeba.tsv \
  --sentences "Common Voice sentences|CC0-1.0|https://commonvoice.mozilla.org|tools/dictgen/testdata/commonvoice.txt" \
  --names tools/dictgen/testdata/names.tsv \
  --variants tools/dictgen/sources/en_variants.tsv \
  --variant-out GB=app/src/test/resources/fixture_en_GB.ekd.gz \
  --out app/src/test/resources/fixture_en.ekd --date 1 --min-count 1 \
  --trigram-min-count 2 --trigram-min-context 2
```

## Benchmark

`tools/bench/` scores the real dictionary: autocorrect on Wikipedia's common
misspellings, words wrongly changed, next-word predictions and keystrokes saved
on held-out Tatoeba sentences (`TypingBenchmarkTest`; CI shows the numbers on
each Build run). Run it before and after changing the pipeline or the ranking.
