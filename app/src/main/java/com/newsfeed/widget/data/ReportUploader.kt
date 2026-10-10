package com.newsfeed.widget.data

import android.content.Context
import android.util.Log
import com.newsfeed.widget.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Send to developer": uploads the plain-text problem report (BugReport.kt) to the collector described in
 * docs/REPORT_SERVER.md. Only ever started by the user's tap on a confirmation dialog; nothing is sent in the
 * background, nothing is retried automatically.
 *
 * Everything here except [ReportUploader.send] and [OkHttpReportTransport] is pure and unit tested through the
 * [ReportTransport] interface, so no test needs a network.
 *
 * The server address and the app key are NOT in the repository: the build injects them
 * (`BuildConfig.REPORT_BASE_URL` / `REPORT_APP_KEY`, see app/build.gradle.kts). Neither the key nor the report text is
 * ever logged or put into an error message: [describeResult] and [describeFailure] are the only things that reach
 * logcat, and they hold only a status code, an id and an exception CLASS name.
 */

const val UPLOAD_SCHEMA = 1
const val UPLOAD_MAX_REPORT_CHARS = 60_000
const val UPLOAD_MAX_BODY_BYTES = 80_000
const val UPLOAD_MAX_VERSION_NAME_CHARS = 40
const val UPLOAD_RESPONSE_MAX_BYTES = 4_096L
const val UPLOAD_MAX_RETRY_AFTER_SECONDS = 7L * 24 * 3600
const val UPLOAD_TRUNCATION_MARKER = "(... part of the report left out to fit the upload size limit ...)"
const val REPORT_API_PATH = "/api/v1/reports"

// ── payload ────────────────────────────────────────────────────────────────

/**
 * Removes what the server refuses: control characters other than newline, carriage return and tab (C0, DEL and
 * C1), and unpaired UTF-16 surrogates (they cannot be encoded as UTF-8).
 */
fun sanitizeUploadText(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' || c == '\r' || c == '\t' -> sb.append(c)
            c.code < 0x20 || c.code in 0x7F..0x9F -> Unit
            Character.isHighSurrogate(c) ->
                if (i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { sb.append(c).append(text[i + 1]); i++ }
            Character.isLowSurrogate(c) -> Unit
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString()
}

/** Cuts [text] to at most [limit] chars by dropping the middle (header and footer survive); never splits a surrogate pair. */
fun truncateMiddle(text: String, limit: Int): String {
    if (text.length <= limit) return text
    val keep = limit - UPLOAD_TRUNCATION_MARKER.length - 2
    if (keep <= 0) return text.take(limit.coerceAtLeast(0))
    var head = keep * 3 / 5
    var tail = keep - head
    if (head > 0 && Character.isHighSurrogate(text[head - 1])) head--
    if (tail > 0 && Character.isLowSurrogate(text[text.length - tail])) tail--
    return text.substring(0, head) + "\n" + UPLOAD_TRUNCATION_MARKER + "\n" + text.substring(text.length - tail)
}

private fun jsonBody(report: String, clientId: String, versionCode: Int, versionName: String, now: Long): String =
    JSONObject().apply {
        put("schema", UPLOAD_SCHEMA)
        put("clientId", clientId)
        put("appVersionCode", versionCode)
        put("appVersionName", versionName)
        put("createdAt", now)
        put("report", report)
    }.toString()

private fun utf8Size(s: String): Int = s.toByteArray(Charsets.UTF_8).size

/**
 * The request body (JSON text) for one report. Respects the server limits: report at most 60,000 chars (cut in
 * the middle with a marker if needed), no disallowed control characters, version name at most 40 chars, and the
 * whole body at most 80,000 bytes of UTF-8 (Hebrew is 2 bytes per letter, an emoji 4, `"` and a newline 2 in JSON,
 * so the text is shortened further until the real encoded body fits).
 */
fun buildUploadPayload(report: String, clientId: String, versionCode: Int, versionName: String, now: Long): String {
    val name = truncateMiddleFree(sanitizeUploadText(versionName), UPLOAD_MAX_VERSION_NAME_CHARS)
    var text = truncateMiddle(sanitizeUploadText(report), UPLOAD_MAX_REPORT_CHARS)
    var body = jsonBody(text, clientId, versionCode, name, now)
    var limit = text.length
    while (utf8Size(body) > UPLOAD_MAX_BODY_BYTES && limit > UPLOAD_TRUNCATION_MARKER.length + 16) {
        val over = utf8Size(body) - UPLOAD_MAX_BODY_BYTES
        limit -= maxOf(1, over / 2)
        text = truncateMiddle(text, limit)
        body = jsonBody(text, clientId, versionCode, name, now)
    }
    return body
}

/** Plain cut at [limit] chars without splitting a surrogate pair. */
private fun truncateMiddleFree(s: String, limit: Int): String {
    if (s.length <= limit) return s
    var end = limit
    if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
    return s.substring(0, end)
}

// ── result and mapping ─────────────────────────────────────────────────────

sealed interface UploadResult {
    /** Stored. [id] is the report number the user can quote, null when the answer had no readable id. */
    data class Sent(val id: Long?) : UploadResult
    data class RateLimited(val retryAfterSeconds: Long?) : UploadResult
    object TooLarge : UploadResult
    object Unauthorized : UploadResult
    /** The server answered but did not take the report (400, 404, 405, a redirect, any other unexpected code). */
    data class Rejected(val code: Int) : UploadResult
    data class ServerError(val code: Int) : UploadResult
    /** No answer at all (offline, DNS, timeout, TLS ...). Holds the exception CLASS name only, never its message. */
    data class NetworkError(val errorClass: String) : UploadResult
    /** This build has no usable https address or key; nothing was sent. */
    object Misconfigured : UploadResult
}

/** Maps the server's answer to a result. Never throws; every parse is defensive. */
fun mapResponse(code: Int, body: String?, retryAfterHeader: String?, nowMs: Long = System.currentTimeMillis()): UploadResult =
    when {
        code == 201 -> UploadResult.Sent(parseReportId(body))
        code == 429 -> UploadResult.RateLimited(parseRetryAfterSeconds(retryAfterHeader, nowMs))
        code == 413 -> UploadResult.TooLarge
        code == 401 || code == 403 -> UploadResult.Unauthorized
        code in 500..599 -> UploadResult.ServerError(code)
        else -> UploadResult.Rejected(code)
    }

/** `{"id":42}` -> 42; anything else (malformed, negative, fractional, text) -> null. */
fun parseReportId(body: String?): Long? {
    if (body.isNullOrBlank()) return null
    return try {
        when (val v = JSONObject(body.trim()).opt("id")) {
            is Int -> v.toLong().takeIf { it >= 0 }
            is Long -> v.takeIf { it >= 0 }
            is Number -> v.toDouble().takeIf { it >= 0 && it == Math.floor(it) && it < 9.0e15 }?.toLong()
            is String -> v.trim().toLongOrNull()?.takeIf { it >= 0 }
            else -> null
        }
    } catch (_: Exception) {
        null
    }
}

/** `Retry-After`: whole seconds, or an HTTP date. Null when absent or unreadable; capped at 7 days. */
fun parseRetryAfterSeconds(header: String?, nowMs: Long): Long? {
    val h = header?.trim().orEmpty()
    if (h.isEmpty()) return null
    if (h.all { it in '0'..'9' }) return h.toLongOrNull()?.coerceAtMost(UPLOAD_MAX_RETRY_AFTER_SECONDS)
    return try {
        val at = ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        val seconds = (at.toEpochMilli() - Instant.ofEpochMilli(nowMs).toEpochMilli()) / 1000
        if (seconds > 0) seconds.coerceAtMost(UPLOAD_MAX_RETRY_AFTER_SECONDS) else null
    } catch (_: Exception) {
        null
    }
}

/** What the user reads after a send. */
fun uploadMessage(result: UploadResult): String = when (result) {
    is UploadResult.Sent ->
        if (result.id != null) "Sent. Report #${result.id} — quote this number if you contact the developer." else "Sent."
    is UploadResult.RateLimited -> "Too many reports right now. Try again ${retryWait(result.retryAfterSeconds)}, or use Share report."
    is UploadResult.NetworkError -> "Couldn't reach the server. Check your connection, or use Share report."
    UploadResult.TooLarge -> "The report is too large for the server. Use Share report instead."
    UploadResult.Unauthorized -> "The server did not accept this version of the app. Use Share report instead."
    is UploadResult.Rejected -> "The server did not accept the report. Use Share report instead."
    is UploadResult.ServerError -> "The server had a problem. Try again later, or use Share report instead."
    UploadResult.Misconfigured -> "Sending is not available in this build. Use Share report instead."
}

private fun retryWait(seconds: Long?): String {
    if (seconds == null) return "later"
    val minutes = maxOf(1L, (seconds + 59) / 60)
    return when {
        minutes >= 120 -> "in about ${(minutes + 30) / 60} hours"
        minutes == 1L -> "in 1 minute"
        else -> "in $minutes minutes"
    }
}

/** The only text about a result that may reach logcat: no key, no report text, no address, no exception message. */
fun describeResult(result: UploadResult): String = when (result) {
    is UploadResult.Sent -> "sent id=${result.id}"
    is UploadResult.RateLimited -> "rate_limited retry_after=${result.retryAfterSeconds}"
    is UploadResult.NetworkError -> "network_error ${result.errorClass}"
    UploadResult.TooLarge -> "too_large"
    UploadResult.Unauthorized -> "unauthorized"
    is UploadResult.Rejected -> "rejected code=${result.code}"
    is UploadResult.ServerError -> "server_error code=${result.code}"
    UploadResult.Misconfigured -> "misconfigured"
}

/** An exception reduced to its class name: its message may carry an address, a header or part of the body. */
fun describeFailure(e: Throwable): String = e.javaClass.simpleName.ifBlank { "Exception" }

// ── endpoint (https only) ──────────────────────────────────────────────────

sealed interface EndpointCheck {
    data class Ok(val url: String, val host: String) : EndpointCheck
    data class Bad(val reason: String) : EndpointCheck
}

/**
 * Validates the build's server address: https only, a host, no user-info, no query or fragment. `url` is the
 * reports endpoint (trailing-slash safe, a path prefix is kept), `host` is what the confirmation dialog shows.
 */
fun checkReportBase(base: String): EndpointCheck {
    val b = base.trim()
    if (b.isEmpty()) return EndpointCheck.Bad("empty")
    if (b.any { it.isWhitespace() || it.isISOControl() }) return EndpointCheck.Bad("whitespace")
    val uri = try { URI(b) } catch (_: Exception) { return EndpointCheck.Bad("unreadable") }
    if (!"https".equals(uri.scheme, ignoreCase = true)) return EndpointCheck.Bad("not https")
    val host = uri.host
    if (host.isNullOrBlank()) return EndpointCheck.Bad("no host")
    if (uri.userInfo != null) return EndpointCheck.Bad("user info")
    if (uri.rawQuery != null || uri.rawFragment != null) return EndpointCheck.Bad("query")
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return EndpointCheck.Ok("https://" + host + port + path + REPORT_API_PATH, host + port)
}

/** The server name for the confirmation dialog (host and port only), null when the address is unusable. */
fun reportServerHost(base: String): String? = (checkReportBase(base) as? EndpointCheck.Ok)?.host

/** Whether this build can send at all; when false the button is hidden. */
fun isReportSendConfigured(base: String, key: String): Boolean =
    key.isNotBlank() && checkReportBase(base) is EndpointCheck.Ok

// ── transport ──────────────────────────────────────────────────────────────

data class TransportResponse(val code: Int, val body: String, val retryAfter: String?)

/** The HTTP layer, replaceable by a fake in tests. Throws an exception (normally [IOException]) when there is no answer. */
interface ReportTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse
}

/**
 * Validates, checks the size, posts once through [transport] and maps the answer. Does not retry. A failure to get
 * an answer becomes [UploadResult.NetworkError] holding only the exception class; cancellation is rethrown.
 */
suspend fun uploadReport(transport: ReportTransport, baseUrl: String, appKey: String, payload: String): UploadResult {
    val endpoint = checkReportBase(baseUrl) as? EndpointCheck.Ok ?: return UploadResult.Misconfigured
    if (appKey.isBlank()) return UploadResult.Misconfigured
    if (utf8Size(payload) > UPLOAD_MAX_BODY_BYTES) return UploadResult.TooLarge
    val response = try {
        transport.post(endpoint.url, mapOf("Content-Type" to "application/json", "X-App-Key" to appKey), payload)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return UploadResult.NetworkError(describeFailure(e))
    }
    return mapResponse(response.code, response.body, response.retryAfter)
}

/** OkHttp transport: its own client, 10 s connect / 15 s read and write, no redirects (so a reply can never send the key elsewhere). */
class OkHttpReportTransport(
    private val client: OkHttpClient = defaultClient,
) : ReportTransport {
    override suspend fun post(url: String, headers: Map<String, String>, body: String): TransportResponse =
        withContext(Dispatchers.IO) {
            val contentType = headers["Content-Type"]?.toMediaType()
            // Raw bytes: OkHttp then sends exactly the Content-Type given, without adding a charset.
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.post(body.toByteArray(Charsets.UTF_8).toRequestBody(contentType)).build()
            val call = client.newCall(request)
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { r ->
                            try {
                                // Never read more than a few KB of any answer.
                                val text = r.peekBody(UPLOAD_RESPONSE_MAX_BYTES).string()
                                if (cont.isActive) cont.resume(TransportResponse(r.code, text, r.header("Retry-After")))
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }
                    }
                })
            }
        }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }
    }
}

// ── the app-facing entry point ─────────────────────────────────────────────

object ReportUploader {
    private const val TAG = "ReportUploader"

    /** True when this build was given a usable https address and a key (the Send button is shown only then). */
    val isConfigured: Boolean
        get() = isReportSendConfigured(BuildConfig.REPORT_BASE_URL, BuildConfig.REPORT_APP_KEY)

    /** The server name shown in the confirmation dialog, or null. */
    val serverHost: String?
        get() = reportServerHost(BuildConfig.REPORT_BASE_URL)

    /**
     * Sends [reportText] once (the user has just confirmed). On success the time and report number are remembered
     * for the "Last sent" line. Only the outcome is logged, never the key, the address or the text.
     */
    suspend fun send(context: Context, reportText: String, transport: ReportTransport = OkHttpReportTransport()): UploadResult {
        val app = context.applicationContext
        val store = SharedPrefsReportStore(app)
        val now = System.currentTimeMillis()
        val payload = buildUploadPayload(
            report = reportText,
            clientId = resolveClientId(store),
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            now = now,
        )
        val result = uploadReport(transport, BuildConfig.REPORT_BASE_URL, BuildConfig.REPORT_APP_KEY, payload)
        runCatching { Log.i(TAG, describeResult(result)) }
        if (result is UploadResult.Sent) recordLastSent(store, now, result.id)
        return result
    }
}
