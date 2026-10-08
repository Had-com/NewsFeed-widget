package com.newsfeed.widget.glance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.height
import androidx.glance.layout.width
import com.newsfeed.widget.data.DotKind
import com.newsfeed.widget.data.RAIL_DOT_DP
import com.newsfeed.widget.data.RAIL_LINE_DP
import com.newsfeed.widget.data.RAIL_RING_DP
import com.newsfeed.widget.data.RAIL_WIDTH_DP
import com.newsfeed.widget.data.RailPart
import com.newsfeed.widget.data.railSpecFor

/**
 * The dots of the story timeline, as white-on-transparent bitmaps tinted at display time
 * (`ColorFilter.tint`, the same technique as the headline text bitmaps). Why bitmaps and not the
 * `Box(...).background(color).cornerRadius(r)` circle the feed icon uses: Glance 1.1.0's
 * `cornerRadius` is a logged no-op before API 31 ("Cannot set the rounded corner of views before
 * Api 31", minSdk here is 26), so a Box dot would be a square on Android 8-11, and a hollow ring
 * made from an outer and an inner Box would paint the inner circle in the (possibly translucent)
 * widget background colour over the accent instead of being truly hollow. A bitmap ring has a real
 * transparent centre on every API level and every background opacity.
 */
internal object RailBitmaps {
    // 72 px for a 12 dp ring: a 2 dp stroke is 12 px; scaled down to its dp size by the ImageView.
    private const val SIZE_PX = 72
    private const val RING_STROKE_PX = SIZE_PX * RAIL_LINE_DP / RAIL_RING_DP

    val ring: Bitmap by lazy {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = RING_STROKE_PX.toFloat()
        }
        val half = SIZE_PX / 2f
        Canvas(bmp).drawCircle(half, half, half - RING_STROKE_PX / 2f, paint)
        bmp
    }

    val dot: Bitmap by lazy {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
        val half = SIZE_PX / 2f
        Canvas(bmp).drawCircle(half, half, half, paint)
        bmp
    }
}

/**
 * The story timeline rail of one list row: a [RAIL_WIDTH_DP] dp column holding a 2 dp vertical line
 * and one dot (hollow ring = the thread's lead, filled dot = a member), in the theme's `primary`
 * colour. [part] (see `railPartFor`) says which dot and which line pieces; [dotCenterDp] is the
 * distance from the item's top to the middle of the row's first text line (`railDotCenterDpFor`),
 * so the dot is a FIXED offset from the top and lines up with that line at any font size.
 *
 * Layout, a vertical stack in one narrow Column: [line above, `dotTop` dp tall, or an equally tall
 * Spacer], [dot], [line below, the only fillMaxHeight child]. The line above is a fixed height
 * (item top to dot), so only the line below needs the item's height. The line pieces end exactly at
 * the dot's edge, so the line reads as joining the dots without any overlay. This is placed (by the
 * caller) as a SIBLING of the padded row inside a `Box(contentAlignment = TopEnd)`; it never
 * contains a divider (BUG-2: a divider next to a fillMaxHeight child is not rendered).
 */
@Composable
internal fun ThreadRail(part: RailPart, dotCenterDp: Float) {
    val spec = railSpecFor(part)
    if (spec.dot == DotKind.NONE) return
    val hollow = spec.dot == DotKind.HOLLOW
    val dotDp = if (hollow) RAIL_RING_DP else RAIL_DOT_DP
    val dotTopDp = (dotCenterDp - dotDp / 2f).coerceAtLeast(0f)
    val accent = GlanceTheme.colors.primary
    Column(
        modifier = GlanceModifier
            .width(RAIL_WIDTH_DP.dp)
            .let { if (spec.lineBelow) it.fillMaxHeight() else it },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spec.lineAbove) {
            Box(modifier = GlanceModifier
                .width(RAIL_LINE_DP.dp)
                .height(dotTopDp.dp)
                .background(accent)) {}
        } else {
            Spacer(modifier = GlanceModifier.height(dotTopDp.dp))
        }
        Image(
            provider = ImageProvider(if (hollow) RailBitmaps.ring else RailBitmaps.dot),
            contentDescription = null,
            modifier = GlanceModifier.width(dotDp.dp).height(dotDp.dp),
            colorFilter = ColorFilter.tint(accent),
        )
        if (spec.lineBelow) {
            Box(modifier = GlanceModifier
                .width(RAIL_LINE_DP.dp)
                .fillMaxHeight()
                .background(accent)) {}
        }
    }
}
