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
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.readyou.widget.data.FeedConfig
import com.readyou.widget.data.ThumbnailHelper
import java.util.concurrent.TimeUnit

@Composable
fun FeedItemRow(
    article: ArticleItem,
    feedConfig: FeedConfig,
    expandedArticleId: String,
    widgetId: Int,
    fontSize: Float,
) {
    val context        = LocalContext.current
    val isExpanded     = article.id == expandedArticleId
    val accentColor    = runCatching { android.graphics.Color.parseColor(feedConfig.accentColor) }
        .getOrDefault(android.graphics.Color.parseColor("#9B72E3"))
    val accentProvider = ColorProvider(Color(accentColor))
    val isRtl          = feedConfig.layoutDirection == "rtl"
    val metaFontSize   = (9f * fontSize).sp
    val headlineSize   = (13f * fontSize).sp

    val toggleAction = actionRunCallback<ToggleExpandCallback>(
        actionParametersOf(ToggleExpandCallback.ARTICLE_ID_KEY to article.id)
    )

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(toggleAction),
        verticalAlignment = Alignment.Top,
    ) {
        if (!isRtl) {
            Box(modifier = GlanceModifier
                .width(3.dp)
                .height(if (isExpanded) 80.dp else 44.dp)
                .background(accentProvider)) {}
        }

        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
        ) {
            // Meta row: feed name + timestamp
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!article.isRead) {
                    Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                    Spacer(GlanceModifier.width(3.dp))
                }
                if (isRtl) {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(relativeTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize, color = GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(article.feedName,
                        style = TextStyle(fontSize = metaFontSize, color = accentProvider))
                } else {
                    Text(article.feedName,
                        style = TextStyle(fontSize = metaFontSize, color = accentProvider))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(relativeTime(article.publishedAt),
                        style = TextStyle(fontSize = metaFontSize, color = GlanceTheme.colors.onSurfaceVariant))
                    Spacer(GlanceModifier.defaultWeight())
                }
            }

            Spacer(GlanceModifier.height(2.dp))

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
                maxLines = 2,
                modifier = GlanceModifier.fillMaxWidth(),
            )

            // Expanded: show full description + Open button
            if (isExpanded) {
                if (article.description.isNotBlank()) {
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = article.description,
                        style = TextStyle(
                            fontSize = (10f * fontSize).sp,
                            color = GlanceTheme.colors.onSurfaceVariant,
                            textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                        else      androidx.glance.text.TextAlign.Start,
                        ),
                        maxLines = 6,
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

        // Thumbnail (compact / non-expanded only)
        if (feedConfig.displayMode == "image" && !isExpanded) {
            val thumbFile = ThumbnailHelper.file(context, article.id)
            if (thumbFile.exists()) {
                val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                if (bmp != null) {
                    Image(
                        provider = ImageProvider(bmp),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = GlanceModifier.width(40.dp).height(40.dp),
                    )
                }
            }
        }

        if (isRtl) {
            Spacer(GlanceModifier.width(6.dp))
            Box(modifier = GlanceModifier
                .width(3.dp)
                .height(if (isExpanded) 80.dp else 44.dp)
                .background(accentProvider)) {}
        }
    }
}

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(60) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.HOURS.toMillis(24)   -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
    }
}
