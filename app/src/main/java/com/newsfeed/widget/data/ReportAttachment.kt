package com.newsfeed.widget.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure rules for the ONE picture a user may attach to a problem report (Settings > REPORT A PROBLEM > Attach a
 * picture). No Android types here, everything is unit tested; the Android side (decode, re-encode, thumbnail) lives
 * in config/ReportPicture.kt and only calls these.
 *
 * Whatever the user picks (JPEG, PNG, WEBP, HEIC ...) is decoded with sampling, scaled so its long edge is at most
 * [PICTURE_MAX_EDGE], and ALWAYS re-encoded as a JPEG. Re-encoding drops every EXIF tag (location, camera, time).
 * Picture bytes, keys and report text are never logged.
 */

/** Longest edge of the picture that is kept and sent. */
const val PICTURE_MAX_EDGE = 1600

/** The ladder never makes the long edge smaller than this (a picture that is already smaller is left alone). */
const val PICTURE_MIN_EDGE = 800

/** A result up to this size is accepted at once. */
const val PICTURE_TARGET_BYTES = 900_000

/** Above this the picture is refused with a clear message (the server accepts at most 1,500,000). */
const val PICTURE_HARD_MAX_BYTES = 1_400_000

const val PICTURE_START_QUALITY = 80
const val PICTURE_MIN_QUALITY = 40

/** The thumbnail shown in Settings and in the send confirmation. */
const val PICTURE_THUMB_EDGE = 160

data class ImageSize(val width: Int, val height: Int)

/**
 * The size the picture is scaled to: long edge at most [maxEdge], aspect kept, never upscaled, at least 1 px on
 * each side. Non-positive input gives 1 x 1.
 */
fun planImageSize(width: Int, height: Int, maxEdge: Int = PICTURE_MAX_EDGE): ImageSize {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val long = maxOf(w, h)
    if (maxEdge < 1 || long <= maxEdge) return ImageSize(w, h)
    val factor = maxEdge.toDouble() / long
    return ImageSize(
        Math.round(w * factor).toInt().coerceIn(1, maxEdge),
        Math.round(h * factor).toInt().coerceIn(1, maxEdge),
    )
}

/**
 * `BitmapFactory.Options.inSampleSize` for a first, cheap decode: the largest power of two that still leaves the
 * long edge at least [maxEdge] (so the decoded bitmap is at most twice the target, never the full-size photo).
 */
fun inSampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    if (width < 1 || height < 1 || maxEdge < 1) return 1
    val long = maxOf(width, height)
    var sample = 1
    while (long / (sample * 2) >= maxEdge) sample *= 2
    return sample
}

/** One try of the encoder: JPEG [quality] at [scale] of the planned size. */
data class CompressionStep(val quality: Int, val scale: Float)

private val LADDER_QUALITIES = intArrayOf(PICTURE_START_QUALITY, 70, 60, 50)
private val LADDER_SCALES = floatArrayOf(1.0f, 0.75f, 0.5f)

/**
 * The retry ladder. Attempts 0..3 are quality 80, 70, 60, 50 at full size; then the picture is scaled by 0.75 and
 * the four qualities are tried again, then by 0.5; the very last attempt is quality [PICTURE_MIN_QUALITY] at 0.5.
 * Returns null when the ladder is used up (or for a negative attempt).
 */
fun nextCompressionStep(attempt: Int): CompressionStep? {
    if (attempt < 0) return null
    val perRound = LADDER_QUALITIES.size
    val rounds = LADDER_SCALES.size
    return when {
        attempt < perRound * rounds -> CompressionStep(LADDER_QUALITIES[attempt % perRound], LADDER_SCALES[attempt / perRound])
        attempt == perRound * rounds -> CompressionStep(PICTURE_MIN_QUALITY, LADDER_SCALES.last())
        else -> null
    }
}

/** The pixel size of one ladder step: the planned size times the step's scale, but the long edge stays at least [PICTURE_MIN_EDGE] (or the original, when smaller). */
fun planStepSize(sourceWidth: Int, sourceHeight: Int, step: CompressionStep): ImageSize {
    val base = planImageSize(sourceWidth, sourceHeight, PICTURE_MAX_EDGE)
    val baseLong = maxOf(base.width, base.height)
    val wanted = Math.round(baseLong * step.scale.toDouble()).toInt()
    val edge = maxOf(wanted, minOf(baseLong, PICTURE_MIN_EDGE))
    return planImageSize(base.width, base.height, edge)
}

sealed interface PictureEncoding {
    data class Ok(val bytes: ByteArray, val width: Int, val height: Int, val quality: Int) : PictureEncoding
    /** Still above [PICTURE_HARD_MAX_BYTES] after the whole ladder. */
    object TooLarge : PictureEncoding
    /** The encoder itself failed (returned null). */
    object Failed : PictureEncoding
}

/**
 * Runs the ladder against [encode] (width, height, quality -> JPEG bytes, or null on failure). Returns the first
 * result of at most [PICTURE_TARGET_BYTES]; when none gets there, the smallest result if it is at most
 * [PICTURE_HARD_MAX_BYTES], otherwise [PictureEncoding.TooLarge]. Identical attempts (same size and quality, which
 * happens for small pictures where the scale steps clamp) are skipped.
 */
fun compressToBudget(sourceWidth: Int, sourceHeight: Int, encode: (width: Int, height: Int, quality: Int) -> ByteArray?): PictureEncoding {
    var best: PictureEncoding.Ok? = null
    val tried = HashSet<Triple<Int, Int, Int>>()
    var attempt = 0
    while (true) {
        val step = nextCompressionStep(attempt++) ?: break
        val size = planStepSize(sourceWidth, sourceHeight, step)
        if (!tried.add(Triple(size.width, size.height, step.quality))) continue
        val bytes = encode(size.width, size.height, step.quality) ?: return PictureEncoding.Failed
        val candidate = PictureEncoding.Ok(bytes, size.width, size.height, step.quality)
        if (bytes.size <= PICTURE_TARGET_BYTES) return candidate
        if (best == null || bytes.size < best.bytes.size) best = candidate
    }
    return best?.takeIf { it.bytes.size <= PICTURE_HARD_MAX_BYTES } ?: PictureEncoding.TooLarge
}

/** What to do to the decoded pixels so they look the way the camera meant: rotate clockwise by [degrees], then mirror horizontally when [mirror]. */
data class OrientationFix(val degrees: Int, val mirror: Boolean)

/** EXIF Orientation tag (1..8) -> the fix. Anything else (0, unknown) = leave as is. The re-encoded file carries no EXIF, so the pixels must be turned. */
fun orientationFix(exifOrientation: Int): OrientationFix = when (exifOrientation) {
    2 -> OrientationFix(0, true)
    3 -> OrientationFix(180, false)
    4 -> OrientationFix(180, true)
    5 -> OrientationFix(90, true)
    6 -> OrientationFix(90, false)
    7 -> OrientationFix(270, true)
    8 -> OrientationFix(270, false)
    else -> OrientationFix(0, false)
}

// ── files ──────────────────────────────────────────────────────────────────

private const val ATTACHMENT_PREFIX = "attachment-"
private const val ATTACHMENT_SUFFIX = ".jpg"

/** `attachment-20261010-140352.jpg` (local time of [zone]). */
fun attachmentFileName(timeMs: Long, zone: ZoneId): String =
    ATTACHMENT_PREFIX + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(zone)
        .format(Instant.ofEpochMilli(timeMs)) + ATTACHMENT_SUFFIX

/** A file this feature may delete from the reports cache folder: a report text or an attached picture; nothing else. */
fun isReportCacheFileName(name: String): Boolean =
    (name.startsWith("newsfeed-report-") && name.endsWith(".txt")) ||
        (name.startsWith(ATTACHMENT_PREFIX) && name.endsWith(ATTACHMENT_SUFFIX) && name.length > ATTACHMENT_PREFIX.length + ATTACHMENT_SUFFIX.length)

// ── texts ──────────────────────────────────────────────────────────────────

/** Whole kilobytes (1 KB = 1000 bytes), at least 1: `812 KB`. */
fun pictureSizeText(bytes: Int): String = "${((bytes + 500L) / 1000).coerceAtLeast(1)} KB"

fun pictureAttachedText(bytes: Int): String = "Picture attached · ${pictureSizeText(bytes)}"

const val PICTURE_PRIVACY_HINT =
    "The picture is re-saved without location data. It may still show private things — check it before you send or share."

const val PICTURE_IDLE_HINT = "Optional. One picture, re-saved as a small JPEG without location data."

const val PICTURE_ERR_UNREADABLE = "This picture could not be read. Try another one."
const val PICTURE_ERR_TOO_LARGE = "This picture is too large even after shrinking it. Try a smaller one."
const val PICTURE_ERR_MEMORY = "This picture is too big for this phone to process. Try a smaller one."
const val PICTURE_ERR_SAVE = "The picture could not be saved. Try again."

/**
 * The photo picker is opened only when the one-at-a-time slot was taken: [tryBegin] runs synchronously on the tap, BEFORE
 * [launch], so a double tap cannot start two pickers (BUG-045). Returns whether [launch] ran.
 */
inline fun launchOnlyIfFree(tryBegin: () -> Boolean, launch: () -> Unit): Boolean {
    if (!tryBegin()) return false
    launch()
    return true
}
