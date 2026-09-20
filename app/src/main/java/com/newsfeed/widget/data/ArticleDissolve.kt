package com.newsfeed.widget.data

/**
 * Staged "dissolve" for a just-read article under Show = "Unread only", shown during the
 * last part of its UNREAD_GRACE_PERIOD_MS window: all text turns into middle dots, then the
 * dots are erased from the END toward the beginning until the article is removed. Glance
 * cannot animate, so this is a few discrete re-renders (scheduled by
 * glance/UnreadGracePeriod.kt). Pure functions only, so it is unit-testable off-device.
 *
 *   stage 0: readAt + 0     .. 2.5s   normal text
 *   stage 1: readAt + 2.5s  .. 3.33s  every non-space char is a dot, full length
 *   stage 2: readAt + 3.33s .. 4.17s  first 2/3 of the dots remain
 *   stage 3: readAt + 4.17s .. 5s     first 1/3 of the dots remain
 *   >= 5s: removed by applyFilterAndSort
 *
 * To change the animation, edit the two lists below (same size; entry i describes stage i+1).
 * Every start must be increasing and below UNREAD_GRACE_PERIOD_MS (asserted in the tests).
 */
val DISSOLVE_STAGE_STARTS_MS: List<Long> = listOf(2_500L, 3_333L, 4_167L)

/** Fraction of the text kept at each stage, as numerator to denominator (all-dots, 2/3, 1/3). */
val DISSOLVE_KEEP_FRACTIONS: List<Pair<Int, Int>> = listOf(1 to 1, 2 to 3, 1 to 3)

private const val DOT = '·'.code
private const val SCHEDULE_BUFFER_MS = 100L

/** 0 = normal, 1..DISSOLVE_STAGE_STARTS_MS.size = dissolving. A null readAt is always 0. */
fun dissolveStage(readAt: Long?, now: Long): Int {
    if (readAt == null) return 0
    val elapsed = now - readAt
    return DISSOLVE_STAGE_STARTS_MS.count { elapsed >= it }
}

/**
 * Stage 0 returns [text] unchanged. Otherwise every non-whitespace code point becomes a
 * middle dot (whitespace kept, so word shapes and RTL layout survive), then the result is cut
 * to the first part given by DISSOLVE_KEEP_FRACTIONS[stage - 1] (counted in code points, so
 * surrogate pairs are never split) and trailing whitespace is trimmed. Non-blank text always
 * keeps at least one dot, so it never turns empty before the removal itself.
 */
fun dissolveText(text: String, stage: Int): String {
    if (stage <= 0 || text.isBlank()) return text
    val (num, den) = DISSOLVE_KEEP_FRACTIONS[(stage - 1).coerceAtMost(DISSOLVE_KEEP_FRACTIONS.lastIndex)]
    val cps = text.codePoints().map { if (Character.isWhitespace(it)) it else DOT }.toArray()
    val keep = (cps.size.toLong() * num / den).toInt().coerceAtLeast(1)
    val cut = String(cps, 0, keep).trimEnd()
    return cut.ifEmpty { "·" }
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
        title = dissolveText(article.title, stage),
        description = dissolveText(article.description, stage),
    )
}

/**
 * Delays (from the moment an article is marked read) at which the widget must re-render:
 * each dissolve stage boundary, then the removal. The small buffer makes each fire strictly
 * after its boundary, never before it.
 */
fun graceRefreshDelays(): List<Long> =
    (DISSOLVE_STAGE_STARTS_MS + UNREAD_GRACE_PERIOD_MS).map { it + SCHEDULE_BUFFER_MS }

/**
 * How long to wait from [now] for each re-render still needed, given the article was marked
 * read at [markedAt] (the same instant stamped into its readAt). Counting from the mark, not
 * from whenever scheduling happens, matters because the callback's own update() can take over
 * a second on a heavy row, which would otherwise push every stage late.
 *
 * Boundaries still in the future keep their (shortened) wait. Boundaries already passed are
 * superseded by the latest one, so they collapse into at most ONE immediate (0) catch-up
 * update - and if the removal itself is already due, that single update is all that remains.
 * Never negative; increasing.
 */
fun remainingRefreshDelays(markedAt: Long, now: Long): List<Long> {
    val waits = graceRefreshDelays().map { markedAt + it - now }
    val future = waits.filter { it > 0L }
    val anyPassed = future.size < waits.size
    return if (anyPassed) listOf(0L) + future else future
}
