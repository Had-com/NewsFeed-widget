package com.newsfeed.widget.glance

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MainScope().launch {
            try {
                val glanceIds = GlanceAppWidgetManager(context).getGlanceIds(NewsFeedWidget::class.java)
                // Union with the system's own list so a Glance-side lookup miss can't skip re-arming.
                val systemIds = runCatching {
                    AppWidgetManager.getInstance(context)
                        .getAppWidgetIds(ComponentName(context, NewsFeedWidgetReceiver::class.java))
                }.getOrDefault(IntArray(0))
                if (glanceIds.isNotEmpty() || systemIds.isNotEmpty()) {
                    NewsFeedWidgetReceiver.scheduleClockTick(context)
                    // UpdateCheckWorker used to silently never resume its daily check after a
                    // device reboot until a widget was removed and re-added; reschedule both
                    // periodic jobs here.
                    WidgetWorker.ensureScheduled(context)
                    UpdateCheckWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
