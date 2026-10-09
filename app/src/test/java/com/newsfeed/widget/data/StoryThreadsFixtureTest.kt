package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the matcher over REAL headlines (src/test/resources/story_threads_fixture.tsv: 294 public
 * headlines from 11 feeds captured on 2026-09-20 and 2026-10-07; columns feedKey, publishedAt epoch
 * ms, title; the second golden set is StoryThreadsFixture2Test). Also writes the group list for
 * human review to app/build/story-threads-review.txt.
 */
class StoryThreadsFixtureTest {

    private val articles: List<ArticleItem> by lazy { StoryFixtures.load("story_threads_fixture.tsv") }

    private val threads: List<StoryThread> by lazy { groupStories(articles) }

    private fun find(feed: String, titleStart: String): ArticleItem {
        val hits = articles.filter { it.feedId == StoryFixtures.feedId(feed) && it.title.startsWith(titleStart) }
        assertEquals("fixture lookup '$titleStart' in $feed", 1, hits.size)
        return hits[0]
    }

    private fun threadOf(a: ArticleItem) = threads.first { t -> t.members.any { it.id == a.id } }

    @Test
    fun `fixture loads and every article lands in exactly one thread`() {
        assertEquals(294, articles.size)
        assertEquals(294, threads.sumOf { it.size })
        assertEquals(294, threads.flatMap { it.members }.map { it.id }.toSet().size)
        assertTrue(threads.all { it.size <= STORY_MAX_THREAD_SIZE })
    }

    @Test
    fun `real group count stays in the reviewed range`() {
        val multi = threads.count { it.size > 1 }
        // Reviewed on 2026-10-07: 27 groups; 28 since the 2026-10-09 similarity revision (BUG-028): the
        // "מבזקן 12" label no longer dilutes the N12 chat flash, so it now joins the N12 channel's
        // twin post about the Neve Tzuf scene. A change outside this band means the matcher was
        // retuned - re-run the review (see the plan's Task 2 Step 8) before adjusting the band.
        assertTrue("groups=$multi", multi in 22..32)
    }

    @Test
    fun `the Binyamin shooting reports from six feeds form one thread`() {
        val a = find("ynet-news", "פצוע קשה בפיגוע ירי במעיין בבנימין")
        val b = find("kan-flash", "פצוע קשה בפיגוע ירי בבנימין")
        val c = find("n12-chat", "ניצן שפירא החשד לפיגוע בבנימין")
        val d = find("globes-all", "פיגוע בבנימין: בן 30 נפצע אנוש")
        val e = find("ynet-flash", "הירי בבנימין: גבר נפצע קשה")
        val t = threadOf(a)
        for (x in listOf(b, c, d, e)) assertTrue(x.title, t.members.any { it.id == x.id })
    }

    @Test
    fun `a Kan google-news headline with a source suffix joins its newsflash twin`() {
        val flash = find("kan-flash", "דיווחים בלבנון: צה\"ל תקף בדרום המדינה")
        val gnews = find("kan-gnews", "דיווחים בלבנון: צה\"ל תקף בדרום המדינה - כאן 11")
        assertEquals(threadOf(flash).lead.id, threadOf(gnews).lead.id)
    }

    @Test
    fun `hebrew prefixes do not stop the stock-market flash and the Globes article joining`() {
        val flash = find("ynet-flash", "הבורסה בתל אביב ננעלה בירידות חדות")
        val globes = find("globes-all", "הבורסה ננעלה בירידות חדות")
        assertEquals(threadOf(flash).lead.id, threadOf(globes).lead.id)
    }

    @Test
    fun `Eisenkot quote with different quote marks in rotter and ynet joins`() {
        val rotter = find("rotter", "איזנקוט: ''נתניהו פועל לזיוף האמת")
        val ynet = find("ynet-flash", "איזנקוט: \"נתניהו פועל לזיוף האמת")
        assertEquals(threadOf(rotter).lead.id, threadOf(ynet).lead.id)
    }

    @Test
    fun `the judo final is one thread of four articles even though two feeds repeat`() {
        val judo = articles.filter { "פרימו" in it.title }
        assertEquals(4, judo.size)
        assertEquals(1, judo.map { threadOf(it).lead.id }.toSet().size)
        assertEquals(4, threadOf(judo[0]).size)
    }

    @Test
    fun `a Jenin ramming attempt is not part of the Binyamin shooting thread`() {
        val jenin = find("ynet-flash", "צה\"ל: ניסיון פיגוע דריסה בצפון השומרון")
        val jeninKan = find("kan-gnews", "ניסיון פיגוע שני תוך שעה")
        val binyamin = find("ynet-news", "פצוע קשה בפיגוע ירי במעיין בבנימין")
        assertEquals(threadOf(jenin).lead.id, threadOf(jeninKan).lead.id)
        assertNotEquals(threadOf(jenin).lead.id, threadOf(binyamin).lead.id)
    }

    @Test
    fun `the Yom Kippur fast-times pieces of walla and the N12 channel are one thread, not tied to an unrelated baby story`() {
        val walla = find("walla", "יום כיפור 2026: זמני הצום ברחבי הארץ")
        val n12 = find("n12-tg", "יום כיפור: זמני כניסת ויציאת הצום")
        val unrelated = find("rotter", "בית שמש: בן 3 לא התעורר משנתו")
        assertEquals(threadOf(walla).lead.id, threadOf(n12).lead.id)
        assertNotEquals(threadOf(walla).lead.id, threadOf(unrelated).lead.id)
    }

    @Test
    fun `the N12 chat flash label does not keep the Neve Tzuf scene posts apart`() {
        // "מבזקן 12 זירת הפיגוע בנווה צוף. צילום: מד"א" vs "<reporter>: תיעוד מזירת הפיגוע בנווה צוף" shared
        // 4 of 7 words (57%) while the flash label counted as two words; without it they link (BUG-028).
        val flash = find("n12-chat", "מבזקן 12 זירת הפיגוע בנווה צוף")
        val doc = find("n12-tg", "גאנם איברהים: תיעוד מזירת הפיגוע בנווה צוף")
        assertEquals(threadOf(flash).lead.id, threadOf(doc).lead.id)
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
        val out = File(System.getProperty("user.dir"), "build/story-threads-review.txt")
        out.parentFile!!.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        assertTrue(out.exists())
    }
}
