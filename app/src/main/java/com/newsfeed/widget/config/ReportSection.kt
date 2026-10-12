package com.newsfeed.widget.config

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.newsfeed.widget.BuildConfig
import com.newsfeed.widget.data.BUG_REPORT_MAX_NOTE_CHARS
import com.newsfeed.widget.data.BugReportDefaultFeed
import com.newsfeed.widget.data.BugReportInput
import com.newsfeed.widget.data.BugReportRefreshStatus
import com.newsfeed.widget.data.HoldOff
import com.newsfeed.widget.data.LastSent
import com.newsfeed.widget.data.OpmlManager
import com.newsfeed.widget.data.CrashLogStore
import com.newsfeed.widget.data.RefreshLogEntry
import com.newsfeed.widget.data.RefreshLogStore
import com.newsfeed.widget.data.RefreshStatusStore
import com.newsfeed.widget.data.REPORT_PREFS_NAME
import com.newsfeed.widget.data.ReportFileInfo
import com.newsfeed.widget.data.ReportUploader
import com.newsfeed.widget.data.SharedPrefsReportStore
import com.newsfeed.widget.data.UploadResult
import com.newsfeed.widget.data.WidgetConfigStore
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.buildBugReport
import com.newsfeed.widget.data.cooldownMessage
import com.newsfeed.widget.data.cooldownRemainingMs
import com.newsfeed.widget.data.effectiveRefreshIntervalMinutes
import com.newsfeed.widget.data.lastSentText
import com.newsfeed.widget.data.readLastSent
import com.newsfeed.widget.data.reportFileName
import com.newsfeed.widget.data.reportShareSummary
import com.newsfeed.widget.data.sendConfirmationParagraphs
import com.newsfeed.widget.data.staleReportFiles
import com.newsfeed.widget.data.summarizeRun
import com.newsfeed.widget.data.toBugReportWidget
import com.newsfeed.widget.data.uploadMessage
import com.newsfeed.widget.glance.NewsFeedWidget
import com.newsfeed.widget.glance.WidgetWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

/**
 * Settings: "REPORT A PROBLEM" (always available) and "LAST REFRESHES" (bug logger phase 1b).
 * The report is a plain text file the user shares through the system share sheet, or, in a build that was given
 * a server address and key (see docs/REPORT_SERVER.md), sent to the developer after an explicit confirmation.
 * Nothing is ever sent without a tap on Send. The pure content lives in data/BugReport.kt and data/ReportUploader.kt,
 * this file only gathers the inputs and shows the UI.
 */

/** Everything the two sections show or edit. Hoisted out of the LazyColumn so a scroll cannot reset it. */
class ReportUiState(initialRecipient: String, initialLastSent: LastSent? = null) {
    var note by mutableStateOf("")
    var includeAddresses by mutableStateOf(false)
    var recipient by mutableStateOf(initialRecipient)
    /** The report text shown in the preview dialog; null = dialog closed. */
    var previewText by mutableStateOf<String?>(null)
    /** Mirrors [guard] for the UI (progress wheel); false during the hold-off after a share. */
    var busy by mutableStateOf(false)
        private set
    private val guard = HoldOff(SHARE_HOLD_OFF_MS)

    /**
     * Takes the one-at-a-time slot synchronously, BEFORE any coroutine is launched, so a double tap on Share /
     * Preview / Send cannot start two runs (BUG-039). Every true result must be paired with [end] in a finally.
     */
    fun tryBegin(): Boolean = guard.tryBegin(SystemClock.elapsedRealtime()).also { if (it) busy = true }

    /**
     * [holdOff] = true after an external activity (the share chooser) was started: the guard keeps rejecting
     * taps for [SHARE_HOLD_OFF_MS] more, until the chooser is really on screen (BUG-041).
     */
    fun end(holdOff: Boolean = false) { guard.end(SystemClock.elapsedRealtime(), holdOff); busy = false }
    /** Newest first, at most [REPORT_UI_RUNS]. */
    var runs by mutableStateOf<List<RefreshLogEntry>>(emptyList())
    /** The confirmation dialog of "Send to developer" is open. */
    var confirmSendOpen by mutableStateOf(false)
    /** The text the user already previewed and will send; null = a fresh report is built at send time. */
    var pendingSendText by mutableStateOf<String?>(null)
    /** Progress / result line under the buttons. */
    var sendMessage by mutableStateOf<String?>(null)
    var sendFailed by mutableStateOf(false)
    var lastSent by mutableStateOf(initialLastSent)
    var logOpen by mutableStateOf(false)
    var expandedRunTimestamps by mutableStateOf<Set<Long>>(emptySet())
    /**
     * The whole problem-report area (crash list, report form, refresh log) is collapsed until the user opens it.
     * Always starts closed: it is only needed when something went wrong, so it stays out of the way.
     */
    var sectionOpen by mutableStateOf(false)
}

const val REPORT_UI_RUNS = 20

/** How long Share keeps rejecting taps after it launched the chooser. */
const val SHARE_HOLD_OFF_MS = 1500L

/** App-level optional recipient, kept in its own tiny SharedPreferences file. Blank by default, never hardcoded. */
object ReportSettings {
    private const val NAME = REPORT_PREFS_NAME
    private const val K_RECIPIENT = "recipient_email"

    fun recipient(context: Context): String =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(K_RECIPIENT, "").orEmpty()

    fun setRecipient(context: Context, value: String) {
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit().putString(K_RECIPIENT, value).apply()
    }

    fun lastSent(context: Context): LastSent? = readLastSent(SharedPrefsReportStore(context))

    fun isValidEmail(value: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(value.trim()).matches()
}

private fun reportsDir(context: Context): File = File(context.cacheDir, "reports")

/** Deletes report files older than 7 days (called when Settings opens and before each new report). */
fun cleanOldReports(context: Context) {
    runCatching {
        val dir = reportsDir(context)
        val files = dir.listFiles()?.map { ReportFileInfo(it.name, it.lastModified()) } ?: return
        staleReportFiles(files, System.currentTimeMillis()).forEach { File(dir, it).delete() }
    }
}

/** Reads the log shown under LAST REFRESHES (newest first). */
fun loadRefreshRuns(context: Context): List<RefreshLogEntry> =
    RefreshLogStore.readAll(context).takeLast(REPORT_UI_RUNS).reversed()

/**
 * The app's bundled default feeds (the same asset the Settings screen loads), so the report may name them even when
 * the user's own feeds are left out. Empty if the asset cannot be read: then every feed counts as the user's own,
 * which only ever hides more.
 */
fun loadDefaultFeedsForReport(context: Context): List<BugReportDefaultFeed> = runCatching {
    val xml = context.assets.open("default_feeds.opml").bufferedReader().use { it.readText() }
    OpmlManager.parse(xml).map { (name, url) -> BugReportDefaultFeed(name, url) }
}.getOrDefault(emptyList())

/** Gathers the report inputs from the stores. Off the main thread. */
private suspend fun collectReportInput(context: Context, note: String, includeAddresses: Boolean): BugReportInput =
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val ids = WidgetWorker.liveWidgetIds(app)
        val configs = WidgetConfigStore(app).savedConfigs(ids.takeIf { it.isNotEmpty() })
        val statusStore = RefreshStatusStore(app)

        // What the footers show, summarised over the placed widgets (each keeps its own state).
        var lastAttempt = 0L
        var failedFeeds = 0
        var failedAll = false
        runCatching {
            val manager = GlanceAppWidgetManager(app)
            manager.getGlanceIds(NewsFeedWidget::class.java).forEach { gid ->
                val prefs = getAppWidgetState(app, PreferencesGlanceStateDefinition, gid)
                lastAttempt = maxOf(lastAttempt, prefs[WidgetStateKey.lastRefreshAttemptTime] ?: 0L)
                failedFeeds = maxOf(failedFeeds, prefs[WidgetStateKey.failedFeedCount] ?: 0)
                if (prefs[WidgetStateKey.lastRefreshFailed] == true) failedAll = true
            }
        }
        BugReportInput(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            packageName = app.packageName,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            locale = Locale.getDefault().toLanguageTag(),
            timeZone = TimeZone.getDefault().id,
            timestampMs = System.currentTimeMillis(),
            userNote = note,
            includeOwnFeeds = includeAddresses,
            defaultFeeds = loadDefaultFeedsForReport(app),
            widgets = configs.map { it.toBugReportWidget() },
            status = BugReportRefreshStatus(
                lastOkMs = statusStore.lastOkRefreshMs,
                lastAttemptMs = lastAttempt,
                failedFeedCount = failedFeeds,
                lastRefreshFailed = failedAll,
                effectiveIntervalMinutes = statusStore.intervalMinutes.takeIf { it > 0 }
                    ?: effectiveRefreshIntervalMinutes(configs),
                jobState = WidgetWorker.periodicJobState(app),
            ),
            refreshLog = RefreshLogStore.readAll(app),
            crashes = CrashLogStore.readAll(app),
        )
    }

/** Builds the report for the preview dialog. */
fun previewReport(activity: ComponentActivity, scope: CoroutineScope, state: ReportUiState) {
    if (!state.tryBegin()) return
    scope.launch {
        try {
            state.previewText = withContext(Dispatchers.IO) {
                buildBugReport(collectReportInput(activity, state.note, state.includeAddresses))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toast.makeText(activity, "Could not build the report: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally { state.end() }
    }
}

/**
 * Writes the report to cacheDir/reports/newsfeed-report-<yyyyMMdd-HHmmss>.txt and opens the share sheet with it
 * attached (FileProvider + EXTRA_STREAM, like the OPML export). [readyText] is the text already shown in the
 * preview dialog, so what the user read is exactly what is shared.
 */
fun shareReport(activity: ComponentActivity, scope: CoroutineScope, state: ReportUiState, readyText: String? = null) {
    if (!state.tryBegin()) return
    scope.launch {
        var chooserStarted = false
        try {
            val input = collectReportInput(activity, state.note, state.includeAddresses)
            val text = readyText ?: buildBugReport(input)
            val file = withContext(Dispatchers.IO) {
                cleanOldReports(activity)
                val dir = reportsDir(activity).also { it.mkdirs() }
                File(dir, reportFileName(System.currentTimeMillis(), ZoneId.systemDefault())).also { it.writeText(text) }
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val recipient = state.recipient.trim()
            val recipientOk = recipient.isNotEmpty() && ReportSettings.isValidEmail(recipient)
            if (recipient.isNotEmpty() && !recipientOk) {
                Toast.makeText(activity, "The email address is not valid, so it was left out.", Toast.LENGTH_LONG).show()
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "NewsFeed problem report (build ${input.appVersionCode})")
                putExtra(Intent.EXTRA_TEXT, reportShareSummary(input))
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData + the grant flag: the receiving app may read exactly this file, nothing else.
                clipData = ClipData.newRawUri("NewsFeed problem report", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (recipientOk) putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            }
            activity.startActivity(Intent.createChooser(send, "Share problem report"))
            chooserStarted = true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Toast.makeText(activity, "Could not share the report: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally {
            // The chooser appears a few hundred ms after startActivity returns: keep rejecting taps until it has (BUG-041).
            state.end(holdOff = chooserStarted)
        }
    }
}

/**
 * The Send button (or Send in the preview dialog) was tapped: stops at once while the cooldown after the last
 * successful send is running, otherwise opens the confirmation dialog. Nothing is sent here.
 */
fun requestSend(state: ReportUiState, readyText: String? = null) {
    if (!ReportUploader.isConfigured || state.busy) return
    val wait = cooldownRemainingMs(state.lastSent?.timeMs ?: 0L, System.currentTimeMillis())
    if (wait > 0L) {
        state.sendFailed = true
        state.sendMessage = cooldownMessage(wait)
        return
    }
    state.pendingSendText = readyText
    state.confirmSendOpen = true
}

/**
 * Sends the report once, after the user confirmed. [readyText] is the text shown in the preview dialog (so what was
 * read is exactly what is sent); without it a fresh report is built from the current note and checkbox. The result is
 * shown under the buttons and in a Toast. No automatic retry.
 */
fun sendReport(activity: ComponentActivity, scope: CoroutineScope, state: ReportUiState, readyText: String? = null) {
    if (!ReportUploader.isConfigured) return
    if (!state.tryBegin()) return
    scope.launch {
        try {
            state.sendFailed = false
            state.sendMessage = "Sending…"
            val wait = cooldownRemainingMs(state.lastSent?.timeMs ?: 0L, System.currentTimeMillis())
            if (wait > 0L) {
                state.sendFailed = true
                state.sendMessage = cooldownMessage(wait)
                return@launch
            }
            val text = readyText ?: withContext(Dispatchers.IO) {
                buildBugReport(collectReportInput(activity, state.note, state.includeAddresses))
            }
            val result = ReportUploader.send(activity, text)
            val message = uploadMessage(result)
            state.sendFailed = result !is UploadResult.Sent
            state.sendMessage = message
            if (result is UploadResult.Sent) state.lastSent = ReportSettings.lastSent(activity)
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) { state.sendMessage = null; throw e }
            val message = "Could not send the report. Use Share report instead."
            state.sendFailed = true
            state.sendMessage = message
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        } finally { state.end() }
    }
}

// ── UI ─────────────────────────────────────────────────────────────────────

/** The one always-visible row of the problem-report area: tap to open or close everything below it. */
@Composable
fun ReportSectionHeader(state: ReportUiState) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth()
            .clickable { state.sectionOpen = !state.sectionOpen }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("REPORT A PROBLEM", fontSize = 10.sp, color = muted, letterSpacing = 0.05.sp)
            Text(if (state.sectionOpen) "Hide" else "Show", fontSize = 11.sp, color = muted)
        }
        if (!state.sectionOpen) {
            Spacer(Modifier.height(4.dp))
            Text("Something not working? Tap to make a report.", fontSize = 12.sp, color = muted)
        }
    }
}

@Composable
fun ReportProblemSection(
    state: ReportUiState,
    onSaveRecipient: (String) -> Unit,
    onPreview: () -> Unit,
    onShare: () -> Unit,
    onSend: () -> Unit,
) {
    // Hidden (not disabled) in a build without a server address and key: local builds, forks.
    val canSend = ReportUploader.isConfigured
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The "REPORT A PROBLEM" title now lives in ReportSectionHeader (the collapsible header above this block).
        Text(
            if (canSend) "Something not working? Make a report, share it wherever you like, or send it straight to the developer. Nothing is sent unless you tap Share or Send."
            else "Something not working? Make a report and share it wherever you like. Nothing is sent automatically.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.note,
            onValueChange = { state.note = it.take(BUG_REPORT_MAX_NOTE_CHARS) },
            label = { Text("What did you see?") },
            minLines = 3, maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickable { state.includeAddresses = !state.includeAddresses },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = state.includeAddresses, onCheckedChange = { state.includeAddresses = it })
            Column(Modifier.weight(1f)) {
                Text("Include my own feeds (names and addresses)", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Off by default. When off, only the app's own default feeds are named; yours appear as feed #1, #2 … " +
                        "When on, passwords and tokens in addresses are removed.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val recipientInvalid = state.recipient.isNotBlank() && !ReportSettings.isValidEmail(state.recipient)
        OutlinedTextField(
            value = state.recipient,
            onValueChange = { state.recipient = it.take(200); onSaveRecipient(it.take(200)) },
            label = { Text("Send reports to (email, optional)") },
            singleLine = true,
            isError = recipientInvalid,
            supportingText = { if (recipientInvalid) Text("Not a valid email address; it will be left out.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (canSend) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send to developer") }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share report") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPreview) { Text("Preview report") }
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            state.sendMessage?.let {
                Text(
                    it, fontSize = 12.sp,
                    color = if (state.sendFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            state.lastSent?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    lastSentText(it, ZoneId.systemDefault(), Locale.getDefault()),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPreview) { Text("Preview report") }
                Button(onClick = onShare) { Text("Share report") }
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun ReportPreviewDialog(state: ReportUiState, onShare: (String) -> Unit, onSend: (String) -> Unit) {
    val text = state.previewText ?: return
    AlertDialog(
        onDismissRequest = { state.previewText = null },
        title = { Text("Report preview") },
        text = {
            // The report is English, machine-written text: always left-to-right, monospace, read-only.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Row {
                    TextButton(onClick = { state.previewText = null }) { Text("Close") }
                    TextButton(onClick = { state.previewText = null; onShare(text) }) { Text("Share") }
                }
                if (ReportUploader.isConfigured) {
                    Button(onClick = { state.previewText = null; onSend(text) }) { Text("Send to developer") }
                }
            }
        },
    )
}

/** "Send to developer": says what is sent and where, and only the Send button sends. */
@Composable
fun ReportSendConfirmDialog(state: ReportUiState, onPreviewFirst: () -> Unit, onSend: (String?) -> Unit) {
    if (!state.confirmSendOpen) return
    val previewed = state.pendingSendText
    val warningColor = if (isSystemInDarkTheme()) Color(0xFFFFB300) else Color(0xFFB26A00)
    AlertDialog(
        onDismissRequest = { state.confirmSendOpen = false },
        title = { Text("Send this report to the developer?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Text lives in data/SendConfirmation.kt (unit tested): no server address is shown, and the
                // feed-address warning appears only when "Include my feed addresses" is ticked.
                sendConfirmationParagraphs(previewedAlready = previewed != null, includeAddresses = state.includeAddresses)
                    .forEach { p ->
                        Text(
                            p.text,
                            fontSize = 13.sp,
                            color = if (p.isWarning) warningColor else Color.Unspecified,
                        )
                    }
                if (previewed == null) {
                    TextButton(onClick = { state.confirmSendOpen = false; onPreviewFirst() }) { Text("Preview first") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { state.confirmSendOpen = false; onSend(previewed) }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { state.confirmSendOpen = false }) { Text("Cancel") } },
    )
}

@Composable
fun LastRefreshesSection(state: ReportUiState) {
    val warning = if (isSystemInDarkTheme()) Color(0xFFFFB300) else Color(0xFFB26A00)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { state.logOpen = !state.logOpen }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("LAST REFRESHES", fontSize = 10.sp, color = muted, letterSpacing = 0.05.sp)
            Text(if (state.logOpen) "Hide" else "Show (${state.runs.size})", fontSize = 11.sp, color = muted)
        }
        if (!state.logOpen) return@Column
        Spacer(Modifier.height(4.dp))
        if (state.runs.isEmpty()) {
            Text("No refreshes recorded yet.", fontSize = 12.sp, color = muted)
            return@Column
        }
        state.runs.forEach { run ->
            val summary = summarizeRun(run)
            val expanded = run.timestamp in state.expandedRunTimestamps
            val rowColor = if (summary.anyFailed) warning else MaterialTheme.colorScheme.onSurface
            Column(
                Modifier.fillMaxWidth().clickable {
                    state.expandedRunTimestamps =
                        if (expanded) state.expandedRunTimestamps - run.timestamp else state.expandedRunTimestamps + run.timestamp
                }.padding(vertical = 6.dp),
            ) {
                Text(
                    "${formatRunTime(run.timestamp)} · ${run.trigger.label} · ${summary.text}",
                    fontSize = 13.sp, color = rowColor,
                )
                if (expanded) {
                    Text(
                        "attempt ${run.attempt + 1} · ${run.durationMs} ms · ${run.widgetCount} widget(s) · ${run.verdict}",
                        fontSize = 10.sp, color = muted,
                    )
                    run.feeds.forEach { f ->
                        val code = f.httpCode?.let { " $it" } ?: ""
                        Column(Modifier.padding(top = 4.dp, start = 12.dp)) {
                            Text(f.label.ifBlank { "(unnamed feed)" }, fontSize = 12.sp,
                                color = if (f.status.isFailure) warning else MaterialTheme.colorScheme.onSurface)
                            Text("${f.status.name}$code · ${f.elapsedMs} ms", fontSize = 10.sp, color = muted)
                            if (f.detail.isNotBlank()) Text(f.detail, fontSize = 10.sp, color = muted)
                        }
                    }
                }
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

/** `14:03` for today, `Oct 9 14:03` for an earlier day. */
private fun formatRunTime(ms: Long): String {
    val now = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    val today = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    val pattern = if (today) "HH:mm" else "MMM d HH:mm"
    return java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(java.util.Date(ms))
}
