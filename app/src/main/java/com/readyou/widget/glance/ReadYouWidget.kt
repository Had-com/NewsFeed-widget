package com.readyou.widget.glance

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.readyou.widget.data.ArticleItem
import com.readyou.widget.data.WidgetConfig
import com.readyou.widget.data.WidgetStateKey
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class ReadYouWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent { WidgetContent() }
    }

    @Composable
    private fun WidgetContent() {
        val prefs           = currentState<androidx.datastore.preferences.core.Preferences>()
        val configJson      = prefs[WidgetStateKey.configJson]
        val articlesJson    = prefs[WidgetStateKey.articles]
        val lastRefreshTime = prefs[WidgetStateKey.lastRefreshTime] ?: 0L

        val config = configJson
            ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
            ?: WidgetConfig(widgetId = -1)

        val articles: List<ArticleItem> = articlesJson
            ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
            ?: emptyList()

        val feedMap = config.feeds.associateBy { it.feedId }

        // Only count unread among articles that will actually render (have a known feed)
        val displayArticles = articles.filter { feedMap.containsKey(it.feedId) }.take(50)
        val unreadCount     = displayArticles.count { !it.isRead }

        GlanceTheme {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(18.dp)
                    .padding(0.dp),
            ) {
                WidgetHeader(unreadCount)
                Divider()

                Column(modifier = GlanceModifier.defaultWeight().fillMaxWidth()) {
                    if (displayArticles.isEmpty()) {
                        EmptyState()
                    } else {
                        displayArticles.forEach { article ->
                            val feedConfig = feedMap[article.feedId] ?: return@forEach
                            FeedItemRow(
                                article    = article,
                                feedConfig = feedConfig,
                                widgetId   = config.widgetId,
                                fontSize   = config.fontSize,
                            )
                            Divider(thin = true)
                        }
                    }
                }
                WidgetFooter(lastRefreshTime, config.refreshIntervalMinutes)
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
                text = "Read You",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
            )
            Spacer(GlanceModifier.defaultWeight())
            if (unreadCount > 0) {
                Text(
                    text = if (unreadCount > 99) "99+" else "$unreadCount",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = ColorProvider(Color(0xFFC4A9FF)),
                    ),
                    modifier = GlanceModifier
                        .background(ColorProvider(Color(0x296750A4)))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }

    @Composable
    private fun WidgetFooter(lastRefreshTime: Long, intervalMinutes: Int) {
        Divider()
        val ageMs   = (System.currentTimeMillis() - lastRefreshTime).coerceAtLeast(0L)
        val ageText = when {
            lastRefreshTime == 0L                        -> "never"
            ageMs < TimeUnit.MINUTES.toMillis(1)         -> "just now"
            ageMs < TimeUnit.HOURS.toMillis(1)           -> "${TimeUnit.MILLISECONDS.toMinutes(ageMs)}m ago"
            else                                         -> "${TimeUnit.MILLISECONDS.toHours(ageMs)}h ago"
        }

        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↻ last: $ageText",
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF9B72E3))),
                modifier = GlanceModifier.clickable(actionRunCallback<RefreshNowCallback>()),
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = "every ${intervalMinutes}min",
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(Color(0xFF9B72E3))),
            )
        }
    }

    @Composable
    private fun EmptyState() {
        Box(
            modifier = GlanceModifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No articles",
                style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant),
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

class ReadYouWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = ReadYouWidget()

    override fun onEnabled(context: android.content.Context) {
        super.onEnabled(context)
        WidgetWorker.schedule(context)
    }

    override fun onDisabled(context: android.content.Context) {
        super.onDisabled(context)
        WidgetWorker.cancel(context)
    }
}
