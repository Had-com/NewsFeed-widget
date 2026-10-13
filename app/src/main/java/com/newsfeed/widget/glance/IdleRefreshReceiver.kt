package com.newsfeed.widget.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the idle refresh alarm (BUG-046, [IdleRefresh]). Not exported: only our own PendingIntent (explicit
 * component) reaches it. All it does is re-arm the next alarm and enqueue one expedited refresh, both quick, so no
 * goAsync is needed. It never throws: a crash here would be a crash in the background for nothing.
 */
class IdleRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IdleRefresh.ACTION_IDLE_REFRESH) return
        try {
            IdleRefresh.onAlarm(context)
        } catch (_: Exception) {
        }
    }
}
