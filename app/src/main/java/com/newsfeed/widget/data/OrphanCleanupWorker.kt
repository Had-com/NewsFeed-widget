package com.newsfeed.widget.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * Removes the saved config / backup / Glance files of widgets the user just removed. Runs off the
 * receiver's onDeleted(), which must not use goAsync() (Glance's super already did: BUG-021).
 * The periodic WidgetWorker sweep remains the safety net if this never runs.
 */
class OrphanCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ids = inputData.getIntArray(KEY_IDS)?.toSet().orEmpty()
        OrphanCleanup.removeIds(applicationContext, ids) // swallows its own failures
        return Result.success()
    }

    companion object {
        private const val KEY_IDS = "ids"

        fun enqueue(context: Context, ids: IntArray) {
            if (ids.isEmpty()) return
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<OrphanCleanupWorker>()
                    .setInputData(workDataOf(KEY_IDS to ids))
                    .build(),
            )
        }
    }
}
