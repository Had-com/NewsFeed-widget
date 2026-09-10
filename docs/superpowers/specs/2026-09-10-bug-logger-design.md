# Bug logger (Phase 1: crash detection + local logging) — design

## Context

NewsFeed has no crash-reporting or bug-collection infrastructure at all today. Every bug
found so far in this project came from either the developer manually testing on one
physical device, or a user directly reporting it in conversation. There is no way to know
whether a crash has ever happened on a real user's device, nor any list a user could check
to see what's already been reported and fixed.

## Decisions made during brainstorming

- **Detection scope, Phase 1: crashes only.** A custom `Thread.UncaughtExceptionHandler`
  catches uncaught exceptions in the widget's process. This deliberately does NOT attempt to
  detect silent failures (a feed not loading, a button not responding, a layout glitch) —
  those require hand-coded detectors per bug class, which is real, ongoing maintenance work
  with no natural stopping point. Crash detection is the well-understood, low-risk starting
  point every mainstream crash-reporting tool (Crashlytics, Sentry, Bugsnag) is built around.
- **Transport, Phase 1: local log + manual share only. No automatic network transport, no
  GitHub interaction, in this phase.** Sending crash data automatically from a user's device
  to GitHub requires a GitHub credential embedded in the shipped app. A write-capable token
  inside a distributed APK can be extracted by anyone and abused — and since this repo's CI
  auto-publishes the rolling `latest` release that the app's own self-update mechanism
  installs on real users' devices, a leaked repo-write token here is a materially worse risk
  than this project's prior local git-config token leak (which never left the developer's own
  machine). Phase 1 therefore only ever writes crash data to a local file and offers it to the
  OS share sheet (email, WhatsApp, Telegram, etc.) — there is no code path in this phase that
  makes an outbound network request with any credential at all.
- **Phase 2, explicitly queued for later (NOT part of this spec or its implementation plan):**
  a small serverless relay (e.g. a Cloudflare Worker) holding the real GitHub token as a
  server-side secret, never shipped to any device. The app would POST crash data to the
  relay's public URL (safe to embed — the relay does its own validation/rate-limiting, and
  holds no credential the app could leak), both immediately on a new crash and via a weekly
  `WorkManager` job matching `WidgetWorker`'s existing periodic-refresh pattern. The relay
  would create the actual GitHub issue server-side. This is flagged here as the natural next
  step, suggested for the following week's work, but no code for it is part of this plan.
- **"Solved" status: derived automatically from app version, no manual bookkeeping.** A
  logged crash's (exception type, message) signature is "solved" if its most recent
  occurrence was logged under a `versionCode` strictly older than the app's current
  `BuildConfig.VERSION_CODE`; it's "unsolved" if its most recent occurrence matches the
  current running version. This needs no new stored state (no "last update timestamp" to
  track) — it's a pure comparison between each crash record's own logged `versionCode` and
  the value already available via `BuildConfig.VERSION_CODE` at display time. It can produce
  a false "solved" for a crash that simply hasn't recurred yet despite not actually being
  fixed — accepted as a known limitation of the fully-automatic approach, in exchange for
  needing zero manual maintenance.

## Architecture

A new `NewsFeedApplication` (this project has no `Application` subclass today) installs the
crash handler once, as early as possible in the process lifecycle. The handler writes a
small JSON record to a local file via a new `CrashLogStore` object, then always re-throws to
the platform's original handler — Phase 1 only ever *observes* crashes, it never intercepts,
suppresses, or changes how the OS handles the actual crash/process restart. A new "BUG
REPORTS" section in `WidgetConfigActivity`'s settings screen reads and summarizes the stored
records (grouped by signature, with the derived Solved/Unsolved status) and offers a "Share
crash report" button that hands a plain-text summary to the OS share sheet.

## Components

### `NewsFeedApplication.kt` (new file, app root package)

```kotlin
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
```

Registered in `AndroidManifest.xml` by adding `android:name=".NewsFeedApplication"` to the
existing `<application ...>` tag (currently has no `android:name` at all — this is a pure
addition, not a change to any existing attribute).

### `data/CrashLogStore.kt` (new file)

Plain-JSON file storage (`org.json`, already part of the Android platform — no new
dependency, consistent with how the Telegram feature avoided adding an HTML/DOM library for
a similarly small, well-bounded parsing need). Stores at most the 50 most recent records,
oldest dropped first, in a file under the app's private files directory (same directory
`ConfigBackup.kt`/`WidgetConfigStore` already use, via `context.filesDir`).

```kotlin
package com.newsfeed.widget.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CrashLogStore {
    private const val FILE_NAME = "crash_log.json"
    private const val MAX_ENTRIES = 50
    private const val MAX_STACK_TRACE_CHARS = 4000

    data class CrashRecord(
        val timestamp: Long,
        val versionCode: Int,
        val exceptionType: String,
        val message: String,
        val stackTrace: String,
    )

    data class BugSummary(
        val exceptionType: String,
        val message: String,
        val lastSeenAt: Long,
        val lastSeenVersionCode: Int,
        val occurrenceCount: Int,
        val isSolved: Boolean,
    )

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun record(context: Context, throwable: Throwable) {
        val record = CrashRecord(
            timestamp = System.currentTimeMillis(),
            versionCode = com.newsfeed.widget.BuildConfig.VERSION_CODE,
            exceptionType = throwable.javaClass.name,
            message = (throwable.message ?: "").take(300),
            stackTrace = Log.getStackTraceString(throwable).take(MAX_STACK_TRACE_CHARS),
        )
        val existing = readAll(context)
        val updated = (existing + record).takeLast(MAX_ENTRIES)
        writeAll(context, updated)
    }

    fun readAll(context: Context): List<CrashRecord> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CrashRecord(
                    timestamp = o.getLong("timestamp"),
                    versionCode = o.getInt("versionCode"),
                    exceptionType = o.getString("exceptionType"),
                    message = o.getString("message"),
                    stackTrace = o.getString("stackTrace"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(context: Context, records: List<CrashRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("timestamp", r.timestamp)
                put("versionCode", r.versionCode)
                put("exceptionType", r.exceptionType)
                put("message", r.message)
                put("stackTrace", r.stackTrace)
            })
        }
        file(context).writeText(arr.toString())
    }

    // Groups raw records by (exceptionType, message) - repeated identical crashes are the
    // "same bug," not N separate ones - and derives Solved/Unsolved from whether the most
    // recent occurrence of that signature was logged under an older versionCode than the
    // app's current one (see design doc's "Solved status" decision for why this needs no
    // separately-tracked "last update" timestamp).
    fun summarize(records: List<CrashRecord>, currentVersionCode: Int): List<BugSummary> {
        return records
            .groupBy { it.exceptionType to it.message }
            .map { (key, group) ->
                val latest = group.maxBy { it.timestamp }
                BugSummary(
                    exceptionType = key.first,
                    message = key.second,
                    lastSeenAt = latest.timestamp,
                    lastSeenVersionCode = latest.versionCode,
                    occurrenceCount = group.size,
                    isSolved = latest.versionCode < currentVersionCode,
                )
            }
            .sortedByDescending { it.lastSeenAt }
    }
}
```

### `config/WidgetConfigActivity.kt` — new "BUG REPORTS" settings section

Added as a new `item { }` block in the existing settings `LazyColumn`, following the exact
visual pattern of the existing "APP UPDATE" section (label style, spacing, `TextButton`).
Reads `CrashLogStore.readAll(this@WidgetConfigActivity)` and `CrashLogStore.summarize(...)`
once per composition (no live-updating needed — a new crash only ever happens when the
process is about to die, so there's nothing to observe while this screen is open). This file
has no existing `LocalContext.current`/`context` val in scope at this point — confirmed by
checking the file directly — so the Activity instance itself (`this@WidgetConfigActivity`,
already used the same way elsewhere in this file for permission checks) is the Context
passed in, not a `context` variable that doesn't exist here.

```kotlin
// ── Bug reports ──
item {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("BUG REPORTS", fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
        Spacer(Modifier.height(8.dp))
        val crashRecords = remember { CrashLogStore.readAll(this@WidgetConfigActivity) }
        val bugSummaries = remember(crashRecords) {
            CrashLogStore.summarize(crashRecords, BuildConfig.VERSION_CODE)
        }
        if (bugSummaries.isEmpty()) {
            Text("No crashes detected on this device.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            bugSummaries.forEach { bug ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(bug.exceptionType.substringAfterLast('.'),
                            style = MaterialTheme.typography.bodyMedium)
                        Text(bug.message.ifBlank { "(no message)" }, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${bug.occurrenceCount}× · last seen build ${bug.lastSeenVersionCode} · " +
                                java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(bug.lastSeenAt)),
                            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (bug.isSolved) "Solved" else "Unsolved",
                        fontSize = 11.sp,
                        color = if (bug.isSolved) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val body = crashRecords.joinToString("\n\n---\n\n") { r ->
                    "Build ${r.versionCode} · ${java.util.Date(r.timestamp)}\n" +
                        "${r.exceptionType}: ${r.message}\n${r.stackTrace}"
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "NewsFeed crash report")
                    putExtra(android.content.Intent.EXTRA_TEXT, body)
                }
                startActivity(android.content.Intent.createChooser(intent, "Share crash report"))
            }) { Text("Share crash report") }
        }
    }
}
```

## Error handling

- `CrashLogStore.record()`'s own file I/O is not wrapped in `runCatching` internally, but its
  only caller (the exception handler in `NewsFeedApplication`) wraps the call — so a failure
  to write the log (e.g. disk full) is swallowed there and never prevents the real crash from
  reaching the platform's own handler.
- `readAll()` returns `emptyList()` on any parse failure (corrupt file, unexpected format)
  rather than throwing — a corrupted log file degrades to "no crashes shown," not a second
  crash on top of the first.
- The exception handler always re-throws to `defaultHandler` after logging, preserving the
  exact crash/restart behavior the OS already provides today — this feature is purely
  additive observation, not a change to how crashes are handled.

## Out of scope (this phase)

- Any network transport, GitHub interaction, or embedded credential of any kind (see
  "Transport" decision above) — Phase 2, not part of this plan.
- Detecting anything other than uncaught exceptions (silent failures, bad-state patterns) —
  explicitly deferred per the "Detection scope" decision.
- ANR (Application Not Responding) detection — a different, more involved mechanism
  (`ApplicationExitInfo` on API 30+, or a watchdog thread) than a simple exception handler;
  not requested and not included here.
- A "last update timestamp" or any new stored state beyond the crash log file itself — the
  Solved/Unsolved derivation deliberately needs none (see "Solved status" decision).

## Testing / verification

No new unit-testable pure logic beyond `CrashLogStore.summarize()` (a pure function over
in-memory data — a good candidate for the JUnit infrastructure the Telegram feature already
added to this project) and the JSON serialize/deserialize round-trip in `record()`/`readAll()`.
The exception-handler wiring itself and the Settings UI are verified on-device only, matching
this project's established convention: deliberately trigger a test crash (e.g. a debug-only
button, or `adb shell am crash com.newsfeed.widget`), confirm the app actually restarts
normally (re-throw path works), reopen Settings, confirm the crash appears under "BUG
REPORTS" as Unsolved, confirm "Share crash report" opens the OS share sheet with the crash
text, then (harder to test without a real second build) confirm a crash logged under an
older `versionCode` shows as Solved once a newer build is installed.
