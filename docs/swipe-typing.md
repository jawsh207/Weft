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

`SwipeAccuracyTest` swipes ~380 common words from the real dictionary with
realistic wobble and checks the floor. It prints the numbers, so run it before
and after any change:

```
./gradlew testDebugUnitTest --tests '*SwipeAccuracyTest*' -i | grep "Swipe accuracy"
```

Baseline (October 2026): top-1 ≈ 97%, top-3 ≈ 99.7%, about 0.5 ms per decode
on a desktop JVM. Remaining misses are mostly words that trace the same line
on QWERTY (for/four, to/too, of/off, or/our), where frequency and context decide.

## Future improvements

Come back to these, roughly in order of value:

1. **Learn from real use.** Synthetic swipes are straight lines; real thumbs
   curve, overshoot and cut corners. Keep on-device-only (nothing leaves the phone)
   stats, e.g. which alternative the user picks, to tune `Tuning` per user.
2. **Better context.** Rank ambiguous pairs (for/four, to/too) with stronger
   next-word data. *Partly done:* the decoder now adds the user's own word
   frequency and word pairs (`LearnedWords.boost` / `pairBoost`); longer
   context (two words back) would help more.
3. **Machine-learning decoder.** Train a small model (e.g. a GRU or
   transformer over the point sequence) on synthetic swipes generated from the
   dictionary plus any real data, export to ONNX or LiteRT, and run it on
   device. Keep `SwipeDecoder`'s interface so it can replace or re-rank the
   current decoder. Keep it FOSS: open weights, OSI-licensed runtime.
4. **Timing.** Pauses on a key are a strong signal a letter is intended;
   the decoder currently ignores timing.
5. **Swipe across space.** Multiple words in one gesture, and swiping on the
   symbols page.
6. **Look and feel.** Fading, tapering trail; highlight keys the path crosses;
   floating preview of the best word near the finger.
7. **More languages and layouts.** The decoder only needs key centres, so it
   works for any layout once dictionaries exist (see the roadmap).
