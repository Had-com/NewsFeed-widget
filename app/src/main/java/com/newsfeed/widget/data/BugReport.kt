package com.newsfeed.widget.data

import com.newsfeed.widget.config.normalizeFeedUrl
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The plain-text problem report (bug logger phase 1b, docs/superpowers/specs/2026-09-10-bug-logger-design.md).
 * Pure: everything it needs arrives in a [BugReportInput], so the output is deterministic and unit tested.
 * Nothing here sends anything; the Settings screen writes the text to a file and the user picks where it goes,
 * so any transport can carry the same file later.
 *
 * Privacy rules enforced here (and asserted by tests):
 *  - the user's OWN feeds (names and addresses) appear ONLY when [BugReportInput.includeOwnFeeds] is true, and
 *    then secrets are removed by [redactUrl]; with the switch off only the app's own default feeds
 *    ([BugReportInput.defaultFeeds]) are named, every other feed is `feed #1`, `feed #2` ... (numbered in order
 *    of first appearance, the same number everywhere in the report), and no custom name, no URL and no host of
 *    a feed appears anywhere, not even inside exception messages or stack traces ([scrubAddresses]);
 *  - the user's own note is included (at most [BUG_REPORT_MAX_NOTE_CHARS] chars) and goes through the same
 *    scrubbing / redaction as every other free text, so a pasted address cannot slip past the switch.
 */

const val BUG_REPORT_SCHEMA_VERSION = 1
const val BUG_REPORT_MAX_CHARS = 60_000
const val BUG_REPORT_MAX_NOTE_CHARS = 2_000
const val BUG_REPORT_MAX_LOG_RUNS = 20
const val BUG_REPORT_MAX_STACK_TRACES = 3

data class BugReportFeed(val name: String, val url: String, val enabled: Boolean)

/** One feed of the app's bundled list (`default_feeds.opml`): public, so it may be named even when own feeds are left out. */
data class BugReportDefaultFeed(val name: String, val url: String)

data class BugReportWidget(
    val id: Int,
    val tapMode: String,
    val filter: String,
    val sortOrder: String,
    val theme: String,
    val themeVariant: String,
    val refreshIntervalMinutes: Int,
    val retentionDays: Int,
    val retentionMinutes: Int,
    val groupStories: Boolean,
    val articleLength: String,
    val fontSize: Float,
    val articleFontSize: Float,
    val feeds: List<BugReportFeed>,
)

fun WidgetConfig.toBugReportWidget() = BugReportWidget(
    id = widgetId, tapMode = tapMode, filter = filter, sortOrder = sortOrder, theme = widgetTheme,
    themeVariant = themeVariant, refreshIntervalMinutes = refreshIntervalMinutes, retentionDays = retentionDays,
    retentionMinutes = retentionMinutes, groupStories = groupStories, articleLength = articleLength,
    fontSize = fontSize, articleFontSize = articleFontSize,
    feeds = feeds.map { BugReportFeed(it.displayName, it.feedUrl, it.enabled) },
)

data class BugReportRefreshStatus(
    val lastOkMs: Long,
    val lastAttemptMs: Long,
    val failedFeedCount: Int,
    val lastRefreshFailed: Boolean,
    val effectiveIntervalMinutes: Int,
    /** WorkManager state of the periodic job ("ENQUEUED", "RUNNING", "none" ...), null when it could not be read. */
    val jobState: String? = null,
)

data class BugReportInput(
    val appVersionName: String,
    val appVersionCode: Int,
    val packageName: String,
    val manufacturer: String,
    val model: String,
    val androidSdk: Int,
    val androidRelease: String,
    val locale: String,
    val timeZone: String,
    val timestampMs: Long,
    val userNote: String,
    /** The checkbox: true = the user's own feeds are named and their (redacted) addresses shown; false = `feed #N`. */
    val includeOwnFeeds: Boolean,
    val widgets: List<BugReportWidget>,
    val status: BugReportRefreshStatus,
    /** Oldest first, as stored. */
    val refreshLog: List<RefreshLogEntry>,
    /** Oldest first, as stored. */
    val crashes: List<CrashLogStore.CrashRecord>,
    /** The bundled default feeds. A feed is "default" when its [normalizeFeedUrl] equals one of these (http and https alike). */
    val defaultFeeds: List<BugReportDefaultFeed> = emptyList(),
    /** Size of the one picture the user attached (0 = none). Only the fact and the size go into the text, never the picture. */
    val attachmentBytes: Int = 0,
)

// ── building ───────────────────────────────────────────────────────────────

private class Section(val title: String, val lines: MutableList<String>)

fun buildBugReport(input: BugReportInput): String {
    val zone = runCatching { ZoneId.of(input.timeZone) }.getOrDefault(ZoneId.of("UTC"))
    val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(zone)
    fun at(ms: Long) = if (ms <= 0L) "never" else dateTime.format(Instant.ofEpochMilli(ms))

    // Every address that may appear in free text (exception messages) is known up front.
    val knownUrls = (input.widgets.flatMap { w -> w.feeds.map { it.url } } +
        input.refreshLog.flatMap { r -> r.feeds.map { it.url } }).filter { it.isNotBlank() }.distinct()
    fun text(s: String): String =
        if (input.includeOwnFeeds) redactUrlsInText(s) else scrubAddresses(s, knownUrls)
    fun address(url: String): String = if (input.includeOwnFeeds && url.isNotBlank()) " " + redactUrl(url) else ""

    // Default feeds by their canonical address; an own feed gets the next number the first time it appears.
    val defaultNames = LinkedHashMap<String, String>()
    input.defaultFeeds.filter { it.url.isNotBlank() }.forEach { defaultNames.putIfAbsent(normalizeFeedUrl(it.url), oneLine(it.name)) }
    val ownNumbers = LinkedHashMap<String, Int>()
    fun defaultName(url: String): String? = if (url.isBlank()) null else defaultNames[normalizeFeedUrl(url)]
    fun feedLabel(name: String, url: String): String {
        if (input.includeOwnFeeds) return oneLine(name)
        defaultName(url)?.let { return it }
        val key = if (url.isBlank()) "name:" + oneLine(name) else normalizeFeedUrl(url)
        return "feed #" + ownNumbers.getOrPut(key) { ownNumbers.size + 1 }
    }

    val header = mutableListOf<String>()
    header += "NewsFeed problem report (schema $BUG_REPORT_SCHEMA_VERSION)"
    header += "Created: ${at(input.timestampMs)} (${zone.id})"
    header += "Your own feeds: " + if (input.includeOwnFeeds) "included (passwords and tokens removed)" else "not included (named feed #1, #2 …)"
    if (input.attachmentBytes > 0) header += "Attached picture: yes (${pictureSizeText(input.attachmentBytes)})"
    header += ""
    header += "[App]"
    header += "Version: ${input.appVersionName} (build ${input.appVersionCode})"
    header += "Package: ${input.packageName}"
    header += ""
    header += "[Device]"
    header += "Device: ${input.manufacturer} ${input.model}".trim()
    header += "Android: ${input.androidRelease} (SDK ${input.androidSdk})"
    header += "Locale: ${input.locale}, time zone: ${input.timeZone}"
    header += ""
    header += "[What happened]"
    // The note is scrubbed like every other free text (BUG-038): URLs, hosts and IPs go when addresses are off,
    // secrets inside URLs are redacted when they are on. Done before the length cut so a URL is never half cut.
    val note = text(input.userNote.trim()).take(BUG_REPORT_MAX_NOTE_CHARS)
    header += if (note.isEmpty()) "(no note)" else note
    header += ""
    header += "[Refresh status]"
    header += "Last good refresh: ${at(input.status.lastOkMs)}"
    header += "Last attempt: ${at(input.status.lastAttemptMs)}"
    header += "Last attempt failed for every feed: ${if (input.status.lastRefreshFailed) "yes" else "no"}"
    header += "Feeds that failed in the last attempt: ${input.status.failedFeedCount}"
    header += "Refresh interval in effect: ${input.status.effectiveIntervalMinutes} min"
    input.status.jobState?.let { header += "Periodic job: $it" }

    val widgets = Section("[Widgets] (${input.widgets.size})", mutableListOf())
    input.widgets.forEach { w ->
        val keep = when {
            w.retentionMinutes > 0 -> "${w.retentionMinutes} min"
            w.retentionDays > 0 -> "${w.retentionDays} d"
            else -> "unlimited"
        }
        val enabled = w.feeds.count { it.enabled }
        val feedCounts = if (input.includeOwnFeeds) "$enabled enabled" else {
            val own = w.feeds.count { defaultName(it.url) == null }
            "$enabled enabled; ${w.feeds.size - own} default, $own yours"
        }
        widgets.lines += "Widget ${w.id}: tap=${w.tapMode}, filter=${w.filter}, sort=${w.sortOrder}, " +
            "theme=${w.theme}/${w.themeVariant}, refresh every ${w.refreshIntervalMinutes} min, keep $keep, " +
            "group stories=${if (w.groupStories) "on" else "off"}, article length=${w.articleLength}, " +
            "font ${num(w.fontSize)} / article font ${num(w.articleFontSize)}, feeds: ${w.feeds.size} ($feedCounts)"
        w.feeds.forEach { f ->
            widgets.lines += "  - ${feedLabel(f.name, f.url)}${if (f.enabled) "" else " (off)"}${address(f.url)}"
        }
    }

    val recent = input.refreshLog.takeLast(BUG_REPORT_MAX_LOG_RUNS).reversed()
    val log = Section("[Recent refreshes] (newest first, ${recent.size} of ${input.refreshLog.size} recorded)", mutableListOf())
    recent.forEach { r ->
        val s = summarizeRun(r)
        log.lines += "${at(r.timestamp)} | ${r.trigger.label} | attempt ${r.attempt + 1} | ${durationText(r.durationMs)} | " +
            "${r.widgetCount} widget(s) | ${s.text} | ${r.verdict}"
        r.feeds.filter { it.status.isFailure }.forEach { f ->
            val code = f.httpCode?.let { " $it" } ?: ""
            val detail = text(f.detail).let { if (it.isBlank()) "" else " | $it" }
            log.lines += "  ! ${feedLabel(f.label, f.url)}${address(f.url)} [${f.status.name}$code, ${f.elapsedMs} ms]$detail"
        }
    }

    val crashSection = Section("[Crashes]", mutableListOf())
    if (input.crashes.isEmpty()) {
        crashSection.lines += "No crashes recorded."
    } else {
        CrashLogStore.summarize(input.crashes, input.appVersionCode).forEach { c ->
            crashSection.lines += "${c.exceptionType}: ${text(oneLine(c.message))} (x${c.occurrenceCount}, last ${at(c.lastSeenAt)}, " +
                "build ${c.lastSeenVersionCode}, ${if (c.isSolved) "fixed in a later build" else "not fixed"})"
        }
        input.crashes.sortedByDescending { it.timestamp }.take(BUG_REPORT_MAX_STACK_TRACES).forEach { c ->
            crashSection.lines += ""
            crashSection.lines += "Stack trace (${at(c.timestamp)}, build ${c.versionCode}):"
            text(c.stackTrace).trimEnd().lines().forEach { crashSection.lines += "  $it" }
        }
    }

    val footer = "-- end of report (schema $BUG_REPORT_SCHEMA_VERSION) --"

    // Size cap: shrink the log first (oldest lines are last), then crash stack traces, then widget feed
    // lines. The header (app, device, note, status) and the footer are never touched.
    fun render(): String = buildString {
        header.forEach { appendLine(it) }
        for (sec in listOf(widgets, log, crashSection)) {
            appendLine()
            appendLine(sec.title)
            sec.lines.forEach { appendLine(it) }
        }
        appendLine()
        append(footer)
    }
    var out = render()
    val marker = "(... older lines left out to keep the report under 60,000 characters)"
    for (sec in listOf(log, crashSection, widgets)) {
        while (out.length > BUG_REPORT_MAX_CHARS) {
            val idx = sec.lines.indexOfLast { it != marker }
            if (idx < 0) break
            sec.lines.removeAt(idx)
            if (marker !in sec.lines) sec.lines += marker
            out = render()
        }
    }
    if (out.length > BUG_REPORT_MAX_CHARS) out = out.take(BUG_REPORT_MAX_CHARS)
    return out
}

private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim()

private fun num(f: Float): String = String.format(Locale.ROOT, "%.2f", f)

private fun durationText(ms: Long): String =
    if (ms < 1000) "$ms ms" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

// ── redaction ──────────────────────────────────────────────────────────────

private val SENSITIVE_KEY = Regex(
    "token|key|auth|secret|pass|password|sig|signature|session|apikey|api_key|access|code",
    RegexOption.IGNORE_CASE,
)
private const val REDACTED = "REDACTED"
private const val MAX_REDACTED_URL_CHARS = 500

/**
 * Removes what must not leave the device from a feed address: the user-info (`user:pass@`) and the VALUES of
 * query (and fragment) parameters whose key contains token, key, auth, secret, pass, sig, session, access or code
 * (case-insensitive, so `access_token`, `X-Amz-Signature`, `sessionid` all match; a few harmless keys such as
 * `keyword` are redacted too, on purpose: over-redacting is the safe direction). Host, path and the other
 * parameters stay, so a Google News search query remains readable. Secrets placed in the path itself cannot be
 * recognised. Never throws: anything unexpected returns a short truncation.
 */
fun redactUrl(url: String): String = try {
    val s = url.trim()
    var rest = s
    // fragment
    var fragment: String? = null
    val hash = rest.indexOf('#')
    if (hash >= 0) { fragment = rest.substring(hash + 1); rest = rest.substring(0, hash) }
    // query
    var query: String? = null
    val q = rest.indexOf('?')
    if (q >= 0) { query = rest.substring(q + 1); rest = rest.substring(0, q) }
    // user-info: inside the authority only
    val schemeEnd = rest.indexOf("://")
    val authStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
    val slash = rest.indexOf('/', authStart).let { if (it < 0) rest.length else it }
    val at = rest.substring(authStart, slash).lastIndexOf('@')
    if (at >= 0) rest = rest.substring(0, authStart) + rest.substring(authStart + at + 1)
    val sb = StringBuilder(rest)
    if (query != null) sb.append('?').append(redactParams(query))
    if (fragment != null) sb.append('#').append(if ('=' in fragment) redactParams(fragment) else fragment)
    sb.toString().let { if (it.length > MAX_REDACTED_URL_CHARS) it.take(MAX_REDACTED_URL_CHARS) + "..." else it }
} catch (_: Throwable) {
    url.take(60) + "... (unreadable address)"
}

private fun redactParams(query: String): String = query.split('&').joinToString("&") { pair ->
    val eq = pair.indexOf('=')
    if (eq < 0) pair else {
        val key = pair.substring(0, eq)
        if (SENSITIVE_KEY.containsMatchIn(key)) "$key=$REDACTED" else pair
    }
}

private val URL_IN_TEXT = Regex("""https?://[^\s"'<>)\]]+""", RegexOption.IGNORE_CASE)
private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")

/** [redactUrl] applied to every URL found inside free text (exception messages, stack traces). */
fun redactUrlsInText(text: String): String = URL_IN_TEXT.replace(text) { redactUrl(it.value) }

/** The host of an address, lower case, without user-info or port; "" when there is none. */
fun hostOf(url: String): String {
    val s = url.trim()
    val start = s.indexOf("://").let { if (it < 0) 0 else it + 3 }
    var end = s.length
    for (c in "/?#") s.indexOf(c, start).let { if (it in 0 until end) end = it }
    var auth = s.substring(start, end)
    auth = auth.substringAfterLast('@')
    if (auth.startsWith("[")) return auth.substringBefore(']').trimStart('[').lowercase()
    return auth.substringBefore(':').lowercase()
}

/**
 * For a report WITHOUT feed addresses: replaces every URL, every known feed host and every IPv4 literal in free
 * text, so an exception message such as `Unable to resolve host "feeds.example.com"` does not leak the address.
 *
 * A host is replaced only as a whole host token: it must not touch a letter, digit, `-` or `.` before it, and
 * must not be followed by a letter, digit, `-` or `.label`. Sub-domains and `www.` variants are replaced as one
 * token (`www.feeds.example.com` -> `<host>`). So a short host such as `t.me` can no longer eat the inside of
 * `java.lang.reflect.Method` (BUG-037). Lines of a stack trace (`at ...`, `Caused by: ...`) hold package and
 * class names, so they only get dotted hosts (`ynet.co.il`), never a bare word such as `news`.
 */
fun scrubAddresses(text: String, knownUrls: List<String>): String {
    val withUrls = URL_IN_TEXT.replace(text, "<address>")
    val hosts = knownUrls.map { hostOf(it) }.filter { it.length >= 3 }
        .flatMap { h -> if (h.startsWith("www.") && h.length - 4 >= 3) listOf(h, h.removePrefix("www.")) else listOf(h) }
        .distinct().sortedByDescending { it.length }
    val out = if (hosts.isEmpty()) withUrls else {
        val all = hostTokenRegex(hosts)
        val dotted = hosts.filter { '.' in it }.let { if (it.isEmpty()) null else hostTokenRegex(it) }
        withUrls.split('\n').joinToString("\n") { line ->
            val isStackLine = line.trimStart().startsWith("at ") || line.contains("Caused by:")
            val rx = if (isStackLine) dotted else all
            if (rx == null) line else rx.replace(line, "<host>")
        }
    }
    return IPV4.replace(out, "<ip>")
}

private fun hostTokenRegex(hosts: List<String>): Regex =
    Regex(
        "(?<![A-Za-z0-9.-])(?:[A-Za-z0-9-]+\\.)*(?:" + hosts.joinToString("|") { Regex.escape(it) } +
            ")(?![A-Za-z0-9-]|\\.[A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
