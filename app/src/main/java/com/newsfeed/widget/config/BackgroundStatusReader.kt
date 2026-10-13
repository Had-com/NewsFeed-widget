package com.newsfeed.widget.config

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.newsfeed.widget.data.BackgroundStatus

/** Thin Android wrapper for the pure rules in data/BackgroundStatus.kt (BUG-048). Every read is guarded: unreadable = null. */
object BackgroundStatusReader {

    fun read(context: Context): BackgroundStatus {
        val app = context.applicationContext
        val exempt = runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(app.packageName)
        }.getOrNull()
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted }.getOrNull()
        } else null
        return BackgroundStatus(exempt, restricted, Build.MANUFACTURER.orEmpty())
    }

    /**
     * Opens the system list of battery-optimized apps (needs no special permission; the user picks NewsFeed there).
     * Falls back to the app's own details page; a Toast when neither can be opened.
     */
    fun openBatterySettings(context: Context) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        // No resolveActivity check: on Android 11+ package visibility can hide a system screen from it. Just try, then fall back.
        val ok = runCatching { context.startActivity(list) }.isSuccess ||
            runCatching { context.startActivity(details) }.isSuccess
        if (!ok) Toast.makeText(context, "Could not open the battery settings. Open Settings → Apps → NewsFeed → Battery.", Toast.LENGTH_LONG).show()
    }
}
