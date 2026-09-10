package com.newsfeed.widget

import android.app.Application
import com.newsfeed.widget.data.CrashLogStore

class NewsFeedApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // runCatching: logging itself must never be the reason a crash fails to reach
            // the platform's own handler (which is what actually restarts the process).
            runCatching { CrashLogStore.record(applicationContext, throwable) }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
