package com.newsfeed.widget.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ReleaseNote(val id: Int, val bullets: List<String>)

/**
 * Fetches docs/RELEASE_NOTES.md's raw content straight from GitHub (no CI/build-pipeline
 * change needed — it's just another file in the repo, fetched the same way TelegramFeedParser
 * scrapes an ordinary web page) and parses out plain-language entries.
 */
object ReleaseNotesFetcher {
    private const val RAW_URL =
        "https://raw.githubusercontent.com/Had-com/NewsFeed-widget/main/docs/RELEASE_NOTES.md"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val NOTE_HEADING_REGEX = Regex("""^##\s*Note\s+(\d+)\s*$""")
    private val BULLET_REGEX = Regex("""^-\s+(.+)$""")

    /**
     * Splits raw Markdown into one ReleaseNote per "## Note <n>" heading, each followed by one
     * or more "- " bullet lines, ending at the next heading or end of file. A heading with a
     * non-numeric id, or one with no bullet lines under it, is skipped individually rather
     * than aborting the whole parse — one malformed entry shouldn't hide every other
     * legitimate one.
     */
    internal fun parseNotes(markdown: String): List<ReleaseNote> {
        val lines = markdown.lines()
        val notes = mutableListOf<ReleaseNote>()
        var currentId: Int? = null
        var currentBullets = mutableListOf<String>()

        fun flush() {
            val id = currentId
            if (id != null && currentBullets.isNotEmpty()) {
                notes += ReleaseNote(id, currentBullets.toList())
            }
        }

        for (line in lines) {
            val headingMatch = NOTE_HEADING_REGEX.find(line.trim())
            if (headingMatch != null) {
                flush()
                currentId = headingMatch.groupValues[1].toIntOrNull()
                currentBullets = mutableListOf()
                continue
            }
            val bulletMatch = BULLET_REGEX.find(line)
            if (bulletMatch != null && currentId != null) {
                currentBullets += bulletMatch.groupValues[1].trim()
            }
        }
        flush()
        return notes
    }

    /**
     * Returns every note with id > [sinceId], oldest first. Empty list (not null) on any
     * fetch/parse failure, or when there's genuinely nothing new — both look identical to the
     * caller, which is correct: either way, there's nothing to show. A failed fetch must never
     * block the update flow (matches this app's existing tolerant-degradation convention).
     */
    suspend fun fetchUnseenNotes(sinceId: Int): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val markdown = try {
            val request = Request.Builder().url(RAW_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()
        parseNotes(markdown).filter { it.id > sinceId }.sortedBy { it.id }
    }
}
