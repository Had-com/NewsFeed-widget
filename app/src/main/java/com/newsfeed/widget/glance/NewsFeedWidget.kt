package com.newsfeed.widget.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.newsfeed.widget.config.WidgetConfigActivity
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
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

        val config = configJson
            ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: WidgetConfig(widgetId = -1)

        val articles: List<ArticleItem> = articlesJson
            ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
            ?: emptyList()

        val feedMap         = config.feeds.associateBy { it.feedId }
        val visibleCount    = prefs[WidgetStateKey.visibleArticleCount] ?: LoadMoreArticlesCallback.ARTICLE_CHUNK_SIZE
        val availableArticles = articles.filter { feedMap.containsKey(it.feedId) }

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
        val headlineLineHeightPx = 13f * config.fontSize * scaledDensity2 * 1.2f
        // Worst case per row: a 3-line Glamour headline bitmap plus a small thumbnail —
        // themes without a bitmap headline (plain Text) cost far less, so this deliberately
        // overestimates rather than risking under-provisioning.
        val headlineBytes  = if (config.widgetTheme == "glamer")
            (widthPx2 * (3 * headlineLineHeightPx) * 4f) else 0f
        val thumbnailBytes = 100f * 100f * 4f
        val bytesPerRow    = (headlineBytes + thumbnailBytes).coerceAtLeast(1f)
        val rowBudgetBytes = 7_500_000f
        val maxRowsAllowed = (rowBudgetBytes / bytesPerRow).toInt().coerceIn(5, 60)

        val displayArticles = availableArticles.take(visibleCount.coerceAtMost(maxRowsAllowed))
        // Based on visibleCount (what's been requested), not displayArticles.size (what's
        // actually shown after clamping) — comparing the clamped size against maxRowsAllowed
        // was always false the moment a single "chunk" request met or exceeded the memory
        // ceiling, hiding the button on the very first render whenever that happened instead
        // of only once truly exhausted. visibleCount vs the two ceilings is what actually
        // determines whether tapping "Load more" would reveal anything new.
        val canLoadMoreArticles = visibleCount < maxRowsAllowed && visibleCount < availableArticles.size
        // Scoped to displayArticles, not the full accumulated store (which can hold up to
        // 300) — counting the full store made the header badge claim "99+" unread while only
        // a fraction of articles were ever reachable by scrolling, which read as a bug (and
        // was reported as one) rather than the accumulation feature it actually was.
        val unreadCount     = displayArticles.count { !it.isRead }

        val themeColors = WidgetThemes.colorProvidersFor(config.widgetTheme, config.themeVariant)
        val surfaceColor = WidgetThemes.surfaceColorFor(config.widgetTheme, config.themeVariant)
        val bgColor = ColorProvider(surfaceColor.copy(alpha = config.backgroundAlpha))

        GlanceTheme(colors = themeColors) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(bgColor)
                    .cornerRadius(18.dp)
                    .padding(0.dp),
            ) {
                WidgetHeader(unreadCount)
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
                        items(displayArticles) { article ->
                            val feedConfig = feedMap[article.feedId] ?: return@items
                            val isLast = article == displayArticles.last()
                            Column(modifier = GlanceModifier.fillMaxWidth()) {
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
                                )
                                if (!isLast) {
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
                        }
                    }
                }

                WidgetFooter(lastRefreshTime, lastRefreshFailed, config.refreshIntervalMinutes, config.widgetId)
            }
        }
    }

    @Composable
    private fun WidgetHeader(unreadCount: Int) {
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
            if (unreadCount > 0) {
                Text(
                    text = if (unreadCount > 99) "99+" else "$unreadCount",
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
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshNowCallback>()),
            )
            Spacer(GlanceModifier.defaultWeight())
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
}

class NewsFeedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NewsFeedWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetWorker.schedule(context)
        scheduleClockTick(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetWorker.cancel(context)
        cancelClockTick(context)
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
