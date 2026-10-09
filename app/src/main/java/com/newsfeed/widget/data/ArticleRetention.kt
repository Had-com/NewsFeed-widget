package com.newsfeed.widget.data

/**
 * One choice of the settings dropdown "Keep articles for". Stored as [days] (`WidgetConfig.retentionDays`,
 * 0 = no day limit) plus [minutes] (`WidgetConfig.retentionMinutes`, 0 = not a sub-day option); at most
 * one of the two is non-zero, both zero = Forever. The day options keep the stored values they always had.
 */
data class RetentionOption(val label: String, val days: Int = 0, val minutes: Int = 0)

/** The dropdown, shortest to longest, Forever (no limit) last. */
val RETENTION_OPTIONS: List<RetentionOption> = listOf(
    RetentionOption("15 minutes", minutes = 15),
    RetentionOption("1 day", days = 1),
    RetentionOption("3 days", days = 3),
    RetentionOption("1 week", days = 7),
    RetentionOption("2 weeks", days = 14),
    RetentionOption("1 month", days = 30),
    RetentionOption("Forever"),
)

/**
 * How long an article stays, in ms; 0 = unlimited (only the 300-article cap applies). `retentionMinutes`
 * (the sub-day options) wins over `retentionDays`; configs saved before it existed read it as 0, so they
 * keep their day setting.
 */
fun retentionWindowMs(config: WidgetConfig): Long = when {
    config.retentionMinutes > 0 -> config.retentionMinutes * 60_000L
    config.retentionDays > 0 -> config.retentionDays * 24L * 60 * 60 * 1000
    else -> 0L
}

/** Articles published before this instant are expired; 0 = no cutoff. Pure: depends only on [config] and [now]. */
fun retentionCutoff(config: WidgetConfig, now: Long): Long {
    val window = retentionWindowMs(config)
    return if (window > 0L) now - window else 0L
}

/**
 * Drops the articles older than the retention window (`publishedAt < cutoff`; an article exactly at the
 * cutoff is kept). Applied by the worker's merge AND at render / tap time (displayThreadsFor,
 * storedThreads) so a short window takes effect without waiting for the next refresh. It runs BEFORE
 * [retainWithPerFeedGuarantee], which only protects against the 300-article cap: the guarantee never
 * brings back an expired article, so a feed with nothing newer than the window is simply empty.
 */
fun applyRetention(articles: List<ArticleItem>, config: WidgetConfig, now: Long): List<ArticleItem> {
    val cutoff = retentionCutoff(config, now)
    return if (cutoff <= 0L) articles else articles.filter { it.publishedAt >= cutoff }
}

/** [config] with its retention set to [option] (clears the field the option does not use). */
fun WidgetConfig.withRetention(option: RetentionOption): WidgetConfig =
    copy(retentionDays = option.days, retentionMinutes = option.minutes)

/** The option matching this config, or a label built from an unknown stored value. */
fun WidgetConfig.retentionOption(): RetentionOption =
    RETENTION_OPTIONS.firstOrNull { it.days == retentionDays && it.minutes == retentionMinutes }
        ?: when {
            retentionMinutes > 0 -> RetentionOption("$retentionMinutes minutes", minutes = retentionMinutes)
            else -> RetentionOption("$retentionDays days", days = retentionDays)
        }

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
