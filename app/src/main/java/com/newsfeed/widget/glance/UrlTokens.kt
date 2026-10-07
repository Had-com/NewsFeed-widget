package com.newsfeed.widget.glance

/**
 * Pure (Android-free, unit-testable) helpers for [TextBitmapHelper]'s "widest unbroken word"
 * auto-shrink check. BUG-025: a long URL in the text counted as one huge word, so the whole
 * bitmap was shrunk to a tiny size to fit it. A URL does not need to stay intact - it wraps
 * by characters like it would in any text view - so it is excluded from that measurement.
 */
object UrlTokens {

    private val URL_START_REGEX = Regex("""^[(\[<"'“]*https?://""", RegexOption.IGNORE_CASE)
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** True when [token] (one whitespace-delimited word) is a http(s) URL, optionally wrapped in ( [ < or quotes. */
    fun isUrlToken(token: String): Boolean = URL_START_REGEX.containsMatchIn(token)

    /**
     * The width of the widest whitespace-delimited word in [text] that is NOT a URL token, as
     * measured by [measure]; 0 when there is no such word. Same split as the original
     * inline check in TextBitmapHelper.render, just with URL tokens skipped.
     */
    fun widestWordIgnoringUrls(text: String, measure: (String) -> Float): Float =
        text.split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() && !isUrlToken(it) }
            .maxOfOrNull(measure) ?: 0f
}
