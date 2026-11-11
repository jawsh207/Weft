# Swipe typing

Weft's swipe ("weft") typing is its own, fully open source decoder. This page
explains how it works, how to measure it, and what to improve next.

## How it works

Code: `predict/SwipeDecoder.kt` (decoder, no Android dependencies),
`ui/SwipeArea.kt` (touch handling and trail), `ime/TypingController.kt`
(spacing, delete, alternatives).

1. **Tap or swipe.** A touch that starts on a letter key and travels more than
   0.45 key widths becomes a swipe; the key under the finger is cancelled.
   Shorter touches stay taps. Swipes never start on shift, delete or the
   bottom row, so those keys behave normally.
2. **Coordinates.** Points are converted to key units, the same system as
   `layout/KeyGeometry.kt` (x in key widths, rows 1.45 key widths apart).
3. **Candidates.** The dictionary trie is walked letter by letter, keeping only
   words whose letters the finger passed near, in order, and which start and
   end near the first and last letters. Doubled letters cost no movement and
   apostrophes are skipped. This narrows 150,000 words to a few dozen.
4. **Scoring.** Each candidate's ideal path (straight lines between its key
   centres) is resampled to 48 points and compared with the swipe:
   location (average distance), shape (after normalizing position and size)
   and endpoints. Combined with word frequency and the previous word
   (bigrams). Weights are in `SwipeDecoder.Tuning`.
5. **Typing.** The best word is typed with a space before it if needed; the
   next tapped letter starts a new word automatically; delete removes the whole
   swiped word; the strip offers the next guesses, and tapping one swaps it in.

## Measuring

`SwipeAccuracyTest` swipes ~380 common words from the real dictionary three ways
and checks a floor for each. It prints the numbers (CI shows them on each Build
run), so run it before and after any change:

```
./gradlew testDebugUnitTest --tests '*SwipeAccuracyTest*' -i | grep "Swipe accuracy"
```

The swipes come from `SwipeSimulator` (in the test sources, ready to generate
training data for a learned decoder later):

- **straight:** straight lines between slightly off-centre letters (the original test);
- **natural:** like a relaxed thumb: the whole gesture shifted (thumbs land low)
  and scaled, each letter aimed at with Gaussian error, corners cut at sharp
  turns, a smooth curve rather than straight segments, an overshoot as the
  finger lifts, slow near letters and fast between them;
- **hurried:** the same errors one and a half times as large.

| Swipes | Top-1 before (Oct 2026) | Top-1 now | Top-3 now |
|---|---|---|---|
| Straight | 96.8% | 96.8% | 99.7% |
| Natural | 92.6% | 93.4% | 99.7% |
| Hurried | 67.9% | 84.1% | 96.6% |

Since the dictionary stopped using subtitle frequencies (Nov 2026, see
tools/dictgen/README.md), the test's sample is different words (it picks the
dictionary's most common ones, 437 now), and CI reports 97.5% / 93.1% / 81.2%
top-1. On the same words as before, the change cost about half a point
(96.6 → 96.0, 92.3 → 91.2, 83.6 → 83.0).

The natural set showed the decoder's weak spot: it only considered words whose
letters the path passed within 0.95 of a key width, and hurried thumbs miss
that. The radii are now 1.4 (ranking still prefers close paths), names get a
small handicap, and case twins ("major"/"Major") are offered once. A decode
takes about 1–1.5 ms on a desktop JVM. Remaining misses are mostly words that
trace the same line on QWERTY (for/four, to/too, of/off, the/there), where
frequency and context decide; in real typing the previous two words help.

## Future improvements

Swipe typing is in a good place as of v0.11.0 (Nov 2026): in everyday use it
gets whole messages right, so the remaining ideas are parked rather than
planned. What's done, what's parked and what was dropped:

1. ~~**Learn from real use.**~~ *Done (Nov 2026), on the phone only:* once the
   user moves on from a swipe, `SwipeDecoder.learnFrom` records where it started
   and ended relative to the word's first and last letters (a running average
   the decoder subtracts once there are 15+ swipes), and which guess they picked
   instead of the first one (that word wins next time). On simulated swipes from
   a thumb that lands a third of a key low and left, top-1 goes from 80.6% to
   91.2% after 150 swipes.
2. ~~**Better context.**~~ *Done:* candidates are ranked with the dictionary's
   word pairs and three-word context (two words back), plus the user's own word
   frequency and pairs (`LearnedWords.boost` / `pairBoost`).
3. **Machine-learning decoder.** *Parked:* not worth it while the current
   decoder works this well, and without real swipe data it could only be
   trained on simulated swipes. The idea: train a small model (e.g. a GRU or
   transformer over the point sequence) on synthetic swipes generated from the
   dictionary plus any real data, export to ONNX or LiteRT, and run it on
   device. Keep `SwipeDecoder`'s interface so it can replace or re-rank the
   current decoder. Keep it FOSS: open weights, OSI-licensed runtime.
4. ~~**Timing.**~~ *Done (Nov 2026):* each swipe point carries its time, and the
   decoder finds where the finger slowed down (compared with the fastest
   movement nearby). Among the 40 front-runners, a word loses points for
   slowdowns none of its letters explain and for middle letters passed at full
   speed, and a deliberate pause counts up to three times over: pausing on "o"
   along the straight p–t line gives "pot" rather than "put". On simulated
   swipes with timestamps (`SwipeSimulator.timed`, 999 words), top-1 goes from
   92.1% to 94.5% relaxed and 77.4% to 80.8% hurried. How much real thumbs slow
   on straight stretches isn't well known, so the test also runs a thumb that
   barely slows and hesitates often (92.5 → 93.7, 77.9 → 81.6) and one that
   hardly slows at all (91.4 → 93.4, 79.7 → 83.3): timing never hurt. These
   are simulated. In real use the difference is hard to notice, as expected
   of a tie-breaker, and it caused no odd picks.
5. ~~**Swipe across space.**~~ *Dropped:* people lift their thumb between
   words, and multi-word gestures would make every swipe harder to decode.
   Swiping on the symbols page was dropped with it: symbols are tapped.
6. ~~**Look and feel.**~~ *Done:* the trail tapers and fades toward its tail,
   and letters light up as the path crosses them (Settings › Swipe › Enhanced
   visuals; off shows a plain trail). The best guess shows as the middle
   suggestion; a bubble above the finger was tried and removed as redundant.
7. **More languages and layouts.** The decoder only needs key centres, so it
   works for any layout once dictionaries exist (see the roadmap).
8. **Per-person tuning.** *Parked:* small gains that would be hard to feel.
   Two pieces: adapting how far a swipe may stray from a key to how precise
   the user is (today it's 1.4 key widths for everyone), and judging slowdowns
   against the user's own rhythm.
