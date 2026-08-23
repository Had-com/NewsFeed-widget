package com.readyou.widget.glance

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontStyle
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.readyou.widget.data.ArticleItem
import com.readyou.widget.data.FaviconHelper
import com.readyou.widget.data.FeedConfig
import com.readyou.widget.data.ThumbnailHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun FeedItemRow(
    article: ArticleItem,
    feedConfig: FeedConfig,
    expandedArticleId: String,
    widgetId: Int,
    fontSize: Float,
    articleLength: String = "medium",
) {
    val context        = LocalContext.current
    val isExpanded     = article.id == expandedArticleId
    val accentColor    = runCatching { android.graphics.Color.parseColor(feedConfig.accentColor) }
        .getOrDefault(android.graphics.Color.parseColor("#9B72E3"))
    val accentProvider = ColorProvider(Color(accentColor))
    val isRtl          = feedConfig.layoutDirection == "rtl"
    val metaFontSize   = (9f * fontSize).sp
    val headlineSize   = (13f * fontSize).sp
    // Thumbnail width: square based on font scale (independent of row height)
    val thumbWidth     = (52f * fontSize).dp

    val toggleAction = actionRunCallback<ToggleExpandCallback>(
        actionParametersOf(ToggleExpandCallback.ARTICLE_ID_KEY to article.id)
    )

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(toggleAction),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isRtl) {
            Box(modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentProvider)) {}
        }

        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .clickable(toggleAction),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
        ) {
            // Meta row: favicon circle + feed name + timestamp
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!article.isRead) {
                    Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                    Spacer(GlanceModifier.width(3.dp))
                }

                val circleSize    = (14f * fontSize).dp
                val faviconFile   = FaviconHelper.file(context, feedConfig.feedId)
                val faviconBmp    = if (faviconFile.exists()) BitmapFactory.decodeFile(faviconFile.absolutePath) else null

                @Composable
                fun FeedCircle() {
                    if (faviconBmp != null) {
                        Image(
                            provider = ImageProvider(faviconBmp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .width(circleSize).height(circleSize)
                                .cornerRadius(circleSize / 2),
                        )
                    } else {
                        // Fallback: colored circle with first letter until favicon is downloaded
                        val initial = (feedConfig.displayName.firstOrNull()?.uppercaseChar() ?: '?').toString()
                        Box(
                            modifier = GlanceModifier
                                .width(circleSize).height(circleSize)
                                .background(accentProvider)
                                .cornerRadius(circleSize / 2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initial, style = TextStyle(
                                fontSize = (8f * fontSize).sp,
                                fontWeight = FontWeight.Bold,
                                color = ColorProvider(Color.White),
                            ))
                        }
                    }
                }

                if (isRtl) {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(formatDateTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize, color = GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.width(4.dp))
                    Text(feedConfig.displayName,
                        style = TextStyle(fontSize = metaFontSize, color = accentProvider))
                    Spacer(GlanceModifier.width(4.dp))
                    FeedCircle()
                } else {
                    FeedCircle()
                    Spacer(GlanceModifier.width(4.dp))
                    Text(feedConfig.displayName,
                        style = TextStyle(fontSize = metaFontSize, color = accentProvider))
                    Spacer(GlanceModifier.width(4.dp))
                    Text(formatDateTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize, color = GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }

            Spacer(GlanceModifier.height(3.dp))

            // Headline
            Text(
                text = article.title,
                style = TextStyle(
                    fontSize = headlineSize,
                    fontWeight = if ("bold" in feedConfig.textStyle) FontWeight.Bold else FontWeight.Normal,
                    fontStyle  = if ("italic" in feedConfig.textStyle) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if ("underline" in feedConfig.textStyle) TextDecoration.Underline else TextDecoration.None,
                    fontFamily = when (feedConfig.fontFamily) {
                        "serif" -> FontFamily.Serif
                        "mono"  -> FontFamily.Monospace
                        else    -> FontFamily.SansSerif
                    },
                    color = if (article.isRead) GlanceTheme.colors.onSurfaceVariant
                            else GlanceTheme.colors.onSurface,
                    textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                else      androidx.glance.text.TextAlign.Start,
                ),
                maxLines = 3,
                modifier = GlanceModifier.fillMaxWidth(),
            )

            // Expanded: description + Open article button
            if (isExpanded) {
                if (article.description.isNotBlank()) {
                    // "short" = subtitle/teaser (~1 sentence), "medium" = first paragraph, "full" = everything
                    val limit = when (articleLength) {
                        "short" -> 100
                        "full"  -> Int.MAX_VALUE
                        else    -> 400
                    }
                    val raw        = article.description
                    val clipped    = if (raw.length > limit) raw.take(limit).trimEnd() + "…" else raw
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = clipped,
                        style = TextStyle(
                            fontSize = (10f * fontSize).sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                            textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                        else      androidx.glance.text.TextAlign.Start,
                        ),
                        maxLines = 50,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
                if (article.articleUrl.isNotBlank()) {
                    Spacer(GlanceModifier.height(6.dp))
                    Text(
                        text = "Open article →",
                        style = TextStyle(
                            fontSize = (9f * fontSize).sp,
                            color = accentProvider,
                        ),
                        modifier = GlanceModifier
                            .background(ColorProvider(Color(0x229B72E3)))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(
                                actionRunCallback<OpenExternalCallback>(
                                    actionParametersOf(
                                        OpenExternalCallback.ARTICLE_URL_KEY to article.articleUrl,
                                        OpenExternalCallback.ARTICLE_ID_KEY  to article.id,
                                        OpenExternalCallback.WIDGET_ID_KEY   to widgetId,
                                    )
                                )
                            ),
                    )
                }
            }
        }

        // Thumbnail: fills the full row height so it touches both divider lines
        if (feedConfig.displayMode == "image" && !isExpanded) {
            val thumbFile = ThumbnailHelper.file(context, article.id)
            if (thumbFile.exists()) {
                val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    Image(
                        provider = ImageProvider(bmp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.width(thumbWidth).fillMaxHeight(),
                    )
                }
            }
        }

        if (isRtl) {
            Spacer(GlanceModifier.width(6.dp))
            Box(modifier = GlanceModifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentProvider)) {}
        }
    }
}

private fun formatDateTime(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val articleCal = Calendar.getInstance().also { it.timeInMillis = epochMs }
    val nowCal     = Calendar.getInstance()
    val timeStr    = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
    return if (articleCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
               articleCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)) {
        timeStr
    } else {
        SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
}
