package com.newsfeed.widget.data

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Offline evaluation harness for the story-thread matcher (a report writer, not an assertion test). It is
 * skipped unless the environment variable STORY_EVAL_TSV names a tab-separated file of public headlines
 * (feedId, publishedAt epoch ms, title: the union of the widget backups, de-duplicated by feed + title);
 * the reports go to app/build/story-eval/. The two committed fixtures are always part of the evaluation.
 *
 * Evaluation lists: the fixtures as they are, plus the external file cut into widget-sized lists of 300
 * newest-first articles (lists A0.. start at the newest, B0.. start 150 later so that a story split by a
 * cut in one set is whole in the other).
 *
 * Files written:
 *  - dump.tsv: per list the REAL key tokens of every article (after byline removal), the list's generic
 *    words and the thread of the strong rule alone (the matcher before 2026-10-15). Offline scoring
 *    experiments (Python) read this file.
 *  - groups.tsv: the groups of the strong rule (O) and of the full matcher (N) as lists of article positions.
 *  - report.txt: counts and a size histogram per list; every group the main-subject rule ADDED / EXTENDED /
 *    MERGED (new groups, groups that gained members, groups that fused) or REMOVED, with feeds, times and
 *    titles, for a human review; then the "missed pairs": articles of different feeds within the window that
 *    share at least two non-generic key words but are NOT in one thread, with the shared words and how many
 *    titles each is in.
 * Run with STORY_EVAL_TSV=<file> ./gradlew testDebugUnitTest --tests '*StoryThreadsEvalTest*' (clean the test
 * task first: Gradle does not see the environment change).
 */
class StoryThreadsEvalTest {

    private fun lists(): List<Pair<String, List<ArticleItem>>> {
        val out = ArrayList<Pair<String, List<ArticleItem>>>()
        out += "fx1" to StoryFixtures.load("story_threads_fixture.tsv").sortedWith(NEWEST_FIRST)
        out += "fx2" to StoryFixtures.load("story_threads_fixture2.tsv").sortedWith(NEWEST_FIRST)
        out += "fx3" to StoryFixtures.load("story_threads_fixture3.tsv").sortedWith(NEWEST_FIRST)
        val path = System.getenv("STORY_EVAL_TSV") ?: return out
        val all = File(path).readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
            .mapIndexed { i, line ->
                val (feed, at, title) = line.split("\t", limit = 3)
                ArticleItem(id = "e$i", feedId = feed, feedName = feed.takeLast(14), title = title,
                    publishedAt = at.toLong(), isRead = false)
            }.sortedWith(NEWEST_FIRST)
        for ((name, offset) in listOf("A" to 0, "B" to 150)) {
            var start = offset
            var k = 0
            while (start < all.size) {
                out += "$name${k++}" to all.subList(start, minOf(start + 300, all.size))
                start += 300
            }
        }
        return out
    }

    private fun feedShort(a: ArticleItem): String {
        val f = a.feedId
        if ("://" !in f) return f   // the fixtures use short feed keys
        return when {
            "rotter" in f -> "rotter"
            "Rss1854" in f -> "ynet-flash"
            "StoryRss2" in f || "ynet" in f -> "ynet-news"
            "walla" in f -> "walla"
            "N12chat" in f || "n12-chat" in f -> "n12chat"
            "N12_News" in f || "n12-tg" in f -> "n12tg"
            "Newsflash" in f || "kan-flash" in f -> "kan"
            "globes" in f -> "globes"
            "kan.org" in f || "kan-gnews" in f -> "kan-gnews"
            "news.google" in f || "n12.co" in f || "n12-gnews" in f -> "n12-gnews"
            else -> f.takeLast(10)
        }
    }

    private fun groupsOf(threads: List<StoryThread>, list: List<ArticleItem>): List<List<Int>> {
        val pos = HashMap<String, Int>()
        list.forEachIndexed { i, a -> pos[a.id] = i }
        return threads.filter { it.size > 1 }.map { t -> t.members.map { pos.getValue(it.id) }.sorted() }
    }

    @Test
    fun `evaluation dump and report`() {
        assumeTrue(System.getenv("STORY_EVAL_TSV") != null)
        val outDir = File(System.getProperty("user.dir"), "build/story-eval").also { it.mkdirs() }
        val dump = StringBuilder()
        val groupsOut = StringBuilder()
        val report = StringBuilder()
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Jerusalem") }
        val seenGroups = HashSet<Set<String>>()
        val seenPairs = HashSet<Set<String>>()
        val missed = ArrayList<String>()
        var totalOld = 0
        var totalNew = 0
        val histOld = java.util.TreeMap<Int, Int>()
        val histNew = java.util.TreeMap<Int, Int>()
        val kinds = java.util.TreeMap<String, Int>()
        for ((name, list) in lists()) {
            val stats = storyStats(list)
            val oldThreads = groupStoriesCore(list, list, subjectRule = false)
            val newThreads = groupStories(list)
            val oldGroups = groupsOf(oldThreads, list)
            val newGroups = groupsOf(newThreads, list)
            val oldOf = HashMap<Int, Int>()
            oldGroups.forEachIndexed { gi, g -> g.forEach { oldOf[it] = gi } }
            dump.append("L\t$name\t${list.size}\t${stats.generic.sorted().joinToString(" ")}\n")
            list.forEachIndexed { i, a ->
                dump.append("A\t$name\t$i\t${a.feedId}\t${a.publishedAt}\t${oldOf[i] ?: -1}\t${stats.keyTokens(a).joinToString(" ")}\t${a.title}\n")
            }
            oldGroups.forEach { groupsOut.append("O\t$name\t${it.joinToString(",")}\n") }
            newGroups.forEach { groupsOut.append("N\t$name\t${it.joinToString(",")}\n") }
            totalOld += oldGroups.size
            totalNew += newGroups.size
            for (g in oldGroups) histOld.merge(g.size, 1, Int::plus)
            for (g in newGroups) histNew.merge(g.size, 1, Int::plus)
            report.append("$name: ${list.size} articles, strong rule ${oldGroups.size} groups, now ${newGroups.size} groups\n")

            // classify every group that differs from the strong rule's groups
            val oldSet = oldGroups.map { it.toSet() }.toSet()
            val newSets = newGroups.map { it.toSet() }
            for (g in newGroups) {
                if (g.toSet() in oldSet) continue
                val olds = g.mapNotNull { oldOf[it] }.toSet()
                val singles = g.count { it !in oldOf }
                val kind = when {
                    olds.isEmpty() -> "ADDED"
                    olds.size == 1 && singles > 0 -> "EXTENDED"
                    olds.size > 1 -> "MERGED${olds.size}"
                    else -> "SHRUNK"
                }
                val key = g.map { list[it].feedId + "|" + list[it].title }.toSet()
                if (!seenGroups.add(key)) continue
                kinds.merge(kind, 1, Int::plus)
                report.append("=== [$name] $kind size ${g.size}\n")
                for (i in g.sortedBy { list[it].publishedAt }.reversed()) {
                    val a = list[i]
                    report.append("  ${fmt.format(Date(a.publishedAt))} ${feedShort(a).padEnd(10)} ${oldOf[i]?.let { "o$it" } ?: "--"} ${a.title.take(100)}\n")
                }
            }
            for ((gi, og) in oldGroups.withIndex()) {
                if (newSets.none { it.containsAll(og) }) {
                    val key = og.map { list[it].feedId + "|" + list[it].title }.toSet()
                    if (!seenGroups.add(key)) continue
                    kinds.merge("REMOVED", 1, Int::plus)
                    report.append("=== [$name] REMOVED size ${og.size}\n")
                    for (i in og) report.append("  ${fmt.format(Date(list[i].publishedAt))} ${feedShort(list[i]).padEnd(10)} o$gi ${list[i].title.take(100)}\n")
                }
            }

            // missed pairs: >= 2 shared non-generic key words, different feeds, inside the window, not in one thread
            val threadOf = HashMap<String, String>()
            newThreads.forEach { t -> t.members.forEach { threadOf[it.id] = t.lead.id } }
            val toks = list.map { stats.keyTokens(it) }
            for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (a.feedId == b.feedId || abs(a.publishedAt - b.publishedAt) > STORY_WINDOW_MS) continue
                if (threadOf[a.id] == threadOf[b.id]) continue
                val shared = toks[i].filter { it in toks[j] && it !in stats.generic }
                if (shared.size < 2) continue
                val key = setOf(a.feedId + "|" + a.title, b.feedId + "|" + b.title)
                if (!seenPairs.add(key)) continue
                fun weightOf(w: String) = stats.weight(stats.titlesWith[w] ?: 1)
                val wa = toks[i].sumOf { weightOf(it) }
                val wb = toks[j].sumOf { weightOf(it) }
                val ws = shared.sumOf { weightOf(it) }
                val overlap = if (minOf(wa, wb) > 0) ws * 1000 / minOf(wa, wb) else 0
                missed += "%04d".format(overlap) + " [$name] ${fmt.format(Date(a.publishedAt))} ${feedShort(a)} | ${fmt.format(Date(b.publishedAt))} ${feedShort(b)} | " +
                    "shared " + shared.joinToString(" ") { "$it(${stats.titlesWith[it]})" } + "\n    ${a.title.take(90)}\n    ${b.title.take(90)}\n"
            }
        }
        val head = StringBuilder()
        head.append("groups: strong rule $totalOld, main-subject matcher $totalNew\n")
        head.append("size histogram strong rule: $histOld\nsize histogram now: $histNew\n")
        head.append("changed groups: $kinds\n\n")
        File(outDir, "dump.tsv").writeText(dump.toString(), Charsets.UTF_8)
        File(outDir, "groups.tsv").writeText(groupsOut.toString(), Charsets.UTF_8)
        File(outDir, "report.txt").writeText(head.toString() + report.toString(), Charsets.UTF_8)
        File(outDir, "missed_pairs.txt").writeText(
            "${missed.size} pairs, best weighted overlap (per mille) first\n" + missed.sortedDescending().joinToString(""), Charsets.UTF_8)
    }

    private fun abs(x: Long) = if (x < 0) -x else x
}
