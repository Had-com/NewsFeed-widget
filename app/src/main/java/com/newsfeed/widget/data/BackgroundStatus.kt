package com.newsfeed.widget.data

/**
 * Pure rules for "may Android refresh NewsFeed while the screen is off?" (BUG-048), no Android types, unit tested.
 *
 * Why it exists: on a Samsung phone (One UI) the system froze the app about 50 s after the screen went off and held
 * back every alarm (the idle refresh alarm of BUG-046 and the clock tick) until the phone woke up. An app cannot lift
 * that itself: the user has to allow background activity (Android's battery optimization exemption, Samsung's
 * "Never sleeping apps" list, the app's battery setting "Unrestricted"). So the app tells the user, opens the right
 * system screen and records the state in the problem report. The Android side is in config/BackgroundStatusReader.kt.
 */

/** [ignoringBatteryOptimizations] / [backgroundRestricted] are null when they could not be read (or do not exist on this Android). */
data class BackgroundStatus(
    val ignoringBatteryOptimizations: Boolean?,
    val backgroundRestricted: Boolean?,
    val manufacturer: String,
)

enum class BackgroundAdvice { OK, NEEDS_EXEMPTION, RESTRICTED, UNKNOWN }

/**
 * RESTRICTED (`ActivityManager.isBackgroundRestricted`) comes first because it stops everything, exempt or not.
 * Then not exempt = NEEDS_EXEMPTION; exempt (and not restricted, or restriction unknown on an old Android) = OK.
 * Anything unreadable is UNKNOWN: no warning is shown for a state we could not read.
 */
fun backgroundAdvice(status: BackgroundStatus): BackgroundAdvice = when {
    status.backgroundRestricted == true -> BackgroundAdvice.RESTRICTED
    status.ignoringBatteryOptimizations == false -> BackgroundAdvice.NEEDS_EXEMPTION
    status.ignoringBatteryOptimizations == true -> BackgroundAdvice.OK
    else -> BackgroundAdvice.UNKNOWN
}

private const val SAMSUNG_PATH =
    "On Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add NewsFeed. " +
        "Also set Settings → Apps → NewsFeed → Battery → Unrestricted."

/** The line shown under the "Refresh while the phone is idle" checkbox; empty for UNKNOWN. */
fun backgroundAdviceText(advice: BackgroundAdvice, manufacturer: String): String = when (advice) {
    BackgroundAdvice.OK -> "Background refresh is allowed for NewsFeed."
    BackgroundAdvice.NEEDS_EXEMPTION -> {
        val base = "Android may pause NewsFeed when the screen is off, so the widget can go stale overnight. " +
            "Allow background activity for NewsFeed."
        if (manufacturer.trim().equals("samsung", ignoreCase = true)) "$base $SAMSUNG_PATH" else base
    }
    BackgroundAdvice.RESTRICTED -> "NewsFeed is restricted in the background. Open its battery setting and choose Unrestricted."
    BackgroundAdvice.UNKNOWN -> ""
}

fun yesNoUnknown(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "unknown"
}

/** The problem report's lines about background restrictions (inside [Refresh status]). */
fun backgroundReportLines(status: BackgroundStatus): List<String> = listOf(
    "Battery optimization exempt: ${yesNoUnknown(status.ignoringBatteryOptimizations)}",
    "Background restricted: ${yesNoUnknown(status.backgroundRestricted)}",
    "Manufacturer: ${status.manufacturer.trim().ifEmpty { "unknown" }}",
    "Background advice: ${backgroundAdvice(status).name}",
)
