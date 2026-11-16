# How Weft works

A plain-language tour of the code, for anyone who wants to know what happens
when they press a key, and where to look to change it. No Android knowledge is
assumed. Every source file also opens with its own description; this page is
the map that connects them.

All the app's code lives under `app/src/main/java/app/weft/keyboard/`. Paths
below are relative to that folder.

## The big picture

Android starts one small program for the keyboard, the **keyboard service**.
It draws the keys, hears every touch, and types into whatever app is in front.
It does three things on every key press:

1. **Works out what you meant.** A letter is a letter, but a finished word may
   be a typo, and a swipe is a path that has to become a word.
2. **Types it** into the app's text field.
3. **Updates the suggestion strip** above the keys.

Everything stays on the phone. Weft has no internet permission, so nothing
typed, learned or logged can be sent anywhere.

## What happens when…

| You do this | This code handles it |
|---|---|
| Tap into a text field | `ime/WeftKeyboardService.kt` picks the page (letters, number pad…) and what the enter key does; `ime/FieldPolicy.kt` decides whether suggestions, autocorrect, learning and swipe are allowed there (never in passwords) |
| Tap a letter | `ui/KeyboardScreen.kt` draws the key and reports the tap; the service types the letter |
| Finish a word with space | `ime/TypingController.kt` asks `predict/Suggester.kt` whether the word needs correcting, replaces it if so, and remembers the change so backspace can undo it |
| Look at the three suggestions | `predict/Suggester.kt` finds and ranks them; `ime/TypingController.kt` arranges them in the strip |
| Swipe across letters | `ui/SwipeArea.kt` follows the finger and draws the trail; `predict/SwipeDecoder.kt` turns the path into words |
| Hold a key | `ui/AlternatesPopup.kt` shows accents, fractions or skin tones; the choices are listed in `layout/KeyboardLayout.kt` |
| Use the tools row (select all, cut, copy, paste…) | `ui/KeyboardScreen.kt` draws the buttons; `ime/WeftKeyboardService.kt` asks the app to do the edit |
| Turn the phone sideways | `layout/KeyboardSize.kt` shrinks the rows so the keyboard takes about half the screen |
| Open the emoji page | `ui/EmojiPicker.kt` draws it; `emoji/EmojiCatalog.kt` holds the list and searches it; `emoji/EmojiHistory.kt` remembers recents and skin tones |
| Tap the microphone | `voice/SystemVoiceInput.kt` starts whichever speech engine the phone is set to use; `voice/VoiceText.kt` tidies what comes back (spoken punctuation, capitals) |
| Open Weft's settings | `settings/SettingsActivity.kt` (setup and the menu), `settings/SettingsPages.kt` (each page), `settings/LearnedWordsActivity.kt` (view and edit what was learned) |

## The parts

### `ime/` — the keyboard itself

- **`WeftKeyboardService.kt`** is the program Android starts. It owns
  everything else, receives every key press, and writes text into the app.
- **`TypingController.kt`** is the word-level behavior: autocorrect when a word
  ends, undoing it with backspace, spacing around punctuation, the capital
  "I", and deciding what gets learned. It knows nothing about Android, so it
  is tested by typing into a pretend text field.
- **`KeyboardState.kt`** is the list of things the keys on screen depend on
  (which page, shift, the suggestions…). Changing one redraws what uses it.
- **`FieldPolicy.kt`** decides, for each text field, what prediction may do.
- **`DictionaryLoader.kt`** finds the word list in the app and opens it.
- **`LearnedStore.kt`** makes sure the keyboard and the settings screen use
  the same copy of what was learned.
- **`KeyboardLog.kt`** keeps the troubleshooting log (Settings › About): when
  the keyboard was asked to open, whether it did, and any crash. Never text.

### `predict/` — guessing what you meant

Pure logic with no Android in it, which is why it can be tested and
benchmarked on any computer.

- **`BinaryDictionary.kt`** reads the dictionary file: about 150,000 words,
  how common each is, and which words tend to follow which.
- **`Suggester.kt`** finds dictionary words close to what was typed and ranks
  them. This is where suggestions, autocorrect and next-word predictions come
  from.
- **`KeyProximity.kt`** knows which keys are neighbours, so hitting "r" for
  "e" counts as a small slip and "p" for "e" as a big one.
- **`SwipeDecoder.kt`** turns a swiped path into words, using the shape of the
  path, where the finger slowed down, and how well each word fits the sentence.
- **`LearnedWords.kt`** is everything learned from your typing: new words, how
  often you use words, which word you type after which, corrections you
  undid, words you removed, and your swiping habits. Saved in two small files
  in the app's private storage.
- **`TextContext.kt`** works out, from the text before the cursor, which word
  is being typed and what came before it.

### `layout/` — what is on the keyboard

- **`KeyboardLayout.kt`** lists the keys on each page and the long-press
  alternates.
- **`KeyGeometry.kt`** works out where each key's center is, which typo
  correction and swipe typing both need.
- **`KeyboardSize.kt`** decides how tall the key rows are: the user's choice
  in portrait, shorter on a phone held sideways.

### `ui/` — drawing and touch

- **`KeyboardScreen.kt`** draws the suggestion strip and the keys, and handles
  taps, holding, and sliding on the space bar to move the cursor.
- **`SwipeArea.kt`** sits over the letter rows and takes over when a touch
  becomes a swipe.
- **`AlternatesPopup.kt`** is the long-press popup.
- **`EmojiPicker.kt`** is the emoji page.
- **`KeyFeel.kt`** is vibration strength, key click sounds and key height.

### `voice/`, `emoji/`, `theme/`, `settings/`

- **`voice/`** uses the phone's own speech engine (whichever the user chose in
  system settings), tidies the result, and knows how to open the system page
  where that engine is chosen.
- **`emoji/`** loads the emoji list, searches it, and remembers recents and
  skin tones. Emoji are drawn with the phone's own font, never bundled.
- **`theme/SystemTheme.kt`** takes colors from the phone's wallpaper (Material
  You) and follows dark mode.
- **`settings/`** is Weft's own settings screens; `Prefs.kt` lists every
  setting and its default.

## Where the data comes from

The dictionary and emoji list are not written by hand. Scripts build them from
open data, and the results are stored in the app:

- **`tools/dictgen/`** builds the dictionaries (`app/src/main/assets/dictionaries/`).
  Its [README](../tools/dictgen/README.md) lists every source and its license.
- **`tools/emojigen/`** builds the emoji list (`app/src/main/assets/emoji/`).
- **`tools/bench/`** prepares the test sentences used to measure accuracy.

They run automatically on GitHub (`.github/workflows/`), which also runs the
tests, builds the app and publishes releases.

## How we know it works

`app/src/test/` holds the tests. Besides ordinary checks there are two
measurements that run on every build and print their scores:

- **`TypingBenchmarkTest`** types real sentences and real misspellings and
  reports how many typos were fixed, how many correct words were wrongly
  changed (this must stay at zero), and how many key presses predictions save.
- **`SwipeAccuracyTest`** draws thousands of simulated swipes (neat, natural
  and hurried) and reports how often the right word comes out first.

More detail on swipe typing, including what was tried and dropped, is in
[swipe-typing.md](swipe-typing.md).
