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
import com.readyou.widget.data.ReadStatusStore
import com.readyou.widget.data.ReadYouRepository
import com.readyou.widget.data.WidgetConfigStore
import com.readyou.widget.data.WidgetStateKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = WidgetConfigStore(context)
        val repo = ReadYouRepository(context)
        val readIds = ReadStatusStore(context).readIdsFlow().first()
        val manager = GlanceAppWidgetManager(context)
        val widgetIds = manager.getGlanceIds(ReadYouWidget::class.java)

        for (glanceId in widgetIds) {
            val appWidgetId = manager.getAppWidgetId(glanceId)
            val config = store.configFlow(appWidgetId).first()
            val articles = repo.getArticles(config).map { article ->
                if (article.id in readIds) article.copy(isRead = true) else article
            }
            val serialized = Json.encodeToString(articles)

            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetStateKey.articles] = serialized
                prefs[WidgetStateKey.configJson] = Json.encodeToString(config)
            }
        }

        ReadYouWidget().updateAll(context)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "ReadYouWidgetRefresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Triggers an immediate one-shot refresh — call after the user saves config. */
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetWorker>().build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
