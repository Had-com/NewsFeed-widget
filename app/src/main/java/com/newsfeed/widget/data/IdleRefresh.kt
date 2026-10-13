package com.newsfeed.widget.data

/**
 * Pure rules of the idle refresh alarm (BUG-046), no Android types, unit tested.
 *
 * Why it exists: since the network fix (BUG-034) the periodic job requires a connected network. While the
 * screen is off the system opens network windows only about once an hour (Doze / App Standby), so the job ran
 * about hourly instead of every 15 minutes. A wake-up alarm (inexact, `setAndAllowWhileIdle`, RTC_WAKEUP) at the
 * user's refresh interval starts an EXPEDITED one-time refresh, which the system lets use the network.
 * The Android side is in glance/IdleRefresh.kt and glance/IdleRefreshReceiver.kt.
 */

/** The next alarm is never set closer than this (a run that is overdue is caught up one minute from now). */
const val IDLE_ALARM_MIN_DELAY_MS = 60_000L

/** The Settings switch is on unless the user turns it off. */
const val DEFAULT_IDLE_REFRESH_ENABLED = true

/**
 * How long from [nowMs] the next idle alarm should fire. The base is the user's effective refresh interval
 * (at least [MIN_REFRESH_INTERVAL_MINUTES]). If the last run finished k ms ago the alarm is set `interval - k`
 * from now, so it stays aligned with the real refreshes (a run that just finished pushes the alarm a full
 * interval out; an overdue one is caught up soon). Never below [IDLE_ALARM_MIN_DELAY_MS], never above the
 * interval. `0` or a time in the future (clock moved back) means "no usable last run": a full interval.
 */
fun idleRefreshDelayMs(intervalMinutes: Int, nowMs: Long, lastRunFinishedMs: Long): Long {
    val intervalMs = intervalMinutes.toLong().coerceAtLeast(MIN_REFRESH_INTERVAL_MINUTES.toLong()) * 60_000L
    if (lastRunFinishedMs <= 0L || lastRunFinishedMs > nowMs) return intervalMs
    val sinceRunMs = nowMs - lastRunFinishedMs
    return (intervalMs - sinceRunMs).coerceIn(IDLE_ALARM_MIN_DELAY_MS, intervalMs)
}

/** The alarm exists only while the user wants it and a widget is placed; otherwise it is cancelled (no endless re-arming). */
fun shouldArmIdleAlarm(enabled: Boolean, widgetsExist: Boolean): Boolean = enabled && widgetsExist

/**
 * Self-heal check for the 105 s clock tick, which must not re-set the alarm every time (it would only churn): true when
 * the alarm is wanted ([enabled]) but looks lost, i.e. it was never planned (0), is overdue by more than one full
 * interval (the system drops alarms on a force-stop, and Doze delays one by minutes, not by an interval), or is planned
 * implausibly far ahead (clock moved back).
 */
fun idleAlarmNeedsRearm(enabled: Boolean, plannedDueMs: Long, nowMs: Long, intervalMs: Long): Boolean {
    if (!enabled) return false
    if (plannedDueMs <= 0L) return true
    return nowMs > plannedDueMs + intervalMs || plannedDueMs > nowMs + intervalMs
}

/** Where the Settings switch is kept ([RefreshStatusStore] implements it; tests use a fake). */
interface IdleRefreshSetting {
    var idleRefreshEnabled: Boolean
}

/**
 * The Settings checkbox: saves the choice immediately, THEN lets [ensureArmed] arm or cancel the alarm (it reads the
 * saved value). A failing arm step never undoes or loses the saved choice.
 */
fun applyIdleRefreshToggle(setting: IdleRefreshSetting, enabled: Boolean, ensureArmed: () -> Unit) {
    setting.idleRefreshEnabled = enabled
    try { ensureArmed() } catch (_: Exception) { }
}

/**
 * The problem report's lines about the alarm. There is no API to ask AlarmManager whether our alarm is pending, so
 * the app records when it last planned it ([plannedDueMs], 0 = none planned / cancelled) and the report says that.
 * [at] formats a time exactly like the rest of the report.
 */
fun idleRefreshReportLines(enabled: Boolean, plannedDueMs: Long, nowMs: Long, at: (Long) -> String): List<String> {
    if (!enabled) return listOf("Idle refresh alarm: off")
    val planned = when {
        plannedDueMs <= 0L -> "not armed"
        plannedDueMs < nowMs -> at(plannedDueMs) + " (already due)"
        else -> at(plannedDueMs)
    }
    return listOf("Idle refresh alarm: on", "Idle alarm planned for: $planned")
}
