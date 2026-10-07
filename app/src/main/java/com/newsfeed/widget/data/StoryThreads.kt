package com.newsfeed.widget.data

import java.text.Normalizer
import kotlin.math.abs

/**
 * Story threads: the same news story reported by DIFFERENT feeds (ynet / N12 / Kan / walla /
 * Telegram channels ...) is grouped into one collapsible thread row. Everything here is a pure,
 * deterministic function of the articles' own fields (title, feedId, publishedAt, isRead, readAt):
 * it never looks at "now" and never stores anything per article, so the widget render and the tap
 * callbacks (which recompute the group from the stored articles) always agree.
 *
 * Two articles are LINKED when ALL of these hold:
 *  1. they come from different feeds (feedId differs);
 *  2. |publishedAt difference| <= [STORY_WINDOW_MS];
 *  3. they are in the same read class (both unread, or both read with the same readAt) - this is
 *     what lets a later article start a NEW unread thread instead of joining one already read,
 *     and it makes filtering (All / Unread only / Read only) never split a thread;
 *  4. their key-word sets (see [storyTokens]) share at least [STORY_MIN_SHARED] tokens AND those
 *     shared tokens cover at least [STORY_MIN_COVERAGE_PERCENT]% of the SHORTER title's tokens.
 *     A title with fewer than [STORY_MIN_SHARED] key words therefore never groups (deliberate: a
 *     missed group is cheap, a wrong group hides a different story).
 *
 * A thread is a connected component of that link relation (transitive closure), capped at
 * [STORY_MAX_THREAD_SIZE] members (the newest ones; the older rest stay ungrouped singles).
 * The lead is the newest member (largest publishedAt, ties broken by the smaller id).
 */
const val STORY_WINDOW_MS = 6L * 60 * 60 * 1000
const val STORY_MIN_SHARED = 3
const val STORY_MIN_COVERAGE_PERCENT = 60
const val STORY_MAX_THREAD_SIZE = 12

/** One thread: [lead] (newest member) plus the [others], newest first. A lone article is a thread of 1. */
data class StoryThread(val lead: ArticleItem, val others: List<ArticleItem> = emptyList()) {
    val members: List<ArticleItem> get() = listOf(lead) + others
    val size: Int get() = 1 + others.size
}

private val NIQQUD = Regex("""[֑-ׇ]""")
// "<title> - <source>" (Google News feeds append " - כאן 11" / " - N12"): drop a trailing
// separator-delimited tail of at most 3 words and 24 characters.
private val SOURCE_SUFFIX = Regex("""^(.*\S)\s+[-–—]\s+([^-–—]{1,24})$""")
private val QUOTES = Regex("""["'`״׳”“’‘]""")
private val WORD = Regex("""\p{Nd}+(?:[./,:]\p{Nd}+)*|\p{L}+""")
private val WHITESPACE = Regex("""\s+""")
private const val HE_PREFIX_LETTERS = "הובלמשכ"
private const val MAX_PREFIX_STRIPS = 2
private val STOPWORDS: Set<String> = (
    "של את על עם כי אל זה הוא היא הם הן גם לא או אבל אם כל מן כך אחרי לפני עד אין יש היה היו " +
    "להיות כמו וגם בין לאחר בשל זו זאת אשר קישור לכתבה העדכונים " +
    "the a an of to in on and for is at by with from"
).split(" ").toSet()

private fun isHebrew(c: Char) = c in '֐'..'׿'

/**
 * The key words of a headline, as a set: NFKC-normalised, niqqud and quote marks removed,
 * a trailing "- source" dropped, lower-cased, split into words/numbers (numbers keep inner
 * . / , :), stop words dropped, up to [MAX_PREFIX_STRIPS] leading prefix letters (ה ו ב ל מ ש כ)
 * stripped from Hebrew words longer than 3 letters (so ובבנימין / בנימין / בבנימין agree), and
 * tokens shorter than 2 characters dropped.
 */
fun storyTokens(title: String): Set<String> {
    var t = Normalizer.normalize(title, Normalizer.Form.NFKC)
    t = NIQQUD.replace(t, "")
    SOURCE_SUFFIX.matchEntire(t)?.let { m ->
        if (m.groupValues[2].trim().split(WHITESPACE).size <= 3) t = m.groupValues[1]
    }
    t = QUOTES.replace(t, "").lowercase()
    val out = LinkedHashSet<String>()
    for (m in WORD.findAll(t)) {
        var w = m.value
        if (w in STOPWORDS) continue
        if (isHebrew(w[0])) {
            repeat(MAX_PREFIX_STRIPS) {
                if (w.length > 3 && w[0] in HE_PREFIX_LETTERS) w = w.substring(1)
            }
            if (w in STOPWORDS) continue
        }
        if (w.length < 2) continue
        out += w
    }
    return out
}

/** True when two key-word sets are similar enough to link their articles (rule 4 above). */
fun storyTokensSimilar(a: Set<String>, b: Set<String>): Boolean {
    val shorter = minOf(a.size, b.size)
    if (shorter < STORY_MIN_SHARED) return false
    val (small, big) = if (a.size <= b.size) a to b else b to a
    val shared = small.count { it in big }
    return shared >= STORY_MIN_SHARED && shared * 100 >= shorter * STORY_MIN_COVERAGE_PERCENT
}

// Unread = -2; read before readAt existed = -1; otherwise the readAt stamp (a whole thread is
// marked read with ONE shared stamp, so its members share a class).
private fun readClass(a: ArticleItem): Long = if (!a.isRead) -2L else (a.readAt ?: -1L)

private val NEWEST_FIRST: Comparator<ArticleItem> =
    compareByDescending<ArticleItem> { it.publishedAt }.thenBy { it.id }

/**
 * Groups [articles] into threads. Returns one [StoryThread] per thread AND per ungrouped
 * article, ordered by where each thread's LEAD appears in [articles] (so the caller's sort order
 * is preserved: a thread sits at its lead's position and its other members disappear from the
 * flat list). O(n^2) in the article count (n <= 300): a few tens of thousands of set checks.
 */
fun groupStories(articles: List<ArticleItem>): List<StoryThread> {
    val n = articles.size
    if (n == 0) return emptyList()
    val tokens = articles.map { storyTokens(it.title) }
    val parent = IntArray(n) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
        return x
    }
    for (i in 0 until n) {
        val a = articles[i]
        for (j in i + 1 until n) {
            val b = articles[j]
            if (a.feedId == b.feedId) continue
            if (abs(a.publishedAt - b.publishedAt) > STORY_WINDOW_MS) continue
            if (readClass(a) != readClass(b)) continue
            if (storyTokensSimilar(tokens[i], tokens[j])) parent[find(i)] = find(j)
        }
    }
    val byRoot = LinkedHashMap<Int, MutableList<Int>>()
    for (i in 0 until n) byRoot.getOrPut(find(i)) { mutableListOf() } += i
    val threadAtLead = HashMap<Int, StoryThread>()   // lead's input index -> its thread
    val hidden = HashSet<Int>()                       // non-lead members of a thread
    for (idx in byRoot.values) {
        if (idx.size == 1) continue
        val sorted = idx.sortedWith { x, y -> NEWEST_FIRST.compare(articles[x], articles[y]) }
        val kept = sorted.take(STORY_MAX_THREAD_SIZE)       // the older overflow stays single
        threadAtLead[kept[0]] = StoryThread(articles[kept[0]], kept.drop(1).map { articles[it] })
        hidden += kept.drop(1)
    }
    val out = ArrayList<StoryThread>(n)
    for (i in 0 until n) {
        if (i in hidden) continue
        out += threadAtLead[i] ?: StoryThread(articles[i])
    }
    return out
}

/** Every article as its own one-member thread: what the widget renders when grouping is off. */
fun ungroupedStories(articles: List<ArticleItem>): List<StoryThread> = articles.map { StoryThread(it) }
