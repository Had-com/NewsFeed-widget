package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryThreadsTest {

    private val h = 60L * 60 * 1000

    private fun art(
        id: String,
        feed: String,
        title: String,
        at: Long = 1_000_000_000_000L,
        isRead: Boolean = false,
        readAt: Long? = null,
    ) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = title,
        publishedAt = at, isRead = isRead, readAt = readAt,
    )

    private fun ids(t: StoryThread) = t.members.map { it.id }

    // ---- storyTokens -------------------------------------------------------------------

    @Test
    fun `quotes niqqud and punctuation are stripped and stop words dropped`() {
        // Rotter writes quotes as '' , ynet as " - both must reduce to the same words.
        assertEquals(storyTokens("איזנקוט: ''נתניהו פועל'' בחקירה"), storyTokens("איזנקוט: \"נתניהו פועל\" בחקירה"))
        assertTrue("יועמשית" in storyTokens("קורא ליועמ\"שית לפתוח"))
        assertTrue("יועמשית" in storyTokens("קורא ליועמ''שית לפתוח"))
        // niqqud removed, stop words (של את על) dropped
        assertEquals(setOf("צדק", "עולם"), storyTokens("צֶדֶק של את עַל עולם"))
    }

    @Test
    fun `up to two leading prefix letters are stripped from long hebrew words`() {
        val a = storyTokens("בבנימין")
        val b = storyTokens("בנימין")
        val c = storyTokens("ובנימין")
        assertEquals(a, b)
        assertEquals(b, c)
        // 3-letter words are too short to strip: ביט keeps its first letter
        assertTrue("ביט" in storyTokens("ביט"))
    }

    @Test
    fun `numbers are kept whole and one-character tokens dropped`() {
        val t = storyTokens("7/10 הנדון 6.7 מיליארד ו x")
        assertTrue("7/10" in t)
        assertTrue("6.7" in t)
        assertFalse(t.any { it.length < 2 })
    }

    @Test
    fun `a trailing source suffix is dropped`() {
        assertEquals(
            storyTokens("דיווחים בלבנון: צה\"ל תקף בדרום המדינה"),
            storyTokens("דיווחים בלבנון: צה\"ל תקף בדרום המדינה - כאן 11"),
        )
    }

    @Test
    fun `latin text is lower-cased and english stop words dropped`() {
        assertEquals(setOf("openai", "agents"), storyTokens("OpenAI AGENTS the"))
    }

    // ---- storyTokensSimilar ------------------------------------------------------------

    @Test
    fun `three shared words covering sixty percent of the shorter title link`() {
        assertTrue(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1"), setOf("a1", "b1", "c1", "x1")))        // 3/4
        assertTrue(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1", "f1"), setOf("a1", "b1", "c1", "x1", "y1")))  // 3/5 = exactly 60%
    }

    @Test
    fun `fewer than three shared words never link`() {
        assertFalse(storyTokensSimilar(setOf("a1", "b1"), setOf("a1", "b1")))            // a title this short never groups
        assertFalse(storyTokensSimilar(setOf("a1", "b1", "c1", "d1"), setOf("a1", "b1", "x1", "y1")))
    }

    @Test
    fun `three shared words below sixty percent coverage do not link`() {
        assertFalse(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1", "f1"), setOf("a1", "b1", "c1", "x1", "y1", "z1")))  // 3/6 = 50%
    }

    // ---- groupStories ------------------------------------------------------------------

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"

    @Test
    fun `same story from two different feeds forms one thread led by the newest`() {
        val threads = groupStories(listOf(
            art("old", "ynet", attack1, at = 1_000_000_000_000L),
            art("new", "kan", attack2, at = 1_000_000_060_000L),
        ))
        assertEquals(1, threads.size)
        assertEquals("new", threads[0].lead.id)
        assertEquals(listOf("old"), threads[0].others.map { it.id })
        assertEquals(2, threads[0].size)
    }

    @Test
    fun `articles of the same feed never link directly`() {
        val threads = groupStories(listOf(
            art("a", "ynet", attack1),
            art("b", "ynet", attack2, at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
        assertTrue(threads.all { it.others.isEmpty() })
    }

    @Test
    fun `same feed articles join a thread through a third feed (transitive)`() {
        val threads = groupStories(listOf(
            art("y1", "ynet", attack1, at = 1_000_000_000_000L),
            art("k1", "kan", attack2, at = 1_000_000_010_000L),
            art("y2", "ynet", "ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
        ))
        assertEquals(1, threads.size)
        assertEquals(setOf("y1", "k1", "y2"), ids(threads[0]).toSet())
        assertEquals("y2", threads[0].lead.id)
    }

    @Test
    fun `articles more than six hours apart do not link`() {
        val apart = groupStories(listOf(
            art("a", "ynet", attack1, at = 1_000_000_000_000L),
            art("b", "kan", attack2, at = 1_000_000_000_000L + 6 * h + 1),
        ))
        assertEquals(2, apart.size)
        val inside = groupStories(listOf(
            art("a", "ynet", attack1, at = 1_000_000_000_000L),
            art("b", "kan", attack2, at = 1_000_000_000_000L + 6 * h),
        ))
        assertEquals(1, inside.size)
    }

    @Test
    fun `an unread article never joins a thread that was already read`() {
        val threads = groupStories(listOf(
            art("r1", "ynet", attack1, at = 1_000_000_000_000L, isRead = true, readAt = 5L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 5L),
            art("u", "walla", "פיגוע ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
        ))
        assertEquals(2, threads.size)
        val read = threads.first { it.lead.isRead }
        assertEquals(setOf("r1", "r2"), ids(read).toSet())
        assertEquals(listOf("u"), ids(threads.first { !it.lead.isRead }))
    }

    @Test
    fun `articles read at different times stay in separate threads`() {
        val threads = groupStories(listOf(
            art("r1", "ynet", attack1, isRead = true, readAt = 5L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 9L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `lead ties on time are broken by the smaller id`() {
        val threads = groupStories(listOf(
            art("b", "ynet", attack1),
            art("a", "kan", attack2),
        ))
        assertEquals("a", threads[0].lead.id)
    }

    @Test
    fun `threads are capped at twelve members and the older overflow stays single`() {
        val arts = (0 until 15).map { i ->
            art("m$i", "feed$i", if (i % 2 == 0) attack1 else attack2, at = 1_000_000_000_000L + i * 1_000L)
        }
        val threads = groupStories(arts)
        val big = threads.maxByOrNull { it.size }!!
        assertEquals(STORY_MAX_THREAD_SIZE, big.size)
        assertEquals("m14", big.lead.id)
        assertEquals(15 - STORY_MAX_THREAD_SIZE + 1, threads.size)
        assertEquals(15, threads.sumOf { it.size })
    }

    @Test
    fun `output keeps the input order with a thread sitting at its lead`() {
        val input = listOf(
            art("x", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", at = 3_000_000_000_000L),
            art("lead", "kan", attack2, at = 2_000_000_020_000L),
            art("y", "globes", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", at = 2_000_000_015_000L),
            art("member", "ynet", attack1, at = 2_000_000_000_000L),
        )
        val threads = groupStories(input)
        assertEquals(listOf("x", "lead", "y"), threads.map { it.lead.id })
        assertEquals(listOf("member"), threads[1].others.map { it.id })
    }

    @Test
    fun `the partition does not depend on the input order`() {
        val base = listOf(
            art("1", "ynet", attack1, at = 1_000_000_000_000L),
            art("2", "kan", attack2, at = 1_000_000_010_000L),
            art("3", "walla", "ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
            art("4", "ynet", "הבורסה בתל אביב ננעלה בירידות חדות", at = 1_000_000_030_000L),
        )
        fun canon(l: List<ArticleItem>) = groupStories(l).map { ids(it).sorted() }.toSet()
        assertEquals(canon(base), canon(base.reversed()))
        assertEquals(canon(base), canon(listOf(base[2], base[0], base[3], base[1])))
    }

    @Test
    fun `filtering whole read classes out never changes the threads that remain`() {
        // Unread thread {u1,u2}, read thread {r1,r2} (same readAt) and an unrelated single. Every
        // filter (All / Unread only / Read only) keeps or drops whole classes, so the callbacks
        // (which see ALL stored articles) and the render (which sees the filtered list) agree.
        val all = listOf(
            art("u1", "ynet", attack1, at = 1_000_000_020_000L),
            art("u2", "kan", attack2, at = 1_000_000_030_000L),
            art("r1", "ynet", attack1, at = 1_000_000_000_000L, isRead = true, readAt = 7L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 7L),
            art("s", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", at = 1_000_000_040_000L),
        )
        fun canon(l: List<ArticleItem>) = groupStories(l).map { ids(it).sorted() }.toSet()
        assertEquals(canon(all).filter { "u1" in it || "s" in it }.toSet(), canon(all.filter { !it.isRead }))
        assertEquals(canon(all).filter { "r1" in it }.toSet(), canon(all.filter { it.isRead }))
    }

    // ---- near misses that must NOT group (real headlines) -------------------------------

    @Test
    fun `same numbers but different meaning do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", "רוכב אופנוע בן 30 נהרג בתאונת דרכים בירושלים"),
            art("b", "kan", "בן 30 נפצע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `two different security events do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", attack1),
            art("b", "kan", "צה\"ל: חוסל מחבל שהטמין מטענים שהובאו מבית חולים בצפון הרצועה", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `same topic but different stories do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", "איראן: קיבלנו מידע שארה\"ב מתכננת לתקוף - \"הבסיסים שלה יהיו יעד\""),
            art("b", "kan", "ארה״ב הטילה סנקציות על בורסת הקריפטו ששימשה את איראן לגביית עמלה במיצרי הורמוז", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `ungroupedStories wraps every article as its own thread`() {
        val l = listOf(art("a", "f1", "x1 y1 z1"), art("b", "f2", "x1 y1 z1"))
        assertEquals(listOf("a", "b"), ungroupedStories(l).map { it.lead.id })
        assertTrue(ungroupedStories(l).all { it.others.isEmpty() })
    }

    // ---- token cap ---------------------------------------------------------------------

    private fun hugeTitle(words: Int): String {
        // distinct all-letter words (the tokenizer splits letters from digits): aaa, aab, aac, ...
        val sb = StringBuilder()
        for (i in 0 until words) {
            sb.append(('a' + (i / 676) % 26)).append(('a' + (i / 26) % 26)).append(('a' + i % 26)).append(' ')
        }
        return sb.toString()
    }

    @Test
    fun `a huge title yields at most STORY_MAX_TOKENS tokens`() {
        val t = storyTokens(hugeTitle(5_000))
        assertEquals(STORY_MAX_TOKENS, t.size)
        assertTrue("aaa" in t)       // the FIRST tokens are the ones kept
    }

    @Test
    fun `a huge title still groups with a short title sharing 3 tokens from its first 40`() {
        val threads = groupStories(listOf(
            art("big", "f1", hugeTitle(5_000)),
            art("small", "f2", "aaa aab aac", at = 1_000_000_060_000L),
        ))
        assertEquals(1, threads.size)
        assertEquals(setOf("big", "small"), ids(threads[0]).toSet())
    }

    @Test
    fun `shared words only beyond the first 40 tokens do not link a huge title`() {
        // hugeTitle word #100 = "adw" etc. - well past the cap
        val words = hugeTitle(200).trim().split(" ")
        val late = words.subList(100, 103).joinToString(" ")
        val threads = groupStories(listOf(
            art("big", "f1", hugeTitle(5_000)),
            art("small", "f2", late, at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    // ---- read classes / duplicate ids --------------------------------------------------

    @Test
    fun `a read article without readAt never links to one with a readAt stamp`() {
        val title = "alpha beta gamma delta"
        val threads = groupStories(listOf(
            art("old", "f1", title, isRead = true, readAt = null),
            art("new", "f2", title, isRead = true, readAt = 5_000L),
        ))
        assertEquals(2, threads.size)
        // two legacy reads (no readAt) share the -1 class and DO group
        val legacy = groupStories(listOf(
            art("l1", "f1", title, isRead = true, readAt = null),
            art("l2", "f2", title, isRead = true, readAt = null),
        ))
        assertEquals(1, legacy.size)
    }

    @Test
    fun `duplicate ids across two feeds do not crash grouping or layout - they form one thread with two same-id members`() {
        val threads = groupStories(listOf(
            art("dup", "f1", "alpha beta gamma delta"),
            art("dup", "f2", "alpha beta gamma delta"),
        ))
        assertEquals(1, threads.size)
        assertEquals(listOf("dup", "dup"), ids(threads[0]))
        val collapsed = layoutThreads(threads, emptySet(), rowLimit = 10)
        assertEquals(1, collapsed.rows.size)
        assertEquals(2, collapsed.articleCount)
        val expanded = layoutThreads(threads, setOf("dup"), rowLimit = 10)
        assertEquals(listOf(ThreadRowKind.LEAD, ThreadRowKind.MEMBER), expanded.rows.map { it.kind })
    }
}
