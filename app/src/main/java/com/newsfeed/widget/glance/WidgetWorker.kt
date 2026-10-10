package com.newsfeed.widget.glance

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ConfigBackup
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.NewsFeedRepository
import com.newsfeed.widget.data.MIN_REFRESH_INTERVAL_MINUTES
import com.newsfeed.widget.data.OrphanCleanup
import com.newsfeed.widget.data.RefreshOutcome
import com.newsfeed.widget.data.RefreshStatusStore
import com.newsfeed.widget.data.WorkerVerdict
import com.newsfeed.widget.data.workerVerdict
import com.newsfeed.widget.data.WORK_SCHEMA_VERSION
import com.newsfeed.widget.data.effectiveRefreshIntervalMinutes
import com.newsfeed.widget.data.workNeedsMigration
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.mergeFreshArticles
import com.newsfeed.widget.data.applyRetention
import com.newsfeed.widget.data.retainWithPerFeedGuarantee
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

class WidgetWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store     = WidgetConfigStore(context)
        val repo      = NewsFeedRepository(context)
        val readIds   = ReadStatusStore(context).readIdsFlow().first()
        val manager   = GlanceAppWidgetManager(context)
        val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)

        // Housekeeping: drop config/backup/Glance-state left by ids that are gone (notably the
        // removed NewsFeed Focus widgets, which vanish on update without any callback). The live
        // set is the UNION of the system list for our provider and Glance's own list, so a
        // failure of either lookup can only make the live set larger (= fewer deletions). If the
        // union is empty the sweep is a no-op (never "everything is an orphan"). The worker never
        // cancels the shared periodic jobs itself: an empty list can be transient (right after
        // boot / package replace) and nothing would re-arm them. A genuine last-widget removal
        // cancels them in NewsFeedWidgetReceiver.onDisabled().
        val systemIds = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, NewsFeedWidgetReceiver::class.java)).toSet()
        }.getOrDefault(emptySet())
        val liveIds = systemIds + widgetIds.map { manager.getAppWidgetId(it) }
        OrphanCleanup.sweep(context, liveIds)
        if (liveIds.isEmpty()) return Result.success()
        var runOutcome = RefreshOutcome()
        // Self-heal: widgets exist, so make sure the daily update check is armed (KEEP = no-op if it is).
        runCatching { UpdateCheckWorker.schedule(context) }

        for (glanceId in widgetIds) {
            val appWidgetId = manager.getAppWidgetId(glanceId)
            // restoreIfEmpty is a no-op whenever feeds are already present — this only ever
            // does anything on the refresh right after something (an update, a corrupted
            // DataStore write) unexpectedly wiped this widget's feed list. See ConfigBackup.
            val config      = ConfigBackup.restoreIfEmpty(context, appWidgetId, store.configFlow(appWidgetId).first())
            // Read prior state before fetching (not just inside updateAppWidgetState below)
            // so getArticles() knows which feeds are being fetched for the very first time —
            // a feed with no accumulated articles yet gets its full available backlog instead
            // of the normal per-refresh item cap (see NewsFeedRepository.getArticles).
            val priorPrefs    = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
            val priorArticles = priorPrefs[WidgetStateKey.articles]
                ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                ?: emptyList()
            val knownFeedIds  = priorArticles.map { it.feedId }.toSet()
            val fetchResult = repo.getArticles(config, knownFeedIds)
            val outcome     = fetchResult.outcome
            runOutcome += outcome
            val fresh       = fetchResult.articles.map { a ->
                if (a.id in readIds) a.copy(isRead = true) else a
            }

            val now = System.currentTimeMillis()
            // How long an accumulated article stays in the list, independent of the 300-item
            // safety cap below — 0 means "no date limit" (300-cap-only, the original behavior).
            // (retentionCutoff/applyRetention are pure and unit tested; the render applies the same cutoff,
            // so a short window such as 15 minutes also takes effect between worker runs.)
            var merged: List<ArticleItem> = emptyList()
            updateAppWidgetState(context, glanceId) { prefs ->
                val existing = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                    ?: emptyList()
                val candidates = applyRetention(mergeFreshArticles(fresh, existing), config, now)
                merged = retainWithPerFeedGuarantee(candidates, cap = 300, minPerFeed = 10)
                prefs[WidgetStateKey.articles]        = Json.encodeToString(merged)
                prefs[WidgetStateKey.configJson]      = Json.encodeToString(config)
                // lastRefreshTime moves only when at least one feed was really fetched (BUG-035):
                // a run in which every feed failed must not look like a refresh.
                if (outcome.hasSuccess) prefs[WidgetStateKey.lastRefreshTime] = now
                prefs[WidgetStateKey.lastRefreshAttemptTime] = now
                prefs[WidgetStateKey.lastRefreshFailed] = outcome.allFailed
                prefs[WidgetStateKey.failedFeedCount] = outcome.failed
            }
            // Nothing was reachable: the image downloads would only wait for their own timeouts.
            if (!outcome.allFailed) {
                // Download thumbnails for the merged set so accumulated articles are covered too
                repo.downloadThumbnails(merged.take(30), config.feeds)
                repo.downloadFavicons(config.feeds)
            }
        }

        NewsFeedWidget().updateAll(context)
        if (runOutcome.hasSuccess) RefreshStatusStore(context).lastOkRefreshMs = System.currentTimeMillis()
        // Every feed failed: WorkManager's exponential backoff retries, at most MAX_REFRESH_ATTEMPTS
        // runs per period (workerVerdict); after that this run ends as a success and the next period
        // handles it, so there is never an endless quick retry loop.
        return when (workerVerdict(runOutcome, runAttemptCount)) {
            WorkerVerdict.RETRY   -> Result.retry()
            WorkerVerdict.SUCCESS -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "NewsFeedRefresh"
        // One name for the tap on the footer, the Settings Save and the watchdog's catch-up
        // (BUG-036): with KEEP a burst of them collapses into a single run.
        private const val REFRESH_NOW_WORK_NAME = "NewsFeedRefreshNow"

        // Receivers cannot wait for DataStore; work that must read it runs here, off the main thread.
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        // A refresh needs the network. WITHOUT this constraint the periodic job ran whenever the OS
        // felt like it, typically while the app was in the APP_BACKGROUND firewall state with every
        // DNS lookup blocked: the run "succeeded" with zero data (BUG-034). With it WorkManager only
        // starts the job once a connected network is available.
        private fun networkConstraints(): Constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** The ONE place the periodic request is built (schedule, ensureScheduled, rearm, boot). */
        fun periodicRequest(intervalMinutes: Long): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<WidgetWorker>(
                intervalMinutes.coerceAtLeast(MIN_REFRESH_INTERVAL_MINUTES.toLong()), TimeUnit.MINUTES,
            ).setConstraints(networkConstraints()).build()

        /** The one-time request used by the footer tap, Settings Save and the watchdog. */
        fun oneTimeRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(networkConstraints()).build()

        // UPDATE (WorkManager 2.8+): replaces the job's definition (interval, constraints) in place and
        // keeps its schedule phase, so calling this from several places is safe and an existing
        // install picks up a new constraint without being removed and re-added. REPLACE would restart
        // the period on every call; KEEP would never apply a changed definition.
        fun schedule(context: Context, intervalMinutes: Long = MIN_REFRESH_INTERVAL_MINUTES.toLong()) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodicRequest(intervalMinutes),
            )
        }

        // Makes sure the periodic job exists with the CURRENT definition at the user's interval, for
        // call sites that fire without knowing the interval (onEnabled / onUpdate). The interval
        // comes from the cache written by rearm(); before the first rearm it is derived from the
        // saved widget configs off the main thread. It is never a hard-coded 15 that could reset a
        // user's 6 h setting.
        fun ensureScheduled(context: Context) {
            val app = context.applicationContext
            val cached = RefreshStatusStore(app).intervalMinutes
            if (cached > 0) schedule(app, cached.toLong())
            else appScope.launch { runCatchingNotCancelled { rearm(app) } }
        }

        // Recomputes the shared interval from the saved configs of the placed widgets (all saved
        // configs if no widget is reported), caches it, and (re-)enqueues the job with it.
        suspend fun rearm(context: Context) {
            val app = context.applicationContext
            val ids = liveWidgetIds(app)
            val configs = WidgetConfigStore(app).savedConfigs(ids.takeIf { it.isNotEmpty() })
            val minutes = effectiveRefreshIntervalMinutes(configs)
            val status = RefreshStatusStore(app)
            status.intervalMinutes = minutes
            schedule(app, minutes.toLong())
            status.workSchemaVersion = WORK_SCHEMA_VERSION
        }

        // App start: a job enqueued by an older build (no network constraint) is re-enqueued once.
        fun migrateIfNeeded(context: Context) {
            val app = context.applicationContext
            if (!workNeedsMigration(RefreshStatusStore(app).workSchemaVersion)) return
            appScope.launch {
                runCatchingNotCancelled {
                    // No widget = no job to migrate (onEnabled creates it, with the new definition).
                    if (liveWidgetIds(app).isNotEmpty()) rearm(app)
                }
            }
        }

        /** Placed widget ids: the system's list for our provider UNION Glance's own list. */
        suspend fun liveWidgetIds(context: Context): Set<Int> {
            val manager = GlanceAppWidgetManager(context)
            val systemIds = runCatching {
                AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, NewsFeedWidgetReceiver::class.java)).toSet()
            }.getOrDefault(emptySet())
            val glanceIds = try {
                manager.getGlanceIds(NewsFeedWidget::class.java).map { manager.getAppWidgetId(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            return systemIds + glanceIds
        }

        // KEEP means a tap while one is already pending/running is silently absorbed instead of
        // stacking another full fetch-all-feeds run (BUG-011); the next tap after it finishes enqueues
        // normally again. CONNECTED: offline, the run waits for the network instead of failing.
        fun refreshNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                REFRESH_NOW_WORK_NAME, ExistingWorkPolicy.KEEP, oneTimeRequest(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            RefreshStatusStore(context).clearJobState()
        }

        // runCatching would also swallow a CancellationException and so stop a cancelled coroutine
        // from being cancelled; this rethrows it.
        private suspend fun runCatchingNotCancelled(block: suspend () -> Unit) {
            try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }
}
