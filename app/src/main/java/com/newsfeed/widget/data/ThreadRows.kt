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
fun layoutThreads(threads: List<StoryThread>, expandedLeadIds: Set<String>, rowLimit: Int): ThreadLayout {
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
    val rows = flat.take(rowLimit.coerceAtLeast(0))
    val hidden = rows.filter { it.kind == ThreadRowKind.LEAD && !it.expanded }
    return ThreadLayout(
        rows = rows,
        availableRows = flat.size,
        articleCount = rows.size + hidden.sumOf { it.otherCount },
        unreadCount = rows.count { !it.article.isRead } + rows.sumOf { it.hiddenUnread },
    )
}

/** True for the lead of an EXPANDED thread and for every member row: the rows that carry the thread rail. */
val ThreadRow.inExpandedThread: Boolean
    get() = kind == ThreadRowKind.MEMBER || (kind == ThreadRowKind.LEAD && expanded)

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
