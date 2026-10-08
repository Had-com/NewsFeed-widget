package com.newsfeed.widget.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.glance.LocalGlanceId
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.newsfeed.widget.config.WidgetConfigActivity
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.OrphanCleanup
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.Separator
import com.newsfeed.widget.data.ThreadRowKind
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.applyFilterAndSort
import com.newsfeed.widget.data.dissolveArticle
import com.newsfeed.widget.data.groupStories
import com.newsfeed.widget.data.RAIL_LINE_DP
import com.newsfeed.widget.data.RAIL_WIDTH_DP
import com.newsfeed.widget.data.RailPart
import com.newsfeed.widget.data.railDotCenterDpFor
import com.newsfeed.widget.data.railPartFor
import com.newsfeed.widget.data.isLastOfThread
import com.newsfeed.widget.data.isDissolving
import com.newsfeed.widget.data.layoutThreads
import com.newsfeed.widget.data.resumeKeys
import com.newsfeed.widget.data.separatorStyleFor
import com.newsfeed.widget.data.ungroupedStories
import com.newsfeed.widget.data.verticalPaddingDpFor
import com.newsfeed.widget.data.UNREAD_GRACE_PERIOD_MS
import com.newsfeed.widget.data.OrphanCleanupWorker
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class NewsFeedWidget : GlanceAppWidget() {

    // Default SizeMode.Single pins LocalSize.current to the widget's declared minimum
    // size forever, regardless of how large the user actually places/resizes it — that
    // was silently starving the Glamour headline bitmaps of their real column width.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Glance's Row/Column child order auto-mirrors under the device's system locale —
        // same underlying LocalLayoutDirection mechanism as regular Compose, and just as
        // invisible in English-locale testing as the isRtl xor bug this project also hit.
        // On a genuinely Hebrew-locale device this flipped the ENTIRE row (accent stripe,
        // meta-row time/name order, thumbnail side, footer) regardless of each feed's own
        // explicit RTL/LTR setting, which is the one thing meant to control it — this app
        // manages direction per-feed deliberately, not by following system locale. Locking
        // layout direction to Ltr here makes every Row/Column's physical child order behave
        // identically regardless of device locale, matching what every screenshot taken
        // during this project's (English-locale) development actually showed.
        provideContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                WidgetContent()
            }
        }
    }
}

/**
 * Re-renders the widget instance owning [glanceId]. Kept as a single named entry point for the
 * ActionCallbacks and the settings screen. There is only one widget class now (Focus is a
 * per-widget setting, see WidgetConfig.tapMode), so there is no provider lookup.
 */
suspend fun updateNewsFeedWidget(context: Context, glanceId: GlanceId) {
    NewsFeedWidget().update(context, glanceId)
}

@Composable
private fun WidgetContent() {
    val prefs             = currentState<androidx.datastore.preferences.core.Preferences>()
    val configJson        = prefs[WidgetStateKey.configJson]
    val articlesJson      = prefs[WidgetStateKey.articles]
    val lastRefreshTime   = prefs[WidgetStateKey.lastRefreshTime] ?: 0L
    val lastRefreshFailed = prefs[WidgetStateKey.lastRefreshFailed] ?: false
    val expandedArticleId = prefs[WidgetStateKey.expandedArticleId] ?: ""
    val fullArticleId     = prefs[WidgetStateKey.fullArticleId]     ?: ""
    val fullArticleText   = prefs[WidgetStateKey.fullArticleText]   ?: ""
    val fullArticleShown  = prefs[WidgetStateKey.fullArticleShownChars] ?: FetchFullArticleCallback.CHUNK_CHARS
    // Focus mode only (isFocusWidget, i.e. config.tapMode == "focus") — see FeedItemRow.kt's
    // row scaling. Read unconditionally: in Expand mode the key is simply blank (it is cleared
    // by resetTapState() whenever a widget's tap mode changes).
    val focusedArticleId  = prefs[WidgetStateKey.focusedArticleId] ?: ""
    // Focus Mode only — live, on-widget-adjustable via +/- buttons on the focused row
    // itself (AdjustFocusScaleCallback), not a Settings-screen slider. Absent means
    // either nothing's been adjusted yet, or focus just moved to a different article
    // (both callbacks clear this key on any change of *which* article is focused).
    val focusScale        = prefs[WidgetStateKey.focusScale] ?: AdjustFocusScaleCallback.DEFAULT_SCALE
    // Story threads (config.groupStories): lead ids of the threads the user expanded with the "+N"
    // badge. Independent of the tap mode and of expandedArticleId (a thread and an article can both
    // be expanded at once).
    val expandedThreadIds = parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds])

    val config = configJson
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        ?: WidgetConfig(widgetId = -1)

    // Focus (tap-to-enlarge) is a per-widget setting, not a widget class: every widget renders
    // through this one path and reads its own saved config.tapMode.
    val isFocusWidget = TapMode.fromKey(config.tapMode) == TapMode.FOCUS

    val articles: List<ArticleItem> = articlesJson
        ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
        ?: emptyList()

    val feedMap         = config.feeds.associateBy { it.feedId }
    val visibleCount    = prefs[WidgetStateKey.visibleArticleCount] ?: LoadMoreArticlesCallback.ARTICLE_CHUNK_SIZE
    // Sort/filter applied here, not just at fetch time — WidgetWorker's merge step always
    // re-sorts the accumulated store by publishedAt and never re-applies the filter to
    // already-stored articles, so without this, config.sortOrder/config.filter had no
    // effect on what actually got displayed once an article survived one refresh cycle.
    // One `now` for both the filter and the dissolve stage, so an article is never dissolved
    // in a render that also drops it (or vice versa).
    val now = System.currentTimeMillis()
    val availableArticles = applyFilterAndSort(articles.filter { feedMap.containsKey(it.feedId) }, config, now)

    // Each row can carry a Glamour-theme headline bitmap and/or a thumbnail image, and
    // RemoteViews has a real total bitmap-memory budget for one widget update (this
    // project has hit "IllegalArgumentException: RemoteViews for widget update exceeds
    // maximum bitmap memory usage" before) — so the row count is capped by an actual
    // computed budget instead of a flat guessed number, the same chunked-reveal approach
    // used for full-article "Load more" (see FeedItemRow's maxChunksAllowed). 7.5MB here
    // + the full-article path's own 6MB budget stay under the ~15.5MB ceiling this
    // project has hit before, with margin left for other home-screen widgets.
    val context2       = LocalContext.current
    val density2       = context2.resources.displayMetrics.density
    val scaledDensity2 = context2.resources.displayMetrics.scaledDensity
    val widthPx2       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f) * density2)
                              .toInt().coerceAtLeast(50)
    // Focus mode only: the focused row can render at up to focusScale× (default 1.25×, up to
    // 2.5×) config.fontSize. Every other row is at normal size (1×), since Focus no longer
    // shrinks them, so a uniform worst case is the larger of focusScale and 1. This is the same
    // deliberately-overestimating shape as the "3-line worst case" comment below: it protects
    // against the "RemoteViews for widget update exceeds maximum bitmap memory usage" crash.
    // (It could later be tightened to n-1 rows at 1× plus one at focusScale; that would change
    // the on-screen row counts the on-device "Can't show content" fix was tuned against, so it
    // is intentionally left as the uniform bound.)
    val worstCaseRowScale = if (isFocusWidget) maxOf(focusScale, 1f) else 1f
    val headlineLineHeightPx = 13f * config.fontSize * worstCaseRowScale * scaledDensity2 * 1.2f
    // Worst case per row: a Glamour headline bitmap at AdjustFocusScaleCallback's shared
    // HEADLINE_MAX_LINES (8) — every row uses the same cap now, focused or not, see that
    // constant's own comment for why one shared number replaced what used to be two — plus
    // a small thumbnail — themes without a bitmap headline (plain Text) cost far less, so
    // this deliberately overestimates rather than risking under-provisioning.
    val worstCaseHeadlineLines = AdjustFocusScaleCallback.HEADLINE_MAX_LINES
    // 1 byte/pixel, not 4 — TextBitmapHelper now renders headline bitmaps as ALPHA_8 (a
    // colorless coverage mask, tinted at display time via ColorFilter.tint()), not
    // ARGB_8888. This formula predates that change and was left at the old multiplier,
    // making this budget ~4x more conservative than the real bitmaps it's sizing for —
    // confirmed on-device: Focus Mode's own combined-extreme test case (Font size 3.0 ×
    // focus scale 2.5) still capped at "Showing 1 of 300" after the ALPHA_8 switch, which
    // should have relaxed given the real per-row cost just dropped ~4x.
    val headlineBytes  = if (config.widgetTheme == "glamer")
        (widthPx2 * (worstCaseHeadlineLines * headlineLineHeightPx) * 1f) else 0f
    // Unchanged: this is a real downloaded photo thumbnail (ARGB_8888), not a text bitmap —
    // the ALPHA_8 switch only applies to TextBitmapHelper's headline/paragraph rendering.
    val thumbnailBytes = 100f * 100f * 4f
    val bytesPerRow    = (headlineBytes + thumbnailBytes).coerceAtLeast(1f)
    // Rebalanced from the original 7.5MB/9MB split against FeedItemRow.kt's chunkBudgetBytes
    // (down to 6.5MB there, up to 10MB here — same ~16.5MB total, just reallocated). The
    // list itself is what's visible and memory-constrained all the time; the full-article
    // chunk budget only matters for whichever single article is currently expanded, so it
    // never needed as large a share as this one.
    val rowBudgetBytes = 10_000_000f
    // Floor was 5 (never show fewer than 5 rows) until Focus Mode's combined fontSize ×
    // focusScale × HEADLINE_MAX_LINES made a single row's real worst-case bitmap
    // exceed this entire row budget on its own (confirmed on-device at Font size 3.0 +
    // focus scale 2.5: "Can't show content", RemoteViews' real bitmap-memory ceiling hit
    // for real) — forcing a minimum of 5 such rows when even 1 already doesn't fit is
    // exactly backwards for a safety floor. 1 lets the math legitimately reflect "only a
    // little room" instead of demanding room that was already established not to exist;
    // see the textSizePx cap in TextBitmapHelper.render() for the actual backstop that
    // keeps a single row's bitmap safe regardless of what this calculation concludes.
    //
    // Upper bound was a flat 60 for every theme — but headlineBytes is already 0 for
    // every non-Glamour theme (plain Text, no bitmap headline), so the honest formula
    // above already accounts for the real cost (thumbnails only) and safely allows nearly
    // the whole 300-article store. Capping it at the same flat 60 as Glamour needlessly
    // throttled non-Glamour themes far below what's actually safe (reported: articles
    // available when not using Glamour should reach the real total, not stop at 60).
    // Glamour keeps 60 since its bitmap cost genuinely can grow large and unpredictable
    // (see AdjustFocusScaleCallback.HEADLINE_MAX_LINES / Focus Mode's combined scaling).
    val maxRowsCeiling = if (config.widgetTheme == "glamer") 60 else 300
    val maxRowsAllowed = (rowBudgetBytes / bytesPerRow).toInt().coerceIn(1, maxRowsCeiling)

    // Dissolve effect: only under "Unread only", where a just-read article in its grace
    // window is about to vanish - other filters and long-read articles are untouched
    // (dissolveArticle is a no-op unless the article is read AND its readAt is under 5s old).
    // Applied to the article itself so standard rows, Focus rows and the Focus enlarged view
    // (all fed from displayArticles) dissolve identically.
    //
    // Story threads: when config.groupStories is on, the post-filter/sort list is grouped into
    // threads (data/StoryThreads.kt) and flattened to ROWS (data/ThreadRows.kt). A collapsed thread
    // is ONE row (its lead, with a "+N" badge), an expanded thread is the lead plus one row per
    // other member; maxRowsAllowed / visibleCount / "Load more" all count rows. With the switch off
    // every article is its own single row, i.e. exactly the list this code rendered before threads.
    // Dissolve is applied to every visible row, members included: a thread is read as a whole with
    // one shared readAt, so all of its rows dissolve in lockstep.
    val threads = if (config.groupStories) groupStories(availableArticles) else ungroupedStories(availableArticles)
    val layout = layoutThreads(threads, expandedThreadIds, visibleCount.coerceAtMost(maxRowsAllowed))
    val displayRows = layout.rows.let { rows ->
        if (config.filter == FilterMode.UNREAD.key) rows.map { it.copy(article = dissolveArticle(it.article, now)) } else rows
    }
    val displayArticles = displayRows.map { it.article }
    // Based on visibleCount (what's been requested), not displayArticles.size (what's
    // actually shown after clamping) — comparing the clamped size against maxRowsAllowed
    // was always false the moment a single "chunk" request met or exceeded the memory
    // ceiling, hiding the button on the very first render whenever that happened instead
    // of only once truly exhausted. visibleCount vs the two ceilings is what actually
    // determines whether tapping "Load more" would reveal anything new.
    val canLoadMoreArticles = visibleCount < maxRowsAllowed && visibleCount < layout.availableRows
    // Complementary "stuck" state: maxRowsAllowed itself (not the requested chunk size) is
    // the binding constraint, so tapping "Load more" would never reveal anything no matter
    // how many times it's tapped — this happens whenever maxRowsAllowed <= visibleCount
    // while more articles are genuinely available (common at larger font sizes / the
    // Glamour theme's bitmap headlines, where maxRowsAllowed can land as low as its 5-row
    // floor even with hundreds of articles cached). Previously this was indistinguishable
    // from "the feed is simply exhausted" — the widget just silently stopped growing with
    // zero indication that a large backlog was sitting unreachable behind it (reported as a
    // bug: "Load more" permanently unreachable at normal/large font sizes).
    val memoryCapReached = displayArticles.size >= maxRowsAllowed &&
        displayArticles.size < layout.availableRows
    // Scoped to displayArticles, not the full accumulated store (which can hold up to
    // 300) — counting the full store made the header badge claim "99+" unread while only
    // a fraction of articles were ever reachable by scrolling, which read as a bug (and
    // was reported as one) rather than the accumulation feature it actually was.
    // Counts ARTICLES, not rows: a collapsed thread contributes its hidden members too, so the
    // badge means what it always did (see ThreadLayout).
    val unreadCount     = layout.unreadCount
    // Focus mode only (tapMode = focus, isFocusWidget) — position within what's actually rendered
    // (displayArticles), so the "N / M" indicator always matches what's really on screen.
    val focusedIndex    = if (focusedArticleId.isNotBlank())
        displayArticles.indexOfFirst { it.id == focusedArticleId } else -1

    // Resume the dissolve/removal refreshes if this process didn't schedule them (the
    // scheduler's coroutine dies with the process, e.g. `adb install -r` mid-dissolve, which
    // would leave a row stuck half-dotted until some unrelated refresh). Deduped in-process by
    // UnreadGracePeriod's registry, so the re-renders those refreshes trigger, and the tap
    // callbacks' own scheduling, never spawn a second schedule. An article already >= 5s
    // past readAt is filtered out of this very render, so it never gets here.
    if (config.filter == FilterMode.UNREAD.key) {
        val graceGlanceId = LocalGlanceId.current
        val graceContext = LocalContext.current
        val resumeArticles = articles.filter {
            it.isRead && it.readAt != null && feedMap.containsKey(it.feedId) &&
                now - it.readAt < UNREAD_GRACE_PERIOD_MS
        }
        if (resumeArticles.isNotEmpty()) {
            SideEffect {
                // One schedule per readAt, not per article: a story thread's members share one readAt
                // and one schedule re-renders them all. Keyed by the group's representative id, the
                // same key the tap callbacks register, so the two never double-schedule.
                resumeKeys(resumeArticles).forEach { (readAt, repId) ->
                    UnreadGracePeriod.scheduleRefresh(
                        graceContext, graceGlanceId, repId, readAt,
                    ) { c, g -> NewsFeedWidget().update(c, g) }
                }
            }
        }
    }

    val themeColors = WidgetThemes.colorProvidersFor(
        config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
    val surfaceColor = WidgetThemes.surfaceColorFor(
        config.widgetTheme, config.themeVariant, config.customFontColor, config.customBackgroundColor)
    val bgColor = ColorProvider(surfaceColor.copy(alpha = config.backgroundAlpha))

    GlanceTheme(colors = themeColors) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .cornerRadius(18.dp)
                .padding(0.dp),
        ) {
            // Header counts: unread(total) are ARTICLES (layout.articleCount); the Focus "N/M" position
            // counts the ROWS actually on screen (displayArticles.size).
            WidgetHeader(unreadCount, layout.articleCount, focusedArticleId, focusedIndex, displayArticles.size, isFocusWidget)
            Divider()

            if (displayArticles.isEmpty()) {
                Box(
                    modifier = GlanceModifier.defaultWeight().fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No articles",
                        style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.onSurfaceVariant),
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    // Explicit itemId (verified via decompiling the real glance-appwidget:1.1.0
                    // jar — LazyListScope.items' default is LazyListScope.UnspecifiedItemId, a
                    // fixed constant, not a per-item hash as might be assumed; every row was
                    // effectively unidentified/position-only before this). A reported bug — a
                    // previously-focused row's highlight not clearing after stepping focus to a
                    // different row — is consistent with RemoteViews' list-item recycling
                    // getting view identity wrong across an update when every item shares the
                    // same id. Keying on the immutable article.id gives Android's list adapter a
                    // real, stable identity to track per row, independent of which other fields
                    // (isRead, etc.) changed — a correct improvement on its own regardless of
                    // whether it's the row-highlight bug's exact root cause, which is not yet
                    // independently confirmed on-device.
                    itemsIndexed(displayRows, itemId = { _, it -> it.article.id.hashCode().toLong() }) { rowIndex, row ->
                        val article = row.article
                        val feedConfig = feedMap[article.feedId] ?: return@itemsIndexed
                        val isLast = rowIndex == displayRows.lastIndex
                        // Story threads: where this row sits in its thread decides its separator and its
                        // vertical padding (thin between the rows of an EXPANDED thread, normal otherwise).
                        // A SINGLE row and a collapsed lead are always "last of thread" -> normal, exactly
                        // the pre-thread values.
                        val lastOfThread = isLastOfThread(row, displayRows.getOrNull(rowIndex + 1))
                        val (padTop, padBottom) = verticalPaddingDpFor(row.kind, lastOfThread)
                        val separator = separatorStyleFor(row.kind, lastOfThread, isLast)
                        // Story timeline (thread rows only): which piece of the right-hand rail this row draws,
                        // and where its dot sits (the middle of the row's first text line, see railDotCenterDpFor;
                        // the +N badge only exists on a lead that is not dissolving).
                        val railPart = railPartFor(row, lastOfThread)
                        val rowDissolving = config.filter == FilterMode.UNREAD.key && isDissolving(article, now)
                        val railDotCenterDp = railDotCenterDpFor(
                            paddingTopDp = padTop,
                            metaScale = config.fontSize,
                            hasBadge = row.kind == ThreadRowKind.LEAD && row.otherCount > 0 && !rowDissolving,
                        )
                        @Composable
                        fun ArticleRow() {
                            FeedItemRow(
                                article           = article,
                                feedConfig        = feedConfig,
                                expandedArticleId = expandedArticleId,
                                fontSize          = config.fontSize,
                                articleFontSize   = config.articleFontSize,
                                articleLength     = config.articleLength,
                                fullArticleId     = fullArticleId,
                                fullArticleText   = fullArticleText,
                                fullArticleShown  = fullArticleShown,
                                useThemeColors    = config.useThemeColors,
                                widgetTheme       = config.widgetTheme,
                                externalApp       = config.externalApp,
                                themeVariant      = config.themeVariant,
                                focusedArticleId  = focusedArticleId,
                                focusScale        = focusScale,
                                isFocusWidget     = isFocusWidget,
                                isDissolving      = rowDissolving,
                                threadOtherCount  = if (row.kind == ThreadRowKind.LEAD) row.otherCount else 0,
                                threadExpanded    = row.expanded,
                                paddingTopDp      = padTop,
                                paddingBottomDp   = padBottom,
                                // An RTL row's per-feed accent stripe is at the physical RIGHT, exactly where the
                                // timeline rail sits (the rail column is added outside the row, flush with the
                                // item's right edge): the stripe would be a second vertical line 8dp from the rail
                                // line, so rows that carry a rail drop it (their rail dot/line is the right-edge
                                // marker; the feed is still identified by the circle icon and the name). LTR rows
                                // keep their stripe on the left, far from the rail.
                                showRtlAccentStripe = railPart == RailPart.NONE,
                            )
                        }
                        // The divider(s) after a row. NORMAL is the long-standing 1dp line + 2dp shadow strip;
                        // THIN (between the rows of an expanded thread) is the 1dp line alone, with a 2dp x 1dp
                        // piece of the rail colour painted over it where the rail line runs (8dp in from the right
                        // edge: centre of the 18dp rail column) so the rail reads as one unbroken line through the
                        // separator (a Box overlay: no weights, no padding on a filled box). The 10dp wrapper Box
                        // is right-aligned by the hairline Box and holds the 2dp piece at ITS start, i.e. at 8dp
                        // from the physical right.
                        @Composable
                        fun Dividers() {
                            if (separator == Separator.THIN) {
                                Box(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(GlanceTheme.colors.surfaceVariant),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Box(modifier = GlanceModifier
                                        .width(((RAIL_WIDTH_DP + RAIL_LINE_DP) / 2).dp)
                                        .height(1.dp)) {
                                        Box(modifier = GlanceModifier
                                            .width(RAIL_LINE_DP.dp)
                                            .height(1.dp)
                                            .background(GlanceTheme.colors.primary)) {}
                                    }
                                }
                            }
                            if (separator == Separator.NORMAL) {
                                Box(modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(GlanceTheme.colors.surfaceVariant)) {}
                                Box(modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(ColorProvider(Color(0x33000000)))) {}
                            }
                        }
                        // Both branches are ONE outer Column(fillMaxWidth) holding the item and then its
                        // dividers, exactly like the long-standing single-row layout.
                        //
                        // WHY THE DIVIDERS ARE NOT INSIDE THE RAIL WRAPPER (BUG-2, DEBUG_PLAN 14.ad / 14.ae / 14.af):
                        // two earlier builds put the dividers in the same container as the rail bar
                        // (909: Row{ rail, Spacer, Column(defaultWeight){ row + dividers } }; 910: Box{
                        // Column(padding start){ row + dividers }, rail Box(fillMaxHeight) }). On device the
                        // divider Boxes were then ABSENT from the rendered view tree (the item's Column had
                        // exactly 1 child in the uiautomator dump; the paddings still applied), thin and normal
                        // alike. With the dividers as siblings of the wrapper, in the outer Column, they paint
                        // (build 911). So: never put a divider in a container that also holds a
                        // fillMaxHeight() rail child in Glance 1.1.0; the weight of build 909 was not the (only)
                        // cause.
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            if (railPart != RailPart.NONE) {
                                // Story timeline (thread rows): a rail column on the physical RIGHT of the item (a dot
                                // per article, hollow ring on the lead, filled dots on the members, joined by a 2dp
                                // line, see ThreadRail), overlaid (Box = FrameLayout) on the row, which is padded
                                // RAIL_WIDTH_DP at the end so the text never runs under it. The rail lives OUTSIDE (to the
                                // right of) the FeedItemRow, so it cannot overlap an RTL row's feed circle icon (inside
                                // the row, left of its stripe). The dividers are NOT inside this wrapper (see above): they
                                // are siblings of it in the outer Column, and the THIN one carries its own piece of rail
                                // line. The wrapper has two children, the padded row and the rail.
                                //
                                // Physical right: the composition is locked to LayoutDirection.Ltr (provideGlance), so the
                                // Box's TopEnd alignment and the Column's end padding are the right edge for RTL and LTR
                                // feeds alike (same assumption the old left rail made with start/TopStart).
                                Box(
                                    modifier = GlanceModifier.fillMaxWidth(),
                                    contentAlignment = Alignment.TopEnd,
                                ) {
                                    Column(modifier = GlanceModifier.fillMaxWidth().padding(end = RAIL_WIDTH_DP.dp)) {
                                        ArticleRow()
                                    }
                                    ThreadRail(railPart, railDotCenterDp)
                                }
                            } else {
                                ArticleRow()
                            }
                            Dividers()
                        }
                    }
                    if (canLoadMoreArticles) {
                        item {
                            Box(
                                modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Load more articles ↓",
                                    style = TextStyle(
                                        fontSize   = 12.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = GlanceTheme.colors.primary,
                                    ),
                                    modifier = GlanceModifier
                                        .background(GlanceTheme.colors.primaryContainer)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .clickable(actionRunCallback<LoadMoreArticlesCallback>()),
                                )
                            }
                        }
                    } else if (memoryCapReached) {
                        // Not clickable — there's genuinely nothing tapping could reveal at
                        // this font size (see memoryCapReached above). Telling the user why,
                        // instead of silently stopping, is the actual fix: the number itself
                        // points them at the way out (a smaller font size raises the cap).
                        // Font size's own slider floor (WidgetConfigActivity.kt) is 0.5f —
                        // below that, "reduce font size" is not actually possible, so showing
                        // that hint there is actively misleading (confirmed on-device: still
                        // capped at 29 of 300 even at the Font size slider's minimum, "Tiny").
                        // Even at the floor there's a real, permanent ceiling here — the
                        // RemoteViews bitmap-memory limit this project has hit for real
                        // before — worth saying plainly rather than pointing at a dead end.
                        val atFontFloor = config.fontSize <= 0.5f
                        item {
                            Box(
                                modifier = GlanceModifier.fillMaxWidth().padding(12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (atFontFloor)
                                        "Showing ${displayArticles.size} of ${layout.availableRows} · this device can't show more at once"
                                    else
                                        "Showing ${displayArticles.size} of ${layout.availableRows} · reduce font size to see more",
                                    style = TextStyle(
                                        fontSize   = 11.sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = GlanceTheme.colors.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            WidgetFooter(lastRefreshTime, lastRefreshFailed, config.refreshIntervalMinutes, config.widgetId)
        }
    }
}

@Composable
private fun WidgetHeader(
    unreadCount: Int,
    totalCount: Int,
    focusedArticleId: String = "",
    focusedIndex: Int = -1,
    displayCount: Int = 0,
    isFocusWidget: Boolean,
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "NewsFeed",
            style = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.onSurfaceVariant),
        )
        Spacer(GlanceModifier.defaultWeight())
        // Focus mode only (tapMode = focus, isFocusWidget — see FeedItemRow.kt's fontSize shadowing). Focus
        // is set/cleared purely by tapping a row directly (SetFocusArticleCallback) — the
        // ▲/▼ step and ✕ clear buttons that used to live here were removed (explicit user
        // request: "remove the button, it's not needed" / "remove the step down or up
        // buttons they are not nedded too"). What's left is the "N/M" position indicator and
        // the focus-scale +/- buttons, both only shown once something is actually focused.
        if (isFocusWidget) {
            // Bigger than before (explicit user request, alongside removing the step/clear
            // buttons above: "make the magnifing buttons bigger") — these are now the only
            // on-widget controls left in the header, so they get a more generous tap target
            // and font size than the old shared stepStyle (13.sp / 6.dp-2.dp padding).
            val scaleButtonStyle = TextStyle(
                fontSize   = 20.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                color      = GlanceTheme.colors.primary,
            )
            // Position indicator ("N / M") — only meaningful once something is focused;
            // otherwise every row is the same size and "position" doesn't mean anything.
            // Answers "where am I in the list" without counting rows by eye.
            if (focusedArticleId.isNotBlank() && focusedIndex >= 0) {
                Text(
                    text = "${focusedIndex + 1}/$displayCount",
                    style = TextStyle(
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        color      = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                )
            }
            // Focus-area size — only meaningful once something is focused (nothing to scale
            // otherwise).
            if (focusedArticleId.isNotBlank()) {
                Text(
                    text = "−",
                    style = scaleButtonStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to -AdjustFocusScaleCallback.STEP)
                        )),
                )
                Text(
                    text = "+",
                    style = scaleButtonStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to AdjustFocusScaleCallback.STEP)
                        )),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
        }
        // Both numbers scoped to displayArticles (what's actually on screen right now),
        // not the full accumulated store (which can hold up to 300) — showing the full
        // store's total here read as a bug (reported directly): a badge claiming e.g.
        // "10(300)" when only 30 articles are actually reachable by scrolling implies 300
        // are available, not 30. The real total-vs-available accounting lives at the end
        // of the list instead (canLoadMoreArticles/memoryCapReached's "Showing X of Y"
        // message below), where "of Y" unambiguously means the full available count.
        Text(
            text = "${if (unreadCount > 99) "99+" else "$unreadCount"}($totalCount)",
            style = TextStyle(
                fontSize   = 11.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                color      = GlanceTheme.colors.onPrimaryContainer,
            ),
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun WidgetFooter(lastRefreshTime: Long, lastRefreshFailed: Boolean, intervalMinutes: Int, widgetId: Int) {
    Divider()
    val now           = System.currentTimeMillis()
    val nextMs        = lastRefreshTime + TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
    val leftMs        = (nextMs - now).coerceAtLeast(0L)
    val leftMin       = TimeUnit.MILLISECONDS.toMinutes(leftMs)
    val countdownText = when {
        lastRefreshFailed     -> "⚠ refresh failed — tap to retry"
        lastRefreshTime == 0L -> "↻ now"
        leftMs < 60_000L      -> "↻ <1min"
        else                  -> "↻ in ${leftMin}min"
    }
    val countdownColor = if (lastRefreshFailed) ColorProvider(Color(0xFFE0A030)) else GlanceTheme.colors.primary

    val context = LocalContext.current
    val settingsIntent = Intent(context, WidgetConfigActivity::class.java).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = countdownText,
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = countdownColor),
            maxLines = 1,
            // defaultWeight() here (not on a separate Spacer) makes the countdown text itself
            // the row's one flexible/shrinkable element - it truncates first when space is
            // tight, so the actionable Share/gear controls after it (fixed-size, no weight)
            // always keep their full width and stay fully visible and tappable. Before this,
            // a lone Spacer(defaultWeight()) between the countdown and Share/gear meant the
            // countdown's own text never shrank - at a narrow widget width with the longest
            // countdown string ("⚠ refresh failed — tap to retry"), the fixed-size Share/gear
            // group got pushed past the row's bounds instead, clipping "Share" and cutting the
            // gear icon out of the layout entirely (confirmed on-device: zero matches for the
            // gear glyph in the accessibility tree in that state, making Settings untappable).
            // Matches FeedItemRow.kt's own maxLines = 1 precedent for feedConfig.displayName -
            // truncate the informational text, never the interactive controls.
            modifier = GlanceModifier
                .defaultWeight()
                .padding(4.dp)
                .clickable(actionRunCallback<RefreshNowCallback>()),
        )
        Text(
            text = "Share",
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.primary),
            // Background pill matching the approved mockup shown during design brainstorming -
            // a light wash of the accent color behind accent-colored text, with rounded
            // corners. Uses GlanceTheme.colors.primaryContainer (the theme's own "light wash
            // of primary" role) rather than the mockup's illustrative hardcoded hex, so this
            // stays correct across all 10 themes, not just the default purple one the mockup
            // happened to visualize against.
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .cornerRadius(6.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable(actionStartActivity(
                    Intent(context, ShareRelayActivity::class.java)
                        .putExtra(ShareRelayActivity.EXTRA_SHOW_APP_SHARE_CHOICE, true)
                )),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = "⚙",
            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(4.dp)
                .clickable(actionStartActivity(settingsIntent)),
        )
    }
}

@Composable
private fun Divider(thin: Boolean = false) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(if (thin) 0.5.dp else 1.dp)
            .background(GlanceTheme.colors.surfaceVariant),
    ) {}
}

class NewsFeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NewsFeedWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWorker.ensureScheduled(context)
        UpdateCheckWorker.schedule(context)
        scheduleClockTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // NewsFeedWidgetReceiver is the only receiver, so its widget count reaching zero
        // means no widget of the app is left: stop the shared periodic jobs.
        WidgetWorker.cancel(context)
        UpdateCheckWorker.cancel(context)
        cancelClockTick(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Glance's own receiver removes the per-widget Glance state file; drop the saved config
        // and its backup too, so they no longer leak for every widget the user removes.
        // NEVER call goAsync() here: Glance's own onDeleted (super) already took the broadcast's
        // PendingResult, so a second call returns null (BUG-021 crash). Hand off to WorkManager.
        runCatching { OrphanCleanupWorker.enqueue(context, appWidgetIds) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_CLOCK_TICK) {
            val pending = goAsync()
            MainScope().launch {
                try { NewsFeedWidget().updateAll(context) }
                finally { pending.finish() }
            }
            scheduleClockTick(context)
        }
    }

    companion object {
        const val ACTION_CLOCK_TICK = "com.newsfeed.widget.CLOCK_TICK"
        private const val RC_CLOCK  = 1001

        fun scheduleClockTick(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.RTC,
                System.currentTimeMillis() + 60_000L,
                clockPi(context),
            )
        }

        private fun cancelClockTick(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(clockPi(context))
        }

        private fun clockPi(context: Context) = PendingIntent.getBroadcast(
            context, RC_CLOCK,
            Intent(ACTION_CLOCK_TICK, null, context, NewsFeedWidgetReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
