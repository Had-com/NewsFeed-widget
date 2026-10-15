package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Third golden set, added with the main-subject rule (2026-10-15, BUG-049): 828 REAL public headlines from 10
 * feeds captured from the widget backups in four windows, 2026-09-19 14:00 to 2026-09-20 07:30 (Yom Kippur eve:
 * the Riyadh / Sanaa explosions, the weather, the fast times), 2026-10-07 22:00 to 2026-10-08 02:30 (the tanker near Qatar,
 * the Maduro indictment), 2026-10-08 11:30 to 14:00 (the Ukraine attacks) and 2026-10-09 00:00 to 2026-10-10 11:40 (the tank
 * missile indictment, the Binyamin road crash, the US plan against Iran, the live execution, the Nobel prize,
 * the electric-bike accidents: the stories the device QA of 2026-10-09 / 10 listed as missed). Columns feedKey,
 * publishedAt epoch ms, title (src/test/resources/story_threads_fixture3.tsv). The golden threads that must and
 * must not form are in [StoryThreadsSubjectTest]; this class pins the totals and writes the review listing to
 * app/build/story-threads-review3.txt.
 */
class StoryThreadsFixture3Test {

    private val articles: List<ArticleItem> by lazy { StoryFixtures.load("story_threads_fixture3.tsv") }
    private val threads: List<StoryThread> by lazy { groupStories(articles) }

    @Test
    fun `fixture loads and every article lands in exactly one thread`() {
        assertEquals(828, articles.size)
        assertEquals(828, threads.sumOf { it.size })
        assertEquals(828, threads.flatMap { it.members }.map { it.id }.toSet().size)
        assertTrue(threads.all { it.size <= STORY_MAX_THREAD_SIZE })
    }

    @Test
    fun `real group count stays in the reviewed range`() {
        val multi = threads.count { it.size > 1 }
        val strongOnly = groupStoriesCore(articles, articles, subjectRule = false).count { it.size > 1 }
        // Reviewed on 2026-10-15: 81 groups; the strong rule alone finds 71 of them. A change outside this
        // band means the matcher was retuned - re-run the review before adjusting it.
        assertEquals(71, strongOnly)
        assertTrue("groups=$multi", multi in 76..86)
    }

    @Test
    fun `write the review listing`() {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
        val sb = StringBuilder()
        val multi = threads.filter { it.size > 1 }
        sb.append("${articles.size} articles, ${multi.size} threads, sizes ${multi.map { it.size }.sorted()}\n")
        for (t in multi) {
            sb.append("---\n")
            for (m in t.members) {
                sb.append("  ${if (m === t.lead) "*" else " "} ${fmt.format(Date(m.publishedAt))} ${m.feedName.padEnd(10)} | ${m.title.take(100)}\n")
            }
        }
        val out = File(System.getProperty("user.dir"), "build/story-threads-review3.txt")
        out.parentFile!!.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        assertTrue(out.exists())
    }
}
