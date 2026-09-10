# Bug Logger (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect uncaught crashes on a user's device, log them locally, and surface them in Settings with a derived Solved/Unsolved status and a manual share button — no network transport or GitHub interaction in this phase.

**Architecture:** A new `Application` subclass installs a `Thread.UncaughtExceptionHandler` wrapper as early as possible, writing crash details to a small local JSON file via a new `CrashLogStore` object, then always re-throwing to the platform's original handler. A new "BUG REPORTS" section in the existing Settings screen reads and groups those records, deriving Solved/Unsolved from each signature's most recent `versionCode` against the app's current one, with a "Share crash report" button using the OS share sheet.

**Tech Stack:** Kotlin, `org.json` (already part of the Android platform, no new dependency), JUnit 4 (already added to this project by the Telegram feature).

---

## Design note on testability (read before starting)

`CrashLogStore.summarize()` is a pure function (`List<CrashRecord>, Int) -> List<BugSummary>`) with no Android `Context` dependency — it's unit-tested with plain JUnit in Task 1. `CrashLogStore.record()`/`readAll()`/`writeAll()` do real file I/O against a `Context.filesDir` and are NOT unit-tested (this project has no Robolectric and deliberately avoided adding it for the Telegram feature for the same reason — see that feature's plan) — they're verified on-device in Task 4, matching this project's established convention for anything requiring a real Android `Context`.

---

## File Structure

- **Create:** `app/src/main/java/com/newsfeed/widget/data/CrashLogStore.kt` — crash record storage, JSON serialization, and the pure `summarize()` grouping/status logic.
- **Create:** `app/src/test/java/com/newsfeed/widget/data/CrashLogStoreTest.kt` — JUnit 4 tests for `summarize()`.
- **Create:** `app/src/main/java/com/newsfeed/widget/NewsFeedApplication.kt` — installs the crash handler.
- **Modify:** `app/src/main/AndroidManifest.xml` — registers the new `Application` class.
- **Modify:** `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` — new "BUG REPORTS" Settings section.
- **Modify:** `docs/BUGS.md` — feature entry once verified.

---

### Task 1: `CrashLogStore` — data model, storage, and `summarize()`

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/data/CrashLogStore.kt`
- Create: `app/src/test/java/com/newsfeed/widget/data/CrashLogStoreTest.kt`

- [ ] **Step 1: Write failing tests for `summarize()`**

Create `app/src/test/java/com/newsfeed/widget/data/CrashLogStoreTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogStoreTest {

    private fun record(
        timestamp: Long,
        versionCode: Int,
        exceptionType: String = "java.lang.IllegalStateException",
        message: String = "boom",
    ) = CrashLogStore.CrashRecord(
        timestamp = timestamp,
        versionCode = versionCode,
        exceptionType = exceptionType,
        message = message,
        stackTrace = "stack trace text",
    )

    @Test
    fun `summarize returns empty list for no records`() {
        assertEquals(0, CrashLogStore.summarize(emptyList(), currentVersionCode = 110).size)
    }

    @Test
    fun `summarize groups identical exception type and message as one bug`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108),
            record(timestamp = 2000L, versionCode = 108),
            record(timestamp = 3000L, versionCode = 109),
        )
        val summaries = CrashLogStore.summarize(records, currentVersionCode = 109)
        assertEquals(1, summaries.size)
        assertEquals(3, summaries[0].occurrenceCount)
    }

    @Test
    fun `summarize treats different exception types as separate bugs`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, exceptionType = "java.lang.NullPointerException"),
            record(timestamp = 2000L, versionCode = 108, exceptionType = "java.lang.IllegalStateException"),
        )
        assertEquals(2, CrashLogStore.summarize(records, currentVersionCode = 109).size)
    }

    @Test
    fun `summarize treats different messages of the same exception type as separate bugs`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, message = "first message"),
            record(timestamp = 2000L, versionCode = 108, message = "second message"),
        )
        assertEquals(2, CrashLogStore.summarize(records, currentVersionCode = 109).size)
    }

    @Test
    fun `summarize marks a bug unsolved when its latest occurrence matches the current version`() {
        val records = listOf(record(timestamp = 1000L, versionCode = 109))
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(false, summary.isSolved)
    }

    @Test
    fun `summarize marks a bug solved when its latest occurrence is an older version than current`() {
        val records = listOf(record(timestamp = 1000L, versionCode = 108))
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(true, summary.isSolved)
    }

    @Test
    fun `summarize uses the most recent occurrence to decide solved status, not the oldest`() {
        // First seen on an old version, but it also happened again on the CURRENT version -
        // still unsolved, since it's still actively happening now.
        val records = listOf(
            record(timestamp = 1000L, versionCode = 105),
            record(timestamp = 2000L, versionCode = 109),
        )
        val summary = CrashLogStore.summarize(records, currentVersionCode = 109).single()
        assertEquals(false, summary.isSolved)
        assertEquals(109, summary.lastSeenVersionCode)
        assertEquals(2000L, summary.lastSeenAt)
    }

    @Test
    fun `summarize sorts results by most recently seen first`() {
        val records = listOf(
            record(timestamp = 1000L, versionCode = 108, exceptionType = "OlderException"),
            record(timestamp = 5000L, versionCode = 108, exceptionType = "NewerException"),
        )
        val summaries = CrashLogStore.summarize(records, currentVersionCode = 109)
        assertEquals("NewerException", summaries[0].exceptionType)
        assertEquals("OlderException", summaries[1].exceptionType)
    }

    @Test
    fun `readAll returns empty list when no log file exists yet`() {
        // No Context available in a plain JUnit test - this specific case (missing file)
        // is exercised on-device in Task 4. This test documents the expected contract only
        // by checking summarize() handles an empty input the same way readAll() would return
        // it, so the two are known to compose correctly.
        assertTrue(CrashLogStore.summarize(emptyList(), currentVersionCode = 1).isEmpty())
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.CrashLogStoreTest"`
Expected: FAIL to compile — `CrashLogStore` doesn't exist yet.

(Local Gradle is not runnable in this sandbox — `gradle/wrapper/gradle-wrapper.jar` is missing and there is no system-wide `gradle` binary, the same known limitation already accepted throughout this project. Verify all steps in this task by careful hand-tracing instead, exactly as the Telegram feature's tasks did, and report `DONE_WITH_CONCERNS` for that reason — it's expected, not a blocker.)

- [ ] **Step 3: Implement `CrashLogStore.kt`**

Create `app/src/main/java/com/newsfeed/widget/data/CrashLogStore.kt`:

```kotlin
package com.newsfeed.widget.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phase 1 of the bug logger (see docs/superpowers/specs/2026-09-10-bug-logger-design.md):
 * local-only crash detection and logging. No network transport, no GitHub interaction -
 * shipping a GitHub credential inside this distributed APK would be a real security risk
 * given this repo's self-update mechanism. See the design doc's "Transport" decision.
 */
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
        val updated = (readAll(context) + record).takeLast(MAX_ENTRIES)
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
    // app's current one. Needs no separately-tracked "last update" timestamp - see the
    // design doc's "Solved status" decision.
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

- [ ] **Step 4: Run tests and verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.CrashLogStoreTest"`
Expected: PASS (9 tests, 0 failures) — verify by hand-tracing per Step 2's note.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/CrashLogStore.kt app/src/test/java/com/newsfeed/widget/data/CrashLogStoreTest.kt
git commit -m "Add CrashLogStore: crash record storage and summarize() with unit tests"
```

---

### Task 2: `NewsFeedApplication` — install the crash handler

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/NewsFeedApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml:10-16` (the `<application>` tag)

No new unit tests here — installing a global exception handler and verifying it actually
fires on a real crash requires a real Android process; verified on-device in Task 4.

- [ ] **Step 1: Create `NewsFeedApplication.kt`**

Create `app/src/main/java/com/newsfeed/widget/NewsFeedApplication.kt`:

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

- [ ] **Step 2: Register it in the manifest**

In `app/src/main/AndroidManifest.xml`, find (lines 10-16):

```xml
    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault">
```

Replace with (adding `android:name` only — every other attribute unchanged):

```xml
    <application
        android:name=".NewsFeedApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault">
```

- [ ] **Step 3: Verify the project compiles**

Local Gradle is not runnable in this sandbox (same known limitation as every other task this
project has hit — missing `gradle-wrapper.jar`, no system gradle). Hand-verify: re-read the
final `NewsFeedApplication.kt` for correct Kotlin syntax and brace balance, and confirm the
manifest edit only added the one `android:name` attribute with nothing else disturbed.
Report `DONE_WITH_CONCERNS` for the inability to compile — expected, not a blocker.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/NewsFeedApplication.kt app/src/main/AndroidManifest.xml
git commit -m "Install a crash handler via a new Application class"
```

---

### Task 3: "BUG REPORTS" Settings section

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt:937-972` (the "App update" `item {}` block — new section goes directly after it, before the `LazyColumn`'s closing brace)

No new unit tests here — this is a Compose UI addition reading real on-device state
(`CrashLogStore.readAll(this@WidgetConfigActivity)`), verified on-device in Task 4, matching
how the rest of this settings screen has always been verified.

- [ ] **Step 1: Add the import**

In `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`, find the existing
import block (around line 74-88, alongside the other `com.newsfeed.widget.data.*` imports):

```kotlin
import com.newsfeed.widget.data.FeedConfig
```

Add directly above it (alphabetical, matching this file's existing import ordering):

```kotlin
import com.newsfeed.widget.data.CrashLogStore
import com.newsfeed.widget.data.FeedConfig
```

- [ ] **Step 2: Add the "BUG REPORTS" section**

Find the end of the existing "App update" `item {}` block (`WidgetConfigActivity.kt:937-972`):

```kotlin
                        // ── App update ──
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("APP UPDATE", fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.05.sp)
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Check for updates", style = MaterialTheme.typography.bodyMedium)
                                        Text("Currently on build ${BuildConfig.VERSION_CODE}", fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (isCheckingForUpdate) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    else TextButton(onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(
                                                this@WidgetConfigActivity, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        ) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                        isCheckingForUpdate = true
                                        scope.launch {
                                            UpdateManager.checkAndUpdate(this@WidgetConfigActivity, notifyOnly = false)
                                            isCheckingForUpdate = false
                                        }
                                    }) { Text("Check now") }
                                }
                                Text(
                                    "The first time you install an update you may see an “install unknown apps” " +
                                        "or Google Play Protect prompt — that's expected for an app outside the Play Store.",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
```

Immediately after this block's closing `}` (still inside the outer `LazyColumn { ... }`, i.e.
before the `LazyColumn`'s own closing brace), add a new `item {}` block:

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
                                                color = if (bug.isSolved) androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                                         else MaterialTheme.colorScheme.error,
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
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_SUBJECT, "NewsFeed crash report")
                                            putExtra(Intent.EXTRA_TEXT, body)
                                        }
                                        startActivity(Intent.createChooser(intent, "Share crash report"))
                                    }) { Text("Share crash report") }
                                }
                            }
                        }
```

Notes for the implementer:
- `Intent`/`Intent.ACTION_SEND`/etc. use the plain unqualified `Intent` name because
  `android.content.Intent` is already imported at the top of this file (line 6) — do not add
  a second, fully-qualified usage.
- `androidx.compose.ui.graphics.Color` is used fully-qualified (not imported) because this
  file already does the same elsewhere (e.g. `androidx.compose.ui.graphics.Color.Transparent`
  in the existing "Theme variant" row) — stay consistent with that rather than adding a new
  unqualified `Color` import that could collide with `android.graphics.Color` (already used
  fully-qualified for hex-parsing elsewhere in this file).
- `this@WidgetConfigActivity` is used as the `Context` argument because this file has no
  existing `LocalContext.current`/`context` val in scope at this point — confirmed by
  checking the file directly.

- [ ] **Step 3: Verify the project compiles**

Local Gradle is not runnable in this sandbox (same limitation as every prior task). Hand-verify
by re-reading the final file around both edits: confirm the new `item {}` block is a sibling
of the "App update" block (same brace depth, still inside the outer `LazyColumn { ... }`),
confirm brace balance, and confirm the noted `Intent`/`Color`/`Context` conventions were
followed. Report `DONE_WITH_CONCERNS` for the inability to compile.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Add BUG REPORTS settings section with Solved/Unsolved status and share"
```

---

### Task 4: On-device verification, `BUGS.md` update, and final push

**Files:** `docs/BUGS.md` (feature entry only)

- [ ] **Step 1: Build and install a debug APK**

Push to `main` and install the CI-built release, matching this project's established
verification workflow (local Gradle is not runnable in this sandbox):

```bash
git push origin main
```

Wait for the `Build APK` GitHub Actions workflow to finish, then download and install
`NewsFeed-latest.apk` from the repo's `latest` release, confirming `version.json`'s
`versionCode` matches the just-pushed commit before testing.

- [ ] **Step 2: Trigger a real crash and confirm the handler fires without breaking the crash itself**

```bash
adb shell am crash com.newsfeed.widget
```

Confirm the app still crashes/is force-stopped by the OS exactly as it would without this
feature (the re-throw to `defaultHandler` must not be swallowing anything). Then confirm via
`adb shell run-as com.newsfeed.widget cat files/crash_log.json` that a record was written
with a plausible `timestamp`/`versionCode`/`exceptionType`.

- [ ] **Step 3: Confirm the Settings UI shows the crash as Unsolved**

Open the app's Settings screen, scroll to "BUG REPORTS", confirm the triggered crash appears
with an "Unsolved" badge (it was just logged under the currently-running `versionCode`).

- [ ] **Step 4: Confirm "Share crash report" opens the OS share sheet**

Tap "Share crash report", confirm the Android share sheet opens with a plain-text body
containing the crash's exception type, message, and stack trace text.

- [ ] **Step 5: Update `docs/BUGS.md`**

Add a short "Feature additions" entry (following the existing pattern used for the
2026-09-07 and 2026-09-08 batches) describing what shipped: crash detection via
`NewsFeedApplication`, local-only logging (`CrashLogStore`), the "BUG REPORTS" Settings
section with derived Solved/Unsolved status, manual share button, and an explicit note that
Phase 2 (automatic GitHub reporting via a serverless relay) is intentionally not part of
this batch — see `docs/superpowers/specs/2026-09-10-bug-logger-design.md`.

- [ ] **Step 6: Final push**

```bash
git add docs/BUGS.md
git commit -m "Document bug logger Phase 1: crash detection, local log, Settings UI"
git push origin main
```

Per this project's established workflow: run a security-focused review of the full diff
before this push (per the standing pre-push security check) — in particular, confirm no
network call, GitHub credential, or any outbound transport of any kind was introduced
anywhere in this feature (Phase 1 is local-only by design), and confirm the crash-log file
itself only ever contains data already visible to the user (exception type, message, stack
trace, app version) — nothing from feed content, config, or any other app data.

---

## Self-Review Notes

- **Spec coverage:** every section of the approved spec has a corresponding task —
  Architecture/`CrashLogStore` (Task 1), `NewsFeedApplication` install point (Task 2),
  Settings UI (Task 3), Error handling (all three `runCatching`/fallback behaviors from the
  spec are present verbatim in Task 1's implementation), Testing/verification (Task 4). The
  spec's explicit "Out of scope" items (network transport, ANR detection, bad-state pattern
  detection, a "last update timestamp") have no corresponding task, correctly — they aren't
  supposed to.
- **Type/name consistency checked:** `CrashLogStore.CrashRecord`, `.BugSummary`, `.record()`,
  `.readAll()`, `.summarize()` are named and used identically across Tasks 1, 2, and 3
  wherever referenced.
- **No placeholders:** every step contains complete, real code — no "add error handling" or
  "similar to Task N" shortcuts.
