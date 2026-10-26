package app.weft.keyboard.ime

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.weft.keyboard.emoji.EmojiCatalog
import app.weft.keyboard.layout.KeyboardPage

enum class ShiftState { Off, Once, Locked }

/** UI state observed by the Compose keyboard. Owned by the IME service. */
@Stable
class KeyboardState {
    var page by mutableStateOf(KeyboardPage.Letters)
    var shift by mutableStateOf(ShiftState.Off)

    /** Resolved editor action for the enter key, or IME_ACTION_NONE for a newline. */
    var enterAction by mutableIntStateOf(EditorInfo.IME_ACTION_NONE)

    /** Three slots (left, center, right); null slots are empty. Empty list = no strip. */
    var suggestions by mutableStateOf<List<SuggestionChip?>>(emptyList())

    // --- Emoji picker ---
    var emojiCatalog by mutableStateOf<EmojiCatalog?>(null)
    var emojiRecents by mutableStateOf<List<String>>(emptyList())

    /** Chosen skin tone per base emoji. */
    var emojiTones by mutableStateOf<Map<String, String>>(emptyMap())

    /** Text typed into emoji search; null when not searching. */
    var emojiQuery by mutableStateOf<String?>(null)

    var voiceAvailable by mutableStateOf(false)
    var listening by mutableStateOf(false)

    /** Short status line in the strip (voice errors etc.). Null = nothing to show. */
    var message by mutableStateOf<String?>(null)

    /** Bumped to make SystemTheme re-read the system palette. */
    var themeEpoch by mutableIntStateOf(0)
}
