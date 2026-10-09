package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cases for the "similar stories group but only on a reporter / channel name" bug
 * (BUG-028, revised 2026-10-09): per-feed boilerplate (reporter bylines, channel labels) never counts
 * as shared, and the shared words must include real content (see StoryThreads.kt).
 */
class StoryThreadsBoilerplateTest {

    private val h = 60L * 60 * 1000
    private val t0 = 1_000_000_000_000L
    private val tgNews = "https://t.me/s/N12_News"
    private val tgChat = "https://t.me/s/N12chat"

    private fun art(
        id: String, feed: String, title: String, at: Long = t0,
        isRead: Boolean = false, readAt: Long? = null,
    ) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = title,
        publishedAt = at, isRead = isRead, readAt = readAt,
    )

    private fun partition(articles: List<ArticleItem>) =
        groupStories(articles).map { t -> t.members.map { it.id }.sorted() }.toSet()

    // ---- channel labels (static) -------------------------------------------------------

    @Test
    fun `the Telegram reporter plus chat-label lead is dropped from the key words`() {
        assertEquals(
            storyTokens("תינוק נמצא ללא דופק בבית שמש"),
            storyTokens("ענבר טויזר @ צ'אט כתבים תינוק נמצא ללא דופק בבית שמש"),
        )
        assertEquals(
            storyTokens("זירת הפיגוע בנווה צוף"),
            storyTokens("מבזקן 12 זירת הפיגוע בנווה צוף"),
        )
    }

    @Test
    fun `channel and show names are not key words`() {
        assertEquals(storyTokens("שריפה בגבעתיים"), storyTokens("כאן חדשות שריפה בגבעתיים"))
        assertEquals(storyTokens("שריפה בגבעתיים"), storyTokens("N12 ynet שריפה בגבעתיים"))
    }

    @Test
    fun `two flashes that share only the reporter name and the chat label do not group`() {
        // Before the fix: tg {ניצן פירא צאט תבים שריפה גבעתיים} vs chat {ניצן פירא שריפה ראשון ציון}
        // shared 3 of 5 = 60% -> a false thread on "reporter + one word".
        val threads = groupStories(listOf(
            art("tg", tgNews, "ניצן שפירא @ צ'אט כתבים שריפה בגבעתיים", t0),
            art("chat", tgChat, "ניצן שפירא שריפה בראשון לציון", t0 + 5 * 60_000),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `the same channel label on two different stories does not group`() {
        val threads = groupStories(listOf(
            art("a", tgChat, "מבזקן 12 שריפה בגבעתיים", t0),
            art("b", "https://t.me/s/other", "מבזקן 12 שריפה בראשון לציון", t0 + 60_000),
        ))
        assertEquals(2, threads.size)
        val shows = groupStories(listOf(
            art("c", "ynet", "כאן חדשות שריפה בגבעתיים", t0),
            art("d", "kan", "כאן חדשות שריפה בראשון לציון", t0 + 60_000),
        ))
        assertEquals(2, shows.size)
    }

    // ---- per-feed reporter bylines -----------------------------------------------------

    @Test
    fun `a reporter byline repeated in a Telegram feed is not a shared word`() {
        // The same reporter signs two posts in each channel, on different stories.
        val threads = groupStories(listOf(
            art("a1", tgChat, "ניצן שפירא שריפה גדולה פרצה במפעל באשדוד", t0),
            art("a2", tgChat, "ניצן שפירא חילופי אש בגבול הצפון הלילה", t0 + 10 * 60_000),
            art("b1", tgNews, "ניצן שפירא שריפה בגבעתיים", t0 + 20 * 60_000),
            art("b2", tgNews, "ניצן שפירא תאונה קשה בכביש חיפה", t0 + 30 * 60_000),
        ))
        assertEquals(4, threads.size)
        assertTrue(threads.all { it.others.isEmpty() })
    }

    @Test
    fun `the byline is stripped from the key words of the posts that carry it`() {
        val a1 = art("a1", tgChat, "איתי גל-און נער בן 14 נפצע קשה מירי בערד", t0)
        val a2 = art("a2", tgChat, "איתי גל-און פיצוץ נשמע בבאר שבע", t0 + 1)
        val stats = storyStats(listOf(a1, a2))
        assertEquals(storyTokens("נער בן 14 נפצע קשה מירי בערד"), stats.keyTokens(a1))
        assertEquals(storyTokens("פיצוץ נשמע בבאר שבע"), stats.keyTokens(a2))
    }

    @Test
    fun `different reporters on the same story still group`() {
        val threads = groupStories(listOf(
            art("a", tgChat, "אור רביד פיצוץ עז נשמע בבאר שבע, שני פצועים פונו לבית החולים", t0),
            art("b", tgNews, "ניר דבורי פיצוץ עז נשמע בבאר שבע, שני פצועים", t0 + 60_000),
        ))
        assertEquals(1, threads.size)
        assertEquals(2, threads[0].size)
    }

    @Test
    fun `a repeated opening in an RSS feed is content, not a byline`() {
        val articles = listOf(
            art("y1", "ynet", "מלי וליאל נחתו בארץ הערב", t0),
            art("y2", "ynet", "מלי וליאל נעצרו באפריקה בחשד", t0 + 1),
            art("k1", "kan", "מלי וליאל נחתו בארץ", t0 + 2),
        )
        assertEquals(setOf(listOf("k1", "y1"), listOf("y2")), partition(articles))
    }

    // ---- content bar (generic words) ---------------------------------------------------

    private val things = listOf("דגל", "סכר", "רכבת", "חוף", "גשר", "נמל", "מגדל", "פארק", "כביש", "מסוף", "שוק", "ספינה")
    private val adjectives = listOf("חדש", "ישן", "גבוה", "ארוך", "רחב", "עמוק", "קרוב", "רחוק", "כבד", "קל", "מהיר", "איטי")
    private val towns = listOf("אילת", "חיפה", "עכו", "צפת", "דימונה", "שדרות", "לוד", "רמלה", "יבנה", "נתיבות", "טבריה", "ערד")

    /** Twelve unrelated headlines over 11 hours that all mention the same three politicians. */
    private fun politicsFillers() = (0 until 12).map { i ->
        art("f$i", "feed${i % 4}", "נתניהו בנט ליברמן ${things[i]} ${adjectives[i]} ${towns[i]}", at = t0 - 6 * h + i * h)
    }

    private val meet = art("meet", "fa", "נתניהו בנט ליברמן נפגשו בירושלים", t0)
    private val spoke = art("spoke", "fb", "נתניהו בנט ליברמן הופיעו בכנס", t0 + 10 * 60_000)

    @Test
    fun `headlines sharing only the names of the days politicians do not group`() {
        // 3 shared words / 60% coverage: the old rule linked them. With a day of headlines in the
        // list, the three names are recurring words, so no shared CONTENT word is left.
        val threads = groupStories(politicsFillers() + meet + spoke)
        assertEquals(listOf("meet"), threads.first { th -> th.members.any { it.id == "meet" } }.members.map { it.id })
        assertEquals(listOf("spoke"), threads.first { th -> th.members.any { it.id == "spoke" } }.members.map { it.id })
    }

    @Test
    fun `the same two headlines do group when nothing marks the names as recurring`() {
        // A lone pair has no evidence that the words are generic: the bar needs list statistics.
        assertEquals(setOf(listOf("meet", "spoke")), partition(listOf(meet, spoke)))
    }

    @Test
    fun `recurring names plus two real shared words still group`() {
        val a = art("a", "fa", "נתניהו בנט ליברמן נפגשו בירושלים הערב", t0)
        val b = art("b", "fb", "נתניהו בנט ליברמן נפגשו בירושלים הלילה", t0 + 10 * 60_000)
        val threads = groupStories(politicsFillers() + a + b)
        assertEquals(setOf("a", "b"), threads.first { th -> th.members.any { it.id == "a" } }.members.map { it.id }.toSet())
    }

    @Test
    fun `a burst of one story does not make its own words generic`() {
        // Ten reports of one event inside ninety minutes: its key words recur but only within one
        // story window, so they stay content words and the reports keep grouping.
        val burst = (0 until 10).map { i ->
            art("e$i", "feed${i % 5}", "פיצוץ עז נשמע בבאר שבע ${towns[i]}", t0 + i * 10 * 60_000)
        }
        val threads = groupStories(burst)
        assertEquals(1, threads.size)
        assertEquals(10, threads[0].size)
    }

    @Test
    fun `storyTokensSimilar needs two shared words outside the generic set`() {
        val a = setOf("אדום", "כחול", "ירוק", "צהוב", "שחור")
        val b = setOf("אדום", "כחול", "ירוק", "לבן")
        assertTrue(storyTokensSimilar(a, b))
        assertTrue(storyTokensSimilar(a, b, generic = setOf("אדום")))                  // 2 content words left
        assertFalse(storyTokensSimilar(a, b, generic = setOf("אדום", "כחול")))          // only 1 left
    }

    // ---- render and tap callbacks must agree --------------------------------------------

    @Test
    fun `statistics from the full list keep a filtered render identical to the unfiltered one`() {
        // The fillers are READ (they would be filtered out under "Unread only"); meet / spoke are
        // unread. Grouping the unread subset with statsFrom = everything gives the same threads as
        // grouping everything (what the tap callbacks do) - and differs from grouping the subset alone.
        val read = politicsFillers().map { it.copy(isRead = true, readAt = 5L) }
        val all = read + meet + spoke
        val unreadOnly = listOf(meet, spoke)
        val full = partition(all).filter { "meet" in it || "spoke" in it }.toSet()
        val filtered = groupStories(unreadOnly, statsFrom = all).map { t -> t.members.map { it.id }.sorted() }.toSet()
        assertEquals(full, filtered)
        assertEquals(setOf(listOf("meet"), listOf("spoke")), filtered)
        assertEquals(setOf(listOf("meet", "spoke")), partition(unreadOnly))
    }

    @Test
    fun `displayThreadsFor under Unread only matches the unfiltered grouping`() {
        val read = politicsFillers().map { it.copy(isRead = true, readAt = 5L) }
        val all = read + meet + spoke
        val feeds = (listOf("fa", "fb") + (0 until 4).map { "feed$it" }).map { FeedConfig(feedId = it, displayName = it) }
        val cfg = WidgetConfig(widgetId = 1, filter = FilterMode.UNREAD.key, sortOrder = SortOrder.NEWEST.key, feeds = feeds)
        val shown = displayThreadsFor(all, cfg, now = t0 + 24 * h).map { t -> t.members.map { it.id }.sorted() }.toSet()
        assertEquals(setOf(listOf("meet"), listOf("spoke")), shown)
    }
}
