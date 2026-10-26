# Dictionary pipeline

Builds the `.ekd` dictionaries the keyboard uses for suggestions, autocorrect
and next-word prediction. Python 3.10+, standard library only.

## Quick start

```sh
python3 tools/dictgen/make_dict.py en_US
```

This downloads the sources in `sources/en_US.json` into `cache/en_US/`
(git-ignored), builds `app/src/main/assets/dictionaries/main_en_US.ekd`, and
prints a summary. Rebuild the app afterwards.

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
4. Next-word strength is the conditional probability of word B following word A,
   on the same log scale, merged from AOSP and Leipzig. The top 12 per word are kept.
5. The top `--max-words` (default 150,000) are written out.

Output is deterministic for the same inputs. Set `SOURCE_DATE_EPOCH` or
`--date` for byte-identical rebuilds.

## Adding a language

Copy `sources/en_US.json` to `sources/<locale>.json` and point it at that
language's files (AOSP has many; FrequencyWords covers about 60 languages;
Leipzig covers hundreds). Add a matching subtype in `res/xml/method.xml`.

## File format (`.ekd`, version 1)

A breadth-first trie stored as flat little-endian arrays, memory-mapped by the
app with no parsing. See the docstring at the top of `build_dict.py` for the
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
  --out app/src/test/resources/fixture_en.ekd --date 1 --min-count 1
```
