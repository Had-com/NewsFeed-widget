# Unread-only 5-second grace period + Focus Mode header simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Under "Show: Unread only", keep a just-read article visible (dimmed, via its existing "read" color) for 5 seconds before it disappears, instead of vanishing instantly; and simplify Focus Mode's header by removing the ▲/▼ step and ✕ clear buttons, moving its read-marking to "when focus moves to another article" instead of "the instant a row is tapped," and making the remaining −/+ scale buttons visibly bigger.

**Architecture:** `ArticleItem` gains a `readAt: Long?` timestamp. `ArticleSorting.applyFilterAndSort()`'s "Unread only" filter keeps an article visible for `UNREAD_GRACE_PERIOD_MS` (5000ms) past its `readAt`. Because Glance never re-renders between explicit `update()` calls, every callback that marks an article read also fires one detached coroutine (`UnreadGracePeriod.scheduleRefresh`) that waits past the grace window and calls `update()` again, so the article actually disappears once its grace period elapses. `SetFocusArticleCallback` is rewritten to mark the article *losing* focus, not the one gaining it. `FocusStepCallback` and `ClearFocusCallback` are deleted outright, along with their header buttons in `NewsFeedWidget.kt`.

**Tech Stack:** Kotlin, Jetpack Glance (`androidx.glance.appwidget:1.1.0`), `kotlinx.coroutines`, `kotlinx.serialization`, plain JUnit (no Robolectric).

**Design reference:** `docs/superpowers/specs/2026-09-11-unread-grace-period-design.md` — read it first if anything below is ambiguous; this plan implements it task-by-task and does not repeat its rationale in full.

---

### Task 1: `readAt` field + grace-period-aware filter in `ArticleSorting.kt`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` (the `ArticleItem` data class, lines 50-61)
- Modify: `app/src/main/java/com/newsfeed/widget/data/ArticleSorting.kt` (the whole file, 49 lines)
- Test: `app/src/test/java/com/newsfeed/widget/data/ArticleSortingTest.kt` (new file)

- [ ] **Step 1: Add `readAt` to `ArticleItem`**

In `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`, find:

```kotlin
@Serializable
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
)
```

Replace with:

```kotlin
@Serializable
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

`readAt = null` is the correct default: nothing retroactively becomes "just read" when this field is introduced, and an already-read article with `readAt == null` gets no grace period, which is correct — it wasn't "just" read, it just is read.

- [ ] **Step 2: Write the failing tests for the grace-period filter**

Create `app/src/test/java/com/newsfeed/widget/data/ArticleSortingTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleSortingTest {

    private fun article(
        id: String,
        isRead: Boolean,
        readAt: Long? = null,
        publishedAt: Long = 0L,
    ) = ArticleItem(
        id = id,
        feedId = "feed1",
        feedName = "Feed",
        title = "Title $id",
        publishedAt = publishedAt,
        isRead = isRead,
        readAt = readAt,
    )

    private fun unreadOnlyConfig() = WidgetConfig(widgetId = 1, filter = FilterMode.UNREAD.key)

    @Test
    fun `unread filter includes an article that was never read`() {
        val articles = listOf(article("a1", isRead = false))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(listOf("a1"), result.map { it.id })
    }

    @Test
    fun `unread filter includes an article read within the last 5 seconds`() {
        val now = System.currentTimeMillis()
        val articles = listOf(article("a1", isRead = true, readAt = now - 1_000L))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(listOf("a1"), result.map { it.id })
    }

    @Test
    fun `unread filter excludes an article read more than 5 seconds ago`() {
        val now = System.currentTimeMillis()
        val articles = listOf(article("a1", isRead = true, readAt = now - 6_000L))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `unread filter excludes an already-read article with no readAt timestamp`() {
        val articles = listOf(article("a1", isRead = true, readAt = null))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `unread filter excludes a read article right at the grace period boundary`() {
        val now = System.currentTimeMillis()
        // By the time applyFilterAndSort computes its own `now`, at least
        // UNREAD_GRACE_PERIOD_MS will have elapsed since this readAt - the filter's
        // strict less-than comparison must exclude it, not include it.
        val articles = listOf(article("a1", isRead = true, readAt = now - UNREAD_GRACE_PERIOD_MS))
        val result = applyFilterAndSort(articles, unreadOnlyConfig())
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    @Test
    fun `all filter includes read and unread articles regardless of readAt`() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            article("a1", isRead = false),
            article("a2", isRead = true, readAt = now - 100_000L),
        )
        val config = WidgetConfig(widgetId = 1, filter = FilterMode.ALL.key)
        val result = applyFilterAndSort(articles, config)
        assertEquals(setOf("a1", "a2"), result.map { it.id }.toSet())
    }

    @Test
    fun `read filter is unaffected by the grace period`() {
        val now = System.currentTimeMillis()
        val articles = listOf(
            article("a1", isRead = true, readAt = now - 1_000L),
            article("a2", isRead = false),
        )
        val config = WidgetConfig(widgetId = 1, filter = FilterMode.READ.key)
        val result = applyFilterAndSort(articles, config)
        assertEquals(listOf("a1"), result.map { it.id })
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail (compile error — field/constant don't exist yet)**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.ArticleSortingTest"`
Expected: FAIL — compile error, `readAt` is not a parameter of `ArticleItem` and `UNREAD_GRACE_PERIOD_MS` is unresolved (Step 1 already added `readAt`, so specifically this fails on the missing `UNREAD_GRACE_PERIOD_MS` reference until Step 4).

- [ ] **Step 4: Update `ArticleSorting.kt`'s filter**

Replace the full contents of `app/src/main/java/com/newsfeed/widget/data/ArticleSorting.kt` with:

```kotlin
package com.newsfeed.widget.data

/**
 * Applies the user's chosen filter (all/unread/read) and sort order (newest/oldest/by-feed/
 * unread-first) to an already-accumulated article list — the render-time counterpart to
 * NewsFeedRepository's own filter+sort logic, which only ever touches a single refresh's
 * freshly-fetched batch.
 *
 * Without this, config.sortOrder/config.filter had no effect on what the widget actually
 * displayed once even one article had been merged into the accumulated store:
 * WidgetWorker's merge step re-sorts the whole store by publishedAt unconditionally (so any
 * non-default sort order was immediately discarded on the very next refresh) and never
 * re-applies the filter to previously-stored articles (so "Unread only"/"Read only" never
 * actually hid anything already accumulated). Confirmed on-device — reported as a bug.
 *
 * Deliberately NOT the same function as NewsFeedRepository's private applyFiltersAndSort():
 * that one also does per-feed item capping, which is a fetch-volume concern specific to a
 * single refresh, not a display concern — capping the already-accumulated list here would
 * make "Load more" reveal fewer real articles than expected for no reason.
 */

// Not private: UnreadGracePeriod (glance/UnreadGracePeriod.kt) reads this too, so the
// delayed re-render's wait time and this filter's own window can never drift apart.
const val UNREAD_GRACE_PERIOD_MS = 5_000L

fun applyFilterAndSort(articles: List<ArticleItem>, config: WidgetConfig): List<ArticleItem> {
    val now = System.currentTimeMillis()
    val filtered = articles.filter { article ->
        when (config.filter) {
            // A just-read article stays visible for UNREAD_GRACE_PERIOD_MS past its readAt,
            // shown dimmed via its existing "read" color (no new rendering code — a true
            // fade/dissolve is impossible on this platform, see the design doc), before it's
            // excluded outright on the next render.
            FilterMode.UNREAD.key -> !article.isRead ||
                (article.readAt != null && now - article.readAt < UNREAD_GRACE_PERIOD_MS)
            FilterMode.READ.key   -> article.isRead
            else                  -> true
        }
    }
    return when (config.sortOrder) {
        SortOrder.OLDEST.key -> filtered.sortedBy { it.publishedAt }
        SortOrder.UNREAD_FIRST.key -> filtered.sortedWith(compareBy({ it.isRead }, { -it.publishedAt }))
        SortOrder.BY_FEED.key -> {
            // Same round-robin interleave as NewsFeedRepository's own BY_FEED handling —
            // one article per feed per round, in the user's configured feed order.
            val orderedIds = config.feedOrder.filter { id -> filtered.any { it.feedId == id } }
            val byFeed = orderedIds.associateWith { id ->
                filtered.filter { it.feedId == id }.sortedByDescending { it.publishedAt }
            }
            val result = mutableListOf<ArticleItem>()
            val maxSize = byFeed.values.maxOfOrNull { it.size } ?: 0
            for (i in 0 until maxSize) {
                for (id in orderedIds) { byFeed[id]?.getOrNull(i)?.let { result += it } }
            }
            result
        }
        else -> filtered.sortedByDescending { it.publishedAt }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.newsfeed.widget.data.ArticleSortingTest"`
Expected: PASS — all 7 tests green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt app/src/main/java/com/newsfeed/widget/data/ArticleSorting.kt app/src/test/java/com/newsfeed/widget/data/ArticleSortingTest.kt
git commit -m "Add readAt-based grace period to the Unread-only filter"
```

---

### Task 2: `UnreadGracePeriod` — shared delayed re-render helper

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/glance/UnreadGracePeriod.kt`

There is no test for this file — it's a thin coroutine-scheduling wrapper with no pure logic to unit test (the real behavior, "does the widget actually re-render 5s later," is only verifiable on-device per this project's established convention for anything involving real Glance rendering or coroutine timing against a live widget).

- [ ] **Step 1: Create the file**

```kotlin
package com.newsfeed.widget.glance

import com.newsfeed.widget.data.UNREAD_GRACE_PERIOD_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Glance never re-renders on its own between explicit update() calls, so a callback that
// marks an article read under "Unread only" also needs to trigger one delayed follow-up
// update once the grace period elapses - otherwise the article would linger visible until
// some unrelated future refresh instead of actually disappearing after 5 seconds.
object UnreadGracePeriod {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // No-op if nothing was actually marked read this action (markedArticleId is null) - e.g.
    // a toggle-off in Focus Mode, or an already-expanded/already-focused article being
    // re-tapped, or an article that was already read before this tap.
    fun scheduleRefresh(markedArticleId: String?, update: suspend () -> Unit) {
        if (markedArticleId == null) return
        scope.launch {
            // +100ms buffer past ArticleSorting.UNREAD_GRACE_PERIOD_MS's own window, so this
            // fires strictly after the filter would exclude the article, never before it -
            // shares the same constant rather than a second hardcoded number, so the two can
            // never silently drift out of sync.
            delay(UNREAD_GRACE_PERIOD_MS + 100L)
            update()
        }
    }
}
```

- [ ] **Step 2: Confirm the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (this file has no callers yet, so this only proves it compiles in isolation — Tasks 3 and 4 wire it up). Note: this project has a single `:app` module and no Gradle product flavors — both widget types (`NewsFeedWidget`/`NewsFeedFocusWidget`) live in the same `main` source set, differentiated at runtime by the `isFocusWidget: Boolean` parameter threaded through `WidgetContent()`, not by a build variant. Any comment in this codebase mentioning a "focusMode build flavor" or `BuildConfig.FOCUS_MODE` is describing a pre-merge architecture that no longer exists as actual Gradle config — don't go looking for a `focusMode` source set or flavor task, there isn't one.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/UnreadGracePeriod.kt
git commit -m "Add UnreadGracePeriod: delayed re-render after marking an article read"
```

---

### Task 3: `ToggleExpandCallback` sets `readAt` and schedules the grace-period refresh

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt` (full file, 48 lines)

This is the standard widget's read-marking path. Per the design doc, its *timing* is unchanged (expand is still the correct moment to mark read) — only the addition of `readAt` and the grace-period refresh trigger are new.

- [ ] **Step 1: Replace the file's contents**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ToggleExpandCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        var didExpand = false
        // Only set when this action is what actually flips isRead false -> true (not when
        // collapsing, and not when the article was already read) - this is both what
        // ReadStatusStore should be told about and what should get a grace-period refresh
        // scheduled; re-marking an already-read article isn't a new "just read" moment.
        var markedReadId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            didExpand = current != articleId
            prefs[WidgetStateKey.expandedArticleId] = if (didExpand) articleId else ""

            // Expanding an article counts as having seen it. Read status also needs marking
            // here now — the external "Open article" button uses actionStartActivity(), which
            // (unlike a custom ActionCallback) can't run a suspend body of its own.
            if (didExpand) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    val target = articles.firstOrNull { it.id == articleId }
                    if (target != null && !target.isRead) {
                        val now = System.currentTimeMillis()
                        prefs[WidgetStateKey.articles] = Json.encodeToString(
                            articles.map { if (it.id == articleId) it.copy(isRead = true, readAt = now) else it }
                        )
                        markedReadId = articleId
                    }
                }
            }
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        UnreadGracePeriod.scheduleRefresh(markedReadId) { NewsFeedWidget().update(context, glanceId) }
    }
}
```

- [ ] **Step 2: Confirm the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt
git commit -m "Set readAt and schedule grace-period refresh when expanding marks an article read"
```

- [ ] **Step 4: On-device verification (standard NewsFeed widget)**

Place a standard "NewsFeed" widget, set **Show: Unread only** in Settings and Save. Tap an unread article to expand it. Confirm: (a) it stays visible, now dimmed in the existing "read" color, for about 5 seconds; (b) after ~5 seconds it disappears from the list without needing to manually refresh; (c) tapping an already-read article (expand/collapse) does not restart or extend any grace period, since it was never "just" read.

---

### Task 4: `SetFocusArticleCallback` marks the article losing focus, not the one gaining it

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt` (full file, 58 lines)

**Context for the implementer:** today, tapping a row in Focus Mode immediately marks *that* row's article read, before the user has had a chance to read the enlarged text (reported live as too early). The fix: mark the article that focus is *moving away from* read, only once focus has genuinely moved to a *different* article. A first tap (nothing previously focused) marks nothing. Toggling focus off (re-tapping the already-focused row) marks nothing either.

- [ ] **Step 1: Replace the file's contents**

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow.kt's fontSize
// shadowing). Tapping a row sets it as the focused article, shrinking every other displayed
// row; tapping the already-focused row again clears focus, returning all rows to the normal
// configured size.
//
// Read is flagged on the article LOSING focus, once the user has actually moved on to
// another one — not the moment an article is tapped, which was too early (reported live: the
// user hasn't read the enlarged text yet at that instant). There's no separate "step to
// another article" path anymore (the ▲/▼ FocusStepCallback buttons were removed alongside
// this change, since every row is directly tappable), so this is the only place Focus Mode
// marks anything read.
class SetFocusArticleCallback : ActionCallback {
    companion object {
        val ARTICLE_ID_KEY = ActionParameters.Key<String>("articleId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        // Only set when the article losing focus was actually unread — re-tapping through
        // already-read articles shouldn't reset their readAt and restart a grace period that
        // doesn't apply to them.
        var articleLosingFocusId: String? = null
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
            val movingToAnotherArticle = current.isNotBlank() && current != articleId
            prefs[WidgetStateKey.focusedArticleId] = if (current == articleId) "" else articleId
            // Each newly-focused article starts at AdjustFocusScaleCallback's default size —
            // an earlier +/- adjustment made while looking at a different article isn't a
            // choice about this one, so it shouldn't carry over silently.
            if (current != articleId) prefs.remove(WidgetStateKey.focusScale)

            if (movingToAnotherArticle) {
                val articles = prefs[WidgetStateKey.articles]
                    ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
                if (articles != null) {
                    val target = articles.firstOrNull { it.id == current }
                    if (target != null && !target.isRead) {
                        val now = System.currentTimeMillis()
                        prefs[WidgetStateKey.articles] = Json.encodeToString(
                            articles.map { if (it.id == current) it.copy(isRead = true, readAt = now) else it }
                        )
                        articleLosingFocusId = current
                    }
                }
            }
        }
        articleLosingFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedFocusWidget().update(context, glanceId)
        UnreadGracePeriod.scheduleRefresh(articleLosingFocusId) { NewsFeedFocusWidget().update(context, glanceId) }
    }
}
```

- [ ] **Step 2: Confirm the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt
git commit -m "Mark the article losing focus read, not the one gaining it"
```

- [ ] **Step 4: On-device verification (NewsFeed Focus widget)**

Place a "NewsFeed Focus" widget, set **Show: Unread only**, Save. Tap an unread article (focuses it) — confirm it is NOT marked read yet (its unread indicator should still show). Tap a different article — confirm the FIRST article is now marked read, stays visible dimmed for ~5 seconds under "Unread only", then disappears; the second article is now focused and NOT yet marked read. Re-tap the currently-focused article to clear focus — confirm nothing gets marked read by this toggle-off.

---

### Task 5: Delete `FocusStepCallback`/`ClearFocusCallback`, enlarge the +/- buttons

**Files:**
- Delete: `app/src/main/java/com/newsfeed/widget/glance/FocusStepCallback.kt`
- Delete: `app/src/main/java/com/newsfeed/widget/glance/ClearFocusCallback.kt`
- Modify: `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` (three spots: a comment near line 137, a comment near line 260, and the `WidgetHeader` composable's `isFocusWidget` block, lines 429-509)
- Modify: `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt` (one comment, lines 28-29)

**Context for the implementer:** explicit user requests, verbatim: "remove the button, it's not needed" (the ✕ clear-focus button) and "remove the step down or up buttons they are not nedded too, and make the magnifing buttons bigger" (the ▲/▼ step buttons, plus enlarging the remaining −/+ scale buttons). Neither deleted callback is referenced anywhere outside the 5 files touched in this task (confirmed by a full-repo search before writing this plan) — deleting them requires no other call-site changes beyond `NewsFeedWidget.kt`'s `WidgetHeader`.

- [ ] **Step 1: Delete the two callback files**

```bash
git rm app/src/main/java/com/newsfeed/widget/glance/FocusStepCallback.kt app/src/main/java/com/newsfeed/widget/glance/ClearFocusCallback.kt
```

- [ ] **Step 2: Update the stale comment in `WidgetStateKey.kt`**

In `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt`, find:

```kotlin
    // Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow's fontSize
    // shadowing and FocusStepCallback): which article, if any, is currently shown at full
    // size while every other displayed row shrinks. Empty string = focus mode inactive, all
    // rows render at the normal configured font size, same as the standard flavor always does.
```

Replace with:

```kotlin
    // Focus Mode only (BuildConfig.FOCUS_MODE build flavor — see FeedItemRow's fontSize
    // shadowing and SetFocusArticleCallback): which article, if any, is currently shown at
    // full size while every other displayed row shrinks. Empty string = focus mode inactive,
    // all rows render at the normal configured font size, same as the standard flavor always
    // does.
```

- [ ] **Step 3: Update the two stale comments in `NewsFeedWidget.kt`**

Find (around line 135-138):

```kotlin
    // Focus widget only (isFocusWidget) — see FeedItemRow.kt's fontSize shadowing.
    // Reading it unconditionally here is harmless for a standard widget instance: the key is
    // simply never written to (SetFocusArticleCallback/FocusStepCallback are only ever
    // wired up when isFocusWidget is true), so it stays blank forever there.
```

Replace with:

```kotlin
    // Focus widget only (isFocusWidget) — see FeedItemRow.kt's fontSize shadowing.
    // Reading it unconditionally here is harmless for a standard widget instance: the key is
    // simply never written to (SetFocusArticleCallback is only ever wired up when
    // isFocusWidget is true), so it stays blank forever there.
```

Find (around line 259-262):

```kotlin
    // Focus widget only (isFocusWidget) — position within what's actually
    // rendered (displayArticles, not FocusStepCallback's own visibleCount-only
    // approximation of it) so the "N / M" indicator always matches what's really on
    // screen, even in the rare case the two disagree because of the memory cap.
```

Replace with:

```kotlin
    // Focus widget only (isFocusWidget) — position within what's actually rendered
    // (displayArticles), so the "N / M" indicator always matches what's really on screen.
```

- [ ] **Step 4: Rewrite the `WidgetHeader` composable's `isFocusWidget` block**

In `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`, find the entire block starting at the comment before `if (isFocusWidget) {` and ending at that same `if` block's closing brace (currently lines ~422-509):

```kotlin
        // Focus widget only (isFocusWidget — see FeedItemRow.kt's
        // fontSize shadowing). Steps focus to the previous/next article via
        // FocusStepCallback rather than requiring a precise tap on a row that may currently
        // be shrunk to half size — that's the actual point of stepping instead of tapping.
        // Always shown on this widget type (not conditioned on a focus target already being
        // set): pressing either one from the normal, nothing-focused state starts focus
        // mode at the first article, same as tapping a row directly would.
        if (isFocusWidget) {
            val stepStyle = TextStyle(
                fontSize   = 13.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                color      = GlanceTheme.colors.primary,
            )
            // Position indicator ("N / M") — only meaningful once something is focused;
            // otherwise every row is the same size and "position" doesn't mean anything.
            // Answers "where am I in the list" without counting rows by eye, and confirms
            // ▲/▼ actually moved (there was previously no feedback beyond the row sizes
            // themselves changing, which is easy to miss at a glance).
            if (focusedArticleId.isNotBlank() && focusedIndex >= 0) {
                Text(
                    text = "${focusedIndex + 1}/$displayCount",
                    style = TextStyle(
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        color      = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                )
            }
            Text(
                text = "▲",
                style = stepStyle,
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(actionRunCallback<FocusStepCallback>(
                        actionParametersOf(FocusStepCallback.DIRECTION_KEY to "prev")
                    )),
            )
            Text(
                text = "▼",
                style = stepStyle,
                modifier = GlanceModifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(actionRunCallback<FocusStepCallback>(
                        actionParametersOf(FocusStepCallback.DIRECTION_KEY to "next")
                    )),
            )
            // Clear-focus button — only shown once something is actually focused (nothing
            // to clear otherwise). Exists because the alternative way to clear focus —
            // tapping the already-focused row again — only works if that tap lands on the
            // row's current bounds, and focusing a row reflows the whole list (every other
            // row shrinks), so the row the user thinks they're re-tapping may no longer be
            // there. This button's position never moves, so it doesn't have that problem.
            if (focusedArticleId.isNotBlank()) {
                Text(
                    text = "✕",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<ClearFocusCallback>()),
                )
                // Focus-area size, moved here from the focused row itself (was FeedItemRow's
                // problem to render before) — a fixed header position that never moves as
                // the list reflows, same reasoning as the ✕ button beside it, and keeps
                // every other on-widget control (▲▼✕) in one place instead of split between
                // the header and whichever row happens to be focused.
                Text(
                    text = "−",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to -AdjustFocusScaleCallback.STEP)
                        )),
                )
                Text(
                    text = "+",
                    style = stepStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to AdjustFocusScaleCallback.STEP)
                        )),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
        }
```

Replace with:

```kotlin
        // Focus widget only (isFocusWidget — see FeedItemRow.kt's fontSize shadowing). Focus
        // is set/cleared purely by tapping a row directly (SetFocusArticleCallback) — the
        // ▲/▼ step and ✕ clear buttons that used to live here were removed (explicit user
        // request: "remove the button, it's not needed" / "remove the step down or up
        // buttons they are not nedded too"). What's left is the "N/M" position indicator and
        // the focus-scale +/- buttons, both only shown once something is actually focused.
        if (isFocusWidget) {
            // Bigger than before (explicit user request, alongside removing the step/clear
            // buttons above: "make the magnifing buttons bigger") — these are now the only
            // on-widget controls left in the header, so they get a more generous tap target
            // and font size than the old shared stepStyle (13.sp / 6.dp-2.dp padding).
            val scaleButtonStyle = TextStyle(
                fontSize   = 20.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                color      = GlanceTheme.colors.primary,
            )
            // Position indicator ("N / M") — only meaningful once something is focused;
            // otherwise every row is the same size and "position" doesn't mean anything.
            // Answers "where am I in the list" without counting rows by eye.
            if (focusedArticleId.isNotBlank() && focusedIndex >= 0) {
                Text(
                    text = "${focusedIndex + 1}/$displayCount",
                    style = TextStyle(
                        fontSize   = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        color      = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    modifier = GlanceModifier.padding(horizontal = 4.dp),
                )
            }
            // Focus-area size — only meaningful once something is focused (nothing to scale
            // otherwise).
            if (focusedArticleId.isNotBlank()) {
                Text(
                    text = "−",
                    style = scaleButtonStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to -AdjustFocusScaleCallback.STEP)
                        )),
                )
                Text(
                    text = "+",
                    style = scaleButtonStyle,
                    modifier = GlanceModifier
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .clickable(actionRunCallback<AdjustFocusScaleCallback>(
                            actionParametersOf(AdjustFocusScaleCallback.DELTA_KEY to AdjustFocusScaleCallback.STEP)
                        )),
                )
            }
            Spacer(GlanceModifier.width(6.dp))
        }
```

- [ ] **Step 5: Confirm the module compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If it fails referencing `FocusStepCallback`/`ClearFocusCallback`, a call site was missed — search again (`grep -r "FocusStepCallback\|ClearFocusCallback" app/src`) before proceeding.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Remove Focus Mode's step/clear header buttons, enlarge the scale buttons"
```

- [ ] **Step 7: On-device verification (NewsFeed Focus widget)**

Place a "NewsFeed Focus" widget. Confirm the header no longer shows ▲, ▼, or ✕ in any state. Tap a row to focus it — confirm the "N/M" indicator and the −/+ buttons still appear, and the −/+ buttons are visibly larger than before (compare against a screenshot of the pre-change header if one is available, or against the standard widget's unrelated text for a size reference). Confirm −/+ still adjust the focused row's scale within its existing 0.75×–2.5× clamp. Confirm tapping the already-focused row again still clears focus (the only way to clear focus now that ✕ is gone) and does not mark anything read.

---

### Task 6: Full-flow on-device regression pass (both widget types)

**Files:** none — this is a verification-only task, no code changes.

This project's own convention (see `docs/DEBUG_PLAN.md` and this feature's own design doc's "Testing / verification" section) is that anything touching real Glance/RemoteViews rendering or coroutine timing is verified on a real device, not assumed from the unit tests alone. Tasks 3, 4, and 5 already include a focused on-device check for their own change; this task is the combined pass across both widget types together, to catch any interaction between them.

- [ ] **Step 1: Standard widget, Unread only, full grace-period timing**

Set **Show: Unread only**. Expand an unread article. Using a stopwatch or the device clock, confirm it is still visible (dimmed) at ~4 seconds and gone by ~6 seconds — not instantly, and not indefinitely.

- [ ] **Step 2: Focus widget, Unread only, focus-away timing**

Set **Show: Unread only** on a Focus widget. Tap article A (focuses it, marks nothing read yet). Tap article B (focuses B, marks A read with `readAt = now`). Confirm A is still visible dimmed at ~4 seconds and gone by ~6 seconds, same as Task 6 Step 1.

- [ ] **Step 3: Show = All / Read only are unaffected**

With **Show: All**, confirm read articles never disappear (no grace period applies — that's Unread-only-specific). With **Show: Read only**, confirm a freshly-read article appears immediately (no delay) and stays.

- [ ] **Step 4: Widget removal doesn't crash a pending grace-period refresh**

Mark an article read under "Unread only" (either widget type), then remove that widget from the home screen within the 5-second window, before the scheduled refresh fires. Confirm no crash (check `adb logcat` around the time the delayed refresh would have fired) — `UnreadGracePeriod`'s `SupervisorJob` should absorb a failed/no-op `update()` against a since-removed widget without affecting anything else.

- [ ] **Step 5: Regression spot-check against `docs/BUGS.md`**

Confirm the previously-reported bug this feature addresses is fixed: an article marked read under "Unread only" no longer vanishes on the very next frame with zero warning.

- [ ] **Step 6: Update `docs/BUGS.md`**

Add a "Feature additions" entry (matching this file's existing format — check recent entries for the exact style) describing: the 5-second grace period for "Unread only" (with the "true fade is impossible on this platform" note, matching the design doc), and the Focus Mode header simplification (▲/▼ and ✕ removed, read-marking moved to focus-away, +/- buttons enlarged). Commit this doc update on its own:

```bash
git add docs/BUGS.md
git commit -m "Document unread grace period and Focus Mode header simplification"
```

---

## Self-review notes (for whoever executes this plan)

- **Spec coverage:** every "Components" entry in the design doc maps to a task above — `ArticleItem.readAt` and the filter (Task 1), `UnreadGracePeriod` (Task 2), `SetFocusArticleCallback` (Task 4), `FocusStepCallback`/`ClearFocusCallback` deletion and the header buttons (Task 5). `ToggleExpandCallback`'s `readAt` wiring (Task 3) is implied by the spec's Architecture section ("written whenever an article is actually marked read... immediately on expand for the standard widget") even though the spec's Components section doesn't show its full body — Task 3 fills that in.
- **Type consistency:** `UnreadGracePeriod.scheduleRefresh(markedArticleId: String?, update: suspend () -> Unit)` is defined once in Task 2 and called identically (by name and signature) in Tasks 3 and 4.
- **Out of scope, confirmed not touched by any task:** any real fade/dissolve animation; the standard widget's read-marking *timing* (only its `readAt` field gains a value — Task 3 does not change *when* `ToggleExpandCallback` marks read); a Settings toggle for the grace period's duration; `AdjustFocusScaleCallback`'s own scale-adjustment logic (Task 5 only changes its buttons' text style, not the callback itself).
