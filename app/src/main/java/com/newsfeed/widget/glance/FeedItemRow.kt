package com.newsfeed.widget.glance

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
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
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.LocalSize
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
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FaviconHelper
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.ThumbnailHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun FeedItemRow(
    article: ArticleItem,
    feedConfig: FeedConfig,
    expandedArticleId: String,
    fontSize: Float,
    articleLength: String = "medium",
    fullArticleId: String = "",
    fullArticleText: String = "",
    useThemeColors: Boolean = false,
    widgetTheme: String = "auto",
    themeVariant: String = "light",
    externalApp: String = "browser",
) {
    val context        = LocalContext.current
    val isExpanded     = article.id == expandedArticleId
    val accentProvider = if (useThemeColors) {
        GlanceTheme.colors.primary
    } else {
        val parsed = runCatching { android.graphics.Color.parseColor(feedConfig.accentColor) }
            .getOrDefault(android.graphics.Color.parseColor("#9B72E3"))
        ColorProvider(Color(parsed))
    }
    val systemIsRtl    = context.resources.configuration.layoutDirection == android.util.LayoutDirection.RTL
    val isRtl          = (feedConfig.layoutDirection == "rtl") xor systemIsRtl
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
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .clickable(toggleAction),
            horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
        ) {
            // Meta row: favicon circle + feed name + timestamp
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = if (isRtl) Alignment.End else Alignment.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                        val initial = (feedConfig.displayName.firstOrNull()?.uppercaseChar() ?: '?').toString()
                        Box(
                            modifier = GlanceModifier
                                .width(circleSize).height(circleSize)
                                .background(accentProvider)
                                .cornerRadius(circleSize / 2),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(initial, style = TextStyle(
                                fontSize   = (8f * fontSize).sp,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                color      = ColorProvider(Color.White),
                            ))
                        }
                    }
                }

                val tsStyle = TextStyle(
                    fontSize    = metaFontSize,
                    fontFamily  = FontFamily.SansSerif,
                    color       = GlanceTheme.colors.onSurfaceVariant,
                )
                val nameStyle = TextStyle(
                    fontSize   = metaFontSize,
                    fontFamily = FontFamily.SansSerif,
                    color      = accentProvider,
                )

                if (isRtl) {
                    // Time always on physical LEFT regardless of RTL direction; the dot+name+
                    // circle group is pushed to hug the physical right edge by the weighted
                    // spacer below (previously this weight sat on the name Text itself, which
                    // fixed a text-overflow bug but broke the grouping — the name's box then
                    // absorbed all leftover space right after the dot instead of the group being
                    // positioned first, leaving the name stranded mid-row instead of beside the
                    // circle on the right). The name now gets a fixed max width instead, so it
                    // still can't overflow, without needing to be the row's one flexible element.
                    val nameMaxWidth = (70f * fontSize).dp
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    Spacer(GlanceModifier.defaultWeight())
                    if (!article.isRead) {
                        Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.width(nameMaxWidth))
                    Spacer(GlanceModifier.width(4.dp))
                    FeedCircle()
                } else {
                    // Time on the left, then circle + name
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    FeedCircle()
                    Spacer(GlanceModifier.width(4.dp))
                    if (!article.isRead) {
                        Box(modifier = GlanceModifier.width(5.dp).height(5.dp).background(accentProvider)) {}
                        Spacer(GlanceModifier.width(3.dp))
                    }
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.defaultWeight())
                }
            }

            Spacer(GlanceModifier.height(3.dp))

            // Headline — Glamour theme uses a custom Hebrew handwriting font (Miriam Libre Bold)
            // rendered to a Bitmap, since Glance/RemoteViews only supports system font families.
            val headlineFontStr = if (feedConfig.fontFamily == "serif" || feedConfig.fontFamily == "mono")
                feedConfig.fontFamily else WidgetThemes.fontFamilyFor(widgetTheme)
            val headlineFontFamily = when (headlineFontStr) {
                "serif"   -> FontFamily.Serif
                "mono"    -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else      -> FontFamily.SansSerif
            }
            if (widgetTheme == "glamer") {
                val density       = context.resources.displayMetrics.density
                val scaledDensity = context.resources.displayMetrics.scaledDensity
                val thumbDp       = if (feedConfig.displayMode == "image" && !isExpanded) 52f * fontSize else 0f
                // Margin was originally estimated at 19dp (3dp accent stripe + 8dp*2 column
                // padding) but measured ~10dp too conservative on a real device: a uiautomator
                // bounds comparison on a properly-sized widget (303dp total) showed the text
                // column actually gets 242dp (303 - 52 thumbnail - 9 true overhead), while this
                // formula was leaving headlines ~16dp narrower than their available column,
                // visibly not reaching the row's edge.
                val widthPx       = ((LocalSize.current.width.value - 9f - thumbDp) * density)
                                        .toInt().coerceAtLeast(50)
                val colorArgb     = if (themeVariant == "dark") 0xFFA08060.toInt()
                                    else                        0xFF7A5C3A.toInt()
                // Below this, there isn't enough room for the bitmap's internal wrapping to stay
                // proportionate to how wide it actually gets displayed (fillMaxWidth() + Fit scale
                // up a too-narrow bitmap into huge, clipped, single-word lines). A plain Text()
                // degrades far more gracefully at extreme widths (native wrap/ellipsis) than the
                // custom bitmap layout does — kept as a guard for genuinely tiny placements now
                // that NewsFeedWidget.sizeMode = Exact reports real widths instead of always 130dp.
                val bmp = if (widthPx >= 120) TextBitmapHelper.headline(
                    context    = context,
                    text       = article.title,
                    textSizePx = headlineSize.value * scaledDensity,
                    colorArgb  = colorArgb,
                    widthPx    = widthPx,
                    isRtl      = isRtl,
                ) else null
                if (bmp != null) {
                    Image(
                        provider           = ImageProvider(bmp),
                        contentDescription = article.title,
                        modifier           = GlanceModifier.fillMaxWidth(),
                        contentScale       = ContentScale.Fit,
                    )
                } else {
                    // Font load failed — render as Text so the headline is never blank
                    Text(
                        text = article.title,
                        style = TextStyle(
                            fontSize   = headlineSize,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Cursive,
                            color      = ColorProvider(Color(colorArgb)),
                            textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                         else       androidx.glance.text.TextAlign.Start,
                        ),
                        maxLines = 3,
                        modifier = GlanceModifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    text = article.title,
                    style = TextStyle(
                        fontSize   = headlineSize,
                        fontWeight = if ("normal" in feedConfig.textStyle) FontWeight.Normal else FontWeight.Bold,
                        fontStyle  = if ("italic" in feedConfig.textStyle) FontStyle.Italic else FontStyle.Normal,
                        textDecoration = if ("underline" in feedConfig.textStyle) TextDecoration.Underline else TextDecoration.None,
                        fontFamily = headlineFontFamily,
                        color = if (article.isRead) GlanceTheme.colors.onSurfaceVariant
                                else GlanceTheme.colors.onSurface,
                        textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                    else      androidx.glance.text.TextAlign.Start,
                    ),
                    maxLines = 3,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }

            // Expanded: show thumbnail as a header image (if feed is in image mode)
            if (isExpanded && feedConfig.displayMode == "image") {
                val thumbFile = ThumbnailHelper.file(context, article.id)
                if (thumbFile.exists()) {
                    val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (bmp != null) {
                        Spacer(GlanceModifier.height(6.dp))
                        Image(
                            provider = ImageProvider(bmp),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = GlanceModifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .cornerRadius(6.dp),
                        )
                    }
                }
            }

            // Expanded: description + Open article button
            if (isExpanded) {
                val resolvedFont = if (feedConfig.fontFamily == "serif" || feedConfig.fontFamily == "mono")
                    feedConfig.fontFamily else WidgetThemes.fontFamilyFor(widgetTheme)
                val feedFontFamily = when (resolvedFont) {
                    "serif"   -> FontFamily.Serif
                    "mono"    -> FontFamily.Monospace
                    "cursive" -> FontFamily.Cursive
                        else      -> FontFamily.SansSerif
                }
                val descStyle = TextStyle(
                    fontSize   = (10f * fontSize).sp,
                    fontFamily = feedFontFamily,
                    color      = GlanceTheme.colors.onSurfaceVariant,
                    textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                 else      androidx.glance.text.TextAlign.Start,
                )

                if (articleLength == "full") {
                    if (fullArticleId == article.id && fullArticleText.isNotBlank()) {
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = fullArticleText,
                            style = descStyle,
                            maxLines = 200,
                            modifier = GlanceModifier.fillMaxWidth(),
                        )
                    } else {
                        if (article.description.isNotBlank()) {
                            Spacer(GlanceModifier.height(4.dp))
                            Text(
                                text = article.description,
                                style = descStyle,
                                maxLines = 50,
                                modifier = GlanceModifier.fillMaxWidth(),
                            )
                        }
                        if (article.articleUrl.isNotBlank()) {
                            Spacer(GlanceModifier.height(6.dp))
                            Text(
                                text = "Load full article ↓",
                                style = TextStyle(
                                    fontSize   = (9f * fontSize).sp,
                                    fontFamily = FontFamily.SansSerif,
                                    color      = accentProvider,
                                ),
                                modifier = GlanceModifier
                                    .background(GlanceTheme.colors.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clickable(
                                        actionRunCallback<FetchFullArticleCallback>(
                                            actionParametersOf(
                                                FetchFullArticleCallback.ARTICLE_ID_KEY          to article.id,
                                                FetchFullArticleCallback.ARTICLE_URL_KEY         to article.articleUrl,
                                                FetchFullArticleCallback.ARTICLE_DESCRIPTION_KEY to article.description,
                                            )
                                        )
                                    ),
                            )
                        }
                    }
                } else {
                    if (article.description.isNotBlank()) {
                        val limit   = if (articleLength == "short") 100 else 400
                        val raw     = article.description
                        val clipped = if (raw.length > limit) raw.take(limit).trimEnd() + "…" else raw
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = clipped,
                            style = descStyle,
                            maxLines = 50,
                            modifier = GlanceModifier.fillMaxWidth(),
                        )
                    }
                }

                if (article.articleUrl.isNotBlank()) {
                    Spacer(GlanceModifier.height(6.dp))
                    // Article rows live inside a LazyColumn, so clicks route through Glance's
                    // list-adapter trampoline (InvisibleActionTrampolineActivity). Building the
                    // Intent at compose time and using actionStartActivity() (rather than a custom
                    // ActionCallback manually calling context.startActivity()) is what makes Browser
                    // mode (a plain ACTION_VIEW) work reliably. Share mode does not: an ACTION_SEND
                    // intent is inherently ambiguous (multiple apps can match), and two different
                    // attempts to fix it directly — dropping Intent.createChooser(), then giving each
                    // row's intent a distinct `data` field to dodge Glance's action-conflation — both
                    // still failed (the second differently: the trampoline now fires but silently
                    // self-finishes without ever launching anything). Rather than keep fighting
                    // Glance's handling of ambiguous/chooser intents, Share mode now targets
                    // ShareRelayActivity — a real, single, unambiguous target within our own app —
                    // which then builds and launches the actual chooser from a proper Activity
                    // context that isn't subject to any of this.
                    val openIntent = if (externalApp == "share") {
                        // Every row targets the same explicit component, so without a distinct
                        // `data` too they'd still be Intent.filterEquals()-identical to each other
                        // and hit the same action-conflation bug this relay was meant to avoid.
                        Intent(context, ShareRelayActivity::class.java)
                            .setData(Uri.parse(article.articleUrl))
                            .putExtra(ShareRelayActivity.EXTRA_ARTICLE_URL, article.articleUrl)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(article.articleUrl))
                    }
                    Text(
                        text = "Open article →",
                        style = TextStyle(
                            fontSize   = (9f * fontSize).sp,
                            fontFamily = FontFamily.SansSerif,
                            color      = accentProvider,
                        ),
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(actionStartActivity(openIntent)),
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
                        modifier = GlanceModifier.width(thumbWidth).fillMaxHeight().padding(vertical = 4.dp),
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
