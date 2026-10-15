package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The main-subject rule (BUG-049, 2026-10-15): "articles group when they share the same article main subject".
 * User report 22: similar articles were not all collapsed into the thread. The strong rule (3 shared words, 60%)
 * misses paraphrases that share only the two words that identify the story (a name and the event noun); the
 * subject rule weighs words by their rarity in the list, links such pairs, and keeps different incidents of the
 * same type apart. See the class comment of StoryThreads.kt and docs/BUGS.md BUG-049.
 *
 * Golden sets: REAL headlines of three public fixtures (story_threads_fixture*.tsv) read through the real
 * matcher. The results depend on the list the statistics come from (rare words are rare relative to the list), so
 * each test names its list: a widget keeps up to 300 articles, so the two slices of fixture 3 below are the
 * realistic lists; fixtures 1 and 2 are used whole as they were reviewed.
 */
class StoryThreadsSubjectTest {

    // ---- helpers -----------------------------------------------------------------------

    private class Ctx(val list: List<ArticleItem>) {
        val threads: List<StoryThread> by lazy { groupStories(list) }
        val strongOnly: List<StoryThread> by lazy { groupStoriesCore(list, list, subjectRule = false) }

        fun find(feed: String, titleStart: String): ArticleItem {
            val hits = list.filter { it.feedId == StoryFixtures.feedId(feed) && it.title.startsWith(titleStart) }
            assertEquals("lookup '$titleStart' in $feed", 1, hits.size)
            return hits[0]
        }

        fun leadOf(a: ArticleItem, ts: List<StoryThread> = threads): String =
            ts.first { t -> t.members.any { it.id == a.id } }.lead.id

        fun sameThread(vararg a: ArticleItem) = a.map { leadOf(it) }.toSet().size == 1
        fun sameThreadStrong(vararg a: ArticleItem) = a.map { leadOf(it, strongOnly) }.toSet().size == 1
        fun sizeOf(a: ArticleItem) = threads.first { t -> t.members.any { it.id == a.id } }.size
    }

    private fun fixture(name: String) = Ctx(StoryFixtures.load(name))

    /** A widget-sized window of fixture 3 (newest first): rows [skip, skip + take). */
    private fun fx3(skip: Int, take: Int) = Ctx(StoryFixtures.load("story_threads_fixture3.tsv").drop(skip).take(take))

    /** fixture 3 rows 0..299: 2026-10-10 11:35 back to 2026-10-09 09:05 (the Oct 9 morning, e-bike, matador stories). */
    private val newest300: Ctx by lazy { fx3(0, 300) }

    /** fixture 3 rows 100..399: the night and morning of 2026-10-09 (Iran plan, execution, Binyamin road crash, UK election). */
    private val oct9night: Ctx by lazy { fx3(100, 300) }

    /** The whole of fixture 3 (828 articles: the Yom Kippur eve, two evenings of 7 / 8 October and 9 / 10 October). */
    private val fx3All: Ctx by lazy { fixture("story_threads_fixture3.tsv") }

    private val fx1: Ctx by lazy { fixture("story_threads_fixture.tsv") }
    private val fx2: Ctx by lazy { fixture("story_threads_fixture2.tsv") }

    // ---- recall: stories the strong rule left in pieces ------------------------------------

    @Test
    fun `the tank-missile indictment from four feeds is one thread, the strong rule found two of the four reports`() {
        val c = newest300
        val reports = listOf(
            c.find("n12-chat", "אור רביד מטאדור צה\"לי - בידי שב\"ח מיו\"ש"),
            c.find("ynet-news", "הפשיטה החריגה של המשטרה: טיל מטאדור"),
            c.find("ynet-flash", "אישום נגד שלושה צעירים: החזיקו בטיל"),
            c.find("rotter", "שלושה צעירים תועדו עם טיל"),
        )
        assertTrue("subject rule", c.sameThread(*reports.toTypedArray()))
        assertFalse("strong rule alone left them in pieces", c.sameThreadStrong(*reports.toTypedArray()))
        // Known limit (BUG-049): N12 reported the case twice (09:29 and the follow-up at 11:31) and posted it on the
        // Telegram relay too, so every other feed's link to "the" N12 article is ambiguous and is not trusted; those two
        // N12 posts stay out of the thread rather than risk tying a different story to it.
        val relay = c.find("n12-tg", "אור רביד @ צ'אט כתבים 📷 שלושה צעירים הואשמו")
        val followUp = c.find("n12-chat", "אור רביד במשטרה חושדים: השב\"ח")
        assertEquals(4, c.sizeOf(reports[0]))
        assertNotEquals(c.leadOf(relay), c.leadOf(reports[0]))
        assertNotEquals(c.leadOf(followUp), c.leadOf(reports[0]))
    }

    @Test
    fun `the US three-day Iran plan reported by six feeds is one thread (it was two)`() {
        val c = oct9night
        val reports = listOf(
            c.find("ynet-news", "ארה\"ב נערכת למתקפה של 3 ימים באיראן"),
            c.find("ynet-flash", "דיווח: ארה\"ב נערכת למתקפה של 3 ימים"),
            c.find("kan-flash", "דיווח: ארה\"ב הכינה תוכנית למתקפה אינטנסיבית בת שלושה ימים"),
            c.find("n12-tg", "דיווח: הפנטגון גיבש תוכנית למערכה"),
            c.find("rotter", "ניו יורק טיימס: הפנטגון גיבש תוכנית בת 3 ימים"),
        )
        assertTrue(c.sameThread(*reports.toTypedArray()))
        assertFalse(c.sameThreadStrong(*reports.toTypedArray()))
    }

    @Test
    fun `Kan's report of the live execution joins the thread of the other feeds`() {
        val c = oct9night
        val kan = c.find("kan-flash", "החלטה חריגה בארה\"ב: הקצין הפלסטיני-אמריקני")
        val others = listOf(
            c.find("n12-tg", "החלטה חסרת תקדים בארה\"ב: המחבל הפלסטיני"),
            c.find("ynet-news", "הוצאה להורג בשידור חי: כיתת יורים"),
            c.find("ynet-flash", "ארה\"ב: ההוצאה להורג של המחבל ממוצא פלסטיני"),
        )
        assertTrue(c.sameThread(kan, *others.toTypedArray()))
        assertFalse(c.sameThreadStrong(kan, *others.toTypedArray()))
    }

    @Test
    fun `the taxi driver killed in the Binyamin road crash joins the thread of the crash`() {
        val c = oct9night
        val ynet = c.find("ynet-flash", "נהג מונית נהרג בתאונה עם אוטובוס בבנימין")
        val others = listOf(
            c.find("n12-chat", "מבזקן 12 גבר בן 35 נהרג בתאונת דרכים"),
            c.find("n12-tg", "הרוג בתאונה בין רכב לאוטובוס"),
            c.find("kan-flash", "גבר בשנות ה-30 לחייו נהרג בתאונה"),
        )
        assertTrue(c.sameThread(ynet, *others.toTypedArray()))
        assertFalse(c.sameThreadStrong(ynet, *others.toTypedArray()))
    }

    @Test
    fun `the tanker attacked near Qatar, three reports from two feeds, are one thread`() {
        val c = fx3All
        val a = c.find("ynet-flash", "דיווח: נפגעים בתקיפת מכלית צפונית לקטאר")
        val b = c.find("n12-chat", "קרן בצלאל מרכז הסחר הימי הבריטי")
        val d = c.find("n12-chat", "דסק החוץ מרכז הסחר הימי הבריטי")
        assertTrue(c.sameThread(a, b, d))
        assertFalse(c.sameThreadStrong(a, b, d))
    }

    @Test
    fun `Syria considering aid to Saudi Arabia, ynet news and ynet flash, is one thread`() {
        val c = fx2
        val a = c.find("ynet-news", "נגד החות'ים: סוריה שוקלת להעניק סיוע לסעודיה")
        val b = c.find("ynet-flash", "דיווח: סוריה שוקלת לספק סיוע צבאי לסעודיה")
        assertTrue(c.sameThread(a, b))
        assertFalse(c.sameThreadStrong(a, b))
    }

    @Test
    fun `an article that resembles the thread but not its lead joins it (report 22)`() {
        // Walla's Ed Sheeran headline shares few words with the newest report of the thread but most of its
        // name and subject words with the thread as a whole.
        val c = fx1
        val walla = c.find("walla", "אד שירן מגיב לפרשה על הבמה")
        val kan = c.find("kan-flash", "אד שירן בתגובה לסערת מאקלמור")
        val gnews = c.find("kan-gnews", "אד שירן על סערת מקלמור")
        assertTrue(c.sameThread(walla, kan, gnews))
        assertFalse(c.sameThreadStrong(walla, kan, gnews))
    }

    @Test
    fun `the leaked education data is one thread across ynet kan globes and rotter`() {
        val c = fx2
        val a = c.find("kan-gnews", "הרשות להגנת הפרטיות: משרד החינוך הדליף")
        val b = c.find("globes-all", "משרד החינוך הפר את החוק")
        val d = c.find("rotter", "''אירוע אבטחה חמור'': מידע רגיש על 21 אלף")
        assertTrue(c.sameThread(a, b, d))
    }

    // ---- precision: different things must stay apart ------------------------------------------

    @Test
    fun `two electric-bike accidents of the same night are two threads`() {
        val c = newest300
        val tayibe = c.find("ynet-flash", "רוכב אופניים חשמליים נפצע קשה ברכיבה בטייבה")
        val bitHanan = c.find("kan-flash", "רוכב אופניים חשמליים בן 38 נפצע בינוני סמוך לבית חנן")
        assertNotEquals(c.leadOf(tayibe), c.leadOf(bitHanan))
        // the Tayibe accident itself is one thread of three feeds (the strong rule already knew)
        assertTrue(c.sameThread(tayibe, c.find("n12-chat", "מבזקן 12 רוכב אופניים חשמליים כבן 20"), c.find("kan-flash", "בן 20 מטייבה")))
    }

    @Test
    fun `the Kiryat Gat and Sderot incidents do not join the Ashkelon grenade thread`() {
        val c = fx2
        val ashkelon = c.find("kan-flash", "המשטרה: רימון רסס הושלך לעבר בניין באשקלון")
        val kiryatGat = c.find("ynet-flash", "רימון רסס הושלך לבית בקריית גת")
        val sderot = c.find("n12-chat", "איתי גל-און חשד להצתת עסק בשדרות")
        assertNotEquals(c.leadOf(ashkelon), c.leadOf(kiryatGat))
        assertNotEquals(c.leadOf(ashkelon), c.leadOf(sderot))
        assertEquals("Kiryat Gat stays a single", 1, c.sizeOf(kiryatGat))
        assertEquals("Sderot stays a single", 1, c.sizeOf(sderot))
    }

    @Test
    fun `an attack on Kyiv and the Kramatorsk bus attack are different stories`() {
        val c = fx3All
        val kyiv = c.find("ynet-flash", "אוקראינה: שלושה הרוגים במתקפה רוסית בקייב")
        val kramatorsk = c.find("ynet-flash", "התקיפה במזרח אוקראינה: מניין ההרוגים עלתה")
        val kan = c.find("kan-flash", "\"לפחות 33 הרוגים\"")
        assertNotEquals(c.leadOf(kyiv), c.leadOf(kramatorsk))
        assertNotEquals(c.leadOf(kyiv), c.leadOf(kan))
    }

    @Test
    fun `a pair that only shares a phrase and a generic news word stays apart`() {
        val c = fx2
        // "AI" in two unrelated stories (a stock-market piece and a film trailer)
        val globes = c.find("globes-all", "הבינה המלאכותית דוחקת הצידה")
        val rotter = c.find("rotter", "הסרט הבינה המלאכותית (AI)")
        assertNotEquals(c.leadOf(globes), c.leadOf(rotter))
        // "seriously injured" in a wedding shooting and in a street accident
        val wedding = c.find("ynet-news", "נער נורה בחתונה ונפצע קשה")
        val haifa = c.find("ynet-flash", "קשיש נפצע קשה בתאונה בחיפה")
        assertNotEquals(c.leadOf(wedding), c.leadOf(haifa))
    }

    @Test
    fun `the same person in two different events inside one window stays apart`() {
        val c = fx2
        // Trump: the call to Netanyahu (21:48) and the request for strike plans against Iran (23:03).
        val call = c.find("ynet-flash", "טראמפ שוחח עם נתניהו")
        val plans = c.find("ynet-flash", "דיווח: טראמפ ביקש לגבש תוכניות לתקיפה באיראן")
        assertNotEquals(c.leadOf(call), c.leadOf(plans))
        val rotterPlans = c.find("rotter", "אטלנטיק מדווח: הבית הלבן ביקש מהפנטגון")
        assertNotEquals(c.leadOf(call), c.leadOf(rotterPlans))
    }

    @Test
    fun `three-year anniversary pieces of different stories do not group`() {
        val c = fx1
        val kan = c.find("kan-gnews", "היום לפני שלוש שנים: בחזרה לשבעה באוקטובר")
        val rotter = c.find("rotter", "ראש ממשלת הולנד מציין שלוש שנים לטבח")
        assertNotEquals(c.leadOf(kan), c.leadOf(rotter))
    }

    @Test
    fun `the weather forecast and the Yom Kippur fast times stay two threads (chain check)`() {
        // Sep 19 14:00 - Sep 20 07:30 (fixture 3) followed by Sep 20 07:30 - 13:30 (fixture 1): the night's weather
        // reports, the fast-time pieces and a walla piece about both topics in one list.
        val sep21 = 1_789_938_000_000L   // 2026-09-21 00:00 in Israel: everything of the Yom Kippur eve and day before it
        val night = StoryFixtures.load("story_threads_fixture3.tsv").filter { it.publishedAt < sep21 }
        val morning = StoryFixtures.load("story_threads_fixture.tsv").filter { it.publishedAt < sep21 }
        val list = (night.mapIndexed { i, a -> a.copy(id = "n$i") } + morning.mapIndexed { i, a -> a.copy(id = "m$i") })
            .sortedWith(NEWEST_FIRST)
        val c = Ctx(list)
        val forecast = c.list.filter { "תחזית מזג האוויר: עומס חום במהלך יום כיפור" in it.title || "מזג האוויר ביום כיפור" in it.title || "עומסי חום במהלך הצום" in it.title }
        val fastTimes = c.list.filter { "זמני כניסת ויציאת הצום" in it.title || "זמני הצום ברחבי הארץ" in it.title }
        assertTrue("forecast pieces ${forecast.size}", forecast.size >= 3)
        assertTrue("fast-time pieces ${fastTimes.size}", fastTimes.size >= 2)
        val forecastLeads = forecast.map { c.leadOf(it) }.toSet()
        for (t in fastTimes) assertFalse("a fast-time piece joined a forecast thread: ${t.title}", c.leadOf(t) in forecastLeads)
    }

    // ---- structure ---------------------------------------------------------------------------

    @Test
    fun `no thread ever mixes read classes or exceeds the cap on any fixture slice`() {
        for (c in listOf(fx1, fx2, newest300, oct9night)) {
            assertEquals(c.list.size, c.threads.sumOf { it.size })
            assertTrue(c.threads.all { it.size <= STORY_MAX_THREAD_SIZE })
            // every thread's lead is its newest member, ties by the smaller id
            for (t in c.threads) assertTrue(t.members.all { NEWEST_FIRST.compare(t.lead, it) <= 0 })
        }
    }

    @Test
    fun `the strong rule alone still gives the groups reviewed on 2026-10-09`() {
        assertEquals(28, fx1.strongOnly.count { it.size > 1 })
        assertEquals(35, fx2.strongOnly.count { it.size > 1 })
    }

    @Test
    fun `the subject rule only ever adds articles to threads, it never takes members away from a strong thread`() {
        for (c in listOf(fx1, fx2, newest300, oct9night)) {
            val newThreadOf = HashMap<String, Set<String>>()
            c.threads.forEach { t -> val ids = t.members.map { it.id }.toSet(); ids.forEach { newThreadOf[it] = ids } }
            for (t in c.strongOnly.filter { it.size > 1 && it.size < STORY_MAX_THREAD_SIZE }) {
                val ids = t.members.map { it.id }.toSet()
                assertTrue("a strong thread stays together", newThreadOf.getValue(t.lead.id).containsAll(ids))
            }
        }
    }

    // ---- determinism -------------------------------------------------------------------------

    private fun partition(threads: List<StoryThread>): Set<Set<String>> =
        threads.map { t -> t.members.map { it.id }.toSet() }.toSet()

    @Test
    fun `the threads do not depend on the order the articles are passed in`() {
        for (c in listOf(fx1, fx2, newest300, oct9night)) {
            val expected = partition(groupStories(c.list))
            val rnd = Random(2026_10_15)
            repeat(4) {
                val shuffled = c.list.shuffled(rnd)
                assertEquals(expected, partition(groupStories(shuffled)))
            }
            assertEquals(expected, partition(groupStories(c.list.reversed())))
            assertEquals(expected, partition(groupStories(c.list.sortedBy { it.publishedAt })))
        }
    }

    @Test
    fun `a read-state filter never changes the threads when the statistics list is the whole list`() {
        for (c in listOf(fx2, newest300)) {
            val mixed = c.list.mapIndexed { i, a ->
                when (i % 3) { 0 -> a.copy(isRead = true, readAt = 1_000L + (i % 5)); 1 -> a.copy(isRead = true); else -> a }
            }
            val full = groupStories(mixed)
            val fullOf = HashMap<String, Set<String>>()
            full.forEach { t -> val ids = t.members.map { it.id }.toSet(); ids.forEach { fullOf[it] = ids } }
            val unread = mixed.filter { !it.isRead }
            for (t in groupStories(unread, statsFrom = mixed)) {
                assertEquals(fullOf.getValue(t.lead.id), t.members.map { it.id }.toSet())
            }
        }
    }

    // ---- the pieces, on a synthetic list ---------------------------------------------------------

    private val h = 60L * 60 * 1000
    private val base = 1_800_000_000_000L

    private fun art(id: String, feed: String, title: String, at: Long) =
        ArticleItem(id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = false)

    /** [n] unrelated filler headlines from three feeds (every word unique), spread over a day. */
    private fun fillers(n: Int) = (0 until n).map { i ->
        art("f$i", "filler${i % 3}", "qa$i wb$i xc$i yd$i", base + (i % 24) * h)
    }

    private fun threadsWith(extra: List<ArticleItem>, fillerCount: Int) = groupStories(extra + fillers(fillerCount))

    private fun together(ts: List<StoryThread>, a: String, b: String) =
        ts.any { t -> t.members.any { it.id == a } && t.members.any { it.id == b } }

    @Test
    fun `two rare shared words are enough when they are most of the headline`() {
        val a = art("a", "feedA", "yaron zuker arrested", base)
        val b = art("b", "feedB", "zuker yaron indicted", base + 10 * 60_000L)
        assertTrue(together(threadsWith(listOf(a, b), 150), "a", "b"))
        assertFalse("not by the strong rule", groupStoriesCore(listOf(a, b) + fillers(150), listOf(a, b) + fillers(150), false)
            .any { t -> t.members.any { it.id == "a" } && t.size > 1 })
    }

    @Test
    fun `one shared word is never enough, and a headline with one key word never groups`() {
        val a = art("a", "feedA", "yaron zuker arrested", base)
        val b = art("b", "feedB", "yaron minister resigned", base)
        assertFalse(together(threadsWith(listOf(a, b), 150), "a", "b"))
        val c = art("c", "feedA", "yaron", base)
        val d = art("d", "feedB", "yaron zuker", base)
        assertFalse(together(threadsWith(listOf(c, d), 150), "c", "d"))
    }

    @Test
    fun `the same feed never links, however alike the headlines are`() {
        val a = art("a", "feedA", "yaron zuker arrested", base)
        val b = art("b", "feedA", "zuker yaron indicted", base)
        assertFalse(together(threadsWith(listOf(a, b), 150), "a", "b"))
    }

    @Test
    fun `reports hours apart need more evidence than reports minutes apart`() {
        // two shared rare words + one word of its own each: score about 630 per mille; the bar is 450 plus 50 per mille for
        // every hour beyond the first 90 minutes
        fun pair(gapMinutes: Long) = listOf(
            art("a", "feedA", "yaron zuker arrested", base),
            art("b", "feedB", "zuker yaron indicted", base + gapMinutes * 60_000L),
        )
        assertTrue("30 minutes apart", together(threadsWith(pair(30), 150), "a", "b"))
        assertTrue("85 minutes apart", together(threadsWith(pair(85), 150), "a", "b"))
        assertFalse("just under 6 hours apart", together(threadsWith(pair(350), 150), "a", "b"))
    }

    @Test
    fun `a short list asks for more evidence because rarity says little in it`() {
        val stats150 = storyStats(fillers(150))
        val stats20 = storyStats(fillers(20))
        assertEquals(0, stats150.smallListBonus)
        assertTrue(stats20.smallListBonus in 150..200)
        assertEquals(STORY_SUBJECT_MIN_TITLES, stats20.subjectMaxTitles)
        assertEquals(STORY_SUBJECT_MAX_TITLES, storyStats(fillers(300)).subjectMaxTitles)
        assertTrue(stats20.weight(1) > stats20.weight(2))
        assertEquals(0, stats20.weight(20))
        // a pair that links in a long list but scores below the raised bar in a short one
        val a = art("a", "feedA", "yaron zuker arrested", base)
        val b = art("b", "feedB", "zuker yaron indicted", base + 20 * 60_000L)
        assertTrue("in a list of 152", together(threadsWith(listOf(a, b), 150), "a", "b"))
        assertFalse("in a list of 12", together(threadsWith(listOf(a, b), 10), "a", "b"))
    }

    @Test
    fun `an empty list and a single article are fine`() {
        assertTrue(groupStories(emptyList()).isEmpty())
        val one = groupStories(listOf(art("a", "feedA", "yaron zuker arrested", base)))
        assertEquals(1, one.size)
        assertEquals(1, one[0].size)
    }
}
