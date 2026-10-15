package com.newsfeed.widget.data

import java.text.Normalizer
import java.util.Collections
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
 *     A title with fewer than [STORY_MIN_SHARED] key words therefore never groups through this
 *     rule (deliberate: a missed group is cheap, a wrong group hides a different story);
 *  5. (revised 2026-10-09, BUG-028) at least [STORY_MIN_CONTENT] of the shared tokens are CONTENT
 *     words: not in the list's generic set (see [StoryStats]). Two headlines that only share the
 *     recurring names / words of the day ("נתניהו", "צה"ל", "יום" ...) are two different stories.
 * That is the STRONG rule; it is unchanged since 2026-10-09 and links pairs unconditionally.
 *
 * Revised 2026-10-15 (BUG-049, "same main subject"): the strong rule needs three shared words, which
 * misses paraphrased headlines about one story that share only its two distinctive words (a person's
 * name and the event noun). Two more link kinds, both built from the SUBJECT words of a headline,
 * recognise those pairs. A word is a subject word when it is not generic and rare in the list: it is in
 * at most [STORY_SUBJECT_MAX_TITLES] titles and at most [STORY_SUBJECT_MAX_PERCENT]% of them (never fewer
 * than [STORY_SUBJECT_MIN_TITLES] titles of allowance). Names, places and event nouns are rare; the words
 * that describe only the KIND of event ("injured", "attack", "explosion") are not. Every word also has an
 * integer weight 1000 * ln(N / titles-with-the-word), N = size of the statistics list, so a rarer shared
 * word counts for more. For a pair that is not strongly linked the score is
 *     score = 1000 * (sum of shared weights) / (smaller of the two titles' total weights)
 *             + [STORY_SUBJECT_EXTRA_BONUS] * (shared subject words beyond two)
 * and the pair needs at least [STORY_SUBJECT_MIN_SHARED] shared subject words (so a title with fewer
 * than two key words never groups; function words such as `מה`, `עוד`, `נגד` never count as subject words). Then:
 *  - SUBJECT link: score >= [STORY_SUBJECT_MIN_SCORE] (plus a bonus when the list is small, see
 *    [STORY_SMALL_LIST]: rarity says little in a short list; plus a penalty for each hour the two
 *    reports are apart beyond [STORY_GAP_FREE_MINUTES]: duplicates of one story arrive within minutes);
 *  - SUPPORT link: score >= [STORY_SUPPORT_MIN_SCORE] (a weaker overlap that only counts as evidence
 *    together with other links, below).
 * To keep two different incidents of the same type from merging (two grenade incidents, two e-bike
 * accidents, two attacks on one country) the new links are filtered and the threads are built carefully:
 *  - one clear best per feed: a subject / support link between x and y is dropped when x has a stronger link
 *    to ANOTHER article y2 of y's feed, or one within [STORY_AMBIGUITY_MARGIN] of it when y2 is another story
 *    (not in y's strong cluster and not a near copy of y, see [STORY_NEAR_COPY_SCORE]); the same from y's side.
 *    A feed that reports two near-identical incidents then cannot tie both to one article of a third feed;
 *  - the strong links are merged first (as before); then subject links, strongest first, join two
 *    clusters only when the clusters are COHERENT: at least half of the comparable pairs between them
 *    (different feeds, inside the window, same read class) are linked in some way (an article joins a thread
 *    because it resembles the thread, not because it resembles one member) AND every article of the smaller
 *    cluster that has a comparable partner on the other side is linked to one (a bridging article, say a
 *    weather bulletin that mentions the fast times, must not pull its own partners into the other topic);
 *  - clusters joined by several support links merge too, when there are at least
 *    [STORY_SUPPORT_MIN_LINKS] such links, they carry at least [STORY_SUPPORT_MIN_SETS] different sets of
 *    shared subject words and [STORY_SUPPORT_MIN_TOKENS] different subject words in all (one repeated phrase
 *    seen twice is one piece of evidence), and the same coherence test passes.
 * An article that is similar to a thread - not to its lead - now joins it (report 22).
 *
 * Boilerplate never counts as shared (revised 2026-10-09, BUG-028). Reporter names and channel labels
 * repeat across the unrelated posts of one feed, so "reporter + chat label + one word" used to be
 * enough for 3 shared tokens / 60% on a short post. Three layers, all deterministic:
 *  - static labels in [storyTokens]: the Telegram "<reporter> @ צ'אט כתבים" lead, the "מבזקן 12" lead,
 *    and channel / show names (n12, ynet, כאן, חדשות ...) are not key words;
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
 * A thread is a cluster built from those links, capped at [STORY_MAX_THREAD_SIZE] members (the newest
 * ones; the older rest stay ungrouped singles). The lead is the newest member (largest publishedAt, ties
 * broken by the smaller id). Every tie-break in the clustering uses that same newest-first order, so the
 * threads never depend on the order the articles are passed in.
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

/** Main-subject rule (BUG-049): a subject word is in at most this many titles ... */
const val STORY_SUBJECT_MAX_TITLES = 8
/** ... and at most this percent of the statistics list (rounded down), but always allowed in [STORY_SUBJECT_MIN_TITLES]. */
const val STORY_SUBJECT_MAX_PERCENT = 3
const val STORY_SUBJECT_MIN_TITLES = 3
/** A subject / support link needs at least this many shared subject words (so a title needs at least two key words). */
const val STORY_SUBJECT_MIN_SHARED = 2
/** Score (per mille) of a subject link / of a support link, see the class documentation. */
const val STORY_SUBJECT_MIN_SCORE = 450
const val STORY_SUPPORT_MIN_SCORE = 240
/** Per mille added to the score for every shared subject word beyond [STORY_SUBJECT_MIN_SHARED]. */
const val STORY_SUBJECT_EXTRA_BONUS = 30
/** Reports of one story reach the feeds within minutes: the subject score needs this many per mille more per hour beyond [STORY_GAP_FREE_MINUTES]. */
const val STORY_GAP_FREE_MINUTES = 90
const val STORY_GAP_PENALTY_PER_HOUR = 50
/** In a list smaller than this, rarity says little: the thresholds rise by up to [STORY_SMALL_LIST_BONUS] per mille (linearly, 0 at this size). */
const val STORY_SMALL_LIST = 120
const val STORY_SMALL_LIST_BONUS = 200
/** A rival link within this many per mille of a link makes it ambiguous, unless the rival's article is a near copy (this score) of the linked one. */
const val STORY_AMBIGUITY_MARGIN = 100
const val STORY_NEAR_COPY_SCORE = 700
/** Clusters joined by support links: at least this many links, different shared-word sets and different subject words in all. */
const val STORY_SUPPORT_MIN_LINKS = 2
const val STORY_SUPPORT_MIN_SETS = 2
const val STORY_SUPPORT_MIN_TOKENS = 3
/** Articles with fewer key words never take part in the subject rule. */
const val STORY_SUBJECT_MIN_TOKENS = 2

/** One thread: [lead] (newest member) plus the [others], newest first. A lone article is a thread of 1. */
data class StoryThread(val lead: ArticleItem, val others: List<ArticleItem> = emptyList()) {
    val members: List<ArticleItem> get() = listOf(lead) + others
    val size: Int get() = 1 + others.size
}

private val NIQQUD = Regex("""[֑-ׇ]""")
// Invisible direction marks (LRM, RLM, ALM) and their literal entity text ("&rlm;" / "&lrm;" kept by
// articles stored before the Telegram parser dropped them): never part of a word, and the entity text
// would otherwise become the key words "rlm" / "lrm".
private val DIRECTION_MARKS = Regex("[\\u200E\\u200F\\u061C]|&(?:rlm|lrm);")
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
 * Words that are rare in a short list but carry no subject: Hebrew function words and fillers (in the form [storyTokens]
 * leaves them, i.e. after prefix stripping: "מאוד" is "אוד", "הרבה" is "רבה"). They still count in the weights and in the
 * strong rule; they just never count as a SUBJECT word of the main-subject rule (a pair that shares `מה` and one rare word is
 * not about one story). Two-letter names (`אד` of Ed Sheeran) are not on the list.
 */
private val FUNCTION_WORDS: Set<String> = (
    "עוד כבר רק אוד יותר פחות אחד שני שלא כמה למה איך מתי האם עדיין אותו תוך דרך ליד נגד מול לפי בלי ללא כדי אחר אפשר רבה מעט " +
    "מה מי בו בה לו לה אך אז לי לך שם פי"
).split(" ").toSet()

private fun subjectEligible(w: String) = w !in FUNCTION_WORDS

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
    synchronized(TOKEN_CACHE) { TOKEN_CACHE[title] }?.let { return it }
    val tokens = Collections.unmodifiableSet(storyTokensUncached(title))
    synchronized(TOKEN_CACHE) { TOKEN_CACHE[title] = tokens }
    return tokens
}

/**
 * Process-wide memo of [storyTokens] by exact title (DEBUG_PLAN 14.aj, BUG-033): grouping tokenised every
 * title three times per run (storyStats' two passes, then the matching pass) and ran on every render and in
 * both tap callback passes; tokenising (a dozen regexes and a Unicode normalisation per title) was 60% of the
 * 0.6-0.7 s a grouping took on the phone. A title always yields the same tokens, so the result is cached
 * (read-only sets, first-occurrence order kept). Bounded LRU: a widget holds at most 300 articles.
 */
private const val TOKEN_CACHE_MAX = 2048
private val TOKEN_CACHE = object : LinkedHashMap<String, Set<String>>(512, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>) = size > TOKEN_CACHE_MAX
}

internal fun storyTokensUncached(title: String): Set<String> {
    var t = Normalizer.normalize(title, Normalizer.Form.NFKC)
    t = DIRECTION_MARKS.replace(t, "")
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

/**
 * [storyTokensSimilar] on sorted int-id arrays (one id per distinct token; [generic] marks the generic words):
 * the same three rules, counted with a merge instead of hash lookups. Both arrays hold distinct ids.
 */
internal fun idsSimilar(a: IntArray, b: IntArray, generic: BooleanArray): Boolean {
    val shorter = minOf(a.size, b.size)
    if (shorter < STORY_MIN_SHARED) return false
    var i = 0
    var j = 0
    var shared = 0
    var content = 0
    while (i < a.size && j < b.size) {
        val x = a[i]
        val y = b[j]
        if (x == y) { shared++; if (!generic[x]) content++; i++; j++ }
        else if (x < y) i++ else j++
    }
    return shared >= STORY_MIN_SHARED && shared * 100 >= shorter * STORY_MIN_COVERAGE_PERCENT &&
        content >= STORY_MIN_CONTENT
}

private fun isTelegramFeed(feedId: String) = "t.me/s/" in feedId

/**
 * What a list of articles says about its own boilerplate (BUG-028) and about how rare its words are
 * (BUG-049), computed from the list alone:
 *  - [bylines]: (feedId, opening key words) pairs - in a Telegram feed, an opening of
 *    [STORY_BYLINE_MIN_TOKENS]..[STORY_BYLINE_MAX_TOKENS] key words shared by at least
 *    [STORY_BYLINE_MIN_TITLES] titles of that feed is a reporter signature. The longest prefix that
 *    is shared by exactly as many titles as its shorter parent is kept ("איתי גל און" rather than
 *    "איתי גל"), so a topic word that follows the name is not swallowed;
 *  - [generic]: words in at least [STORY_GENERIC_MIN_TITLES] titles and [STORY_GENERIC_MIN_PERCENT]%
 *    of the list (counted after byline removal) whose first and last occurrence are more than
 *    [STORY_WINDOW_MS] apart, i.e. they recur across several stories rather than inside one;
 *  - [titleCount] and [titlesWith]: the number of titles in the list and, per key word (after byline
 *    removal), the number of titles that contain it - the document frequency the subject rule weighs with.
 */
internal class StoryStats(
    val bylines: Set<Pair<String, List<String>>>,
    val generic: Set<String>,
    val titleCount: Int = 0,
    val titlesWith: Map<String, Int> = emptyMap(),
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

    /** Integer weight (per mille of the natural log) of a word in [titles] of the [titleCount] titles; 0 when it is in every title. */
    fun weight(titles: Int): Int =
        if (titleCount <= 0 || titles <= 0) 0 else Math.round(1000.0 * Math.log(titleCount.toDouble() / titles)).toInt().coerceAtLeast(0)

    /** The most titles a subject word may be in, see [STORY_SUBJECT_MAX_TITLES]. */
    val subjectMaxTitles: Int
        get() = minOf(STORY_SUBJECT_MAX_TITLES, maxOf(STORY_SUBJECT_MIN_TITLES, titleCount * STORY_SUBJECT_MAX_PERCENT / 100))

    /** Extra score (per mille) a short list must clear: up to [STORY_SMALL_LIST_BONUS], falling to 0 at [STORY_SMALL_LIST] titles. */
    val smallListBonus: Int
        get() = STORY_SMALL_LIST_BONUS * maxOf(0, STORY_SMALL_LIST - titleCount) / STORY_SMALL_LIST
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
    // One map entry per word with its count and first / last publish time (a single hash lookup per word, no boxing).
    class WordSpan(var count: Int, var first: Long, var last: Long)
    val spans = HashMap<String, WordSpan>()
    for (a in articles) {
        for (w in partial.keyTokens(a)) {
            val sp = spans.getOrPut(w) { WordSpan(0, Long.MAX_VALUE, Long.MIN_VALUE) }
            sp.count++
            if (a.publishedAt < sp.first) sp.first = a.publishedAt
            if (a.publishedAt > sp.last) sp.last = a.publishedAt
        }
    }
    val n = articles.size
    val generic = HashSet<String>()
    val titlesWith = HashMap<String, Int>(spans.size * 2)
    for ((w, sp) in spans) {
        titlesWith[w] = sp.count
        if (sp.count >= STORY_GENERIC_MIN_TITLES && sp.count * 100 >= STORY_GENERIC_MIN_PERCENT * n &&
            sp.last - sp.first > STORY_WINDOW_MS) generic += w
    }
    return StoryStats(bylines, generic, n, titlesWith)
}

// Unread = -2; read before readAt existed = -1; otherwise the readAt stamp (a whole thread is
// marked read with ONE shared stamp, so its members share a class).
private fun readClass(a: ArticleItem): Long = if (!a.isRead) -2L else (a.readAt ?: -1L)

internal val NEWEST_FIRST: Comparator<ArticleItem> =
    compareByDescending<ArticleItem> { it.publishedAt }.thenBy { it.id }

/** A link between two articles (indexes into the list being grouped) with its score (per mille) and the subject words it shares. */
private class Edge(val i: Int, val j: Int, val score: Int, val subject: IntArray)

/** What the support-link merge learns about one pair of clusters. */
private class SupportEvidence {
    var links = 0
    var scoreSum = 0L
    val words = HashSet<Int>()
    val sets = HashSet<List<Int>>()
}

/**
 * Groups [articles] into threads. [statsFrom] is the list the boilerplate and word-frequency statistics
 * ([StoryStats]) are computed from; it defaults to [articles], and the widget render passes the unfiltered
 * list so that a read-state filter cannot change the threads the tap callbacks recompute. Returns one
 * [StoryThread] per thread AND per ungrouped article, ordered by where each thread's LEAD appears in
 * [articles] (so the caller's sort order is preserved: a thread sits at its lead's position and its other
 * members disappear from the flat list). O(n^2) in the article count (n <= 300): a few tens of thousands of
 * small int-array merges, then clustering over at most a few hundred links.
 */
fun groupStories(articles: List<ArticleItem>, statsFrom: List<ArticleItem> = articles): List<StoryThread> =
    groupStoriesCore(articles, statsFrom, subjectRule = true)

/**
 * [groupStories] with the main-subject rule (BUG-049) switchable: `subjectRule = false` is exactly the strong
 * rule of 2026-10-09 alone (every link unconditional, plain transitive closure), kept so tests can compare
 * the fast windowed pair loop with an all-pairs oracle and measure what the subject rule adds.
 */
internal fun groupStoriesCore(
    articles: List<ArticleItem>,
    statsFrom: List<ArticleItem>,
    subjectRule: Boolean,
): List<StoryThread> {
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
    // Linking is symmetric, so the pairs can be visited in any order and the partition is the same as the
    // original all-pairs i<j loop. Only articles with enough key words can ever link, and two can only link
    // within STORY_WINDOW_MS of each other: sorted by time, each article is compared with the ones that
    // follow it inside that window instead of with all of them.
    val times = LongArray(n) { articles[it].publishedAt }
    val classes = LongArray(n) { readClass(articles[it]) }
    val minTokens = if (subjectRule) STORY_SUBJECT_MIN_TOKENS else STORY_MIN_SHARED
    val candidates = (0 until n).filter { tokens[it].size >= minTokens }.sortedBy { times[it] }
    // Tokens as sorted int ids, so a pair is compared with a merge of two small int arrays instead of string
    // hash lookups (same answer as storyTokensSimilar, see idsSimilar).
    val tokenId = HashMap<String, Int>()
    val ids = Array(n) { i ->
        if (tokens[i].size < minTokens) IntArray(0)
        else tokens[i].map { tokenId.getOrPut(it) { tokenId.size } }.toIntArray().also { it.sort() }
    }
    val tokenCount = tokenId.size
    val genericId = BooleanArray(tokenCount)
    val subjectId = BooleanArray(tokenCount)
    val weightId = IntArray(tokenCount)
    val subjectMax = stats.subjectMaxTitles
    for ((w, id) in tokenId) {
        val isGeneric = w in stats.generic
        genericId[id] = isGeneric
        val titles = stats.titlesWith[w] ?: 1
        weightId[id] = stats.weight(titles)
        subjectId[id] = !isGeneric && titles <= subjectMax && subjectEligible(w)
    }
    val totalWeight = LongArray(n) { i -> var s = 0L; for (id in ids[i]) s += weightId[id]; s }

    // Newest-first rank: the canonical article order every tie-break below uses (never the input order).
    val rank = IntArray(n)
    (0 until n).sortedWith { x, y -> NEWEST_FIRST.compare(articles[x], articles[y]) }
        .forEachIndexed { r, idx -> rank[idx] = r }
    val feedIndex = HashMap<String, Int>()
    val feedOf = IntArray(n) { feedIndex.getOrPut(articles[it].feedId) { feedIndex.size } }

    val strong = ArrayList<Edge>()
    val subjectEdges = ArrayList<Edge>()
    val supportEdges = ArrayList<Edge>()
    val bonus = stats.smallListBonus
    for (p in candidates.indices) {
        val i = candidates[p]
        val a = articles[i]
        var q = p + 1
        while (q < candidates.size && times[candidates[q]] - times[i] <= STORY_WINDOW_MS) {
            val j = candidates[q++]
            if (a.feedId == articles[j].feedId) continue
            if (classes[i] != classes[j]) continue
            if (!subjectRule) {
                if (idsSimilar(ids[i], ids[j], genericId)) parent[find(i)] = find(j)
                continue
            }
            // One merge over the two sorted id arrays: shared / content words (the strong rule), shared weight
            // and shared subject words (the subject rule).
            val x = ids[i]
            val y = ids[j]
            var u = 0
            var v = 0
            var shared = 0
            var content = 0
            var sharedWeight = 0L
            var subjectShared = 0
            while (u < x.size && v < y.size) {
                val s = x[u]
                val t = y[v]
                if (s == t) {
                    shared++
                    if (!genericId[s]) content++
                    if (subjectId[s]) subjectShared++
                    sharedWeight += weightId[s]
                    u++; v++
                } else if (s < t) u++ else v++
            }
            val shorter = minOf(x.size, y.size)
            val smallerWeight = minOf(totalWeight[i], totalWeight[j])
            val score = (if (smallerWeight > 0) (sharedWeight * 1000 / smallerWeight).toInt() else 0) +
                STORY_SUBJECT_EXTRA_BONUS * maxOf(0, subjectShared - STORY_SUBJECT_MIN_SHARED)
            if (shorter >= STORY_MIN_SHARED && shared >= STORY_MIN_SHARED &&
                shared * 100 >= shorter * STORY_MIN_COVERAGE_PERCENT && content >= STORY_MIN_CONTENT) {
                strong += Edge(i, j, score, IntArray(0))
                continue
            }
            if (subjectShared < STORY_SUBJECT_MIN_SHARED || smallerWeight <= 0) continue
            val gapMinutes = (abs(times[i] - times[j]) / 60_000L).toInt()
            val penalty = STORY_GAP_PENALTY_PER_HOUR * maxOf(0, gapMinutes - STORY_GAP_FREE_MINUTES) / 60
            if (score >= STORY_SUBJECT_MIN_SCORE + bonus + penalty) {
                subjectEdges += Edge(i, j, score, sharedSubjectIds(x, y, subjectId))
            } else if (score >= STORY_SUPPORT_MIN_SCORE + bonus) {
                supportEdges += Edge(i, j, score, sharedSubjectIds(x, y, subjectId))
            }
        }
    }
    // Weighted overlap (per mille) of two articles' key words, whatever their feeds: the share of the lighter title's weight.
    fun articleSimilarity(a: Int, b: Int): Int {
        val x = ids[a]
        val y = ids[b]
        var u = 0
        var v = 0
        var shared = 0L
        while (u < x.size && v < y.size) {
            if (x[u] == y[v]) { shared += weightId[x[u]]; u++; v++ } else if (x[u] < y[v]) u++ else v++
        }
        val smaller = minOf(totalWeight[a], totalWeight[b])
        return if (smaller > 0) (shared * 1000 / smaller).toInt() else 0
    }
    if (subjectRule) {
        for (e in strong) parent[find(e.i)] = find(e.j)
        clusterByMainSubject(
            n = n, parent = parent, find = ::find, times = times, classes = classes,
            feedOf = feedOf, rank = rank, strong = strong, subjectEdges = subjectEdges,
            supportEdges = supportEdges, similarity = ::articleSimilarity,
        )
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

/** The ids that both sorted arrays hold and that are subject words (ascending). */
private fun sharedSubjectIds(a: IntArray, b: IntArray, subject: BooleanArray): IntArray {
    val out = ArrayList<Int>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val x = a[i]
        val y = b[j]
        if (x == y) { if (subject[x]) out += x; i++; j++ } else if (x < y) i++ else j++
    }
    return out.toIntArray()
}

/**
 * The cluster step of the main-subject rule: starts from the clusters of the strong links (already in
 * [parent]) and adds the subject and support links with the safeguards described in the class documentation.
 * Everything is ordered by the newest-first [rank], never by the input order.
 */
private fun clusterByMainSubject(
    n: Int,
    parent: IntArray,
    find: (Int) -> Int,
    times: LongArray,
    classes: LongArray,
    feedOf: IntArray,
    rank: IntArray,
    strong: List<Edge>,
    subjectEdges: List<Edge>,
    supportEdges: List<Edge>,
    similarity: (Int, Int) -> Int,
) {
    // Every candidate link, kept for the coherence test below (a bit per pair), BEFORE the best-per-feed filter.
    val linked = LongArray((n * n + 63) / 64)
    fun setLinked(i: Int, j: Int) {
        val k = minOf(i, j) * n + maxOf(i, j)
        linked[k ushr 6] = linked[k ushr 6] or (1L shl (k and 63))
    }
    fun isLinked(i: Int, j: Int): Boolean {
        val k = minOf(i, j) * n + maxOf(i, j)
        return linked[k ushr 6] and (1L shl (k and 63)) != 0L
    }
    for (e in strong) setLinked(e.i, e.j)
    for (e in subjectEdges) setLinked(e.i, e.j)
    for (e in supportEdges) setLinked(e.i, e.j)

    // One clear best per feed. A new link between x and y is dropped when x has, to ANOTHER article y2 of y's feed, a
    // link that is stronger, or one within STORY_AMBIGUITY_MARGIN of it when y2 is a different story than y (not in
    // y's strong cluster and not a near copy of y); the same from y's side. A feed that reports two incidents with the
    // same words (two grenade incidents, two attacks on Ukraine) cannot tie both to a third feed's article.
    val strongRoot = IntArray(n) { find(it) }
    val incident = Array(n) { ArrayList<Edge>(4) }
    for (list in listOf(strong, subjectEdges, supportEdges)) for (e in list) { incident[e.i] += e; incident[e.j] += e }
    fun rivalled(e: Edge, x: Int, y: Int): Boolean {
        for (r in incident[x]) {
            val y2 = if (r.i == x) r.j else r.i
            if (y2 == y || feedOf[y2] != feedOf[y]) continue
            if (r.score > e.score) return true
            if (r.score >= e.score - STORY_AMBIGUITY_MARGIN && strongRoot[y2] != strongRoot[y] &&
                similarity(y, y2) < STORY_NEAR_COPY_SCORE) return true
        }
        return false
    }
    fun trusted(e: Edge) = !rivalled(e, e.i, e.j) && !rivalled(e, e.j, e.i)
    val subject = subjectEdges.filter(::trusted).sortedWith(
        compareBy<Edge>({ -it.score }, { minOf(rank[it.i], rank[it.j]) }, { maxOf(rank[it.i], rank[it.j]) }),
    )
    val support = supportEdges.filter(::trusted)

    val members = HashMap<Int, ArrayList<Int>>()
    for (i in 0 until n) members.getOrPut(find(i)) { ArrayList() } += i
    val newest = HashMap<Int, Int>()   // cluster root -> the smallest rank among its members (its canonical key)
    for ((root, list) in members) newest[root] = list.minOf { rank[it] }

    // Two clusters may merge when at least half of the comparable article pairs between them (different feeds, inside the
    // window, same read class) are linked in some way AND every article of the smaller cluster (of both when they are the
    // same size) that has a comparable partner on the other side is linked to at least one of them: an article that only
    // bridges the two (a weather report that mentions the fast times) must not pull its own partners along.
    fun coherent(x: List<Int>, y: List<Int>): Boolean {
        var comparable = 0
        var linkedPairs = 0
        val xHas = IntArray(x.size)      // comparable partners of x[k] on the other side
        val xLinked = IntArray(x.size)   // ... of which linked
        val yHas = IntArray(y.size)
        val yLinked = IntArray(y.size)
        for (u in x.indices) for (v in y.indices) {
            val a = x[u]
            val b = y[v]
            if (feedOf[a] == feedOf[b] || classes[a] != classes[b] || abs(times[a] - times[b]) > STORY_WINDOW_MS) continue
            comparable++; xHas[u]++; yHas[v]++
            if (isLinked(a, b)) { linkedPairs++; xLinked[u]++; yLinked[v]++ }
        }
        if (linkedPairs * 2 < comparable) return false
        fun everyoneLinked(has: IntArray, linkedCount: IntArray) = has.indices.all { has[it] == 0 || linkedCount[it] > 0 }
        return when {
            x.size < y.size -> everyoneLinked(xHas, xLinked)
            y.size < x.size -> everyoneLinked(yHas, yLinked)
            else -> everyoneLinked(xHas, xLinked) && everyoneLinked(yHas, yLinked)
        }
    }
    fun merge(ri: Int, rj: Int) {
        parent[rj] = ri
        members.getValue(ri) += members.getValue(rj)
        members.remove(rj)
        newest[ri] = minOf(newest.getValue(ri), newest.getValue(rj))
        newest.remove(rj)
    }

    for (e in subject) {
        val ri = find(e.i)
        val rj = find(e.j)
        if (ri == rj) continue
        if (coherent(members.getValue(ri), members.getValue(rj))) merge(ri, rj)
    }

    // Support links: several weaker links between the same two clusters count together.
    if (support.isEmpty()) return
    while (true) {
        val evidence = HashMap<Long, SupportEvidence>()
        val roots = HashMap<Long, Pair<Int, Int>>()
        for (e in support) {
            val ri = find(e.i)
            val rj = find(e.j)
            if (ri == rj) continue
            val lo = minOf(ri, rj)
            val hi = maxOf(ri, rj)
            val key = lo.toLong() * n + hi
            val ev = evidence.getOrPut(key) { SupportEvidence() }
            roots[key] = lo to hi
            ev.links++
            ev.scoreSum += e.score
            for (w in e.subject) ev.words += w
            ev.sets += e.subject.toList()
        }
        if (evidence.isEmpty()) return
        val order = evidence.entries.sortedWith(
            compareBy<Map.Entry<Long, SupportEvidence>>(
                { -it.value.links },
                { -it.value.scoreSum },
                { minOf(newest.getValue(roots.getValue(it.key).first), newest.getValue(roots.getValue(it.key).second)) },
                { maxOf(newest.getValue(roots.getValue(it.key).first), newest.getValue(roots.getValue(it.key).second)) },
            ),
        )
        var merged = false
        for ((key, ev) in order) {
            if (ev.links < STORY_SUPPORT_MIN_LINKS || ev.sets.size < STORY_SUPPORT_MIN_SETS ||
                ev.words.size < STORY_SUPPORT_MIN_TOKENS) continue
            val (ri, rj) = roots.getValue(key)
            if (coherent(members.getValue(ri), members.getValue(rj))) { merge(ri, rj); merged = true; break }
        }
        if (!merged) return
    }
}

/** Every article as its own one-member thread: what the widget renders when grouping is off. */
fun ungroupedStories(articles: List<ArticleItem>): List<StoryThread> = articles.map { StoryThread(it) }
