package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.Preferences
import com.newsfeed.widget.data.ThreadRow
import com.newsfeed.widget.data.ThreadRowKind
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.displayThreadsFor
import com.newsfeed.widget.data.flattenThreads
import com.newsfeed.widget.data.layoutRows
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/*
 * The widget's SECOND, independent row cap: the size of the SERIALIZED RemoteViews (BUG-029).
 *
 * maxRowsAllowedFor() (FocusRowBudget.kt) only bounds the bitmap MEMORY of one update (thumbnails and
 * Glamour text bitmaps, 10MB). But the whole RemoteViews tree - every node, every text, every
 * PendingIntent with its ActionParameters - is also written into a Binder parcel when the update is
 * handed to the launcher, and that parcel has a hard limit unrelated to bitmap memory. Measured on a
 * device (Simple theme, Group ON, ~230 articles, docs/DEBUG_PLAN.md 14.ah): 50 rows = ~460 KB parcel,
 * delivered; 60 rows = 554 832 bytes, "TransactionTooLargeException: data parcel size 554832 bytes", the
 * update is silently dropped (the screen stays at the previous render and Load more keeps doing
 * nothing); from ~120 rows the widget process itself throws (1.1-1.9 MB) and the host shows
 * "Can't show content". The limit that bit is the ASYNC Binder space: AppWidget updates are oneway
 * transactions, and a oneway transaction may use only HALF of a process's 1 MB Binder buffer, i.e.
 * about 507 KB (1 MB minus bookkeeping, halved) shared with everything else pending to that process.
 * That matches the data: 460 KB fits, 554 KB does not.
 *
 * The slope between the two measured points is (554.8 - 460) / 10 rows = ~9.5 KB per row, so a
 * row costs ROW_BASE_BYTES = 9 500 bytes (a typical row: ~100-char headline, ~100-char id, meta line
 * with favicon circle / time / name / unread dot, two clickables each with an ActionParameters
 * fill-in intent, separators). Header, footer and the Load more / "Showing X of Y" item are not a
 * separate term: the measured slope already amortises them over the rows.
 *
 * SERIALIZED_BUDGET_BYTES = 350 000 (~31% under the ~507 KB async limit, ~24% under the largest
 * parcel known to be delivered, 460 KB). The margin is deliberate: (1) the same async space is shared with the
 * other widgets' pending updates to the launcher and the process's other oneway calls, (2) per-row
 * size varies (RTL rows add an accent-stripe Box and Spacer, the favicon Bitmap decoded per row is
 * written inline when it is <= 16 KB, feed names and ids vary), (3) the estimate is linear while the
 * parcel also carries a per-view overhead that grew in the measurement. For typical rows the cap is
 * 350 000 / 9 500 = 36 rows (up from "up to 250" which broke at ~55); lists of <= ~36 typical rows are
 * not limited at all, so a short list renders exactly as before.
 *
 * Bitmaps are NOT counted: RemoteViews writes each Bitmap as a Parcel blob, and blobs over 16 KB
 * (BLOB_INPLACE_LIMIT; a 100x100 thumbnail is 40 KB, a Glamour headline bitmap is 90 KB+) travel as
 * ashmem file descriptors outside the data parcel - they are budgeted by maxRowsAllowedFor() instead.
 * A thumbnail therefore adds only its ImageView node (THUMBNAIL_NODE_BYTES).
 * ASSUMPTION TO CONFIRM ON DEVICE: that an image-mode feed's parcel stays ~9.5 KB per row.
 *
 * Text: strings are written to the parcel as UTF-16, so every character that is put into the view as
 * text costs 2 bytes (BYTES_PER_CHAR). In Glamour the text becomes a bitmap but is still passed as the
 * Image's contentDescription, so the same rule applies to every theme. Only the part of a headline /
 * id that exceeds the typical length is added (the typical one is inside ROW_BASE_BYTES).
 */

/** Total serialized RemoteViews bytes the row list may use. See the header comment for the margin. */
internal const val SERIALIZED_BUDGET_BYTES = 350_000

/** Marginal parcel bytes of one typical collapsed row, measured (slope of 50 -> 60 rows, 14.ah). */
internal const val ROW_BASE_BYTES = 9_500

/** The headline / id length that ROW_BASE_BYTES already contains. */
internal const val TYPICAL_TITLE_CHARS = 100
internal const val TYPICAL_ID_CHARS = 100

/** UTF-16 in the parcel. */
internal const val BYTES_PER_CHAR = 2

/** An id appears in the row's two clickables' ActionParameters (and the badge's) as a String extra. */
internal const val ID_COPIES = 2

/**
 * Extra nodes of an expanded row: the body Column, spacers, the description Text, the "Open article"
 * and "Share" pills with their Intents / PendingIntents (an Intent carries the URL a few times, see
 * URL_COPIES) and the Load full article button. A generous estimate (not measured separately).
 */
internal const val EXPANDED_EXTRA_BYTES = 4_000

/** The article URL appears in the open Intent, the share Intent and the load-full-article extras. */
internal const val URL_COPIES = 3

/** An ImageView node (the pixels are an ashmem blob, budgeted by maxRowsAllowedFor, not counted here). */
internal const val THUMBNAIL_NODE_BYTES = 1_000

/** A thread lead's "+N" badge: a Box + Text + clickable with its own ActionParameters. */
internal const val BADGE_BYTES = 1_000

/**
 * Estimated parcel bytes of ONE row. Pure and monotonic in every input; never below [ROW_BASE_BYTES].
 *  - [titleChars] / [idChars]: only the part beyond the typical length costs extra.
 *  - [bodyChars]: characters put into the view as the expanded body (description, full article text,
 *    and the description repeated in a Load full article button's parameters); counted only when [expanded].
 *  - [urlChars]: the article URL; counted only when [expanded] (the Open / Share intents exist only then).
 */
internal fun estimatedRowBytes(
    titleChars: Int,
    bodyChars: Int = 0,
    expanded: Boolean,
    hasThumbnail: Boolean,
    hasBadge: Boolean = false,
    urlChars: Int = 0,
    idChars: Int = 0,
): Int {
    var bytes = ROW_BASE_BYTES.toLong()
    bytes += BYTES_PER_CHAR.toLong() * (titleChars - TYPICAL_TITLE_CHARS).coerceAtLeast(0)
    bytes += (BYTES_PER_CHAR * ID_COPIES).toLong() * (idChars - TYPICAL_ID_CHARS).coerceAtLeast(0)
    if (hasThumbnail) bytes += THUMBNAIL_NODE_BYTES
    if (hasBadge) bytes += BADGE_BYTES
    if (expanded) {
        bytes += EXPANDED_EXTRA_BYTES
        bytes += BYTES_PER_CHAR.toLong() * bodyChars.coerceAtLeast(0)
        bytes += (BYTES_PER_CHAR * URL_COPIES).toLong() * urlChars.coerceAtLeast(0)
    }
    return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

/**
 * How many LEADING rows fit [budgetBytes], never below 1 (the first row is always shown; a single row
 * that alone exceeds the budget is the same unavoidable floor as in maxRowsAllowedFor).
 */
internal fun maxRowsBySerializedSize(rowBytes: List<Int>, budgetBytes: Int = SERIALIZED_BUDGET_BYTES): Int {
    var total = 0L
    var n = 0
    for (b in rowBytes) {
        total += b
        if (total > budgetBytes) break
        n++
    }
    return n.coerceAtLeast(1)
}

/**
 * The serialized cap over the real display slice: the largest limit L in 1..min([upper], rows) such that
 * the rows [layoutRows] would show for it - including a Focus row pinned in from beyond the cut, which
 * replaces the last row(s) and is paid for here - cost at most [budgetBytes]. [upper] is the existing
 * (bitmap) cap, so the result is already the min of both. Binary search: a bigger L never shows a
 * cheaper slice.
 */
internal fun serializedRowCap(
    flat: List<ThreadRow>,
    upper: Int,
    pinArticleId: String,
    costOf: (ThreadRow) -> Int,
    budgetBytes: Int = SERIALIZED_BUDGET_BYTES,
): Int {
    val hi0 = minOf(upper, flat.size).coerceAtLeast(1)
    fun fits(limit: Int) = layoutRows(flat, limit, pinArticleId).rows.sumOf { costOf(it).toLong() } <= budgetBytes
    if (fits(hi0)) return hi0
    var lo = 1          // always allowed (floor)
    var hi = hi0 - 1    // hi0 is known not to fit
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (fits(mid)) lo = mid else hi = mid - 1
    }
    return lo
}

/**
 * The cap the render uses: the existing [maxRowsAllowed] untouched unless the serialized cap really binds
 * ([serializedCap] below [reachable] = min(existing cap, rows available)), in which case it is the serialized cap.
 * Keeping the old value when nothing binds makes the non-binding path numerically identical to before.
 */
internal fun effectiveRowCap(maxRowsAllowed: Int, serializedCap: Int, reachable: Int): Int =
    if (serializedCap < reachable) serializedCap else maxRowsAllowed

/**
 * The stored "how many rows are requested" after a Load more tap: [current] + [chunk], never past [cap]
 * (so taps past the cap cannot accumulate into a count that does nothing), pulled back to [cap] when an
 * older, larger value is stored, never below 1. A null [cap] (not binding / unknown) is the old behaviour.
 */
internal fun nextVisibleCount(current: Int, chunk: Int, cap: Int?): Int {
    val next = current + chunk
    return (if (cap == null) next else minOf(next, cap)).coerceAtLeast(1)
}

/**
 * What the render puts into each row, as inputs to [estimatedRowBytes]. Built identically by the render
 * and by the Load more callback ([serializedCapFromPrefs]) so they cannot disagree.
 * [focusedArticleId] must be the EFFECTIVE focused id ("" when none is active).
 */
internal class RowSizing(
    private val config: WidgetConfig,
    private val expandedArticleId: String,
    private val focusedArticleId: String,
    private val fullArticleId: String,
    private val fullArticleChars: Int,
    private val fullArticleShown: Int,
) {
    private val isGlamour = config.widgetTheme == "glamer"
    private val isFocusWidget = TapMode.fromKey(config.tapMode) == TapMode.FOCUS
    private val imageFeeds: Set<String> = config.feeds.filter { it.displayMode == "image" }.mapTo(HashSet()) { it.feedId }

    fun isExpanded(row: ThreadRow): Boolean =
        row.article.id == expandedArticleId || (isFocusWidget && focusedArticleId.isNotBlank() && row.article.id == focusedArticleId)

    /** Characters of the expanded body, mirroring what FeedItemRow emits (see its expanded block). */
    fun bodyCharsFor(a: com.newsfeed.widget.data.ArticleItem): Int {
        val desc = a.description
        if (config.articleLength == "full") {
            if (fullArticleId == a.id && fullArticleChars > 0) {
                // fetched text: non-Glamour is one Text of everything; Glamour renders the shown chunks as bitmaps
                // with the chunk text as contentDescription.
                return if (isGlamour) minOf(fullArticleShown.coerceAtLeast(0), fullArticleChars) else fullArticleChars
            }
            if (desc.isBlank()) return 0
            // Load full article button: its ActionParameters carry the whole description once more.
            val buttonPayload = if (canLoadFullArticle(a.articleUrl)) desc.length else 0
            val shown = if (!canLoadFullArticle(a.articleUrl) && a.articleUrl.isNotBlank()) {
                // Telegram: the whole description (Glamour: one chunk)
                if (isGlamour) minOf(desc.length, FetchFullArticleCallback.CHUNK_CHARS) else desc.length
            } else minOf(desc.length, 400)
            return shown + buttonPayload
        }
        if (desc.isBlank()) return 0
        val limit = if (config.articleLength == "short") 100 else 400
        return minOf(desc.length, limit) + (if (desc.length > limit) 1 else 0)
    }

    fun bytesFor(row: ThreadRow): Int {
        val a = row.article
        val expanded = isExpanded(row)
        return estimatedRowBytes(
            titleChars = a.title.length,
            bodyChars = if (expanded) bodyCharsFor(a) else 0,
            expanded = expanded,
            hasThumbnail = a.feedId in imageFeeds,
            hasBadge = row.kind == ThreadRowKind.LEAD && row.otherCount > 0,
            urlChars = a.articleUrl.length,
            idChars = a.id.length,
        )
    }
}

/**
 * The serialized cap recomputed from the widget's stored state, for the Load more callback (which cannot
 * see the render's locals). Same pipeline as the render: displayThreadsFor -> flattenThreads, same
 * expanded / focused state. Returns the cap ONLY when it binds (below the number of rows), else null.
 * The existing bitmap cap is width / density dependent and not known here; the render still applies it.
 */
internal fun serializedCapFromPrefs(prefs: Preferences, now: Long = System.currentTimeMillis()): Int? {
    val config = prefs[WidgetStateKey.configJson]
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() } ?: return null
    val articles = decodeStoredArticles(prefs) ?: return null
    val flat = flattenThreads(
        displayThreadsFor(articles, config, now),
        parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds]),
    )
    if (flat.isEmpty()) return null
    val isFocusWidget = TapMode.fromKey(config.tapMode) == TapMode.FOCUS
    val focusedId = prefs[WidgetStateKey.focusedArticleId].orEmpty()
    val effectiveFocusedId = if (isFocusWidget && focusedId.isNotBlank() && flat.any { it.article.id == focusedId }) focusedId else ""
    val sizing = RowSizing(
        config = config,
        expandedArticleId = prefs[WidgetStateKey.expandedArticleId].orEmpty(),
        focusedArticleId = effectiveFocusedId,
        fullArticleId = prefs[WidgetStateKey.fullArticleId].orEmpty(),
        fullArticleChars = prefs[WidgetStateKey.fullArticleText].orEmpty().length,
        fullArticleShown = prefs[WidgetStateKey.fullArticleShownChars] ?: FetchFullArticleCallback.CHUNK_CHARS,
    )
    val cap = serializedRowCap(flat, flat.size, effectiveFocusedId, sizing::bytesFor)
    return if (cap < flat.size) cap else null
}

/** Which cap stops the list short of the available rows (decides the footer wording). */
internal enum class CapReason { NONE, MEMORY, SERIALIZED }

/**
 * Why the list stops before [available] rows. [SERIALIZED] when the serialized-size cap is below both the
 * bitmap cap [maxRowsAllowed] and [available] (also when both bind: font size cannot fix that one);
 * [MEMORY] when only the bitmap / device-memory cap is below [available]; else [NONE].
 */
internal fun capReason(maxRowsAllowed: Int, serializedCap: Int, available: Int): CapReason = when {
    serializedCap < minOf(maxRowsAllowed, available) -> CapReason.SERIALIZED
    maxRowsAllowed < available -> CapReason.MEMORY
    else -> CapReason.NONE
}

/**
 * The non-tappable footer shown instead of Load more, or null when no cap binds. A smaller font size only
 * raises the bitmap cap, so the "reduce font size" hint is used only for [CapReason.MEMORY] (and not at the
 * font slider's floor, where it is impossible); the serialized cap never depends on font size.
 */
internal fun capFooterText(reason: CapReason, shown: Int, available: Int, atFontFloor: Boolean): String? = when (reason) {
    CapReason.NONE -> null
    CapReason.SERIALIZED -> "Showing $shown of $available · the widget can't show more at once"
    CapReason.MEMORY ->
        if (atFontFloor) "Showing $shown of $available · this device can't show more at once"
        else "Showing $shown of $available · reduce font size to see more"
}
