package com.readyou.widget.glance

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.readyou.widget.data.ArticleItem
import com.readyou.widget.data.ReadStatusStore
import com.readyou.widget.data.ReadYouRepository
import com.readyou.widget.data.WidgetConfigStore
import com.readyou.widget.data.WidgetStateKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store     = WidgetConfigStore(context)
        val repo      = ReadYouRepository(context)
        val readIds   = ReadStatusStore(context).readIdsFlow().first()
        val manager   = GlanceAppWidgetManager(context)
        val widgetIds = manager.getGlanceIds(ReadYouWidget::class.java)

        for (glanceId in widgetIds) {
            val appWidgetId = manager.getAppWidgetId(glanceId)
            val config      = store.configFlow(appWidgetId).first()
            val fresh       = repo.getArticles(config).map { a ->
                if (a.id in readIds) a.copy(isRead = true) else a
            }

            val now = System.currentTimeMillis()
            var merged: List<ArticleItem> = emptyList()
            updateAppWidgetState(context, glanceId) { prefs ->
                val existing = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                    ?: emptyList()
                val freshIds = fresh.map { it.id }.toSet()
                merged = (fresh + existing.filter { it.id !in freshIds })
                    .sortedByDescending { it.publishedAt }
                    .take(300)
                prefs[WidgetStateKey.articles]        = Json.encodeToString(merged)
                prefs[WidgetStateKey.configJson]      = Json.encodeToString(config)
                prefs[WidgetStateKey.lastRefreshTime] = now
            }
            // Download thumbnails for the merged set so accumulated articles are covered too
            repo.downloadThumbnails(merged.take(30), config.feeds)
            repo.downloadFavicons(config.feeds)
        }

        ReadYouWidget().updateAll(context)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ReadYouWidgetRefresh"

        fun schedule(context: Context, intervalMinutes: Long = 15) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(
                intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.REPLACE, request,
            )
        }

        fun refreshNow(context: Context) {
            WorkManager.getInstance(context)
                .enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
