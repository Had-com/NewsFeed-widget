package com.newsfeed.widget.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateCheckWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        UpdateManager.checkAndUpdate(context, notifyOnly = true)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "NewsFeedUpdateCheck"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS).build()
            // KEEP, not REPLACE (unlike WidgetWorker.schedule, whose interval is user-
            // configurable and so must reset on every call) — this interval never changes, so
            // re-enqueuing on every onEnabled() call (e.g. a widget removed and re-added) would
            // just needlessly reset an already-correct periodic schedule.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
