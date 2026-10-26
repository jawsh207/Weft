# Weft

> In weaving, the warp runs vertically and the weft is drawn horizontally,
> back and forth across the loom. On Weft, your thumb is the shuttle.

A free and open-source Android keyboard built with Jetpack Compose and
**Material 3 Expressive**. It takes everything it can from the system:

- **Colors** — the user's Material You palette, dark/light mode and contrast setting.
- **Font** — the device font at the user's chosen size.
- **Emoji** — the system emoji font (no bundled font, no EmojiCompat).
- **Voice** — whichever speech engine is selected in system settings.

Requires **Android 16 (API 36) or newer**.

## Status

This is the starter scaffold. Working now:

- IME service hosting Compose (lifecycle, saved-state and view-model owners wired up)
- QWERTY, symbols and extra-symbols pages with shift / caps lock and auto-capitalization
- Enter key that adapts to the field (search, send, go, next, done, newline)
- Grapheme-aware backspace with hold-to-repeat (deletes 👨‍👩‍👧 or 🇨🇦 in one press)
- Expressive press motion: keys squish and round out on a spring
- System haptics on every key press
- Voice input through the system speech engine, with live partial results
- **Emoji picker** with every Unicode emoji, drawn in the phone's own emoji font:
  category tabs, recently used, skin tones on long-press (remembered per
  emoji), and search by name, keyword or shortcode ("joy", "lol", ":thumbsup:",
  "us" for 🇺🇸). Emoji the font can't draw are hidden
- Setup screen: enable keyboard, switch to it, grant the mic, test field
- **Suggestions:** typo correction that knows which keys are next to each other,
  word completions, and next-word predictions, in a three-slot strip
- **Autocorrect** on space or punctuation; delete right after undoes it, and that
  word won't be corrected again in the session
- **Learned words**, stored only on the device, plus the system personal
  dictionary. Never learns in password fields or fields that request no
  personalized learning (incognito)
- Smart spacing: picking a suggestion adds a space; typing `.` or `,` next
  moves it after the punctuation
- **Swipe typing** with Weft's own open source decoder: a trail follows your
  finger, the best guess previews live, other guesses wait in the strip, and
  delete removes a swiped word whole. See [docs/swipe-typing.md](docs/swipe-typing.md)

## Download

Every push to `main` builds a debug APK. Open the latest successful
[Build run](https://github.com/jawsh207/Weft/actions/workflows/build.yml) and
download **weft-debug** under Artifacts (you need to be signed in to GitHub).

## Building

1. Open the folder in Android Studio (a release that supports AGP 9.1).
2. Let Gradle sync. Android Studio may offer newer dependency versions —
   accepting them is fine.
3. The dictionary is built by the Dictionary workflow and committed to
   `app/src/main/assets/dictionaries/`. To rebuild it locally:
   `python3 tools/dictgen/make_dict.py en_US` (see
   [tools/dictgen/README.md](tools/dictgen/README.md)).
4. Run the `app` configuration on an Android 16+ device or emulator.
5. Follow the setup screen to enable the keyboard.

From the command line: `./gradlew assembleDebug`.
Unit tests (prediction engine and typing behavior): `./gradlew test`.

## Project layout

```
app/src/main/java/app/weft/keyboard/
├── ime/        WeftKeyboardService (the IME), TypingController, KeyboardState
├── predict/    Dictionary, fuzzy search, ranking, learned words, swipe decoder (no Android deps)
├── layout/     Key model, page layouts and key geometry
├── ui/         Compose keyboard, key button with Expressive motion
├── theme/      SystemTheme: dynamic color + MaterialExpressiveTheme
├── voice/      SystemVoiceInput: wraps android.speech.SpeechRecognizer
├── emoji/      Emoji list parser, search, recents and skin tones
└── settings/   SettingsActivity: onboarding and test field
```

## Roadmap

1. ~~Prediction~~ — done. Next: long-press a suggestion to forget a learned word,
   learn word pairs from the user's own typing.
2. ~~Full emoji picker~~ — done, with CLDR keyword search.
3. **Long-press alternates** — accented letters and key popups.
4. **Settings** — haptics, key height, on-device voice preference, number row.
5. **Large screens** — floating, one-handed and split layouts for tablets and foldables.
6. ~~Gesture typing~~ — first version done. Improvements to come back to
   (learning from real use, ML decoder, timing, multi-word swipes) are listed in
   [docs/swipe-typing.md](docs/swipe-typing.md#future-improvements).
7. **More languages** — layouts and dictionaries per subtype.

## FOSS rules for this repo

- Only OSI-licensed dependencies. AndroidX and Kotlin (Apache 2.0) are fine.
- No Google Play Services, Firebase, analytics or proprietary libraries.
- No bundled closed speech or gesture engines.
- Emoji are never bundled: they're drawn with the phone's font. The list and
  keywords come from Unicode emoji data and CLDR (Unicode-3.0), emojilib and
  GitHub gemoji (MIT), built by [tools/emojigen](tools/emojigen/build_emoji.py)
  with each source's license shipped as `assets/emoji/LICENSE-*.txt`, plus
  Weft's own [extra_keywords.tsv](tools/emojigen/extra_keywords.tsv).
- Dictionary data must come from openly licensed sources (see
  [tools/dictgen/README.md](tools/dictgen/README.md)), and every source is
  credited in the app.
- Stays F-Droid compatible (dependency metadata blob is already disabled).

## License

GPL-3.0-only. See [LICENSE](LICENSE).
