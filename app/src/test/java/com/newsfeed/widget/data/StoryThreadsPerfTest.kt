package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tap-speed work (DEBUG_PLAN 14.aj): grouping ran three times per Focus / Expand tap (the callback's two
 * passes and the render) and each run took ~0.6-0.7 s on the phone, so the tap felt slow. The fast
 * implementation must return EXACTLY what the original all-pairs algorithm returned. [referenceGroupStories]
 * is that original algorithm, kept here only as the oracle; [storyTokensUncached] is the original tokenizer.
 */
class StoryThreadsPerfTest {

    private val NEWEST_FIRST: Comparator<ArticleItem> =
        compareByDescending<ArticleItem> { it.publishedAt }.thenBy { it.id }

    private fun readClass(a: ArticleItem): Long = if (!a.isRead) -2L else (a.readAt ?: -1L)

    /** The pre-14.aj groupStories: O(n^2) over every pair, tokenizing with the uncached tokenizer. */
    private fun referenceGroupStories(articles: List<ArticleItem>, statsFrom: List<ArticleItem> = articles): List<StoryThread> {
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
        val threadAtLead = HashMap<Int, StoryThread>()
        val hidden = HashSet<Int>()
        for (idx in byRoot.values) {
            if (idx.size == 1) continue
            val sorted = idx.sortedWith { x, y -> NEWEST_FIRST.compare(articles[x], articles[y]) }
            val kept = sorted.take(STORY_MAX_THREAD_SIZE)
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

    private val fixtures = listOf("story_threads_fixture.tsv", "story_threads_fixture2.tsv")

    @Test
    fun `cached tokens equal the uncached tokens, also on a second lookup`() {
        for (f in fixtures) for (a in StoryFixtures.load(f)) {
            val expected = storyTokensUncached(a.title)
            assertEquals(a.title, expected, storyTokens(a.title))
            assertEquals(a.title, expected.toList(), storyTokens(a.title).toList())   // order kept (byline logic reads it)
        }
    }

    @Test
    fun `fast grouping equals the original algorithm on the real fixtures, newest first`() {
        for (f in fixtures) {
            val arts = StoryFixtures.load(f).sortedByDescending { it.publishedAt }
            assertEquals(f, referenceGroupStories(arts), groupStories(arts))
        }
    }

    @Test
    fun `fast grouping equals the original algorithm with mixed read classes, other order and a statsFrom list`() {
        for (f in fixtures) {
            val base = StoryFixtures.load(f)
            // Deterministic pseudo-random read state: a third read with a few shared readAt stamps.
            val mixed = base.mapIndexed { i, a ->
                when (i % 3) {
                    0 -> a.copy(isRead = true, readAt = 1_000L + (i % 5))
                    1 -> a.copy(isRead = true)             // read before readAt existed (-1 class)
                    else -> a
                }
            }
            for (list in listOf(mixed, mixed.sortedBy { it.publishedAt }, mixed.shuffled(java.util.Random(42)))) {
                assertEquals(f, referenceGroupStories(list), groupStories(list))
                val subset = list.filter { !it.isRead }
                assertEquals(f, referenceGroupStories(subset, statsFrom = list), groupStories(subset, statsFrom = list))
            }
        }
    }

    @Test
    fun `fast grouping equals the original with equal timestamps and exactly-at-the-window gaps`() {
        val t0 = 1_800_000_000_000L
        val titles = listOf("ראש הממשלה הודיע על מינוי שר חדש לתחום הביטחון", "ראש הממשלה הודיע מינוי שר חדש לתחום הביטחון היום",
            "הודעה על מינוי שר חדש לתחום הביטחון מהממשלה", "שר חדש לתחום הביטחון מינוי ראש הממשלה הודיע")
        val arts = titles.mapIndexed { i, t ->
            ArticleItem(id = "w$i", feedId = "feed${i % 3}", feedName = "f", title = t,
                publishedAt = t0 + (if (i == 3) STORY_WINDOW_MS else 0L) + (if (i == 2) 0L else i * 1000L), isRead = false)
        }
        assertEquals(referenceGroupStories(arts), groupStories(arts))
        assertEquals(referenceGroupStories(arts.reversed()), groupStories(arts.reversed()))
    }

    @Test
    fun `generic words equal the original three-map computation`() {
        for (f in fixtures) {
            val arts = StoryFixtures.load(f)
            val stats = storyStats(arts)
            val partial = StoryStats(stats.bylines, emptySet())
            val count = HashMap<String, Int>(); val first = HashMap<String, Long>(); val last = HashMap<String, Long>()
            for (a in arts) for (w in partial.keyTokens(a)) {
                count.merge(w, 1, Int::plus); first.merge(w, a.publishedAt, ::minOf); last.merge(w, a.publishedAt, ::maxOf)
            }
            val expected = count.filter { (w, c) ->
                c >= STORY_GENERIC_MIN_TITLES && c * 100 >= STORY_GENERIC_MIN_PERCENT * arts.size &&
                    last.getValue(w) - first.getValue(w) > STORY_WINDOW_MS
            }.keys
            assertEquals(f, expected, stats.generic)
            assertTrue("$f: some generic words exist", expected.isNotEmpty())
        }
    }

    @Test
    fun `idsSimilar agrees with storyTokensSimilar on every pair of real titles`() {
        val arts = StoryFixtures.load("story_threads_fixture2.tsv").take(250)
        val sets = arts.map { storyTokens(it.title) }
        val idOf = HashMap<String, Int>()
        val arr = sets.map { s -> s.map { idOf.getOrPut(it) { idOf.size } }.toIntArray().also { it.sort() } }
        // generic words: the ten most common tokens plus a few rare ones, to exercise the content rule
        val freq = HashMap<String, Int>(); sets.forEach { s -> s.forEach { freq.merge(it, 1, Int::plus) } }
        val genericWords = freq.entries.sortedByDescending { it.value }.take(10).map { it.key }.toSet() +
            sets.flatMap { it }.distinct().take(15)
        val genericIds = BooleanArray(idOf.size).also { g -> for ((w, i) in idOf) if (w in genericWords) g[i] = true }
        var similar = 0
        for (i in sets.indices) for (j in i + 1 until sets.size) {
            val expected = storyTokensSimilar(sets[i], sets[j], genericWords)
            assertEquals("$i,$j", expected, idsSimilar(arr[i], arr[j], genericIds))
            if (expected) similar++
        }
        assertTrue("exercised some similar pairs: $similar", similar > 0)
    }

    @Test
    fun `grouping speed on the real fixtures (printed, loose bound)`() {
        val arts = StoryFixtures.load("story_threads_fixture2.tsv").sortedByDescending { it.publishedAt }
        fun time(block: () -> Unit): Double {
            repeat(3) { block() }   // warm up the JIT
            val t0 = System.nanoTime(); repeat(10) { block() }
            return (System.nanoTime() - t0) / 1e6 / 10
        }
        val ref = time { referenceGroupStories(arts) }
        val fast = time { groupStories(arts) }
        println("PERF groupStories on ${arts.size} real headlines (JVM, ms per call, warm): original=%.1f fast=%.1f".format(ref, fast))
        assertTrue("fast=$fast ref=$ref", fast < ref * 1.5 + 5)
    }
}
