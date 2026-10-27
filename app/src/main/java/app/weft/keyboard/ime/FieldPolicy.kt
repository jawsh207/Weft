package app.weft.keyboard.ime

/** What a text field looks like to the keyboard, read from its EditorInfo. */
data class FieldTraits(
    val isText: Boolean,
    val isPassword: Boolean,
    /** The app set TYPE_TEXT_FLAG_NO_SUGGESTIONS. */
    val noSuggestions: Boolean,
    /** Email, URL or filter fields, where words aren't dictionary words. */
    val technical: Boolean,
    /** The app asked for no personalized learning (e.g. incognito). */
    val noLearning: Boolean,
)

/** The user's typing settings. */
data class TypingPrefs(
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val learn: Boolean,
    val swipe: Boolean,
)

/**
 * What prediction may do in a field. Decided in one place so tap typing and
 * swipe typing always agree on where words come from the dictionary.
 *
 * Apps often set "no suggestions" just to hide spell-check underlines (many
 * web pages do it with spellcheck="false"), yet still expect a keyboard to
 * help. So in those fields Weft still shows suggestions and corrections in the
 * strip, the same words swipe would produce, but never replaces a word on its
 * own: the field may hold a username, code or a name.
 */
data class FieldPolicy(
    val suggestions: Boolean,
    val autocorrect: Boolean,
    val learning: Boolean,
    val swipe: Boolean,
) {
    companion object {
        fun of(field: FieldTraits, prefs: TypingPrefs): FieldPolicy {
            val predictable = field.isText && !field.isPassword
            val suggestions = prefs.suggestions && predictable
            return FieldPolicy(
                suggestions = suggestions,
                autocorrect = suggestions && prefs.autocorrect && !field.technical && !field.noSuggestions,
                // Emails and URLs aren't words: learning them would only add noise.
                learning = prefs.learn && !field.isPassword && !field.noLearning && !field.technical,
                swipe = prefs.swipe && predictable && !field.technical,
            )
        }
    }
}
