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
    articleFontSize: Float = 1.0f,
    articleLength: String = "medium",
    fullArticleId: String = "",
    fullArticleText: String = "",
    fullArticleShown: Int = FetchFullArticleCallback.CHUNK_CHARS,
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
    // Each feed's direction is an explicit, absolute per-feed setting (the config screen's
    // RTL/LTR toggle) — it must NOT depend on the device's system locale. This used to XOR
    // against context.resources.configuration.layoutDirection, which silently inverted every
    // feed's direction on a device with its OS language set to Hebrew (or any other RTL
    // system locale): a feed explicitly configured as RTL would compute
    // `true xor true = false` and render LTR, and vice versa for an LTR feed. Invisible in
    // this project's testing since the dev AVD's system locale was always English
    // (`false xor anything` is a no-op) — reported by the user only after testing on a
    // Hebrew-locale device, where justification (and alignment generally) came out backwards.
    val isRtl          = feedConfig.layoutDirection == "rtl"
    val metaFontSize   = (9f * fontSize).sp
    val headlineSize   = (13f * fontSize).sp
    val articleSize    = (10f * articleFontSize).sp
    // Thumbnail width: square based on font scale (independent of row height)
    val thumbWidth     = (52f * fontSize).dp

    // Headline color, hoisted so the expanded-article body text below can reuse the exact
    // same source and always match it (per explicit request: article text should be the
    // same color as the heading, just smaller — previously body text used a separate,
    // intentionally muted color).
    val glamerHeadlineColorArgb = if (themeVariant == "dark") 0xFFEDE4D4.toInt() else 0xFF4A2E14.toInt()
    val nonGlamerHeadlineColor: ColorProvider = when {
        (widgetTheme == "silicon" || widgetTheme == "data_science") && themeVariant != "dark" ->
            ColorProvider(Color(0xFF007870))
        widgetTheme == "aerospace" && themeVariant == "dark" ->
            ColorProvider(Color(0xFFFFE5B4))
        else -> GlanceTheme.colors.onSurface
    }

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
                    // RTL's name box is a fixed width (nameMaxWidth) so it can't push the
                    // circle off the row — without End alignment, a short name sits flush at
                    // the box's start (left) instead of hugging the circle beside it, leaving
                    // a gap whose size varies with how much shorter than nameMaxWidth the name
                    // is. LTR's box is defaultWeight() (starts exactly where the circle ends),
                    // so Start alignment there never produces a gap either way.
                    textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                 else       androidx.glance.text.TextAlign.Start,
                )

                // Feed name is grouped on this same line with the favicon circle, right-
                // justified as a unit. This row is now always the column's full width — the
                // thumbnail (further down) only sits beside the headline lines below, not
                // this meta row — so the name's available space, and thus its position, is
                // consistent whether or not this article has a thumbnail.
                if (isRtl) {
                    // Time always on physical LEFT regardless of RTL direction; the dot+name+
                    // circle group is pushed to hug the physical right edge by the weighted
                    // spacer. Name gets a fixed max width so it can't push the circle off
                    // the row for very long feed names.
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
                    // Time on the left, then circle + name (name absorbs remaining space).
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

            // Headline — Glamour theme uses a custom Hebrew handwriting font (Dana Yad, bold)
            // rendered to a Bitmap, since Glance/RemoteViews only supports system font families.
            val headlineFontStr = if (feedConfig.fontFamily == "serif" || feedConfig.fontFamily == "mono")
                feedConfig.fontFamily else WidgetThemes.fontFamilyFor(widgetTheme)
            val headlineFontFamily = when (headlineFontStr) {
                "serif"   -> FontFamily.Serif
                "mono"    -> FontFamily.Monospace
                "cursive" -> FontFamily.Cursive
                else      -> FontFamily.SansSerif
            }
            val showSideThumb = feedConfig.displayMode == "image" && !isExpanded
            val sideThumbBmp = if (showSideThumb) {
                val thumbFile = ThumbnailHelper.file(context, article.id)
                if (thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
            } else null

            // Thumbnail lives beside the headline only — not the meta row above it — so the
            // meta row (feed name + circle) is always this Column's full width and stays
            // right-justified consistently whether or not this article has a thumbnail.
            // fillMaxHeight() here means the image's height tracks however tall the headline
            // actually renders (1-3 lines), not the whole card.
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(modifier = GlanceModifier.defaultWeight()) {
            if (widgetTheme == "glamer") {
                val density       = context.resources.displayMetrics.density
                val scaledDensity = context.resources.displayMetrics.scaledDensity
                // +8f: the Spacer between the headline and the thumbnail, so the bitmap's
                // width matches its actual available column instead of running under it.
                val thumbDp       = if (feedConfig.displayMode == "image" && !isExpanded) 52f * fontSize + 8f else 0f
                // Margin was originally estimated at 19dp (3dp accent stripe + 8dp*2 column
                // padding) but measured ~10dp too conservative on a real device: a uiautomator
                // bounds comparison on a properly-sized widget (303dp total) showed the text
                // column actually gets 242dp (303 - 52 thumbnail - 9 true overhead), while this
                // formula was leaving headlines ~16dp narrower than their available column,
                // visibly not reaching the row's edge.
                // coerceAtMost(350f): since LocalSize.current now reports the widget's real
                // size (SizeMode.Exact, fixed elsewhere this session) instead of a frozen
                // 130dp, a widely-resized widget (up to maxResizeWidth=500dp) or a high-density
                // device could make each headline bitmap large enough that up to 15 of them
                // together exceed RemoteViews' total bitmap-memory budget — hit for real
                // ("Can't show content", `IllegalArgumentException: ... exceeds maximum bitmap
                // memory usage`) at the config screen's wider 583dp preview panel. Capping the
                // dp width this formula uses (not the final px, so device density still applies
                // normally up to that cap) bounds worst-case memory while leaving every tested,
                // realistic widget size (303dp default, moderate resizes) completely unaffected.
                val widthPx       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f - thumbDp) * density)
                                        .toInt().coerceAtLeast(50)
                // Explicit design values for the Glamour headline. Light matches
                // WidgetThemes.kt's GLAMER_LIGHT.onSurface (#4A2E14 — a visibly brown dark
                // ink, not the near-black #2C1A0A this used to be) so the config-screen
                // preview and the real widget agree; dark is picked independently and
                // doesn't match GLAMER_DARK.onSurface. Hoisted (glamerHeadlineColorArgb)
                // so the expanded-article body text can reuse the identical value.
                val colorArgb     = glamerHeadlineColorArgb
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
                                else nonGlamerHeadlineColor,
                        textAlign = if (isRtl) androidx.glance.text.TextAlign.End
                                    else      androidx.glance.text.TextAlign.Start,
                    ),
                    maxLines = 3,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
            }
            if (showSideThumb && sideThumbBmp != null) {
                Spacer(GlanceModifier.width(8.dp))
                Image(
                    provider = ImageProvider(sideThumbBmp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // Slightly shorter than the headline's own height (vertical padding)
                    // rather than stretching edge-to-edge with it.
                    modifier = GlanceModifier.width(thumbWidth).fillMaxHeight().padding(vertical = 6.dp),
                )
            }
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
                // Article/body text matches the headline's color exactly (same source val,
                // including the isRead dimming) — only font size differs, per explicit
                // request. feedFontFamily is already identical to headlineFontFamily (both
                // derive from the same resolvedFont/headlineFontStr logic), so type already
                // matched; color previously didn't (this used to be a deliberately muted
                // onSurfaceVariant/secondary shade).
                val descStyle = TextStyle(
                    fontSize   = articleSize,
                    fontFamily = feedFontFamily,
                    color      = if (article.isRead) GlanceTheme.colors.onSurfaceVariant
                                 else nonGlamerHeadlineColor,
                    textAlign  = if (isRtl) androidx.glance.text.TextAlign.End
                                 else      androidx.glance.text.TextAlign.Start,
                )

                // Glamour renders body text with the same handwriting font as the headline
                // (regular weight, not bold — the headline is bold specifically to stand out
                // from the body), for short snippets AND the unbounded "full article" fetch —
                // converting to a bitmap trades RemoteViews' cheap TextView rendering for a
                // Bitmap, whose memory cost scales with width × height × 4 bytes, so both the
                // input length (maxChars) and the rendered line count are bounded regardless
                // of the caller's request. Line count specifically is derived from a fixed
                // pixel-height budget divided by the actual line height (which already
                // accounts for articleFontSize and device density) rather than a flat number,
                // so it stays safe at every combination of those settings instead of only the
                // ones this was tested at — a fixed line count could still blow the budget at
                // a high articleFontSize + high-density combination a flat cap wouldn't catch.
                @Composable
                fun DescriptionText(text: String, maxLines: Int, maxChars: Int = 400) {
                    if (widgetTheme == "glamer") {
                        val density       = context.resources.displayMetrics.density
                        val scaledDensity = context.resources.displayMetrics.scaledDensity
                        // Same 350dp cap as the headline bitmap above, and for the same
                        // reason — bounds worst-case RemoteViews bitmap memory at wide/resized
                        // widths without affecting any tested realistic widget size.
                        val widthPx       = ((LocalSize.current.width.value.coerceAtMost(350f) - 9f) * density)
                                                .toInt().coerceAtLeast(50)
                        // 600px (not the 900px this used before "Load more" existed): short/
                        // medium mode is unaffected either way since its own maxLines=10 was
                        // already the tighter constraint, but the "full" article mode's chunks
                        // can now stack multiple independently-bounded bitmaps at once (up to
                        // MAX_CHUNKS), so each one needs a smaller individual budget to keep
                        // the total safe alongside the other rows' headline bitmaps.
                        val lineHeightPx  = 10f * articleFontSize * scaledDensity * 1.2f
                        val safeMaxLines  = (600f / lineHeightPx).toInt().coerceIn(4, maxLines)
                        // Same color as the headline (glamerHeadlineColorArgb, hoisted above)
                        // — only size differs, per explicit request. Previously body text
                        // used a separate, deliberately lighter/warmer color.
                        val safeText = text.take(maxChars)
                        val bmp = if (widthPx >= 120) TextBitmapHelper.paragraph(
                            context    = context,
                            text       = safeText,
                            textSizePx = 10f * articleFontSize * scaledDensity,
                            colorArgb  = glamerHeadlineColorArgb,
                            widthPx    = widthPx,
                            isRtl      = isRtl,
                            maxLines   = safeMaxLines,
                        ) else null
                        if (bmp != null) {
                            Image(
                                provider           = ImageProvider(bmp),
                                contentDescription = safeText,
                                modifier           = GlanceModifier.fillMaxWidth(),
                                contentScale       = ContentScale.Fit,
                            )
                            return
                        }
                    }
                    // Non-Glamour themes (or a failed bitmap render) bear no bitmap-memory
                    // cost, so they get the full, uncapped text/line count here regardless of
                    // maxChars — that cap only exists to bound the Glamour bitmap above.
                    Text(text = text, style = descStyle, maxLines = maxLines, modifier = GlanceModifier.fillMaxWidth())
                }

                if (articleLength == "full") {
                    if (fullArticleId == article.id && fullArticleText.isNotBlank()) {
                        Spacer(GlanceModifier.height(4.dp))
                        if (widgetTheme == "glamer") {
                            // Chunked pagination: each already-revealed CHUNK_CHARS-sized
                            // slice renders as its own independently-bounded bitmap (same
                            // font/color as the headline), rather than one bitmap sized to
                            // the whole unbounded fetch — see FetchFullArticleCallback for
                            // why. "Load more" (LoadMoreArticleCallback) reveals the next
                            // chunk, up to MAX_CHUNKS.
                            val shown = fullArticleShown.coerceAtMost(fullArticleText.length)
                            var chunkStart = 0
                            while (chunkStart < shown) {
                                val chunkEnd = (chunkStart + FetchFullArticleCallback.CHUNK_CHARS).coerceAtMost(shown)
                                if (chunkStart > 0) Spacer(GlanceModifier.height(6.dp))
                                DescriptionText(
                                    fullArticleText.substring(chunkStart, chunkEnd),
                                    maxLines  = 30,
                                    maxChars  = FetchFullArticleCallback.CHUNK_CHARS,
                                )
                                chunkStart = chunkEnd
                            }
                            val atCap = shown >= FetchFullArticleCallback.CHUNK_CHARS * FetchFullArticleCallback.MAX_CHUNKS
                            if (shown < fullArticleText.length && !atCap) {
                                Spacer(GlanceModifier.height(6.dp))
                                Text(
                                    text = "Load more ↓",
                                    style = TextStyle(
                                        fontSize   = (9f * fontSize).sp,
                                        fontFamily = FontFamily.SansSerif,
                                        color      = accentProvider,
                                    ),
                                    modifier = GlanceModifier
                                        .background(GlanceTheme.colors.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                        .clickable(
                                            actionRunCallback<LoadMoreArticleCallback>(
                                                actionParametersOf(LoadMoreArticleCallback.ARTICLE_ID_KEY to article.id)
                                            )
                                        ),
                                )
                            }
                        } else {
                            // Non-Glamour body text is cheap plain-Text with no bitmap
                            // memory cost, so it renders the whole fetched article at once —
                            // pagination only exists to bound Glamour's bitmap rendering.
                            Text(
                                text = fullArticleText,
                                style = descStyle,
                                maxLines = 200,
                                modifier = GlanceModifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        if (article.description.isNotBlank()) {
                            Spacer(GlanceModifier.height(4.dp))
                            val clipped = article.description.take(400).trimEnd()
                            DescriptionText(clipped, maxLines = 10)
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
                        DescriptionText(clipped, maxLines = 10)
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
