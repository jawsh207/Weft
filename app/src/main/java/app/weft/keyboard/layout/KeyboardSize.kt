package app.weft.keyboard.layout

/**
 * How tall the rows of keys should be. In portrait that is simply the height
 * the user chose in settings. In landscape a phone's screen is short, and a
 * full-height keyboard would cover almost all of the app, so the rows shrink
 * until the keyboard takes about half the screen (but never so far that keys
 * become hard to hit). Tablets and unfolded foldables are tall enough in
 * landscape that nothing changes.
 *
 * Plain numbers in, plain numbers out (all in dp), so it can be unit tested.
 */
object KeyboardSize {
    /** The share of a landscape screen's height the keyboard aims to stay within. */
    const val LANDSCAPE_SHARE = 0.55f

    /** Rows never get shorter than this, however small the screen. */
    const val MIN_ROW_DP = 34f

    /** Height of the suggestion strip above the keys. */
    const val STRIP_DP = 48f

    /** Padding above the strip and the clearance below the bottom row. */
    const val PADDING_DP = 21f

    /** Rows of keys on every page, and how tall the optional number row is next to them. */
    const val KEY_ROWS = 4
    const val NUMBER_ROW_SCALE = 0.82f

    /**
     * The height of one key row.
     *
     * @param chosenDp the row height from settings (Short, Normal or Tall).
     * @param screenHeightDp the height of the screen as it is currently held.
     * @param landscape whether the phone is on its side.
     * @param numberRow whether the extra row of digits is showing.
     */
    fun rowHeight(chosenDp: Float, screenHeightDp: Int, landscape: Boolean, numberRow: Boolean): Float {
        if (!landscape || screenHeightDp <= 0) return chosenDp
        val rows = KEY_ROWS + if (numberRow) NUMBER_ROW_SCALE else 0f
        val fits = (screenHeightDp * LANDSCAPE_SHARE - STRIP_DP - PADDING_DP) / rows
        return fits.coerceIn(minOf(MIN_ROW_DP, chosenDp), chosenDp)
    }

    /** The whole keyboard's height for a given row height: strip, rows and padding. */
    fun totalHeight(rowDp: Float, numberRow: Boolean): Float =
        STRIP_DP + PADDING_DP + rowDp * (KEY_ROWS + if (numberRow) NUMBER_ROW_SCALE else 0f)
}
