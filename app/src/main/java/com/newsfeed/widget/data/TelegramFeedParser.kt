package com.newsfeed.widget.data

/**
 * Converts public Telegram channels into the same ArticleItem shape every RSS/Atom feed
 * produces, by scraping each channel's public, no-login HTML preview page
 * (https://t.me/s/<channel>) instead of parsing XML. See
 * docs/superpowers/specs/2026-09-08-telegram-rss-converter-design.md for the full design.
 *
 * Deliberately Android-framework-free (no android.text.Html, no network I/O) so every
 * function here is a plain, directly unit-testable Kotlin function. Network fetching stays
 * in NewsFeedRepository, which already owns a shared OkHttpClient and browser headers for
 * every other feed type.
 */
object TelegramFeedParser {

    private val CHANNEL_REF_REGEX = Regex(
        """^(?:https?://)?(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)/?$"""
    )
    private val AT_HANDLE_REGEX = Regex("""^@([A-Za-z0-9_]+)$""")

    /**
     * Recognizes a Telegram channel reference typed or pasted into the Add Feed field and
     * returns the canonical https://t.me/s/<channel> fetch URL, or null if [raw] doesn't
     * look like a Telegram reference at all (the caller should fall through to normal
     * RSS-URL handling in that case).
     */
    fun canonicalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        AT_HANDLE_REGEX.find(trimmed)?.let { return "https://t.me/s/${it.groupValues[1]}" }
        CHANNEL_REF_REGEX.find(trimmed)?.let { return "https://t.me/s/${it.groupValues[1]}" }
        return null
    }
}
