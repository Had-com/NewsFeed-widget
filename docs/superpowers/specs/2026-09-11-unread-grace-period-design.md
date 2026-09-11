# Unread-only 5-second grace period + Focus Mode header simplification — design

## Context

Under the "Show: Unread only" filter, an article marked as read disappears from the list
the instant `ArticleSorting.applyFilterAndSort()` next runs — `FilterMode.UNREAD.key ->
!article.isRead` (`ArticleSorting.kt:24`) excludes it immediately, and the widget re-renders
right after the read-flag write in the same callback. There is no warning, no transition —
the row is just gone on the next frame. Reported live: the user wants a 5-second grace
period showing the article was just read before it vanishes, with a dissolve/fade
transition.

Separately, in Focus Mode specifically, an article is flagged read the instant it becomes
focused (`SetFocusArticleCallback.kt`, `FocusStepCallback.kt`) — before the user has actually
had a chance to read the enlarged text. Reported live, in the same conversation: read should
only be flagged once focus moves away to a different article, not the moment it's tapped.

## Decisions made during brainstorming

- **True animation is impossible — confirmed by decompiling Glance 1.1.0 directly, not
  assumed.** Every `GlanceAppWidget.update()` call produces a brand-new `RemoteViews` tree
  that fully replaces the old one (`AppWidgetManager.updateAppWidget(int, RemoteViews)`, the
  full-replace overload — confirmed via bytecode, no `partiallyUpdateAppWidget` call anywhere
  in Glance). Grepping every class in both `glance-1.1.0.aar` and `glance-appwidget-1.1.0.aar`
  for `Animat`/`Transition`/`Fade`/`animateLayoutChanges`/`LayoutTransition` returns zero
  matches. This is a genuine Android AppWidget platform ceiling, not a Glance-specific gap —
  a hypothetical future raw-RemoteViews rewrite (`docs/superpowers/plans/2026-09-04-remoteviews-rewrite.md`)
  would not unlock this either, since the underlying platform primitive
  (`AppWidgetManager.updateAppWidget`) has no cross-update transition concept at any layer.
- **Substitute for the impossible fade: the grace period uses the article's already-existing
  muted "read" color as the warning cue, then a hard cut.** `FeedItemRow.kt` already renders
  a read article in a dimmer color today (that's the existing visual meaning of "read") — no
  new rendering code is needed to produce a "this is about to disappear" signal. The article
  shows dimmed for 5 seconds, then is removed outright on the next render.
- **The grace period applies to both the standard NewsFeed widget and NewsFeed Focus.**
- **Focus Mode's read-marking moves from "on focus" to "on focus-away," for direct-tap
  focusing only** (`SetFocusArticleCallback`) — there is no separate "step to another
  article" path anymore, since the ▲/▼ step buttons are being removed in this same change
  (see below). Toggling focus off by re-tapping the already-focused row clears focus without
  marking anything read (matches the user's exact wording: read is flagged only when moving
  to *another* article, not when simply leaving the current one unfocused).
- **The standard widget's read-marking timing is unchanged** — `ToggleExpandCallback` marks
  an article read on expand, which is already the correct moment (there's no "focus" concept
  in the standard widget; expanding an article inline is the direct read signal).
- **Focus Mode header simplification, decided alongside the above**: the ▲/▼ step buttons
  (`FocusStepCallback`) and the ✕ clear-focus button (`ClearFocusCallback`) are both removed
  entirely — dead code, not just hidden. The "N/M" position indicator stays (still useful
  after a direct tap). The remaining −/+ focus-scale buttons (`AdjustFocusScaleCallback`) get
  a visibly larger tap target and font size than their current `13.sp`/`6.dp` padding.

## Architecture

`ArticleItem` gains a `readAt: Long?` timestamp, written whenever an article is actually
marked read (immediately on expand for the standard widget; on focus-away for Focus Mode).
`ArticleSorting`'s "Unread only" filter keeps an article visible for 5 seconds past its
`readAt` before excluding it. Because Glance never re-renders on its own between explicit
`update()` calls, the callback that marks an article read also launches one detached,
un-awaited coroutine that waits 5 seconds and calls `update()` again, so the article actually
disappears once the grace period elapses rather than lingering until some unrelated future
refresh.

## Components

### `data/FeedConfig.kt` — `ArticleItem`

```kotlin
data class ArticleItem(
    val id: String,
    val feedId: String,
    val feedName: String,
    val title: String,
    val articleUrl: String = "",
    val description: String = "",              // plain text, max 400 chars
    val imageUrl: String = "",                 // first image from RSS enclosure/media tags
    val publishedAt: Long,
    val isRead: Boolean,
    val readAt: Long? = null,                  // set when isRead transitions to true; null
                                                // otherwise, including for articles that were
                                                // already read before this field existed
)
```

`readAt = null` is the correct default for every existing persisted article (nothing retroactively
becomes "just read" when this field is introduced) — an already-read article with `readAt ==
null` simply gets no grace period, which is correct: it wasn't "just" read, it just is read.

### `data/ArticleSorting.kt` — grace-period-aware filter

```kotlin
// Not private: UnreadGracePeriod (glance/UnreadGracePeriod.kt) reads this too, so the
// delayed re-render's wait time and this filter's own window can never drift apart.
const val UNREAD_GRACE_PERIOD_MS = 5_000L

fun applyFilterAndSort(articles: List<ArticleItem>, config: WidgetConfig): List<ArticleItem> {
    val now = System.currentTimeMillis()
    val filtered = articles.filter { article ->
        when (config.filter) {
            FilterMode.UNREAD.key -> !article.isRead ||
                (article.readAt != null && now - article.readAt < UNREAD_GRACE_PERIOD_MS)
            FilterMode.READ.key   -> article.isRead
            else                  -> true
        }
    }
    // ...unchanged sort logic below...
}
```

(Read the actual current file to confirm the exact surrounding structure — this shows the
one line that changes and the one new constant, not the whole function.)

### `glance/SetFocusArticleCallback.kt` — mark the article losing focus, not the one gaining it

Current behavior marks whichever article becomes newly focused. New behavior: when focus
moves from article A to a *different* article B, mark A read (with `readAt = now`) — B is
not marked read by this action. If nothing was previously focused (first tap from the normal
state), there's no "previous article" to mark. If the tap is a toggle-off (re-tapping the
already-focused row, clearing focus back to none), nothing is marked read.

```kotlin
class SetFocusArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        var articleLosingFocusId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
            val movingToAnotherArticle = current.isNotBlank() && current != articleId
            prefs[WidgetStateKey.focusedArticleId] = if (current == articleId) "" else articleId
            if (current != articleId) prefs.remove(WidgetStateKey.focusScale)

            // Read is flagged on the article LOSING focus, once the user has actually moved
            // on to another one - not the moment an article is tapped, which was too early
            // (reported live: the user hasn't read the enlarged text yet at that instant).
            if (movingToAnotherArticle) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    val now = System.currentTimeMillis()
                    prefs[WidgetStateKey.articles] = Json.encodeToString(
                        articles.map { if (it.id == current) it.copy(isRead = true, readAt = now) else it }
                    )
                    articleLosingFocusId = current
                }
            }
        }
        articleLosingFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedFocusWidget().update(context, glanceId)
        scheduleGracePeriodRefresh(context, glanceId, articleLosingFocusId)
    }
}
```

### Shared grace-period re-render scheduling

A small shared helper (new file, e.g. `glance/UnreadGracePeriod.kt`, since both
`ToggleExpandCallback` and `SetFocusArticleCallback` need it) launches the detached,
un-awaited follow-up update:

```kotlin
object UnreadGracePeriod {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Fire-and-forget: waits past the grace period, then re-renders so a just-read article
    // under "Unread only" actually disappears instead of lingering until some unrelated
    // future refresh. No-op if nothing was actually marked read this action (markedArticleId
    // is null) - e.g. a toggle-off in Focus Mode, or an already-expanded/already-focused
    // article being re-tapped.
    fun scheduleRefresh(
        markedArticleId: String?,
        update: suspend () -> Unit,
    ) {
        if (markedArticleId == null) return
        scope.launch {
            // +100ms buffer past ArticleSorting.UNREAD_GRACE_PERIOD_MS's own window, so this
            // fires strictly after the filter would exclude the article, never before it -
            // shares the same constant rather than a second hardcoded number, so the two can
            // never silently drift out of sync.
            delay(ArticleSorting.UNREAD_GRACE_PERIOD_MS + 100L)
            update()
        }
    }
}
```

`ToggleExpandCallback` calls this after its own existing `NewsFeedWidget().update(context,
glanceId)`, passing the article ID it just marked read (or `null` if nothing was — e.g.
collapsing an already-expanded article, which is a valid no-op path this function already
has). `SetFocusArticleCallback` calls it the same way with `articleLosingFocusId`.

### `glance/FocusStepCallback.kt`, `glance/ClearFocusCallback.kt` — deleted

Both files are removed entirely, along with their `actionRunCallback<...>()` wiring in
`NewsFeedWidget.kt`'s `WidgetHeader` (the ▲/▼ and ✕ `Text`/`clickable` blocks). The "N/M"
position indicator block stays, along with its own comment updated to no longer reference
the now-deleted step buttons.

### `glance/NewsFeedWidget.kt` — larger −/+ buttons

The `−`/`+` `Text` blocks (currently sharing `stepStyle`: `fontSize = 13.sp`, `padding(horizontal
= 6.dp, vertical = 2.dp)`) get their own larger style — e.g. `fontSize = 20.sp`, `padding(horizontal
= 10.dp, vertical = 4.dp)` (exact numbers are a implementation-time visual call, not a hard
requirement of this spec — the requirement is "visibly bigger than before," not a specific
point size).

## Error handling

- `UnreadGracePeriod.scheduleRefresh`'s coroutine runs on a `SupervisorJob`, so a failure in
  one scheduled refresh (e.g. the widget was removed from the home screen in the meantime,
  making `update()` a no-op or throw) can't affect any other in-flight scheduled refresh or
  crash the app — matches this app's existing tolerant-failure conventions elsewhere.
- If the same article is marked read and then somehow un-read within the 5-second window (no
  current UI path does this, but the design should not assume it can't happen), the scheduled
  refresh still fires and just re-renders with whatever state is actually true at that moment
  — `ArticleSorting`'s filter re-evaluates fresh every time, so this can't show stale/wrong
  data, only trigger one extra harmless re-render.

## Out of scope

- Any actual fade/dissolve animation — confirmed impossible on this platform (see Decisions).
- Changing the standard widget's read-marking timing (unaffected — already correct).
- A settings toggle to adjust the 5-second duration — fixed at 5 seconds per the original
  request, not configurable.
- Any change to `AdjustFocusScaleCallback`'s own scale-adjustment logic — only its buttons'
  visual size changes, not its behavior.

## Testing / verification

`ArticleSorting.applyFilterAndSort()`'s grace-period logic is a pure function over
`List<ArticleItem>` and `WidgetConfig` — unit-testable with plain JUnit (this project has
had real unit test infrastructure since the Telegram feature). A test can construct an
article with `isRead = true, readAt = <a time within the last 5s>` and confirm it's still
included under `FilterMode.UNREAD`, and another with `readAt` older than 5 seconds ago (or
`null`) and confirm it's excluded. Everything else (the delayed re-render actually firing,
Focus Mode's read-on-focus-away behavior, the header buttons' removal/resizing) is verified
on-device, matching this app's established convention for anything involving real
Glance/RemoteViews rendering or coroutine timing.
