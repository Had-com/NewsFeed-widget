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
