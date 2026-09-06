package com.newsfeed.widget.data

/**
 * Caps the accumulated article store at [cap] total, but guarantees every feed keeps at
 * least its [minPerFeed] newest articles regardless of how the global newest-first ranking
 * would otherwise place them (BUG-008) — without this, a feed that posts far less often
 * than its neighbors gets crowded out and evicted within a refresh or two even though its
 * own fetch succeeded. Confirmed on-device: mixing a few low-frequency English feeds into a
 * widget dominated by high-frequency Hebrew news feeds left one low-frequency article
 * ranked #297 of a 300-cap store, one refresh from eviction.
 *
 * minPerFeed defaults to 10 to match the existing per-feed cap already used at render time
 * for known feeds (NewsFeedRepository.applyFiltersAndSort) — a feed's guaranteed minimum
 * here is exactly what it would normally show under the default sort anyway.
 */
fun retainWithPerFeedGuarantee(
    articles: List<ArticleItem>,
    cap: Int = 300,
    minPerFeed: Int = 10,
): List<ArticleItem> {
    if (articles.size <= cap) return articles.sortedByDescending { it.publishedAt }

    val byFeedNewestFirst = articles.groupBy { it.feedId }
        .mapValues { (_, items) -> items.sortedByDescending { it.publishedAt } }

    val guaranteed = byFeedNewestFirst.values.flatMap { it.take(minPerFeed) }

    // Many feeds' guaranteed minimums alone can exceed the cap — fall back to a plain
    // newest-first trim across just the guaranteed set rather than blowing past cap.
    if (guaranteed.size >= cap) {
        return guaranteed.sortedByDescending { it.publishedAt }.take(cap)
    }

    val guaranteedIds = guaranteed.map { it.id }.toSet()
    val remainder = articles
        .filter { it.id !in guaranteedIds }
        .sortedByDescending { it.publishedAt }
        .take(cap - guaranteed.size)

    return (guaranteed + remainder).sortedByDescending { it.publishedAt }
}
