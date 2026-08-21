package com.readyou.widget.glance

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import java.util.concurrent.TimeUnit

@Composable
fun FeedItemRow(article: ArticleItem, feedConfig: FeedConfig) {
    val context = LocalContext.current
    val isRtl = feedConfig.layoutDirection == "rtl"
    val accentColor = runCatching { Color.parseColor(feedConfig.accentColor) }
        .getOrDefault(Color.parseColor("#9B72E3"))
    val accentProvider = ColorProvider(
        androidx.compose.ui.graphics.Color(accentColor)
    )

    val stripeModifier = if (isRtl) {
        GlanceModifier.width(3.dp).background(accentProvider)
    } else {
        GlanceModifier.width(3.dp).background(accentProvider)
    }

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(openArticleAction(context, article.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isRtl) {
            ArticleContent(article, feedConfig, accentProvider, isRtl)
            Box(modifier = stripeModifier.height(40.dp)) {}
        } else {
            Box(modifier = stripeModifier.height(40.dp)) {}
            ArticleContent(article, feedConfig, accentProvider, isRtl)
        }
    }
}

@Composable
private fun ArticleContent(
    article: ArticleItem,
    feedConfig: FeedConfig,
    accentColor: ColorProvider,
    isRtl: Boolean,
) {
    val textAlign = if (isRtl) {
        androidx.glance.text.TextAlign.End
    } else {
        androidx.glance.text.TextAlign.Start
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
    ) {
        // Source + time row
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!article.isRead) {
                Box(
                    modifier = GlanceModifier
                        .width(6.dp)
                        .height(6.dp)
                        .background(accentColor),
                ) {}
                Spacer(GlanceModifier.width(4.dp))
            }

            if (isRtl) {
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    text = relativeTime(article.publishedAt),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = textAlign,
                    ),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = article.feedName,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = accentColor,
                        textAlign = textAlign,
                    ),
                )
            } else {
                Text(
                    text = article.feedName,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = accentColor,
                        textAlign = textAlign,
                    ),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = relativeTime(article.publishedAt),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                        textAlign = textAlign,
                    ),
                )
                Spacer(GlanceModifier.defaultWeight())
            }
        }

        Spacer(GlanceModifier.height(2.dp))

        Text(
            text = article.title,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if ("bold" in feedConfig.textStyle) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if ("italic" in feedConfig.textStyle) FontStyle.Italic else FontStyle.Normal,
                textDecoration = if ("underline" in feedConfig.textStyle) TextDecoration.Underline else TextDecoration.None,
                fontFamily = when (feedConfig.fontFamily) {
                    "serif" -> FontFamily.Serif
                    "mono" -> FontFamily.Monospace
                    else -> FontFamily.SansSerif
                },
                color = if (article.isRead) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onSurface,
                textAlign = textAlign,
            ),
            maxLines = 2,
            modifier = GlanceModifier.fillMaxWidth(),
        )
    }
}

private fun openArticleAction(context: android.content.Context, articleId: String) =
    actionStartActivity(
        android.content.Intent(context, com.readyou.widget.DeepLinkActivity::class.java).also {
            it.putExtra("articleId", articleId)
        }
    )

private fun relativeTime(epochMs: Long): String {
    val diff = System.currentTimeMillis() - epochMs
    return when {
        diff < TimeUnit.MINUTES.toMillis(60) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.HOURS.toMillis(24) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)}d"
    }
}
