package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Second golden set, added with the BUG-028 similarity revision (2026-10-09): 478 REAL public
 * headlines from 10 feeds captured from the widget backups between 2026-10-07 19:19 and
 * 2026-10-08 19:34 (src/test/resources/story_threads_fixture2.tsv; columns feedKey, publishedAt
 * epoch ms, title). It is a separate file from story_threads_fixture.tsv so the original golden
 * set stays untouched. The review listing goes to app/build/story-threads-review2.txt.
 *
 * Reviewed 2026-10-09: the revised matcher groups exactly what the previous one did on this set
 * (the revision removes reporter / channel-label / generic-word overlaps; none of those formed a
 * thread in this capture) - see docs/BUGS.md BUG-028.
 */
class StoryThreadsFixture2Test {

    private val articles: List<ArticleItem> by lazy { StoryFixtures.load("story_threads_fixture2.tsv") }
    private val threads: List<StoryThread> by lazy { groupStories(articles) }

    private fun find(feed: String, titleStart: String): ArticleItem {
        val hits = articles.filter { it.feedId == StoryFixtures.feedId(feed) && it.title.startsWith(titleStart) }
        assertEquals("fixture lookup '$titleStart' in $feed", 1, hits.size)
        return hits[0]
    }

    private fun threadOf(a: ArticleItem) = threads.first { t -> t.members.any { it.id == a.id } }

    private fun assertSameThread(vararg a: ArticleItem) {
        val lead = threadOf(a[0]).lead.id
        for (x in a) assertEquals(x.title, lead, threadOf(x).lead.id)
    }

    @Test
    fun `fixture loads and every article lands in exactly one thread`() {
        assertEquals(478, articles.size)
        assertEquals(478, threads.sumOf { it.size })
        assertEquals(478, threads.flatMap { it.members }.map { it.id }.toSet().size)
        assertTrue(threads.all { it.size <= STORY_MAX_THREAD_SIZE })
    }

    @Test
    fun `real group count stays in the reviewed range`() {
        val multi = threads.count { it.size > 1 }
        // Reviewed on 2026-10-09: 35 groups (the pre-revision matcher also found 35). 45 since the 2026-10-15
        // main-subject rule (BUG-049): the same 35 (4 of them gained a paraphrased report) plus 10 reviewed new
        // groups (docs/BUGS.md BUG-049). A change outside this band means the matcher was retuned - re-run
        // the review before adjusting it.
        assertEquals(35, groupStoriesCore(articles, articles, subjectRule = false).count { it.size > 1 })
        assertTrue("groups=$multi", multi in 40..50)
    }

    @Test
    fun `the Trump no-strike statement from rotter and both ynet feeds is one thread`() {
        assertSameThread(
            find("ynet-news", "טראמפ: לא נתקוף את איראן"),
            find("rotter", "טראמפ: לא נתקוף באיראן"),
            find("ynet-flash", "טראמפ: לא נתקוף את איראן"),
        )
    }

    @Test
    fun `the Arad shooting joins kan ynet and the N12 reporters chat relay`() {
        // The N12 relay post starts with "<reporter> @ צ'אט כתבים": that lead is stripped, the story words match.
        assertSameThread(
            find("kan-flash", "נער בן 14 וצעיר בן 19 נורו סמוך לערד"),
            find("ynet-flash", "בן 14 נפצע קשה מירי סמוך לערד"),
            find("n12-tg", "איתי גל-און @ צ'אט כתבים נער בן 14"),
        )
    }

    @Test
    fun `the national memorial ceremony reports of kan ynet and N12 are one thread`() {
        assertSameThread(
            find("kan-gnews", "טקס הזיכרון הלאומי של משפחות 7 באוקטובר"),
            find("ynet-flash", "החל טקס הזיכרון הלאומי של משפחות"),
            find("n12-tg", "3 שנים לטבח: ישיר מטקס הזיכרון"),
        )
    }

    @Test
    fun `the Iron Dome interceptor story groups across rotter and the N12 chat despite the reporter name`() {
        assertSameThread(
            find("rotter", "ציון דרך היסטורי: בפעם הראשונה על אדמת ארה"),
            find("n12-chat", "ניצן שפירא בפעם הראשונה על אדמת ארצות הברית"),
        )
    }

    @Test
    fun `the two rimon-rassas incidents are the reviewed residual - one chained thread`() {
        // Known limit, documented in BUG-028: an Ashkelon and a Tel Aviv grenade incident the same night
        // share "רימון רסס / אין נפגעים / הרקע פלילי" - all content words - so they chain into one
        // thread. Only the place names differ; telling places apart is out of scope for a pure
        // token matcher. If this test starts failing the matcher got stricter: update the review note.
        val ashkelon = find("ynet-flash", "רימון רסס הושלך בבניין באשקלון")
        val telAviv = find("ynet-flash", "רימון רסס הושלך ברח")
        assertSameThread(ashkelon, telAviv)
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
        val out = File(System.getProperty("user.dir"), "build/story-threads-review2.txt")
        out.parentFile!!.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        assertTrue(out.exists())
    }
}
