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
import java.net.HttpURLConnection
import java.net.URL

class FetchFullArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY          = ActionParameters.Key<String>("fetchArticleId")
        val ARTICLE_URL_KEY         = ActionParameters.Key<String>("fetchArticleUrl")
        val ARTICLE_DESCRIPTION_KEY = ActionParameters.Key<String>("fetchArticleDesc")
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
                prefs[WidgetStateKey.fullArticleId]   = articleId
                prefs[WidgetStateKey.fullArticleText] = description
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
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
            conn.connectTimeout = 8_000
            conn.readTimeout    = 12_000
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            // Prefer <article> or <main> tag; fall back to <body>
            val articleRx = Regex("""<article[^>]*>([\s\S]*?)</article>""", RegexOption.IGNORE_CASE)
            val mainRx    = Regex("""<main[^>]*>([\s\S]*?)</main>""",    RegexOption.IGNORE_CASE)
            val bodyRx    = Regex("""<body[^>]*>([\s\S]*?)</body>""",     RegexOption.IGNORE_CASE)

            val raw = articleRx.find(html)?.groupValues?.get(1)
                ?: mainRx.find(html)?.groupValues?.get(1)
                ?: bodyRx.find(html)?.groupValues?.get(1)
                ?: html

            // Remove script/style blocks then strip remaining tags
            val cleaned = raw
                .replace(Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<style[\s\S]*?</style>""",   RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<nav[\s\S]*?</nav>""",       RegexOption.IGNORE_CASE), "")
                .replace(Regex("""<footer[\s\S]*?</footer>""", RegexOption.IGNORE_CASE), "")

            @Suppress("DEPRECATION")
            Html.fromHtml(cleaned, Html.FROM_HTML_MODE_COMPACT)
                .toString()
                .replace(Regex("""\n{3,}"""), "\n\n")
                .trim()
        } catch (e: Exception) {
            "Could not load article: ${e.message}"
        }
    }
}
