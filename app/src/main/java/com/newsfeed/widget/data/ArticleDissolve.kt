package com.newsfeed.widget.data

/**
 * Staged "dissolve into dots" for a just-read article under Show = "Unread only", shown
 * during the last part of its UNREAD_GRACE_PERIOD_MS window. Glance cannot animate, so the
 * effect is a few discrete re-renders (scheduled by glance/UnreadGracePeriod.kt):
 *   stage 0: readAt + 0    .. 2.5s -> normal text
 *   stage 1: readAt + 2.5s .. 4s   -> about half of the non-space characters become a middle dot
 *   stage 2: readAt + 4s   .. 5s   -> every non-space character becomes a middle dot
 * Pure functions only, so all of it is unit-testable off-device.
 *
 * Both stage boundaries must stay below UNREAD_GRACE_PERIOD_MS (asserted in the tests).
 */
const val DISSOLVE_STAGE1_MS = 2_500L
const val DISSOLVE_STAGE2_MS = 4_000L

private const val DOT = '·'.code
private const val SCHEDULE_BUFFER_MS = 100L

/** 0 = normal, 1 = half dissolved, 2 = fully dissolved. A null readAt is always stage 0. */
fun dissolveStage(readAt: Long?, now: Long): Int {
    if (readAt == null) return 0
    val elapsed = now - readAt
    return when {
        elapsed >= DISSOLVE_STAGE2_MS -> 2
        elapsed >= DISSOLVE_STAGE1_MS -> 1
        else -> 0
    }
}

/**
 * Replaces non-whitespace characters with a middle dot, per code point (so surrogate pairs
 * are never split) and keeping whitespace, so word shapes and RTL layout survive. Stage 1
 * replaces every second non-space character; which parity is replaced comes from [seed], so
 * the result is stable across re-renders.
 */
fun dissolveText(text: String, stage: Int, seed: String): String {
    if (stage <= 0 || text.isEmpty()) return text
    val parity = seed.hashCode() and 1
    val sb = StringBuilder(text.length)
    var index = 0
    var n = 0 // count of non-space code points seen so far
    while (index < text.length) {
        val cp = text.codePointAt(index)
        index += Character.charCount(cp)
        if (Character.isWhitespace(cp)) {
            sb.appendCodePoint(cp)
        } else {
            val dissolve = stage >= 2 || (n and 1) == parity
            sb.appendCodePoint(if (dissolve) DOT else cp)
            n++
        }
    }
    return sb.toString()
}

/**
 * The article as it should be drawn at [now]: title and description dissolved if it is a
 * just-read article inside its dissolve window. The caller only uses this under the
 * Unread-only filter; other filters must show the article unchanged.
 */
fun dissolveArticle(article: ArticleItem, now: Long): ArticleItem {
    if (!article.isRead) return article
    val stage = dissolveStage(article.readAt, now)
    if (stage == 0) return article
    return article.copy(
        title = dissolveText(article.title, stage, article.id + ":t"),
        description = dissolveText(article.description, stage, article.id + ":d"),
    )
}

/**
 * Delays (from the moment an article is marked read) at which the widget must re-render:
 * each dissolve stage boundary, then the removal. The small buffer makes each fire strictly
 * after its boundary, never before it.
 */
fun graceRefreshDelays(): List<Long> = listOf(
    DISSOLVE_STAGE1_MS + SCHEDULE_BUFFER_MS,
    DISSOLVE_STAGE2_MS + SCHEDULE_BUFFER_MS,
    UNREAD_GRACE_PERIOD_MS + SCHEDULE_BUFFER_MS,
)
