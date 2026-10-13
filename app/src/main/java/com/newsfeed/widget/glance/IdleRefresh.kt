package com.newsfeed.widget.glance

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.newsfeed.widget.data.MIN_REFRESH_INTERVAL_MINUTES
import com.newsfeed.widget.data.RefreshStatusStore
import com.newsfeed.widget.data.RefreshTrigger
import com.newsfeed.widget.data.RunGuard
import com.newsfeed.widget.data.idleAlarmNeedsRearm
import com.newsfeed.widget.data.idleRefreshDelayMs
import com.newsfeed.widget.data.shouldArmIdleAlarm
import com.newsfeed.widget.data.shouldSkipRun

/**
 * The idle refresh alarm (BUG-046): a wake-up alarm at the user's refresh interval that starts an expedited
 * one-time refresh, so the widget keeps refreshing on schedule while the screen is off (the periodic job, which
 * needs a network, only gets one about hourly in Doze). The decisions are pure and live in data/IdleRefresh.kt.
 *
 *  - INEXACT: `setAndAllowWhileIdle(RTC_WAKEUP, ...)`. No SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permission is used
 *    or declared. In Doze the system may delay it (about 9 to 15 minutes between such alarms per app).
 *  - IDEMPOTENT: one PendingIntent (same request code, action and component, FLAG_UPDATE_CURRENT), so arming again
 *    REPLACES the pending alarm. Never more than one alarm, one per interval.
 *  - It exists only while the Settings switch is on AND a widget is placed; every other state cancels it, and a
 *    fired alarm only re-arms itself under the same two conditions, so nothing re-arms forever.
 *  - Never runs a refresh itself: it enqueues ONE unique expedited work request, and the worker's RunGuard decides
 *    whether it really runs (same guard as the periodic job, the watchdog and taps).
 */
object IdleRefresh {
    const val ACTION_IDLE_REFRESH = "com.newsfeed.widget.IDLE_REFRESH"
    private const val RC_IDLE = 1002

    /**
     * The one entry point every (re)arming place calls: arms the alarm when the switch is on and a widget is placed
     * (aligned with the last finished run), otherwise cancels it. Cheap and safe to call often. Returns whether armed.
     */
    fun ensureArmed(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val app = context.applicationContext
        val status = RefreshStatusStore(app)
        if (!shouldArmIdleAlarm(status.idleRefreshEnabled, widgetsPlaced(app))) {
            cancel(app)
            return false
        }
        arm(app, nowMs, idleRefreshDelayMs(intervalMinutes(status), nowMs, status.lastFinishedMs))
        return true
    }

    /**
     * For the 105 s clock tick: re-arms only when the alarm looks lost ([idleAlarmNeedsRearm]), so a healthy alarm is
     * not touched every tick.
     */
    fun healIfLost(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val status = RefreshStatusStore(context.applicationContext)
        val intervalMs = intervalMinutes(status) * 60_000L
        if (idleAlarmNeedsRearm(status.idleRefreshEnabled, status.idleAlarmDueMs, nowMs, intervalMs)) ensureArmed(context, nowMs)
    }

    /** Cancels the alarm (switch turned off, last widget removed). */
    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(idlePi(app)) }
        RefreshStatusStore(app).idleAlarmDueMs = 0L
    }

    /** The alarm fired (called by [IdleRefreshReceiver]). Never throws. */
    fun onAlarm(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val status = RefreshStatusStore(app)
        if (!shouldArmIdleAlarm(status.idleRefreshEnabled, widgetsPlaced(app))) {
            // Switch turned off or the widget is gone in the meantime: stop, no re-arming, no refresh.
            cancel(app)
            return
        }
        // 1. The NEXT alarm first, a full interval away (this alarm IS the refresh that is due now); the end of the run
        //    re-aligns it with the real finish time. So even if everything below fails the chain goes on.
        runCatching { arm(app, nowMs, idleRefreshDelayMs(intervalMinutes(status), nowMs, lastRunFinishedMs = nowMs)) }
        // 2. One expedited refresh. The same guard as the worker's, checked here too so a run that is active or has
        //    just finished does not even enqueue a job (the worker would end at once anyway).
        val skip = shouldSkipRun(RefreshTrigger.IDLE_ALARM, nowMs, status.activeStartedMs, status.lastFinishedMs)
        if (!skip) runCatching { WidgetWorker.enqueueIdleRefresh(app) }
    }

    private fun intervalMinutes(status: RefreshStatusStore): Int =
        status.intervalMinutes.takeIf { it > 0 } ?: MIN_REFRESH_INTERVAL_MINUTES

    private fun arm(context: Context, nowMs: Long, delayMs: Long) {
        val dueMs = nowMs + delayMs
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueMs, idlePi(context))
        RefreshStatusStore(context).idleAlarmDueMs = dueMs
    }

    // Same lookup as NewsFeedWidgetReceiver.ensureClockTick: if the system's widget list cannot be read assume a
    // widget exists (one alarm too many costs one wake-up; cancelling wrongly would stop the refreshes).
    private fun widgetsPlaced(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, NewsFeedWidgetReceiver::class.java)).isNotEmpty()
    }.getOrDefault(true)

    // Immutable: nothing needs to be filled in by the system. Explicit component, so the receiver can stay not exported.
    private fun idlePi(context: Context) = PendingIntent.getBroadcast(
        context, RC_IDLE,
        Intent(ACTION_IDLE_REFRESH, null, context, IdleRefreshReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
