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
 *     missed group is cheap, a wrong group hides a different story);
 *  5. (revised 2026-10-09, BUG-028) at least [STORY_MIN_CONTENT] of the shared tokens are CONTENT
 *     words: not in the list's generic set (see [StoryStats]). Two headlines that only share the
 *     recurring names / words of the day ("נתניהו", "צה"ל", "יום" ...) are two different stories.
 *
 * Boilerplate never counts as shared (revised 2026-10-09, BUG-028). Reporter names and channel labels
 * repeat across the unrelated posts of one feed, so "reporter + chat label + one word" used to be
 * enough for 3 shared tokens / 60% on a short post. Three layers, all deterministic:
 *  - static labels in [storyTokens]: the Telegram "<reporter> @ צ'אט כתבים" lead, the "מבזקן 12"
 *    lead, and channel / show names (n12, ynet, כאן, חדשות ...) are not key words;
 *  - per-feed bylines ([StoryStats.bylines]): in a Telegram feed, an opening of 2-3 key words that
 *    starts at least [STORY_BYLINE_MIN_TITLES] of that feed's titles is a reporter signature and is
 *    dropped from those titles' key words (RSS titles carry no byline, so RSS feeds are untouched);
 *  - generic words ([StoryStats.generic]): a word that recurs in at least [STORY_GENERIC_MIN_PERCENT]%
 *    of the list (and [STORY_GENERIC_MIN_TITLES] titles) across MORE than the story window is a
 *    topic word of the day, not evidence of one story (rule 5). A burst of one story inside a single
 *    window never makes its own words generic.
 * The statistics come from the article list itself (never from "now"); the render and the tap
 * callbacks both pass the same list (the de-duplicated articles of the configured feeds) as
 * `statsFrom`, so a read-state filter on the rendered list cannot change the threads.
 *
 * A thread is a connected component of that link relation (transitive closure), capped at
 * [STORY_MAX_THREAD_SIZE] members (the newest ones; the older rest stay ungrouped singles).
 * The lead is the newest member (largest publishedAt, ties broken by the smaller id).
 */
const val STORY_WINDOW_MS = 6L * 60 * 60 * 1000
const val STORY_MIN_SHARED = 3
const val STORY_MIN_COVERAGE_PERCENT = 60
const val STORY_MAX_THREAD_SIZE = 12
/** Rule 5: shared tokens that must be content words (not in [StoryStats.generic]). */
const val STORY_MIN_CONTENT = 2
/** A Telegram feed's leading 2-3 key words that open at least this many of its titles are a byline. */
const val STORY_BYLINE_MIN_TITLES = 2
const val STORY_BYLINE_MIN_TOKENS = 2
const val STORY_BYLINE_MAX_TOKENS = 3
/** A word is generic when it is in at least this many titles AND this percent of the list, spread over more than [STORY_WINDOW_MS]. */
const val STORY_GENERIC_MIN_TITLES = 5
const val STORY_GENERIC_MIN_PERCENT = 3
/** Key tokens kept per title (the first ones): bounds the O(n^2) set work against a pathological title. */
const val STORY_MAX_TOKENS = 40

/** One thread: [lead] (newest member) plus the [others], newest first. A lone article is a thread of 1. */
data class StoryThread(val lead: ArticleItem, val others: List<ArticleItem> = emptyList()) {
    val members: List<ArticleItem> get() = listOf(lead) + others
    val size: Int get() = 1 + others.size
}

private val NIQQUD = Regex("""[֑-ׇ]""")
// "<title> - <source>" (Google News feeds append " - כאן 11" / " - N12"): drop a trailing
// separator-delimited tail of at most 3 words and 24 characters.
private val SOURCE_SUFFIX = Regex("""^(.*\S)\s+[-–—]\s+([^-–—]{1,24})$""")
// Telegram leads that are labels, not content: "<reporter> @ צ'אט כתבים" (the N12 reporters' chat
// relay) and "מבזקן 12" (N12's flash label). Matched before quote marks are removed.
private val CHANNEL_LEAD = Regex("""^(?:.{1,40}?\s@\s*צ['׳’]?אט כתבים|מבזקן 12)\s*:?\s*""")
// "N12" would otherwise split into the letter n (dropped) and the number 12 (kept as a key word).
private val N12_LABEL = Regex("""\bn12\b""", RegexOption.IGNORE_CASE)
private val QUOTES = Regex("""["'`״׳”“’‘]""")
private val WORD = Regex("""\p{Nd}+(?:[./,:]\p{Nd}+)*|\p{L}+""")
private val WHITESPACE = Regex("""\s+""")
private const val HE_PREFIX_LETTERS = "הובלמשכ"
private const val MAX_PREFIX_STRIPS = 2
private val STOPWORDS: Set<String> = (
    "של את על עם כי אל זה הוא היא הם הן גם לא או אבל אם כל מן כך אחרי לפני עד אין יש היה היו " +
    "להיות כמו וגם בין לאחר בשל זו זאת אשר קישור לכתבה העדכונים " +
    "the a an of to in on and for is at by with from " +
    // channel / show names: labels of the source, never part of the story (BUG-028)
    "n12 ynet mako walla וואלה כאן חדשות"
).split(" ").toSet()

private fun isHebrew(c: Char) = c in '֐'..'׿'

/**
 * The key words of a headline, as a set: NFKC-normalised, niqqud and quote marks removed,
 * a trailing "- source" dropped, lower-cased, split into words/numbers (numbers keep inner
 * . / , :), stop words dropped, up to [MAX_PREFIX_STRIPS] leading prefix letters (ה ו ב ל מ ש כ)
 * stripped from Hebrew words longer than 3 letters (so ובבנימין / בנימין / בבנימין agree), and
 * tokens shorter than 2 characters dropped. Only the first [STORY_MAX_TOKENS] distinct tokens are kept.
 * Static channel labels (see [CHANNEL_LEAD], channel / show names in the stop words) are dropped too.
 * The returned set keeps first-occurrence order (the per-feed byline logic reads the opening words).
 */
fun storyTokens(title: String): Set<String> {
    var t = Normalizer.normalize(title, Normalizer.Form.NFKC)
    t = NIQQUD.replace(t, "")
    t = CHANNEL_LEAD.replaceFirst(t, "")
    t = N12_LABEL.replace(t, " ")
    SOURCE_SUFFIX.matchEntire(t)?.let { m ->
        if (m.groupValues[2].trim().split(WHITESPACE).size <= 3) t = m.groupValues[1]
    }
    t = QUOTES.replace(t, "").lowercase()
    val out = LinkedHashSet<String>()
    for (m in WORD.findAll(t)) {
        if (out.size >= STORY_MAX_TOKENS) break
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

/**
 * True when two key-word sets are similar enough to link their articles (rules 4 and 5 above);
 * [generic] is the list's generic-word set ([StoryStats.generic]), empty when nothing is known.
 */
fun storyTokensSimilar(a: Set<String>, b: Set<String>, generic: Set<String> = emptySet()): Boolean {
    val shorter = minOf(a.size, b.size)
    if (shorter < STORY_MIN_SHARED) return false
    val (small, big) = if (a.size <= b.size) a to b else b to a
    var shared = 0
    var content = 0
    for (w in small) if (w in big) { shared++; if (w !in generic) content++ }
    return shared >= STORY_MIN_SHARED && shared * 100 >= shorter * STORY_MIN_COVERAGE_PERCENT &&
        content >= STORY_MIN_CONTENT
}

private fun isTelegramFeed(feedId: String) = "t.me/s/" in feedId

/**
 * What a list of articles says about its own boilerplate (BUG-028), computed from the list alone:
 *  - [bylines]: (feedId, opening key words) pairs - in a Telegram feed, an opening of
 *    [STORY_BYLINE_MIN_TOKENS]..[STORY_BYLINE_MAX_TOKENS] key words shared by at least
 *    [STORY_BYLINE_MIN_TITLES] titles of that feed is a reporter signature. The longest prefix that
 *    is shared by exactly as many titles as its shorter parent is kept ("איתי גל און" rather than
 *    "איתי גל"), so a topic word that follows the name is not swallowed;
 *  - [generic]: words in at least [STORY_GENERIC_MIN_TITLES] titles and [STORY_GENERIC_MIN_PERCENT]%
 *    of the list (counted after byline removal) whose first and last occurrence are more than
 *    [STORY_WINDOW_MS] apart, i.e. they recur across several stories rather than inside one.
 */
internal class StoryStats(
    val bylines: Set<Pair<String, List<String>>>,
    val generic: Set<String>,
) {
    /** The key words of [a] used for matching: [storyTokens] minus the byline of its Telegram feed. */
    fun keyTokens(a: ArticleItem): Set<String> {
        val tokens = storyTokens(a.title)
        if (bylines.isEmpty() || !isTelegramFeed(a.feedId)) return tokens
        val list = tokens.toList()
        for (k in STORY_BYLINE_MAX_TOKENS downTo STORY_BYLINE_MIN_TOKENS) {
            if (list.size > k && (a.feedId to list.subList(0, k)) in bylines) {
                return list.subList(k, list.size).toCollection(LinkedHashSet())
            }
        }
        return tokens
    }
}

internal fun storyStats(articles: List<ArticleItem>): StoryStats {
    // 1. bylines: support of every leading k-prefix per Telegram feed.
    val support = HashMap<Pair<String, List<String>>, Int>()
    for (a in articles) {
        if (!isTelegramFeed(a.feedId)) continue
        val list = storyTokens(a.title).toList()
        for (k in STORY_BYLINE_MIN_TOKENS..STORY_BYLINE_MAX_TOKENS) {
            if (list.size > k) support.merge(a.feedId to list.subList(0, k).toList(), 1, Int::plus)
        }
    }
    val bylines = HashSet<Pair<String, List<String>>>()
    for ((key, count) in support) {
        if (count < STORY_BYLINE_MIN_TITLES) continue
        val k = key.second.size
        if (k == STORY_BYLINE_MIN_TOKENS || support[key.first to key.second.subList(0, k - 1)] == count) bylines += key
    }
    val partial = StoryStats(bylines, emptySet())
    // 2. generic words, from the key words that remain after the byline removal.
    val count = HashMap<String, Int>()
    val first = HashMap<String, Long>()
    val last = HashMap<String, Long>()
    for (a in articles) {
        for (w in partial.keyTokens(a)) {
            count.merge(w, 1, Int::plus)
            first.merge(w, a.publishedAt, ::minOf)
            last.merge(w, a.publishedAt, ::maxOf)
        }
    }
    val n = articles.size
    val generic = HashSet<String>()
    for ((w, c) in count) {
        if (c >= STORY_GENERIC_MIN_TITLES && c * 100 >= STORY_GENERIC_MIN_PERCENT * n &&
            last.getValue(w) - first.getValue(w) > STORY_WINDOW_MS) generic += w
    }
    return StoryStats(bylines, generic)
}

// Unread = -2; read before readAt existed = -1; otherwise the readAt stamp (a whole thread is
// marked read with ONE shared stamp, so its members share a class).
private fun readClass(a: ArticleItem): Long = if (!a.isRead) -2L else (a.readAt ?: -1L)

private val NEWEST_FIRST: Comparator<ArticleItem> =
    compareByDescending<ArticleItem> { it.publishedAt }.thenBy { it.id }

/**
 * Groups [articles] into threads. [statsFrom] is the list the boilerplate statistics ([StoryStats])
 * are computed from; it defaults to [articles], and the widget render passes the unfiltered list so
 * that a read-state filter cannot change the threads the tap callbacks recompute. Returns one [StoryThread] per thread AND per ungrouped
 * article, ordered by where each thread's LEAD appears in [articles] (so the caller's sort order
 * is preserved: a thread sits at its lead's position and its other members disappear from the
 * flat list). O(n^2) in the article count (n <= 300): a few tens of thousands of set checks.
 */
fun groupStories(articles: List<ArticleItem>, statsFrom: List<ArticleItem> = articles): List<StoryThread> {
    val n = articles.size
    if (n == 0) return emptyList()
    val stats = storyStats(statsFrom)
    val tokens = articles.map { stats.keyTokens(it) }
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
            if (storyTokensSimilar(tokens[i], tokens[j], stats.generic)) parent[find(i)] = find(j)
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

