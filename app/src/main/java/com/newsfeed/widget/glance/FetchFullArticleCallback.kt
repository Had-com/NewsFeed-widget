package com.newsfeed.widget.glance

import android.content.Context
import android.text.Html
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FetchFullArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY          = ActionParameters.Key<String>("fetchArticleId")
        val ARTICLE_URL_KEY         = ActionParameters.Key<String>("fetchArticleUrl")
        val ARTICLE_DESCRIPTION_KEY = ActionParameters.Key<String>("fetchArticleDesc")

        // Glamour's full-article body renders through a Bitmap (the only way to use the
        // Dana Yad font in a RemoteViews-hosted widget at all — see TextBitmapHelper), whose
        // memory cost scales with rendered size. Unbounded fetched-article text could reach
        // several KB, so it's revealed in bounded chunks via "Load more" (LoadMoreArticleCallback)
        // instead of one ever-growing bitmap — each already-revealed chunk stays on screen as
        // its own separate, independently-bounded bitmap.
        const val CHUNK_CHARS = 1200
        // Coarse backstop only — the real, precise cap is computed in FeedItemRow from the
        // widget's actual live width/font size/density (see maxChunksAllowed there), since
        // that's what actually determines each chunk's bitmap memory cost. This constant just
        // stops fullArticleShownChars (stored in prefs, read back here without any
        // composition context to do that precise math) from growing unbounded between updates;
        // FeedItemRow's own coerceAtMost against its computed cap is what actually protects
        // the ~15.5MB RemoteViews bitmap-memory ceiling this project has hit once before.
        const val MAX_CHUNKS  = 8

        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId   = parameters[ARTICLE_ID_KEY]  ?: return
        val articleUrl  = parameters[ARTICLE_URL_KEY] ?: return
        val description = parameters[ARTICLE_DESCRIPTION_KEY] ?: ""

        // Show the RSS description immediately so users see content right away
        if (description.isNotBlank()) {
            updateAppWidgetState(context, glanceId) { prefs ->
                prefs[WidgetStateKey.fullArticleId]          = articleId
                prefs[WidgetStateKey.fullArticleText]        = description
                prefs[WidgetStateKey.fullArticleShownChars]  = CHUNK_CHARS
            }
            NewsFeedWidget().update(context, glanceId)
        }

        // Fetch the full article in the background; update again when done
        val content = withContext(Dispatchers.IO) { fetchContent(articleUrl) }

        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[WidgetStateKey.fullArticleId]   = articleId
            prefs[WidgetStateKey.fullArticleText] = content
        }
        NewsFeedWidget().update(context, glanceId)
    }

    private fun fetchContent(url: String): String {
        return try {
            val request = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "Could not load article: HTTP ${response.code}"
                val body  = response.body ?: return "Could not load article: empty response"
                val bytes = body.bytes()

                // response.body.string()/contentType()?.charset() only knows a charset when
                // the HTTP Content-Type HEADER declares one — many Hebrew news sites (rotter.net
                // included) instead only declare it via an in-HTML <meta charset> tag (verified
                // directly: rotter.net's article pages send a bare "text/html" header with no
                // charset param at all, but the page itself has
                // <meta http-equiv="Content-Type" content="text/html; charset=windows-1255">).
                // OkHttp has no way to see that, so it silently falls back to UTF-8 regardless —
                // this was the actual root cause of the mojibake, not a missing string()/bytes()
                // distinction. Sniff the meta tag from the raw bytes (decoded as ISO-8859-1,
                // which maps every byte 1:1 to a code point and so can never itself corrupt the
                // plain-ASCII "charset=..." text being searched for, regardless of the page's
                // real encoding) and only fall back to the header/UTF-8 when no meta tag exists.
                val headerCharset = body.contentType()?.charset()
                val charset = headerCharset ?: run {
                    val prefixAscii = String(bytes, 0, bytes.size.coerceAtMost(2048), Charsets.ISO_8859_1)
                    val metaCharset = Regex("""charset=["']?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
                        .find(prefixAscii)?.groupValues?.get(1)
                    metaCharset?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
                }
                String(bytes, charset)
            }

            // Prefer <article> or <main> tag; fall back to <body>
            val articleRx = Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
            val mainRx    = Regex("""<main[^>]*>([\s\S]*?)</main>""",    RegexOption.IGNORE_CASE)
            val bodyRx    = Regex("""<body[^>]*>([\s\S]*?)</body>""",     RegexOption.IGNORE_CASE)

            val raw = articleRx.find(html)?.groupValues?.get(1)
                ?: mainRx.find(html)?.groupValues?.get(1)
                ?: bodyRx.find(html)?.groupValues?.get(1)
                ?: html

            // Remove script/style/embed/UI-chrome blocks then strip remaining tags.
            // iframe/object/embed cover ad and widget embeds (e.g. booking/travel widgets)
            // that a bare <body> fallback would otherwise pull in verbatim. select (dropdown
            // pickers, e.g. a forum's "jump to forum" menu) is never article content either —
            // and critically, Html.fromHtml() below doesn't recognize <option> as a block
            // element, so stripping <select> only at the *tag* level (not the whole block)
            // left every dropdown option's text glued directly onto the next with no
            // separator at all (confirmed on a real fetch: "בחר פורום---------סקופיםהזעקה
            // פוליטיקה..." — dozens of distinct forum category names concatenated into one
            // unreadable run). <form> was tried the same way but reverted — verified against
            // a real captured page that a single <form> can span nearly the entire body
            // (old-style forum templates commonly wrap the whole page in one search/reply
            // form), so stripping it wholesale risks deleting genuine article text along
            // with the moderator-controls noise it was meant to remove; not worth that risk
            // for a comparatively minor bit of leftover UI text.
            val cleaned = raw
                .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<style[\s\S]*?</style>""",   RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<nav[\s\S]*?</nav>""",       RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<footer[\s\S]*?</footer>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<aside[\s\S]*?</aside>""",   RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<iframe[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<object[\s\S]*?</object>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<embed[^>]*>""",              RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<svg[\s\S]*?</svg>""",       RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<select[\s\S]*?</select>""", RegexOption.IGNORE_CASE), "")
                // Defensive general fix, not just for <option>: Html.fromHtml() only inserts
                // line breaks/spacing around the specific block tags it recognizes (p/div/br/
                // li/...) — any OTHER tag it doesn't know about (custom elements, tags this
                // parser predates) gets silently deleted with no separator, gluing whatever
                // text was on either side of it together. Surrounding every tag with a space
                // before parsing means no tag boundary can ever glue two words together; any
                // resulting doubled-up whitespace is collapsed below anyway.
                .replace(Regex("""<"""), " <")
                .replace(Regex(""">"""), "> ")

            @Suppress("DEPRECATION")
            Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT)
                .toString()
                // Html.fromHtml() represents each <img> it couldn't strip as an ImageSpan
                // backed by U+FFFC (OBJECT REPLACEMENT CHARACTER) in the resulting text; once
                // converted to a plain String the span is gone but the character remains,
                // rendering as a glyphless "[OBJ]" tofu box since no font here has that glyph.
                .replace("￼", "")
                // Collapse runs of spaces/tabs (leftover HTML-source indentation) to one
                // space, then trim each line — without this, lines that were pure
                // indentation whitespace in the original markup survive as visually blank
                // paragraphs, and real lines carry stray leading/trailing tabs.
                .lines()
                .joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        } catch (e: Exception) {
            "Could not load article: ${e.message}"
        }
    }
}
