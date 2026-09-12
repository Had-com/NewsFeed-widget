# Release notes on self-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Before installing a self-update (manual "Check for updates" button, or tapping the "Update available" notification), show the user a short, plain-language summary of what changed since they last updated, with "Update Now" / "Later" — a genuinely new confirmation step, since today the app goes straight from "a newer build exists" to downloading with no confirmation at all.

**Architecture:** `docs/RELEASE_NOTES.md` in the repo holds hand-written, plain-Markdown entries (`## Note <n>` + bullets), fetched raw from GitHub the same way `TelegramFeedParser` scrapes an ordinary page. A tiny new DataStore (`ReleaseNotesStore`) tracks the highest note id the user has been shown, app-wide (not per-widget). `UpdateManager.checkAndUpdate()` splits into `checkForUpdate()` (pure check, no side effects) and `proceedWithUpdate()` (the existing download-and-install body) so a confirmation step can sit between them. `UpdateRelayActivity` (today an invisible relay) becomes a real, minimal Compose screen so the notification-tap path can show the same confirmation UI as the in-app manual button, via one shared composable.

**Tech Stack:** Kotlin, Jetpack Compose (`ComponentActivity`/`setContent`), AndroidX DataStore Preferences, OkHttp, plain JUnit (no Robolectric).

**Design reference:** `docs/superpowers/specs/2026-09-11-release-notes-design.md` (commit `bc0231f`) — read it first if anything below is ambiguous; this plan implements it task-by-task and does not repeat its rationale in full.

**A content decision this plan makes, not fully resolved by the spec:** `docs/RELEASE_NOTES.md` needs real starting content to exist as a parseable file. Rather than retroactively writing marketing copy for every already-shipped-and-forgotten past feature (Telegram feeds, Custom theme, Bug Reports, the unread grace period, etc.), Task 2 seeds it with exactly one entry, `## Note 1`, describing the release-notes feature itself — the honest, simplest choice, and consistent with the spec's own framing ("a short, plain-language entry added whenever something worth telling the user about ships," starting now). Every existing user will see this one note the next time they check for an update after this ships; that's expected and fine for a first note.

---

### Task 1: `ReleaseNotesStore` — app-wide "last seen" tracking

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/data/ReleaseNotesStore.kt`

No unit test for this file — it's a thin DataStore wrapper with no pure logic to test in isolation, matching the existing sibling file `app/src/main/java/com/newsfeed/widget/data/ConfigBackup.kt` (also untested for the same reason). Its correctness is verified on-device in Task 8.

- [ ] **Step 1: Create the file**

```kotlin
package com.newsfeed.widget.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.releaseNotesDataStore by preferencesDataStore(name = "release_notes")
private val LAST_SEEN_ID = intPreferencesKey("last_seen_release_note_id")

/**
 * Tracks which release notes (docs/RELEASE_NOTES.md) the user has already been shown, in its
 * own small DataStore file — mirroring ConfigBackup.kt's pattern of a small dedicated store
 * rather than piggybacking on per-widget WidgetConfig. lastSeenId is a single, app-wide
 * value, not tied to any one widget instance: a device can have multiple placed widgets, but
 * there's only one "has this device's user seen the notes" answer.
 */
object ReleaseNotesStore {
    suspend fun lastSeenId(context: Context): Int =
        context.releaseNotesDataStore.data.first()[LAST_SEEN_ID] ?: 0

    suspend fun markSeen(context: Context, upToId: Int) {
        context.releaseNotesDataStore.edit { prefs -> prefs[LAST_SEEN_ID] = upToId }
    }
}
```

- [ ] **Step 2: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

(`gradlew.bat` alone is known-broken in this environment — missing wrapper jar — use the cached distribution path above directly. This project has a single `:app` module and no Gradle product flavors, so `compileDebugKotlin` is the only compile task needed.)

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/ReleaseNotesStore.kt
git commit -m "Add ReleaseNotesStore: tracks the last release note shown to the user"
```

Use a PLAIN `git commit` — never `git commit --amend`.

---

### Task 2: Seed `docs/RELEASE_NOTES.md`

**Files:**
- Create: `docs/RELEASE_NOTES.md`

- [ ] **Step 1: Create the file with exactly this content**

```markdown
# Release notes

Plain-language notes shown to users right before they install an update — see
`docs/superpowers/specs/2026-09-11-release-notes-design.md` for the format and mechanism.
Oldest note first; append new entries at the end, never renumber or edit an old one.

## Note 1
- You'll now see what's new right before installing an update, with a short summary of what
  changed and why it's worth updating.
```

- [ ] **Step 2: Commit**

```bash
git add docs/RELEASE_NOTES.md
git commit -m "Seed docs/RELEASE_NOTES.md with the release-notes feature's own first entry"
```

---

### Task 3: `ReleaseNotesFetcher` — parsing (TDD) + network fetch

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/update/ReleaseNotesFetcher.kt`
- Test: `app/src/test/java/com/newsfeed/widget/update/ReleaseNotesFetcherTest.kt`

**Context for the implementer:** `parseNotes()` is a plain, Android-framework-free function over a `String` — deliberately kept separate from the network fetch so it's unit-testable without hitting the network, matching this project's established pattern (`TelegramFeedParser`, `WidgetThemes.parseHexColor`). Only `parseNotes()` gets unit tests; the actual network call (`fetchUnseenNotes`) is verified on-device in Task 8, same as every other network code in this app.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/newsfeed/widget/update/ReleaseNotesFetcherTest.kt`:

```kotlin
package com.newsfeed.widget.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesFetcherTest {

    @Test
    fun `parses two notes each with their own bullets`() {
        val markdown = """
            # Release notes

            ## Note 1
            - Add public Telegram channels as a feed source.

            ## Note 2
            - New "Custom" theme: pick your own font and background colors.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(2, notes.size)
        assertEquals(1, notes[0].id)
        assertEquals(listOf("Add public Telegram channels as a feed source."), notes[0].bullets)
        assertEquals(2, notes[1].id)
        assertEquals(
            listOf("New \"Custom\" theme: pick your own font and background colors."),
            notes[1].bullets,
        )
    }

    @Test
    fun `a note with multiple bullet lines keeps them all in order`() {
        val markdown = """
            ## Note 1
            - First bullet.
            - Second bullet.
            - Third bullet.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(listOf("First bullet.", "Second bullet.", "Third bullet."), notes[0].bullets)
    }

    @Test
    fun `empty content produces no notes`() {
        assertEquals(emptyList<ReleaseNote>(), ReleaseNotesFetcher.parseNotes(""))
    }

    @Test
    fun `a heading with no bullet lines under it is skipped`() {
        val markdown = """
            ## Note 1

            ## Note 2
            - Only note 2 has real content.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(2, notes[0].id)
    }

    @Test
    fun `text before the first heading is ignored`() {
        val markdown = """
            # Release notes

            Some intro text that isn't a bullet under any note.

            ## Note 1
            - Real content.
        """.trimIndent()

        val notes = ReleaseNotesFetcher.parseNotes(markdown)

        assertEquals(1, notes.size)
        assertEquals(listOf("Real content."), notes[0].bullets)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail (compile error — `ReleaseNotesFetcher`/`ReleaseNote` don't exist yet)**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon testDebugUnitTest --tests "com.newsfeed.widget.update.ReleaseNotesFetcherTest"
```

Expected: FAIL — compile error, `ReleaseNotesFetcher` is unresolved. (There is also a PRE-EXISTING, unrelated compile error in `app/src/test/java/com/newsfeed/widget/glance/WidgetThemesTest.kt` — `Unresolved reference 'toArgb'` — that blocks the whole test-compile task since Kotlin compiles all test sources together. If you hit it, you may temporarily move that one file out of the source tree, run your own tests, then move it back — verify with `git status` that it shows no diff afterward before finishing.)

- [ ] **Step 3: Create `ReleaseNotesFetcher.kt`**

```kotlin
package com.newsfeed.widget.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ReleaseNote(val id: Int, val bullets: List<String>)

/**
 * Fetches docs/RELEASE_NOTES.md's raw content straight from GitHub (no CI/build-pipeline
 * change needed — it's just another file in the repo, fetched the same way TelegramFeedParser
 * scrapes an ordinary web page) and parses out plain-language entries.
 */
object ReleaseNotesFetcher {
    private const val RAW_URL =
        "https://raw.githubusercontent.com/Had-com/NewsFeed-widget/main/docs/RELEASE_NOTES.md"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val NOTE_HEADING_REGEX = Regex("""^##\s*Note\s+(\d+)\s*$""")
    private val BULLET_REGEX = Regex("""^-\s+(.+)$""")

    /**
     * Splits raw Markdown into one ReleaseNote per "## Note <n>" heading, each followed by one
     * or more "- " bullet lines, ending at the next heading or end of file. A heading with a
     * non-numeric id, or one with no bullet lines under it, is skipped individually rather
     * than aborting the whole parse — one malformed entry shouldn't hide every other
     * legitimate one.
     */
    internal fun parseNotes(markdown: String): List<ReleaseNote> {
        val lines = markdown.lines()
        val notes = mutableListOf<ReleaseNote>()
        var currentId: Int? = null
        var currentBullets = mutableListOf<String>()

        fun flush() {
            val id = currentId
            if (id != null && currentBullets.isNotEmpty()) {
                notes += ReleaseNote(id, currentBullets.toList())
            }
        }

        for (line in lines) {
            val headingMatch = NOTE_HEADING_REGEX.find(line.trim())
            if (headingMatch != null) {
                flush()
                currentId = headingMatch.groupValues[1].toIntOrNull()
                currentBullets = mutableListOf()
                continue
            }
            val bulletMatch = BULLET_REGEX.find(line)
            if (bulletMatch != null && currentId != null) {
                currentBullets += bulletMatch.groupValues[1].trim()
            }
        }
        flush()
        return notes
    }

    /**
     * Returns every note with id > [sinceId], oldest first. Empty list (not null) on any
     * fetch/parse failure, or when there's genuinely nothing new — both look identical to the
     * caller, which is correct: either way, there's nothing to show. A failed fetch must never
     * block the update flow (matches this app's existing tolerant-degradation convention).
     */
    suspend fun fetchUnseenNotes(sinceId: Int): List<ReleaseNote> = withContext(Dispatchers.IO) {
        val markdown = try {
            val request = Request.Builder().url(RAW_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()
        parseNotes(markdown).filter { it.id > sinceId }.sortedBy { it.id }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Same command as Step 2. Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/update/ReleaseNotesFetcher.kt app/src/test/java/com/newsfeed/widget/update/ReleaseNotesFetcherTest.kt
git commit -m "Add ReleaseNotesFetcher: parse docs/RELEASE_NOTES.md into unseen notes"
```

---

### Task 4: Split `UpdateManager.checkAndUpdate` into `checkForUpdate` / `proceedWithUpdate` / `checkForUpdateAndNotify`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/update/UpdateManager.kt`
- Modify: `app/src/main/java/com/newsfeed/widget/update/UpdateCheckWorker.kt`

**Context for the implementer:** today, `UpdateManager.checkAndUpdate(context, notifyOnly: Boolean)` is one function used by 3 call sites: the daily silent background check (`UpdateCheckWorker`, `notifyOnly = true`), the manual "Check for updates" button (`WidgetConfigActivity`, `notifyOnly = false`), and the notification tap (`UpdateRelayActivity`, `notifyOnly = false`). It goes straight from "found a newer build" to downloading, with no confirmation step. This task splits it into three functions so a release-notes confirmation step can sit between "checked" and "downloading" for the two `notifyOnly = false` call sites (wired up in Tasks 6 and 7) — the daily silent check keeps its exact current behavior (a system notification, nothing more).

- [ ] **Step 1: Replace `UpdateManager.kt`'s `checkAndUpdate` function**

In `app/src/main/java/com/newsfeed/widget/update/UpdateManager.kt`, find the entire `checkAndUpdate` function (currently lines 55–105):

```kotlin
    suspend fun checkAndUpdate(context: Context, notifyOnly: Boolean) {
        val latestVersionCode = withContext(Dispatchers.IO) { fetchLatestVersionCode() }
        if (latestVersionCode == null) {
            if (!notifyOnly) toast(context, "Couldn't check for updates — try again later")
            return
        }
        if (latestVersionCode <= BuildConfig.VERSION_CODE) {
            if (!notifyOnly) toast(context, "You're up to date (build ${BuildConfig.VERSION_CODE})")
            return
        }

        if (notifyOnly) {
            notifyUpdateAvailable(context, latestVersionCode)
            return
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            toast(context, "Allow installing updates from this app, then try again")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        toast(context, "Downloading update…")
        val apkFile = withContext(Dispatchers.IO) { downloadApk(context) }
        if (apkFile == null) {
            toast(context, "Update download failed — try again later")
            return
        }

        // Belt-and-suspenders before handing off to the installer — see ConfigBackup's own
        // doc comment for why this exists even though a normal same-signature update already
        // preserves all app data on its own.
        val widgetIds = GlanceAppWidgetManager(context).let { manager ->
            manager.getGlanceIds(NewsFeedWidget::class.java).map { manager.getAppWidgetId(it) } +
                manager.getGlanceIds(NewsFeedFocusWidget::class.java).map { manager.getAppWidgetId(it) }
        }
        ConfigBackup.backupAll(context, widgetIds)

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
```

Replace it with:

```kotlin
    sealed interface UpdateCheckResult {
        data class Available(val versionCode: Int) : UpdateCheckResult
        data object UpToDate : UpdateCheckResult
        data object CheckFailed : UpdateCheckResult
    }

    /** Just checks — no toast, no download, no notification. Callers decide what to do with
     *  the result (WidgetConfigActivity/UpdateRelayActivity show a confirmation UI on
     *  Available; checkForUpdateAndNotify below shows a system notification instead). */
    suspend fun checkForUpdate(context: Context): UpdateCheckResult {
        val latestVersionCode = withContext(Dispatchers.IO) { fetchLatestVersionCode() }
            ?: return UpdateCheckResult.CheckFailed
        return if (latestVersionCode > BuildConfig.VERSION_CODE) UpdateCheckResult.Available(latestVersionCode)
        else UpdateCheckResult.UpToDate
    }

    /** Used only by the silent daily background check (UpdateCheckWorker) — checks, and shows
     *  a system notification if a newer build exists, with no release-notes/confirmation UI
     *  at all. Notes are only shown once the user actually acts on it (taps the notification
     *  or the manual button) — an interruptive dialog during a silent background poll would
     *  defeat the point of it being silent. */
    suspend fun checkForUpdateAndNotify(context: Context) {
        val result = checkForUpdate(context)
        if (result is UpdateCheckResult.Available) notifyUpdateAvailable(context, result.versionCode)
    }

    /** Downloads the latest build and hands it to Android's installer. Callers must have
     *  already confirmed a newer build exists (checkForUpdate returning Available) — this
     *  function does not check the version itself. */
    suspend fun proceedWithUpdate(context: Context) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            toast(context, "Allow installing updates from this app, then try again")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        toast(context, "Downloading update…")
        val apkFile = withContext(Dispatchers.IO) { downloadApk(context) }
        if (apkFile == null) {
            toast(context, "Update download failed — try again later")
            return
        }

        // Belt-and-suspenders before handing off to the installer — see ConfigBackup's own
        // doc comment for why this exists even though a normal same-signature update already
        // preserves all app data on its own.
        val widgetIds = GlanceAppWidgetManager(context).let { manager ->
            manager.getGlanceIds(NewsFeedWidget::class.java).map { manager.getAppWidgetId(it) } +
                manager.getGlanceIds(NewsFeedFocusWidget::class.java).map { manager.getAppWidgetId(it) }
        }
        ConfigBackup.backupAll(context, widgetIds)

        val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
```

`toast()`, `fetchLatestVersionCode()`, `downloadApk()`, and `notifyUpdateAvailable()` (the private helpers below this function in the same file) are unchanged — leave them exactly as they are.

- [ ] **Step 2: Update `UpdateCheckWorker.kt`'s call site**

In `app/src/main/java/com/newsfeed/widget/update/UpdateCheckWorker.kt`, find:

```kotlin
    override suspend fun doWork(): Result {
        UpdateManager.checkAndUpdate(context, notifyOnly = true)
        return Result.success()
    }
```

Replace with:

```kotlin
    override suspend fun doWork(): Result {
        UpdateManager.checkForUpdateAndNotify(context)
        return Result.success()
    }
```

- [ ] **Step 3: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: FAIL at this point — `WidgetConfigActivity.kt` and `UpdateRelayActivity.kt` (Tasks 6 and 7, not yet done) still call the now-deleted `checkAndUpdate`. That's expected; this task's own two files compile correctly in isolation but the other call sites are fixed in Tasks 6–7. Confirm the ONLY compile errors reported are in `WidgetConfigActivity.kt`/`UpdateRelayActivity.kt` referencing `checkAndUpdate` — if there are other, unrelated errors, stop and investigate before proceeding.

- [ ] **Step 4: Commit anyway — the plan proceeds task-by-task and the next two tasks fix the remaining call sites**

```bash
git add app/src/main/java/com/newsfeed/widget/update/UpdateManager.kt app/src/main/java/com/newsfeed/widget/update/UpdateCheckWorker.kt
git commit -m "Split UpdateManager.checkAndUpdate into checkForUpdate/proceedWithUpdate"
```

(This intentionally leaves the tree non-compiling for one commit — Tasks 6 and 7 land immediately after and restore a compiling state. If executing this plan with subagent-driven-development, note this in the task handoff so the next task's implementer isn't alarmed by a pre-existing compile failure that isn't theirs to fix in isolation — it's fixed by their own task's changes.)

---

### Task 5: Shared release-notes content composable

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/update/ReleaseNotesDialogContent.kt`

**Context for the implementer:** both the in-app dialog (Task 6) and the notification-tap screen (Task 7) show the same "here's what's new" body content, but wrap it differently (an `AlertDialog`'s `text = {}` slot vs. a full-screen `Column`) and pair it with different button chrome. This file holds ONLY the shared body content — no buttons, no dialog/screen chrome — so each caller supplies its own surrounding UI.

- [ ] **Step 1: Create the file**

```kotlin
package com.newsfeed.widget.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The shared "here's what's new" body shown before installing a self-update — used by both
 * WidgetConfigActivity's in-app AlertDialog (Task 6) and UpdateRelayActivity's full-screen
 * confirmation (Task 7), so the actual content is defined once. Callers own the surrounding
 * chrome (dialog vs. screen, buttons) and pass in the notes to show.
 */
@Composable
fun ReleaseNotesContent(versionCode: Int, notes: List<ReleaseNote>) {
    Column {
        Text("Build $versionCode is ready to install.", style = MaterialTheme.typography.bodyMedium)
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("What's new:", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            for (note in notes) {
                for (bullet in note.bullets) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodySmall)
                        Text(bullet, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: same two pre-existing errors as Task 4 Step 3 (`WidgetConfigActivity.kt`/`UpdateRelayActivity.kt` still referencing the deleted `checkAndUpdate`) — this file itself introduces no new errors. Confirm no error is reported inside `ReleaseNotesDialogContent.kt` itself.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/update/ReleaseNotesDialogContent.kt
git commit -m "Add ReleaseNotesContent: shared body for the update-confirmation UI"
```

---

### Task 6: Wire the manual "Check for updates" button to show the confirmation dialog

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`

**Context for the implementer:** this is the biggest single-file change in this plan. `WidgetConfigActivity.kt` is a large existing file — read the surrounding ~30 lines before and after each edit point below to confirm you're editing the right spot (line numbers are a snapshot, not a guarantee, if earlier tasks in this plan or other work shifted them).

- [ ] **Step 1: Add two imports**

Near the existing `import com.newsfeed.widget.update.UpdateManager` line (around line 93), add:

```kotlin
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import com.newsfeed.widget.data.ReleaseNotesStore
import com.newsfeed.widget.update.ReleaseNote
import com.newsfeed.widget.update.ReleaseNotesContent
import com.newsfeed.widget.update.ReleaseNotesFetcher
```

(`AlertDialog` is likely already imported — this file already uses `AlertDialog` for the "Edit feed" dialog. Check first with `grep -n "import androidx.compose.material3.AlertDialog" app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` and skip that one line if it's already there.)

- [ ] **Step 2: Add dialog state, alongside the existing `isCheckingForUpdate` state**

Find (around line 214):

```kotlin
                var isCheckingForUpdate by remember { mutableStateOf(false) }
```

Replace with:

```kotlin
                var isCheckingForUpdate by remember { mutableStateOf(false) }
                var showUpdateDialog          by remember { mutableStateOf(false) }
                var pendingUpdateVersionCode  by remember { mutableStateOf(0) }
                var pendingReleaseNotes       by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
                // The highest release-note id actually shown in this dialog - NOT the same
                // as pendingUpdateVersionCode (a build's versionCode and a release note's id
                // are two independent, unrelated counters; see the design doc's "Decisions"
                // section on why notes are keyed by their own id). This is what
                // ReleaseNotesStore.markSeen must be called with.
                var pendingHighestNoteId      by remember { mutableStateOf(0) }
```

- [ ] **Step 3: Add the confirmation `AlertDialog`, alongside the existing "Edit feed" dialog**

Find the existing "Edit feed" dialog's closing (search for the line `dismissButton = { TextButton(onClick = { editingFeed = null }) { Text("Cancel") } },` and the `)` that closes that whole `AlertDialog(...)` call just after it — read enough surrounding context to find that exact closing point before editing). Immediately AFTER that `AlertDialog(...)` call's closing, and its enclosing `if (editingFeed != null) { ... }` block's closing brace, add a new sibling block:

```kotlin
                if (showUpdateDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showUpdateDialog = false
                            scope.launch { ReleaseNotesStore.markSeen(this@WidgetConfigActivity, pendingHighestNoteId) }
                        },
                        title = { Text("Update available") },
                        text = { ReleaseNotesContent(pendingUpdateVersionCode, pendingReleaseNotes) },
                        confirmButton = {
                            TextButton(onClick = {
                                showUpdateDialog = false
                                scope.launch {
                                    ReleaseNotesStore.markSeen(this@WidgetConfigActivity, pendingHighestNoteId)
                                    UpdateManager.proceedWithUpdate(this@WidgetConfigActivity)
                                }
                            }) { Text("Update Now") }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showUpdateDialog = false
                                scope.launch { ReleaseNotesStore.markSeen(this@WidgetConfigActivity, pendingHighestNoteId) }
                            }) { Text("Later") }
                        },
                    )
                }
```

- [ ] **Step 4: Replace the "Check now" button's `onClick`**

Find (around lines 1026–1039):

```kotlin
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
```

Replace with:

```kotlin
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
                                            when (val result = UpdateManager.checkForUpdate(this@WidgetConfigActivity)) {
                                                UpdateManager.UpdateCheckResult.CheckFailed ->
                                                    Toast.makeText(this@WidgetConfigActivity, "Couldn't check for updates — try again later", Toast.LENGTH_LONG).show()
                                                UpdateManager.UpdateCheckResult.UpToDate ->
                                                    Toast.makeText(this@WidgetConfigActivity, "You're up to date (build ${BuildConfig.VERSION_CODE})", Toast.LENGTH_LONG).show()
                                                is UpdateManager.UpdateCheckResult.Available -> {
                                                    val lastSeenId = ReleaseNotesStore.lastSeenId(this@WidgetConfigActivity)
                                                    val notes = ReleaseNotesFetcher.fetchUnseenNotes(lastSeenId)
                                                    pendingUpdateVersionCode = result.versionCode
                                                    pendingReleaseNotes      = notes
                                                    pendingHighestNoteId     = notes.maxOfOrNull { it.id } ?: lastSeenId
                                                    showUpdateDialog         = true
                                                }
                                            }
                                            isCheckingForUpdate = false
                                        }
                                    }) { Text("Check now") }
```

- [ ] **Step 5: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: only `UpdateRelayActivity.kt` (Task 7, not yet done) still references the deleted `checkAndUpdate` — that's the one remaining expected error. `WidgetConfigActivity.kt` itself should compile clean.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Show release notes before a manually-triggered update"
```

---

### Task 7: Rewrite `UpdateRelayActivity` as a real, visible confirmation screen

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/update/UpdateRelayActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Context for the implementer:** today `UpdateRelayActivity` is an invisible relay (`Theme.Translucent.NoTitleBar`, `excludeFromRecents="true"`) that silently re-checks and installs when the "Update available" notification is tapped. It becomes a real, minimal Compose screen — the same confirmation content as Task 6's dialog, since it must work standalone (the app may not already be open when the notification is tapped, so there's no existing screen to show a dialog over).

- [ ] **Step 1: Replace `UpdateRelayActivity.kt`'s full contents**

```kotlin
package com.newsfeed.widget.update

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Tap target for the "Update available" notification (UpdateManager.notifyUpdateAvailable).
 * Re-checks fresh rather than threading the notification's already-known version through
 * Intent extras — cheap, and avoids installing a build that's since been superseded by an
 * even newer one. A real, visible Compose screen (not the invisible relay this used to be):
 * it must work standalone, since the app may not already be open when the notification is
 * tapped, so there's no existing screen to show a confirmation dialog over.
 */
class UpdateRelayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            MaterialTheme(colorScheme = if (isDark) darkColorScheme() else lightColorScheme()) {
                UpdateRelayScreen(onFinished = { finish() })
            }
        }
    }
}

@Composable
private fun UpdateRelayScreen(onFinished: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<UpdateManager.UpdateCheckResult?>(null) }
    var notes  by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val checkResult = UpdateManager.checkForUpdate(context)
        result = checkResult
        if (checkResult is UpdateManager.UpdateCheckResult.Available) {
            val lastSeenId = ReleaseNotesStore.lastSeenId(context)
            notes = ReleaseNotesFetcher.fetchUnseenNotes(lastSeenId)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val r = result) {
            null -> CircularProgressIndicator()
            UpdateManager.UpdateCheckResult.CheckFailed -> {
                Text("Couldn't check for updates — try again later")
                Button(onClick = onFinished) { Text("Close") }
            }
            UpdateManager.UpdateCheckResult.UpToDate -> {
                Text("You're already up to date")
                Button(onClick = onFinished) { Text("Close") }
            }
            is UpdateManager.UpdateCheckResult.Available -> {
                ReleaseNotesContent(r.versionCode, notes)
                val highestNoteId = notes.maxOfOrNull { it.id }
                Button(onClick = {
                    scope.launch {
                        ReleaseNotesStore.markSeen(context, highestNoteId ?: ReleaseNotesStore.lastSeenId(context))
                        UpdateManager.proceedWithUpdate(context)
                        onFinished()
                    }
                }) { Text("Update Now") }
                Button(onClick = {
                    scope.launch {
                        ReleaseNotesStore.markSeen(context, highestNoteId ?: ReleaseNotesStore.lastSeenId(context))
                        onFinished()
                    }
                }) { Text("Later") }
            }
        }
    }
}
```

- [ ] **Step 2: Update the manifest — real theme, no longer excluded from recents**

In `app/src/main/AndroidManifest.xml`, find:

```xml
        <activity
            android:name=".update.UpdateRelayActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true" />
```

Replace with:

```xml
        <activity
            android:name=".update.UpdateRelayActivity"
            android:exported="false"
            android:theme="@android:style/Theme.DeviceDefault" />
```

(Matches `WidgetConfigActivity`'s own theme declaration — this is now a normal visible screen, not an invisible relay, so the translucent theme and `excludeFromRecents` no longer apply. If this reads oddly on-device in Task 8's verification — e.g. it looks jarring appearing briefly in the recents list for what's meant to be a quick action — that's a valid implementation-time visual call to revisit, not a hard requirement of this step.)

- [ ] **Step 3: Confirm the whole module compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL — this was the last remaining call site referencing the old `checkAndUpdate`, so the whole module should now compile clean (Tasks 4–7 together restore a fully-compiling tree).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/update/UpdateRelayActivity.kt app/src/main/AndroidManifest.xml
git commit -m "Turn the update notification's tap target into a real confirmation screen"
```

---

### Task 8: On-device verification + `docs/BUGS.md` entry

**Files:** none (verification only) until Step 8's doc commit.

This project's established convention (see `docs/DEBUG_PLAN.md` and every prior feature in `docs/BUGS.md`) is that any network fetch, DataStore read/write, or real UI flow is verified on a real device, not assumed from a clean compile alone. The previous feature shipped in this repo (unread grace period) passed every code review yet had three real bugs only found via on-device testing — do not skip this step or treat a clean build as equivalent to a working feature.

- [ ] **Step 1: Push `docs/RELEASE_NOTES.md` ahead of testing**

The manual "Check for updates" flow and the notification-tap screen both fetch `docs/RELEASE_NOTES.md` from `raw.githubusercontent.com` on the `main` branch — this only reflects reality once Task 2's commit is actually pushed to `origin/main` (a local-only commit won't be visible to that URL). Confirm all of this plan's commits are pushed before starting on-device verification, or the "Available" path will show zero notes even though the parsing logic is correct (this is expected, not a bug, if testing against a not-yet-pushed local commit).

- [ ] **Step 2: Build and install**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Manual "Check for updates" — up-to-date case**

With the device on a build whose `versionCode` is >= the current `main` branch's real released `versionCode` (or after temporarily lowering `VERSION_JSON_URL`'s expected comparison is not practical — instead, just run this on whatever real build is currently installed, since the real GitHub Release's `version.json` reflects the actual latest CI-built versionCode): tap Settings → APP UPDATE → Check now. Confirm the exact same "You're up to date (build N)" / "Couldn't check for updates" toasts as before this change (Task 6's `Toast.makeText` calls) — these must look identical to pre-this-plan behavior.

- [ ] **Step 4: Manual "Check for updates" — update-available case**

This requires the installed build's `versionCode` to be genuinely older than the real latest GitHub Release. If that's not naturally the case at verification time, install an intentionally-older debug build first (e.g. `adb install -r -d` an older locally-built APK, or wait for the next CI build to land after this plan's own commits are merged and built). Tap Check now. Confirm: an `AlertDialog` titled "Update available" appears showing "Build N is ready to install" and, if any unseen notes exist, a bulleted "What's new" list matching `docs/RELEASE_NOTES.md`'s real content on `main`. Confirm "Later" dismisses the dialog without downloading anything (`adb shell ls -la /data/data/com.newsfeed.widget/cache/updates/` should show no freshly-modified file). Confirm tapping Check now again afterward re-shows the SAME dialog with the SAME notes (since "Later" doesn't mark them seen as fully consumed — re-verify against the spec's actual intent: "Later" DOES call `markSeen`, so confirm instead that re-checking shows NO notes the second time, only the plain version-available dialog with an empty "what's new" section — this is the correct behavior per Task 6/7's code, not a bug if observed).

- [ ] **Step 5: Manual "Check for updates" — "Update Now" actually proceeds**

From the same update-available dialog, tap "Update Now". Confirm the existing download-and-install flow proceeds exactly as it did before this plan (a "Downloading update…" toast, then Android's install screen) — this is `proceedWithUpdate()`, unchanged logic from the old `checkAndUpdate`'s tail end, just relocated.

- [ ] **Step 6: Notification tap path**

Trigger the daily background check's notification (force-fire `UpdateCheckWorker` if possible, or wait for its natural schedule with a genuinely older installed build) and tap it. Confirm `UpdateRelayActivity` now opens as a REAL visible screen (not instantly invisible/no-op as before) showing a loading spinner briefly, then either the same "what's new" content + Update Now/Later buttons (if a newer build exists) or a plain "You're already up to date"/"Couldn't check" message with a Close button. Confirm both buttons work (Later closes the screen without downloading; Update Now proceeds to download+install, same as the in-app path).

- [ ] **Step 7: Regression check — the daily silent background check is unaffected**

Confirm `adb shell dumpsys jobscheduler | grep -A2 NewsFeedUpdateCheck` still shows the same scheduled job as before this plan (Task 4 didn't touch its scheduling, only what it calls internally). Confirm the daily check still shows ONLY a system notification with no dialog/confirmation UI appearing unprompted — that stays silent-until-tapped, exactly as before.

- [ ] **Step 8: Update `docs/BUGS.md`**

Add a "Feature additions" entry (matching this file's existing format — check the two most recent entries for the exact style) describing the release-notes-on-update feature: what it shows, when (before installing, both the manual button and the notification tap), how notes are authored (`docs/RELEASE_NOTES.md`, hand-written per release) and tracked (`ReleaseNotesStore`, app-wide `lastSeenId`), and the on-device verification performed above. Note any real bugs found during verification and how they were fixed, following this file's established convention of documenting the debugging journey, not just the shipped feature. Commit this doc update on its own:

```bash
git add docs/BUGS.md
git commit -m "Document release notes on self-update"
```

---

## Self-review notes (for whoever executes this plan)

- **Spec coverage:** every "Components" entry in the design doc maps to a task — `docs/RELEASE_NOTES.md` (Task 2), `ReleaseNotesStore` (Task 1), `ReleaseNotesFetcher` (Task 3), `UpdateManager`'s split (Task 4), the shared confirmation UI (Task 5, used by Tasks 6–7), `WidgetConfigActivity`'s dialog (Task 6), `UpdateRelayActivity` becoming a real screen (Task 7).
- **Type consistency:** `ReleaseNote(id: Int, bullets: List<String>)` is defined once (Task 3) and used identically by `ReleaseNotesContent` (Task 5), `WidgetConfigActivity` (Task 6), and `UpdateRelayActivity` (Task 7). `UpdateManager.UpdateCheckResult`'s three cases (`Available`, `UpToDate`, `CheckFailed`) are defined once (Task 4) and exhaustively matched (`when`, no `else` needed since it's a sealed interface) in both Task 6 and Task 7.
- **A known transient state, called out explicitly rather than hidden:** Task 4's commit alone leaves the tree non-compiling (two other files still call the just-deleted `checkAndUpdate`) until Tasks 6 and 7 land. This is intentional — the alternative (one giant multi-file task) would violate this plan's own bite-sized-task principle for a change this interconnected. If executing via `subagent-driven-development`, brief the Task 4 implementer that this is expected and not a bug in their own work.
- **Out of scope, confirmed not touched by any task:** any change to `.github/workflows/build.yml` or `version.json` generation (notes are fetched as an independent file); auto-generating notes from commit messages; showing notes during the silent daily background check; localizing release notes.
