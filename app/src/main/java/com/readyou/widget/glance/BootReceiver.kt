package com.readyou.widget.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.readyou.widget.glance.WidgetWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MainScope().launch {
            try {
                val ids = GlanceAppWidgetManager(context)
                    .getGlanceIds(ReadYouWidget::class.java)
                if (ids.isNotEmpty()) {
                    ReadYouWidgetReceiver.scheduleClockTick(context)
                    WidgetWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
