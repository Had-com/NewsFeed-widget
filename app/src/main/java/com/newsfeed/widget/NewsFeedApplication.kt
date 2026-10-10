package com.newsfeed.widget

import android.app.Application
import com.newsfeed.widget.data.CrashLogStore
import com.newsfeed.widget.glance.NewsFeedWidgetReceiver
import com.newsfeed.widget.glance.WidgetWorker

class NewsFeedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A periodic refresh job enqueued by an older build has no network constraint (BUG-034):
        // re-enqueue it once, so an installed widget is fixed without being removed and re-added.
        runCatching { WidgetWorker.migrateIfNeeded(this) }
        // Re-arm the clock tick if a widget is placed (BUG-036): a process start is a free chance to heal
        // an alarm chain that was lost (force-stop, an OS that dropped the alarm).
        runCatching { NewsFeedWidgetReceiver.ensureClockTick(this) }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // runCatching: logging itself must never be the reason a crash fails to reach
            // the platform's own handler (which is what actually restarts the process).
            runCatching { CrashLogStore.record(applicationContext, throwable) }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
