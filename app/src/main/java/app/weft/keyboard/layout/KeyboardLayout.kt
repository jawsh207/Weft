package app.weft.keyboard.layout

/**
 * Key model shared by tap typing now and gesture typing later: every key has a
 * width weight, and the rows of a page add up to the same total so key geometry
 * is predictable for swipe-path matching.
 */
sealed interface Key {
    val weight: Float
}

data class CharKey(
    val label: String,
    val output: String = label,
    override val weight: Float = 1f,
    /** Drawn in the function-key color (operators and punctuation on the number pad). */
    val secondary: Boolean = false,
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
    ToNumpad,
    Emoji,
}

enum class KeyboardPage { Letters, Symbols, MoreSymbols, Numpad, Emoji }

typealias KeyRow = List<Key>

object Layouts {
    private fun chars(s: String): List<Key> = s.map { CharKey(it.toString()) }

    private fun letterBottomRow(): KeyRow = listOf(
        ActionKey(KeyAction.ToSymbols, 1.5f),
        CharKey(","),
        ActionKey(KeyAction.Emoji),
        ActionKey(KeyAction.Space, 4f),
        CharKey("."),
        ActionKey(KeyAction.Enter, 1.5f),
    )

    /** Symbol pages swap the emoji key for the number pad and put "/" beside space. */
    private fun symbolBottomRow(): KeyRow = listOf(
        ActionKey(KeyAction.ToLetters, 1.5f),
        CharKey(","),
        ActionKey(KeyAction.ToNumpad),
        ActionKey(KeyAction.Space, 3f),
        CharKey("/"),
        CharKey("."),
        ActionKey(KeyAction.Enter, 1.5f),
    )

    val qwerty: List<KeyRow> = listOf(
        chars("qwertyuiop"),
        listOf(Gap(0.5f)) + chars("asdfghjkl") + Gap(0.5f),
        listOf(ActionKey(KeyAction.Shift, 1.5f)) + chars("zxcvbnm") +
            ActionKey(KeyAction.Backspace, 1.5f),
        letterBottomRow(),
    )

    /** Optional digits above the letters (Settings › Number row). */
    val numberRow: KeyRow = chars("1234567890")

    val symbols: List<KeyRow> = listOf(
        chars("1234567890"),
        chars("@#\$_%&-+()"),
        listOf(ActionKey(KeyAction.ToMoreSymbols, 1.5f)) + chars("*\"':;!?") +
            ActionKey(KeyAction.Backspace, 1.5f),
        symbolBottomRow(),
    )

    val moreSymbols: List<KeyRow> = listOf(
        chars("~`|•√π÷×¶∆"),
        chars("£¢€¥^°={}\\"),
        listOf(ActionKey(KeyAction.ToSymbols, 1.5f)) + chars("©®™[]<>") +
            ActionKey(KeyAction.Backspace, 1.5f),
        symbolBottomRow(),
    )

    /** Phone-style number pad: operators down the left, digits in a 3×3 grid. */
    val numpad: List<KeyRow> = run {
        fun digit(d: String) = CharKey(d, weight = 2.25f)
        fun side(s: String) = CharKey(s, weight = 1.5f, secondary = true)
        listOf(
            listOf(side("+"), digit("1"), digit("2"), digit("3"), side("%")),
            listOf(side("-"), digit("4"), digit("5"), digit("6"), ActionKey(KeyAction.Space, 1.5f)),
            listOf(side("*"), digit("7"), digit("8"), digit("9"), ActionKey(KeyAction.Backspace, 1.5f)),
            listOf(
                ActionKey(KeyAction.ToLetters, 1.5f),
                CharKey(",", secondary = true),
                ActionKey(KeyAction.ToSymbols, 1.25f),
                digit("0"),
                CharKey("=", weight = 1.25f, secondary = true),
                CharKey(".", secondary = true),
                ActionKey(KeyAction.Enter, 1.5f),
            ),
        )
    }

    /** Bottom row while typing an emoji search. Enter or the emoji key returns to the grid. */
    val emojiSearchBottom: KeyRow = listOf(
        ActionKey(KeyAction.ToLetters, 1.5f),
        ActionKey(KeyAction.Emoji),
        ActionKey(KeyAction.Space, 6f),
        ActionKey(KeyAction.Enter, 1.5f),
    )

    fun rowsFor(page: KeyboardPage): List<KeyRow> = when (page) {
        KeyboardPage.Letters -> qwerty
        KeyboardPage.Symbols -> symbols
        KeyboardPage.MoreSymbols -> moreSymbols
        KeyboardPage.Numpad -> numpad
        KeyboardPage.Emoji -> emptyList()
    }

    /**
     * Long-press alternates, in popup order. Top-row letters start with their
     * digit (like most keyboards), then accented forms.
     */
    private val alternates: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2", "ŵ"),
        "e" to listOf("3", "è", "é", "ê", "ë", "ē", "ę", "ė"),
        "r" to listOf("4", "ř"),
        "t" to listOf("5", "ť", "þ"),
        "y" to listOf("6", "ý", "ÿ", "ŷ"),
        "u" to listOf("7", "ù", "ú", "û", "ü", "ū", "ů"),
        "i" to listOf("8", "ì", "í", "î", "ï", "ī", "į", "ı"),
        "o" to listOf("9", "ò", "ó", "ô", "ö", "õ", "ø", "ō", "œ"),
        "p" to listOf("0"),
        "a" to listOf("à", "á", "â", "ä", "ã", "å", "ā", "ą", "æ"),
        "s" to listOf("ß", "ś", "š", "ş"),
        "d" to listOf("ď", "ð"),
        "g" to listOf("ğ"),
        "l" to listOf("ł", "ľ"),
        "z" to listOf("ž", "ź", "ż"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń", "ň"),
        "." to listOf("/", "@", "-", ";", ":", "\"", "'", "!", "?", "…"),
        "," to listOf("'", "\"", ";", ":"),
        "?" to listOf("¿", "‽"),
        "!" to listOf("¡"),
        "$" to listOf("€", "£", "¥", "¢", "₹", "₩", "₿"),
        "-" to listOf("–", "—", "_", "·"),
        "'" to listOf("‘", "’", "‚", "‹", "›"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "%" to listOf("‰", "℅"),
        "*" to listOf("†", "‡", "★"),
        "&" to listOf("§"),
        "+" to listOf("±"),
        "=" to listOf("≠", "≈", "∞"),
        // Digits: superscript, subscript, then the fractions with that digit on top.
        "1" to listOf("¹", "₁", "½", "⅓", "¼", "⅕", "⅙", "⅐", "⅛", "⅑", "⅒"),
        "2" to listOf("²", "₂", "⅔", "⅖"),
        "3" to listOf("³", "₃", "¾", "⅗", "⅜"),
        "4" to listOf("⁴", "₄", "⅘"),
        "5" to listOf("⁵", "₅", "⅚", "⅝"),
        "6" to listOf("⁶", "₆"),
        "7" to listOf("⁷", "₇", "⅞"),
        "8" to listOf("⁸", "₈"),
        "9" to listOf("⁹", "₉"),
        "0" to listOf("⁰", "₀", "↉", "°", "∅"),
    )

    fun alternatesFor(output: String): List<String> = alternates[output.lowercase()].orEmpty()
}
