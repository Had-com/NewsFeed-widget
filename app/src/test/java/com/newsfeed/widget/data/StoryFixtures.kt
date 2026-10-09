package com.newsfeed.widget.data

/**
 * Loads the real-headline fixtures (src/test/resources/story_threads_fixture*.tsv; columns feedKey,
 * publishedAt epoch ms, title). The two N12 Telegram channels get their real t.me preview URLs as
 * feedId, because byline detection (StoryThreads.kt) only applies to Telegram feeds; every other
 * feed key is used as the feedId unchanged.
 */
internal object StoryFixtures {
    private val telegramIds = mapOf(
        "n12-tg" to "https://t.me/s/N12_News",
        "n12-chat" to "https://t.me/s/N12chat",
    )

    fun feedId(key: String) = telegramIds[key] ?: key

    fun load(resource: String): List<ArticleItem> {
        val text = StoryFixtures::class.java.classLoader!!.getResourceAsStream(resource)!!
            .bufferedReader(Charsets.UTF_8).readText()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, line ->
            val (feed, at, title) = line.split("\t", limit = 3)
            ArticleItem(
                id = "fx$i", feedId = feedId(feed), feedName = feed, title = title,
                publishedAt = at.toLong(), isRead = false,
            )
        }
    }
}
