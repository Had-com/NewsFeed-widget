package com.newsfeed.widget.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val prefs             = currentState<androidx.datastore.preferences.core.Preferences>()
        val configJson        = prefs[WidgetStateKey.configJson]
        val articlesJson      = prefs[WidgetStateKey.articles]
        val lastRefreshTime   = prefs[WidgetStateKey.lastRefreshTime] ?: 0L
        val expandedArticleId = prefs[WidgetStateKey.expandedArticleId] ?: ""
        val fullArticleId     = prefs[WidgetStateKey.fullArticleId]     ?: ""
        val fullArticleText   = prefs[WidgetStateKey.fullArticleText]   ?: ""

        val config = configJson
            ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: WidgetConfig(widgetId = -1)

        val articles: List<ArticleItem> = articlesJson
            ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
            ?: emptyList()

        val feedMap         = config.feeds.associateBy { it.feedId }
        val displayArticles = articles.filter { feedMap.containsKey(it.feedId) }.take(50)
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
                                    widgetId          = config.widgetId,
                                    fontSize          = config.fontSize,
                                    articleLength     = config.articleLength,
                                    fullArticleId     = fullArticleId,
                                    fullArticleText   = fullArticleText,
                                    useThemeColors    = config.useThemeColors,
                                    widgetTheme       = config.widgetTheme,
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
                    }
                }

                WidgetFooter(lastRefreshTime, config.refreshIntervalMinutes, config.widgetId)
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
    private fun WidgetFooter(lastRefreshTime: Long, intervalMinutes: Int, widgetId: Int) {
        Divider()
        val now           = System.currentTimeMillis()
        val nextMs        = lastRefreshTime + TimeUnit.MINUTES.toMillis(intervalMinutes.toLong())
        val leftMs        = (nextMs - now).coerceAtLeast(0L)
        val leftMin       = TimeUnit.MILLISECONDS.toMinutes(leftMs)
        val countdownText = when {
            lastRefreshTime == 0L -> "↻ now"
            leftMs < 60_000L      -> "↻ <1min"
            else                  -> "↻ in ${leftMin}min"
        }

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
                style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.primary),
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
