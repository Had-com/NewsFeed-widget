package com.newsfeed.widget.data

/** What a flattened list row is: an ungrouped article, a thread's lead, or an expanded thread's other member. */
enum class ThreadRowKind { SINGLE, LEAD, MEMBER }

/**
 * One row of the widget's article list after grouping.
 *  - [leadId] is the id of this row's thread lead (the article's own id for SINGLE and LEAD).
 *  - [otherCount] / [expanded] / [hiddenUnread] are only meaningful on a LEAD row: how many members
 *    the thread has besides the lead, whether the user expanded it, and (collapsed only) how many
 *    of those hidden members are unread.
 */
data class ThreadRow(
    val article: ArticleItem,
    val kind: ThreadRowKind,
    val leadId: String,
    val otherCount: Int = 0,
    val expanded: Boolean = false,
    val hiddenUnread: Int = 0,
)

/**
 * The visible slice of the list plus the numbers the widget needs:
 *  - [rows]: the first `rowLimit` rows (this is what is rendered);
 *  - [availableRows]: how many rows exist in total (what "Load more" / "Showing X of Y" count);
 *  - [articleCount] / [unreadCount]: ARTICLES represented by [rows] (the header badge), i.e. a
 *    collapsed thread counts its hidden members too, so the badge means exactly what it did
 *    before threads existed.
 */
data class ThreadLayout(
    val rows: List<ThreadRow>,
    val availableRows: Int,
    val articleCount: Int,
    val unreadCount: Int,
)

/**
 * Flattens [threads] into rows: a one-member thread is a SINGLE row; a thread is a LEAD row, plus a
 * MEMBER row per other article when its lead id is in [expandedLeadIds]. Budget rule: a collapsed
 * thread costs ONE row, an expanded thread costs lead + members, and the cut is a plain
 * `take(rowLimit)` on the flat list (an expanded thread can therefore be cut mid-way; "Load more"
 * reveals the rest).
 */
fun layoutThreads(
    threads: List<StoryThread>,
    expandedLeadIds: Set<String>,
    rowLimit: Int,
    pinArticleId: String = "",
): ThreadLayout = layoutRows(flattenThreads(threads, expandedLeadIds), rowLimit, pinArticleId)

/** The full flat row list of [threads] (no row limit): what [layoutThreads] then cuts. */
fun flattenThreads(threads: List<StoryThread>, expandedLeadIds: Set<String>): List<ThreadRow> {
    val flat = ArrayList<ThreadRow>(threads.size)
    for (t in threads) {
        if (t.others.isEmpty()) {
            flat += ThreadRow(t.lead, ThreadRowKind.SINGLE, t.lead.id)
            continue
        }
        val expanded = t.lead.id in expandedLeadIds
        flat += ThreadRow(
            article = t.lead,
            kind = ThreadRowKind.LEAD,
            leadId = t.lead.id,
            otherCount = t.others.size,
            expanded = expanded,
            hiddenUnread = if (expanded) 0 else t.others.count { !it.isRead },
        )
        if (expanded) t.others.forEach { flat += ThreadRow(it, ThreadRowKind.MEMBER, t.lead.id) }
    }
    return flat
}

/**
 * The first [rowLimit] rows of [flat] plus their counts. When [pinArticleId] is the id of a row that
 * lies BEYOND the cut (Focus mode: the focused row must never fall off the end of the displayed
 * slice), that row is pinned at the END of the slice, replacing the last row(s), so the slice never
 * grows past [rowLimit]:
 *  - a SINGLE / LEAD row simply replaces the last row;
 *  - a MEMBER row keeps its thread together: if its lead is in the slice it replaces the last row (the
 *    thread is shown cut, as the row limit can already do); otherwise the lead and the member replace
 *    the last two rows (a limit of 1 shows just the member).
 * A [pinArticleId] that is blank, unknown, or already inside the cut changes nothing.
 */
fun layoutRows(flat: List<ThreadRow>, rowLimit: Int, pinArticleId: String = ""): ThreadLayout {
    val rows = pinnedSlice(flat, rowLimit.coerceAtLeast(0), pinArticleId)
    val hidden = rows.filter { it.kind == ThreadRowKind.LEAD && !it.expanded }
    return ThreadLayout(
        rows = rows,
        availableRows = flat.size,
        articleCount = rows.size + hidden.sumOf { it.otherCount },
        unreadCount = rows.count { !it.article.isRead } + rows.sumOf { it.hiddenUnread },
    )
}

private fun pinnedSlice(flat: List<ThreadRow>, limit: Int, pinArticleId: String): List<ThreadRow> {
    val head = flat.take(limit)
    if (pinArticleId.isBlank() || limit == 0 || head.any { it.article.id == pinArticleId }) return head
    val idx = flat.indexOfFirst { it.article.id == pinArticleId }
    if (idx < 0) return head
    val pinned = flat[idx]
    if (pinned.kind != ThreadRowKind.MEMBER) return flat.take(limit - 1) + pinned
    val leadIdx = flat.indexOfFirst { it.kind == ThreadRowKind.LEAD && it.article.id == pinned.leadId }
    if (leadIdx in 0 until limit - 1) return flat.take(limit - 1) + pinned
    if (limit < 2 || leadIdx < 0) return flat.take(limit - 1) + pinned
    return flat.take(limit - 2) + flat[leadIdx] + pinned
}

/**
 * What the widget renders for [articles] under [config]: the feeds still configured, filtered and
 * sorted (see [applyFilterAndSort]), de-duplicated by article id ([dedupeById]: the same article
 * served by two feeds is ONE article), then grouped into story threads (or one thread per article
 * when grouping is off). The render and the tap callbacks share this so they cannot disagree.
 * The story-thread boilerplate statistics are computed from the unfiltered, de-duplicated list of the
 * configured feeds ([groupStories]'s `statsFrom`), the same list `storedThreads` groups.
 */
fun displayThreadsFor(articles: List<ArticleItem>, config: WidgetConfig, now: Long): List<StoryThread> {
    val feedIds = config.feeds.map { it.feedId }.toSet()
    val inFeeds = articles.filter { it.feedId in feedIds }
    val available = dedupeById(applyFilterAndSort(inFeeds, config, now))
    // Boilerplate statistics come from the UNFILTERED list - exactly what the tap callbacks group
    // (storedThreads) - so an All / Unread only / Read only filter cannot change the threads.
    return if (config.groupStories) groupStories(available, statsFrom = dedupeById(inFeeds)) else ungroupedStories(available)
}

/**
 * Which piece of the story timeline (the rail on the physical right of a thread's rows) a row draws.
 *  - [NONE]: a single row, no rail;
 *  - [COLLAPSED_LEAD]: a collapsed thread's lead, just the hollow dot next to the "+N" badge;
 *  - [LEAD_OPEN]: an expanded lead, hollow dot with the line running down to the first member;
 *  - [MIDDLE]: a member with another member after it, filled dot with the line above and below;
 *  - [LAST]: the last visible member, filled dot, the line only comes down from above to its dot;
 *  - [LEAD_CUT]: an expanded lead whose members were all cut by the row budget, so there is
 *    nothing to join: hollow dot, no line.
 */
enum class RailPart { NONE, COLLAPSED_LEAD, LEAD_OPEN, MIDDLE, LAST, LEAD_CUT }

/** The dot of a rail row: a hollow ring (the lead = the thread's newest article) or a filled dot (members). */
enum class DotKind { NONE, HOLLOW, FILLED }

/** What to draw for a [RailPart]: its dot and whether the line runs above / below the dot (to the item's top / bottom edge). */
data class RailSpec(val dot: DotKind, val lineAbove: Boolean, val lineBelow: Boolean)

/** The rail piece of [row]; [isLastOfThread] comes from [isLastOfThread] (false when a member row follows). */
fun railPartFor(row: ThreadRow, isLastOfThread: Boolean): RailPart = when (row.kind) {
    ThreadRowKind.SINGLE -> RailPart.NONE
    ThreadRowKind.LEAD -> when {
        !row.expanded -> RailPart.COLLAPSED_LEAD
        isLastOfThread -> RailPart.LEAD_CUT
        else -> RailPart.LEAD_OPEN
    }
    ThreadRowKind.MEMBER -> if (isLastOfThread) RailPart.LAST else RailPart.MIDDLE
}

fun railSpecFor(part: RailPart): RailSpec = when (part) {
    RailPart.NONE -> RailSpec(DotKind.NONE, lineAbove = false, lineBelow = false)
    RailPart.COLLAPSED_LEAD, RailPart.LEAD_CUT -> RailSpec(DotKind.HOLLOW, lineAbove = false, lineBelow = false)
    RailPart.LEAD_OPEN -> RailSpec(DotKind.HOLLOW, lineAbove = false, lineBelow = true)
    RailPart.MIDDLE -> RailSpec(DotKind.FILLED, lineAbove = true, lineBelow = true)
    RailPart.LAST -> RailSpec(DotKind.FILLED, lineAbove = true, lineBelow = false)
}

/** Width of the rail column on the right of a thread row, and the line / dot sizes inside it (dp). */
const val RAIL_WIDTH_DP = 18
const val RAIL_LINE_DP = 2
const val RAIL_RING_DP = 12
const val RAIL_DOT_DP = 8

/**
 * Distance in dp from the top of a list item to the vertical middle of its FIRST text line, where the
 * rail dot is centred: the row's top padding plus half the height of the meta line (feed circle,
 * time, feed name and, on a lead, the "+N" badge). The meta line is [metaScale] times the 1.0 sizes:
 * the feed circle is 14 dp x scale, the badge is its 10 sp x scale text (about 12 dp x scale of line)
 * plus 4 dp padding above and below. The dot is therefore a FIXED offset per row, independent of how
 * many lines the headline wraps to, and it follows the font-size setting.
 */
fun railDotCenterDpFor(paddingTopDp: Int, metaScale: Float, hasBadge: Boolean): Float {
    val circle = 14f * metaScale
    val badge = if (hasBadge) 12f * metaScale + 8f else 0f
    return paddingTopDp + maxOf(circle, badge) / 2f
}

/**
 * True when [row] closes its thread block in the rendered list: no member row follows it ([next] is
 * the row after it, null at the end of the visible slice). A SINGLE row and a collapsed lead are
 * always "last"; the final visible row of a thread cut mid-way by the row limit is too.
 */
fun isLastOfThread(row: ThreadRow, next: ThreadRow?): Boolean = next == null || next.kind != ThreadRowKind.MEMBER

/** How the divider drawn after a row looks. */
enum class Separator { NORMAL, THIN, NONE }

/**
 * The divider after a row: the usual one between unrelated rows and after a thread's last row, a
 * hairline between the rows of an expanded thread (lead->member, member->member), and none after
 * the last row of the list.
 */
fun separatorStyleFor(kind: ThreadRowKind, isLastOfThread: Boolean, isLastRow: Boolean = false): Separator = when {
    isLastRow -> Separator.NONE
    kind == ThreadRowKind.SINGLE || isLastOfThread -> Separator.NORMAL
    else -> Separator.THIN
}

const val ROW_PADDING_NORMAL_DP = 8
const val ROW_PADDING_THIN_DP = 4

/**
 * (top, bottom) vertical padding in dp of a row's content: the standard [ROW_PADDING_NORMAL_DP]
 * everywhere, but only [ROW_PADDING_THIN_DP] on the sides that face another row of the same
 * expanded thread (about half the usual gap), so the thread block is delimited by normal padding on
 * its outer edges.
 */
fun verticalPaddingDpFor(kind: ThreadRowKind, isLastOfThread: Boolean): Pair<Int, Int> = when (kind) {
    ThreadRowKind.SINGLE -> ROW_PADDING_NORMAL_DP to ROW_PADDING_NORMAL_DP
    ThreadRowKind.LEAD ->
        ROW_PADDING_NORMAL_DP to (if (isLastOfThread) ROW_PADDING_NORMAL_DP else ROW_PADDING_THIN_DP)
    ThreadRowKind.MEMBER ->
        ROW_PADDING_THIN_DP to (if (isLastOfThread) ROW_PADDING_NORMAL_DP else ROW_PADDING_THIN_DP)
}

/** The lead row's badge text: "+N" while collapsed, a minus sign (U+2212) while expanded, empty when there is nothing to expand. */
fun threadBadgeLabel(otherCount: Int, expanded: Boolean): String = when {
    otherCount <= 0 -> ""
    expanded -> "−"
    else -> "+$otherCount"
}

/**
 * The grace-period refresh keys for [readArticles] (read articles still inside the grace window):
 * one (readAt, representative id) per distinct readAt - a story thread's members share one readAt, so
 * one schedule covers them all. The representative id is the same key the tap callbacks register
 * (see [GraceRefreshRegistry.representativeId]), so the two never double-schedule.
 */
fun resumeKeys(readArticles: List<ArticleItem>): List<Pair<Long, String?>> =
    readArticles.groupBy { it.readAt!! }.map { (readAt, group) ->
        readAt to GraceRefreshRegistry.representativeId(group.map { it.id })
    }
