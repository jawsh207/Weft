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
- Starter emoji panel using system glyphs, filtered by `Paint.hasGlyph()`
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

## Building

1. Open the folder in Android Studio (a recent stable release).
2. Let Gradle sync. Android Studio may offer newer dependency versions —
   accepting them is fine.
3. Build the dictionary: `python3 tools/dictgen/make_dict.py en_US`
   (see [tools/dictgen/README.md](tools/dictgen/README.md)). Without it the
   keyboard still works, with learned words only.
4. Run the `app` configuration on an Android 16+ device or emulator.
5. Follow the setup screen to enable the keyboard.

From the command line: `./gradlew assembleDebug`.
Unit tests (prediction engine and typing behavior): `./gradlew test`.

## Project layout

```
app/src/main/java/app/weft/keyboard/
├── ime/        WeftKeyboardService (the IME), TypingController, KeyboardState
├── predict/    Dictionary reader, fuzzy search, ranking, learned words (no Android deps)
├── layout/     Key model, page layouts and key geometry
├── ui/         Compose keyboard, key button with Expressive motion
├── theme/      SystemTheme: dynamic color + MaterialExpressiveTheme
├── voice/      SystemVoiceInput: wraps android.speech.SpeechRecognizer
├── emoji/      SystemEmoji: system-font emoji, unsupported ones filtered out
└── settings/   SettingsActivity: onboarding and test field
```

## Roadmap

1. ~~Prediction~~ — done. Next: long-press a suggestion to forget a learned word,
   learn word pairs from the user's own typing.
2. **Full emoji picker** — generated from Unicode `emoji-test.txt`: categories,
   search by name, skin tones, recents.
3. **Long-press alternates** — accented letters and key popups.
4. **Settings** — haptics, key height, on-device voice preference, number row.
5. **Large screens** — floating, one-handed and split layouts for tablets and foldables.
6. **Gesture typing** — swipe path capture and decoding against the shared key geometry.
7. **More languages** — layouts and dictionaries per subtype.

## FOSS rules for this repo

- Only OSI-licensed dependencies. AndroidX and Kotlin (Apache 2.0) are fine.
- No Google Play Services, Firebase, analytics or proprietary libraries.
- No bundled closed speech or gesture engines.
- Dictionary data must come from openly licensed sources (see
  [tools/dictgen/README.md](tools/dictgen/README.md)), and every source is
  credited in the app.
- Stays F-Droid compatible (dependency metadata blob is already disabled).

## License

GPL-3.0-only. See [LICENSE](LICENSE).
