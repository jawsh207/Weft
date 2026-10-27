package app.weft.keyboard.layout

/**
 * Key centers measured in key widths, derived from the same weights the UI
 * draws with. Used for typo proximity now and swipe-path matching later.
 *
 * @param rowAspect row height divided by a standard key's width
 *   (58dp rows on a ~40dp-wide key ≈ 1.45). SwipeArea maps every key height onto
 *   this spacing, so the setting doesn't change swipe geometry.
 */
object KeyGeometry {
    fun centers(rows: List<KeyRow>, rowAspect: Float = 1.45f): Map<Int, FloatArray> {
        val out = HashMap<Int, FloatArray>()
        rows.forEachIndexed { r, row ->
            var x = 0f
            for (key in row) {
                if (key is CharKey && key.output.codePointCount(0, key.output.length) == 1) {
                    val cp = Character.toLowerCase(key.output.codePointAt(0))
                    out[cp] = floatArrayOf(x + key.weight / 2f, r * rowAspect)
                }
                x += key.weight
            }
        }
        return out
    }
}
