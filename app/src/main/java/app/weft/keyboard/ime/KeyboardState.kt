package app.weft.keyboard.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.weft.keyboard.emoji.EmojiCatalog
import app.weft.keyboard.layout.KeyboardPage
import app.weft.keyboard.ui.HapticLevel
import app.weft.keyboard.ui.KeyHeight

/** The shift key: off, on for the next letter only, or caps lock. */
enum class ShiftState { Off, Once, Locked }

/**
 * Everything the keyboard on screen depends on: which page is showing, shift,
 * the suggestions, emoji picker state, look-and-feel settings and status
 * messages. The service ([WeftKeyboardService]) changes these values; the
 * Compose UI redraws whatever depends on the one that changed.
 */
@Stable
class KeyboardState {
    /** The page showing: letters, symbols, number pad or emoji. */
    var page by mutableStateOf(KeyboardPage.Letters)
    /** Shift or caps lock, also set automatically at the start of a sentence. */
    var shift by mutableStateOf(ShiftState.Off)

    /** Resolved editor action for the enter key, or IME_ACTION_NONE for a newline. */
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_NONE)

    /** Three slots (left, center, right); null slots are empty. Empty list = no strip. */
    var suggestions by mutableStateOf<List<SuggestionChip?>>(emptyList())

    /** A suggestion the user long-pressed, waiting for "Remove" to be confirmed. */
    var pendingRemoval by mutableStateOf<String?>(null)

    // --- Emoji picker ---
    /** The emoji list, once loaded from assets; null until then. */
    var emojiCatalog by mutableStateOf<EmojiCatalog?>(null)
    /** Recently used emoji, most recent first. */
    var emojiRecents by mutableStateOf<List<String>>(emptyList())

    /** Chosen skin tone per base emoji. */
    var emojiTones by mutableStateOf<Map<String, String>>(emptyMap())

    /** Text typed into emoji search; null when not searching. */
    var emojiQuery by mutableStateOf<String?>(null)

    /** Swipe typing on the letter keys (off in password fields or if disabled in settings). */
    var swipeEnabled by mutableStateOf(false)

    /** The tools row (paste, clear clipboard, settings) is slid over the suggestions. */
    var toolsOpen by mutableStateOf(false)

    /** Something is on the clipboard, so Paste and Clear have work to do. */
    var clipboardHasContent by mutableStateOf(false)

    // --- Look and feel (Settings) ---
    /** Vibration strength for key presses. */
    var haptics by mutableStateOf(HapticLevel.Normal)
    /** Short, normal or tall keys. */
    var keyHeight by mutableStateOf(KeyHeight.Normal)
    /** Whether a row of digits shows above the letters. */
    var numberRow by mutableStateOf(false)

    /** Tapered swipe trail and lit letters (Settings › Swipe › Enhanced visuals); off shows a plain trail. */
    var swipeVisuals by mutableStateOf(true)

    /** The phone has a speech engine, so the mic key is shown. */
    var voiceAvailable by mutableStateOf(false)
    /** Voice input is running; the strip shows its status instead of suggestions. */
    var listening by mutableStateOf(false)

    /** Short status line in the strip (voice errors etc.). Null = nothing to show. */
    var message by mutableStateOf<String?>(null)

    /** Bumped to make SystemTheme re-read the system palette. */
    var themeEpoch by mutableIntStateOf(0)
}
