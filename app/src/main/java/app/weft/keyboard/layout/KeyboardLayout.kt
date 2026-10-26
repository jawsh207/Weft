package app.weft.keyboard.layout

/**
 * Key model shared by tap typing now and gesture typing later: every key has a
 * width weight, and each row's weights add up to the same total (10) so key
 * geometry is predictable for swipe-path matching.
 */
sealed interface Key {
    val weight: Float
}

data class CharKey(
    val label: String,
    val output: String = label,
    override val weight: Float = 1f,
) : Key

data class ActionKey(
    val action: KeyAction,
    override val weight: Float = 1f,
) : Key

/** Empty space, e.g. the half-key indent on the middle letter row. */
data class Gap(override val weight: Float) : Key

enum class KeyAction {
    Shift,
    Backspace,
    Enter,
    Space,
    ToLetters,
    ToSymbols,
    ToMoreSymbols,
    Emoji,
}

enum class KeyboardPage { Letters, Symbols, MoreSymbols, Emoji }

typealias KeyRow = List<Key>

object Layouts {
    private fun chars(s: String): List<Key> = s.map { CharKey(it.toString()) }

    private fun bottomRow(switchTo: KeyAction): KeyRow = listOf(
        ActionKey(switchTo, 1.5f),
        CharKey(","),
        ActionKey(KeyAction.Emoji),
        ActionKey(KeyAction.Space, 4f),
        CharKey("."),
        ActionKey(KeyAction.Enter, 1.5f),
    )

    val qwerty: List<KeyRow> = listOf(
        chars("qwertyuiop"),
        listOf(Gap(0.5f)) + chars("asdfghjkl") + Gap(0.5f),
        listOf(ActionKey(KeyAction.Shift, 1.5f)) + chars("zxcvbnm") +
            ActionKey(KeyAction.Backspace, 1.5f),
        bottomRow(KeyAction.ToSymbols),
    )

    val symbols: List<KeyRow> = listOf(
        chars("1234567890"),
        chars("@#\$_&-+()/"),
        listOf(ActionKey(KeyAction.ToMoreSymbols, 1.5f)) + chars("*\"':;!?") +
            ActionKey(KeyAction.Backspace, 1.5f),
        bottomRow(KeyAction.ToLetters),
    )

    val moreSymbols: List<KeyRow> = listOf(
        chars("~`|•√π÷×¶∆"),
        chars("£¢€¥^°={}\\"),
        listOf(ActionKey(KeyAction.ToSymbols, 1.5f)) + chars("%©®™✓[]") +
            ActionKey(KeyAction.Backspace, 1.5f),
        bottomRow(KeyAction.ToLetters),
    )

    /** Bottom row shown under the emoji panel. */
    val emojiBottom: KeyRow = listOf(
        ActionKey(KeyAction.ToLetters, 1.5f),
        ActionKey(KeyAction.Space, 7f),
        ActionKey(KeyAction.Backspace, 1.5f),
    )

    fun rowsFor(page: KeyboardPage): List<KeyRow> = when (page) {
        KeyboardPage.Letters -> qwerty
        KeyboardPage.Symbols -> symbols
        KeyboardPage.MoreSymbols -> moreSymbols
        KeyboardPage.Emoji -> emptyList()
    }
}
