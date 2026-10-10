package com.newsfeed.widget.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.newsfeed.widget.data.shouldRearmOnBroadcast
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Re-arms the refresh machinery after a reboot and after an app update (BUG-036): the periodic job
 * (UPDATE policy, saved interval, network constraint), the clock-tick alarm chain, the daily update
 * check, and a catch-up refresh if one is overdue.
 *
 * The periodic job and the tick are re-armed even when no widget id is reported yet: right after boot or
 * an update the system's list can be momentarily empty, and nothing else would bring them back. A user who
 * really has no widget is covered: the first tick stops its own chain when no widget is placed
 * (NewsFeedWidgetReceiver.ensureClockTick), and the worker's run is a cheap no-op. The daily update check
 * is still re-armed only when a widget exists, as before.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!shouldRearmOnBroadcast(intent.action)) return
        val pending = goAsync()
        MainScope().launch {
            try {
                // One step failing must not skip the others.
                try { WidgetWorker.rearm(context) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                runCatching { NewsFeedWidgetReceiver.scheduleClockTick(context) }
                val hasWidgets = try {
                    WidgetWorker.liveWidgetIds(context).isNotEmpty()
                } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
                if (hasWidgets) {
                    // UpdateCheckWorker used to silently never resume its daily check after a device
                    // reboot until a widget was removed and re-added.
                    runCatching { UpdateCheckWorker.schedule(context) }
                    runCatching { WidgetWorker.kickIfStale(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
