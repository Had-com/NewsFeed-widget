# Story Threads Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Group the same news story reported by DIFFERENT feeds (ynet / N12 / Kan / walla / rotter / Telegram channels) into one collapsible **thread** row: the newest article with a `+N ▾` badge; tapping the badge lists the other reports under it. A per-widget switch **Group similar stories** (ON by default, including existing widgets) turns it off and restores today's rendering exactly. Reading a thread then moving on marks the whole thread read at once, and under Unread only the whole thread dissolves and leaves together.

**Architecture:** A pure, deterministic matcher (`data/StoryThreads.kt`: key-word sets from titles, link rule = different feed + 6 h window + same read class + >= 3 shared words covering >= 60% of the shorter title, union-find, cap 12, lead = newest) is applied at render time in `WidgetContent` to the post-filter/sort list; `data/ThreadRows.kt` flattens threads to list rows and does the row/article counting. Nothing is stored per article; only the expanded threads' lead ids are persisted in a new per-widget `WidgetStateKey.expandedThreadIds`. Read marking goes through the two existing move-away paths, extended by one helper that recomputes the thread from the stored articles (threads never mix read states, so the render's filtered threads and the callbacks' unfiltered recomputation always agree).

**Tech Stack:** Kotlin 2.0.21, Jetpack Glance 1.1.0 (`LazyColumn`, nested `clickable`), DataStore Preferences 1.1.1, kotlinx-serialization-json 1.6.3, JUnit 4, Gradle 8.7 (Windows / Git Bash).

**Spec:** `docs/superpowers/specs/2026-10-07-story-threads-design.md` (commit `affe779`, approved).

---

## Decisions this plan implements (all agreed; two small refinements made while tuning on real data)

- Link rule constants: `STORY_WINDOW_MS` = 6 h, `STORY_MIN_SHARED` = 3, `STORY_MIN_COVERAGE_PERCENT` = 60, `STORY_MAX_THREAD_SIZE` = 12.
- **Refinement 1:** up to **two** leading Hebrew prefix letters are stripped (not one): `ו`+`ה`/`ב` combinations are common (`והמחבל`, `שבבנימין`); on the 750 real headlines it finds 52 groups vs 50, none wrong.
- **Refinement 2 (decision):** **no** lower bar for very short titles. Titles with < 3 key words never group (a "2 shared when <= 4 words" bar produced one wrong pair on real data).
- **Read class barrier:** unread articles link only to unread ones; read articles only to read ones with the same `readAt`. This makes "a later article starts a NEW unread thread", and makes filter == whole-class keep/drop, so callbacks can recompute the render's threads from ALL stored articles.
- **Moving between members of one thread is not moving on:** nothing is marked read; leaving the thread marks all of it (same `readAt`).
- Budget: collapsed thread = 1 row, expanded = lead + members, cut by `take(limit)` on the flat rows; header `unread(total)` still counts ARTICLES; Focus `N/M` counts rows.
- `resetTapState` does NOT clear `expandedThreadIds`; threads and the single expanded article are independent (`collapseExpandedOnOtherTap` unchanged).

## Environment notes (read first)

- Windows 11, **Git Bash**. `gradlew.bat` is broken; use the Gradle 8.7 distribution directly through the helper `/c/t/g.sh` (created in the Focus plan; Step 0 recreates it if missing). JDK 17.
- **Known failing test to ignore:** `WidgetThemesTest` "`colorProvidersFor custom wraps ...`" (`android.graphics.Color` not mocked). An UNFILTERED `testDebugUnitTest` therefore ends `BUILD FAILED` with exactly 1 failed test; every per-task command below filters to specific classes so it succeeds, and the full run in Task 9 must show that this is the ONLY failure.
- **Commits** are plain new commits on `main` (never amend), one per task, each ending with the trailer line `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (second `-m`). Use `git add <explicit paths>`, never `git add -A`: the untracked `docs/superpowers/plans/2026-09-0*` files must stay untracked. **Never push** (Task 10 needs the user's explicit go-ahead).
- **Another session may be running on-device QA in this same checkout** and may commit `docs/DEBUG_PLAN.md`. Do not touch `docs/DEBUG_PLAN.md` until Task 8 Step 8, and then only after `git status --short docs/DEBUG_PLAN.md` is clean. Do not run two Gradle builds in the same checkout at once (lock contention): if QA is mid-build, wait, or run the builds from a second copy with `REPO=/path/to/copy bash /c/t/g.sh ...`.
- **Editing code:** every modification of an existing source file below is given as `Edit` blocks (replace the exact `old` text with the `new` text, using the Edit tool; the `old` text is unique in the current file). New files are given in full (use Write; new files are LF, git normalises line endings on commit with `core.autocrlf=true`). The working tree has **mixed line endings** (e.g. `NewsFeedWidget.kt`, `TapRouting.kt`, `SetFocusArticleCallback.kt`, `ReadStatusStore.kt` and `docs/QA_PLAN.md` are CRLF; `FeedItemRow.kt`, `ReadOnMoveAway.kt`, `README.md` are LF; `FeedConfig.kt` is mixed). The Edit tool matches multi-line `old` text and keeps each file's endings in all of these cases (checked on a CRLF and a mixed file), so use it for every edit and never rewrite an existing file wholesale. Five files start with a UTF-8 BOM (`FeedItemRow.kt`, `FeedConfig.kt`, `WidgetStateKey.kt`, `ReadStatusStore.kt`, `WidgetConfigActivity.kt`), which the Edit tool also keeps. The `/c/t/rr.sh` range-replace helper from the Focus plan still works but is not needed. For the shell-appended release note (Task 8 Step 5) `docs/RELEASE_NOTES.md` is LF.
- All code in this plan was compiled and its tests run (267 tests, only the known `WidgetThemesTest` failure) in a scratch copy of the repo before this plan was written.

### Step 0: scratch helpers (outside the repo, no commit)

- [ ] **Make sure the Gradle runner exists**

```bash
mkdir -p /c/t
test -f /c/t/g.sh || cat > /c/t/g.sh <<'EOF'
#!/bin/bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot" TEMP="C:\t" TMP="C:\t"
cd "${REPO:-/c/Users/PhotoStudio/ClaudeWorkspace/repo}"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon "$@"
EOF
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo status --short
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo log --oneline -1
```
Expected: the status lists only the untracked `docs/superpowers/plans/2026-09-0*` files (and possibly files the QA session is touching: leave those alone); HEAD is `affe779` or a later docs commit.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` | Modify | `WidgetConfig.groupStories: Boolean = true` |
| `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt` | Modify | `expandedThreadIds` (per-widget Glance state) |
| `app/src/main/java/com/newsfeed/widget/data/StoryThreads.kt` | Create | Pure matcher: `storyTokens`, `storyTokensSimilar`, `groupStories`, `ungroupedStories`, `StoryThread`, constants |
| `app/src/main/java/com/newsfeed/widget/data/ThreadRows.kt` | Create | Pure `layoutThreads` -> `ThreadLayout` / `ThreadRow` (row budget, article counts) |
| `app/src/main/java/com/newsfeed/widget/glance/ThreadState.kt` | Create | `parse/encodeExpandedThreadIds`, `storedThreads`, `decodeStoredArticles`, `toggleThreadExpanded` (prune/cap/collapse cleanup) |
| `app/src/main/java/com/newsfeed/widget/glance/ReadOnMoveAway.kt` | Modify | `idsToMarkReadOnMoveAway`, `markIdsRead`, `markPreviousTappedReadThread` (+ legacy wrapper) |
| `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt` | Modify | `applyFocusTapThread` (+ legacy wrapper) |
| `app/src/main/java/com/newsfeed/widget/data/GraceRefreshRegistry.kt` | Modify | `representativeId` (one schedule key per thread) |
| `app/src/main/java/com/newsfeed/widget/data/ReadStatusStore.kt` | Modify | `markReadAll(ids)` |
| `app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt`, `NoOpTapFeedbackCallback.kt`, `SetFocusArticleCallback.kt` | Modify | use the thread-aware helpers, mark + schedule for the whole thread |
| `app/src/main/java/com/newsfeed/widget/glance/ToggleThreadCallback.kt` | Create | The `+N` badge action |
| `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` | Modify | Group + lay out rows, member gutter, counts, grace resume once per `readAt` |
| `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt` | Modify | `+N ▾/▴` badge (own clickable) in the meta line |
| `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` | Modify | "Group similar stories" switch |
| `app/src/test/resources/story_threads_fixture.tsv` | Create | 294 real public headlines (11 feeds) |
| `app/src/test/java/com/newsfeed/widget/data/WidgetConfigGroupStoriesTest.kt`, `StoryThreadsTest.kt`, `StoryThreadsFixtureTest.kt`, `ThreadRowsTest.kt` | Create | matcher / config / layout tests |
| `app/src/test/java/com/newsfeed/widget/glance/ThreadStateTest.kt`, `ThreadReadMarkingTest.kt` | Create | expand-state and whole-thread read tests |
| `README.md`, `docs/PRD.md`, `docs/RELEASE_NOTES.md`, `docs/QA_PLAN.md`, `docs/DEBUG_PLAN.md` | Modify | feature docs, `## Note 12`, QA section O, pending release-gate rows |

Not changed on purpose: `WidgetWorker.kt` (a refresh keeps members read because `ReadStatusStore` holds every member id and `mergeFreshArticles` carries `readAt` over), `OrphanCleanup.kt` (the new key lives in the widget's own Glance file), `UnreadGracePeriod.kt`.

---

### Task 1: `WidgetConfig.groupStories` + `WidgetStateKey.expandedThreadIds`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`, `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt`
- Create: `app/src/test/java/com/newsfeed/widget/data/WidgetConfigGroupStoriesTest.kt`

- [ ] **Step 1: Write the failing test** (this follows `WidgetConfigTapModeTest`: the decode uses the default `Json`, with no `ignoreUnknownKeys`, exactly like `WidgetContent`)

Create `app/src/test/java/com/newsfeed/widget/data/WidgetConfigGroupStoriesTest.kt`:

```kotlin
package com.newsfeed.widget.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigGroupStoriesTest {

    @Test
    fun `config saved before groupStories existed reads as on`() {
        // Default Json (no ignoreUnknownKeys), exactly what WidgetContent uses.
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"fontSize":1.5,"tapMode":"focus"}""")
        assertTrue(cfg.groupStories)
    }

    @Test
    fun `the default is on and a default config does not write the field`() {
        val cfg = WidgetConfig(widgetId = 3)
        assertTrue(cfg.groupStories)
        // encodeDefaults is off in every Json this app uses, so a `true` is simply absent ...
        val json = Json.encodeToString(cfg)
        assertFalse(json.contains("groupStories"))
        // ... and reads back as true.
        assertTrue(Json.decodeFromString<WidgetConfig>(json).groupStories)
    }

    @Test
    fun `off survives a round trip with both the default and the lenient Json`() {
        val json = Json.encodeToString(WidgetConfig(widgetId = 3, groupStories = false))
        assertTrue(json.contains("\"groupStories\":false"))
        assertFalse(Json.decodeFromString<WidgetConfig>(json).groupStories)
        // WidgetConfigStore / ConfigBackup decode with ignoreUnknownKeys = true
        val lenient = Json { ignoreUnknownKeys = true }
        assertFalse(lenient.decodeFromString<WidgetConfig>(json).groupStories)
        assertEquals(false, lenient.decodeFromString<WidgetConfig>(lenient.encodeToString(WidgetConfig(widgetId = 1, groupStories = false))).groupStories)
    }

    @Test
    fun `turning it off then on again leaves a config identical to the default one`() {
        val on = WidgetConfig(widgetId = 3)
        val toggled = on.copy(groupStories = false).copy(groupStories = true)
        assertEquals(Json.encodeToString(on), Json.encodeToString(toggled))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.WidgetConfigGroupStoriesTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -5
```
Expected: `Unresolved reference 'groupStories'` and `BUILD FAILED`.

- [ ] **Step 3: Add the field and the state key**

**Edit 1 of 1** in `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt`: replace

```kotlin
    // it through TapMode.fromKey() so an unknown value degrades to EXPAND.
    val tapMode: String = TapMode.EXPAND.key,
)
```

with

```kotlin
    // it through TapMode.fromKey() so an unknown value degrades to EXPAND.
    val tapMode: String = TapMode.EXPAND.key,
    // Group the same news story from DIFFERENT feeds into one collapsible thread row (see
    // data/StoryThreads.kt). On by default, including for configs saved before this field existed:
    // kotlinx uses this constructor default for a missing key, so old JSON decodes to true. With the
    // default Json (encodeDefaults = false) a `true` is simply not written, and a `false` is.
    val groupStories: Boolean = true,
)
```

**Edit 1 of 1** in `app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt`: replace

```kotlin
    val focusScale = floatPreferencesKey("focus_scale")

    // A pure "something changed" touch value with no meaning of its own beyond forcing a
    // genuine Preferences mutation - Glance/Compose skips recomposing a widget when its
```

with

```kotlin
    val focusScale = floatPreferencesKey("focus_scale")

    // Story threads (WidgetConfig.groupStories): the lead article ids of the threads the user has
    // expanded with the "+N" badge, separated by newlines (see glance/ThreadState.kt). Absent or empty =
    // every thread collapsed. Lives in the widget's own Glance state, so it is deleted with the
    // widget (OrphanCleanup needs no change) and a tap-mode switch (resetTapState) leaves it alone.
    val expandedThreadIds = stringPreferencesKey("expanded_thread_ids")

    // A pure "something changed" touch value with no meaning of its own beyond forcing a
    // genuine Preferences mutation - Glance/Compose skips recomposing a widget when its
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.WidgetConfigGroupStoriesTest" --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `BUILD SUCCESSFUL` (4 + 4 tests).

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt app/src/main/java/com/newsfeed/widget/data/WidgetStateKey.kt app/src/test/java/com/newsfeed/widget/data/WidgetConfigGroupStoriesTest.kt
git commit -m "Add WidgetConfig.groupStories (default on) and the expandedThreadIds state key" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: The matcher, a real-headline fixture, and the review gate

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/data/StoryThreads.kt`
- Create: `app/src/test/resources/story_threads_fixture.tsv`
- Create: `app/src/test/java/com/newsfeed/widget/data/StoryThreadsTest.kt`, `StoryThreadsFixtureTest.kt`

- [ ] **Step 1: Build the fixture from the real datastore backups** (public headlines only: feed key, publish time, title; no ids/URLs/personal data)

The backups `C:\t\bk*\appWidget-*.p*b` hold the widget's `articles_json` (see `C:\t\ds.py` for how it is decoded). Create `/c/t/ext/extract_fixture.py`:

```python
"""Builds app/src/test/resources/story_threads_fixture.tsv from the widget DataStore backups under C:/t/bk*.

Each appWidget-<id>(.preferences)_pb file is a protobuf whose `articles_json` value is a JSON array of
ArticleItem; we regex it out the same way C:/t/ds.py does, union the articles by id across all backups,
keep only public headline fields (feed key, publishedAt, title) and slice two busy news windows.
usage: python extract_fixture.py <output.tsv>
"""
import datetime
import glob
import json
import re
import sys

out_path = sys.argv[1]

arts = {}
for f in sorted(glob.glob('C:/t/bk*/appWidget-*.p*b')):
    text = open(f, 'rb').read().decode('utf-8', 'replace')
    m = re.search(r'\[\{"id".*?\}\](?=[\x00-\x1f])', text, re.S)
    if not m:
        continue
    for x in json.loads(m.group(0)):
        arts[x['id']] = x

KEYS = {
    'https://rotter.net/rss/rotternews.xml': 'rotter',
    'https://www.ynet.co.il/integration/StoryRss1854.xml': 'ynet-flash',
    'https://www.ynet.co.il/Integration/StoryRss2.xml': 'ynet-news',
    'https://rss.walla.co.il/feed/1?type=main': 'walla',
    'https://t.me/s/N12_News': 'n12-tg',
    'https://t.me/s/N12chat': 'n12-chat',
    'https://www.kan.org.il/api/newsflash/v2/Newsflash': 'kan-flash',
}


def feed_key(x):
    f = x['feedId']
    if f in KEYS:
        return KEYS[f]
    if 'news.google.com' in f:
        return 'kan-gnews' if 'kan.org' in f else 'n12-gnews'
    if 'globes' in f:
        return 'globes-all' if 'iID=2' in f else 'globes-il'
    return None   # nlpnews (English newsletter) is not part of the fixture


def ts(s):
    return int(datetime.datetime.strptime(s, '%Y-%m-%d %H:%M').timestamp() * 1000)


WINDOWS = [(ts('2026-09-20 07:30'), ts('2026-09-20 13:30')),
           (ts('2026-10-07 12:00'), ts('2026-10-07 19:10'))]   # local time of the capturing machine (Asia/Jerusalem)

sel = [x for x in arts.values()
       if feed_key(x) and any(lo <= x['publishedAt'] <= hi for lo, hi in WINDOWS)]
sel.sort(key=lambda x: (-x['publishedAt'], x['id']))
with open(out_path, 'w', encoding='utf-8', newline='\n') as out:
    out.write('# feedKey<TAB>publishedAtEpochMs<TAB>title  '
              '(public headlines captured 2026-09-20 and 2026-10-07; no personal data)\n')
    for x in sel:
        title = x['title'].replace('\t', ' ').replace('\n', ' ')
        out.write(f"{feed_key(x)}\t{x['publishedAt']}\t{title}\n")
print(len(sel), 'articles written to', out_path)
```

```bash
mkdir -p /c/t/ext /c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/test/resources
# write the script above to /c/t/ext/extract_fixture.py first, then:
cd /c/t/ext
PYTHONIOENCODING=utf-8 python extract_fixture.py C:/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/test/resources/story_threads_fixture.tsv
wc -l /c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/test/resources/story_threads_fixture.tsv
head -c 400 /c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/test/resources/story_threads_fixture.tsv
```
Expected: `294 articles written to ...`, `295 ... story_threads_fixture.tsv` (one header comment line + 294 rows), rows like `rotter<TAB>1791389136000<TAB>השבועות הקשים בשורה: ...`. (If the backups were deleted, the same 294-row file is also kept at `/c/t/ext/fx2.tsv`; copy it instead.)

- [ ] **Step 2: Write the matcher unit tests** (token rules, link thresholds, grouping rules, near misses with REAL Hebrew headlines)

Create `app/src/test/java/com/newsfeed/widget/data/StoryThreadsTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryThreadsTest {

    private val h = 60L * 60 * 1000

    private fun art(
        id: String,
        feed: String,
        title: String,
        at: Long = 1_000_000_000_000L,
        isRead: Boolean = false,
        readAt: Long? = null,
    ) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = title,
        publishedAt = at, isRead = isRead, readAt = readAt,
    )

    private fun ids(t: StoryThread) = t.members.map { it.id }

    // ---- storyTokens -------------------------------------------------------------------

    @Test
    fun `quotes niqqud and punctuation are stripped and stop words dropped`() {
        // Rotter writes quotes as '' , ynet as " - both must reduce to the same words.
        assertEquals(storyTokens("איזנקוט: ''נתניהו פועל'' בחקירה"), storyTokens("איזנקוט: \"נתניהו פועל\" בחקירה"))
        assertTrue("יועמשית" in storyTokens("קורא ליועמ\"שית לפתוח"))
        assertTrue("יועמשית" in storyTokens("קורא ליועמ''שית לפתוח"))
        // niqqud removed, stop words (של את על) dropped
        assertEquals(setOf("צדק", "עולם"), storyTokens("צֶדֶק של את עַל עולם"))
    }

    @Test
    fun `up to two leading prefix letters are stripped from long hebrew words`() {
        val a = storyTokens("בבנימין")
        val b = storyTokens("בנימין")
        val c = storyTokens("ובנימין")
        assertEquals(a, b)
        assertEquals(b, c)
        // 3-letter words are too short to strip: ביט keeps its first letter
        assertTrue("ביט" in storyTokens("ביט"))
    }

    @Test
    fun `numbers are kept whole and one-character tokens dropped`() {
        val t = storyTokens("7/10 הנדון 6.7 מיליארד ו x")
        assertTrue("7/10" in t)
        assertTrue("6.7" in t)
        assertFalse(t.any { it.length < 2 })
    }

    @Test
    fun `a trailing source suffix is dropped`() {
        assertEquals(
            storyTokens("דיווחים בלבנון: צה\"ל תקף בדרום המדינה"),
            storyTokens("דיווחים בלבנון: צה\"ל תקף בדרום המדינה - כאן 11"),
        )
    }

    @Test
    fun `latin text is lower-cased and english stop words dropped`() {
        assertEquals(setOf("openai", "agents"), storyTokens("OpenAI AGENTS the"))
    }

    // ---- storyTokensSimilar ------------------------------------------------------------

    @Test
    fun `three shared words covering sixty percent of the shorter title link`() {
        assertTrue(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1"), setOf("a1", "b1", "c1", "x1")))        // 3/4
        assertTrue(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1", "f1"), setOf("a1", "b1", "c1", "x1", "y1")))  // 3/5 = exactly 60%
    }

    @Test
    fun `fewer than three shared words never link`() {
        assertFalse(storyTokensSimilar(setOf("a1", "b1"), setOf("a1", "b1")))            // a title this short never groups
        assertFalse(storyTokensSimilar(setOf("a1", "b1", "c1", "d1"), setOf("a1", "b1", "x1", "y1")))
    }

    @Test
    fun `three shared words below sixty percent coverage do not link`() {
        assertFalse(storyTokensSimilar(setOf("a1", "b1", "c1", "d1", "e1", "f1"), setOf("a1", "b1", "c1", "x1", "y1", "z1")))  // 3/6 = 50%
    }

    // ---- groupStories ------------------------------------------------------------------

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"

    @Test
    fun `same story from two different feeds forms one thread led by the newest`() {
        val threads = groupStories(listOf(
            art("old", "ynet", attack1, at = 1_000_000_000_000L),
            art("new", "kan", attack2, at = 1_000_000_060_000L),
        ))
        assertEquals(1, threads.size)
        assertEquals("new", threads[0].lead.id)
        assertEquals(listOf("old"), threads[0].others.map { it.id })
        assertEquals(2, threads[0].size)
    }

    @Test
    fun `articles of the same feed never link directly`() {
        val threads = groupStories(listOf(
            art("a", "ynet", attack1),
            art("b", "ynet", attack2, at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
        assertTrue(threads.all { it.others.isEmpty() })
    }

    @Test
    fun `same feed articles join a thread through a third feed (transitive)`() {
        val threads = groupStories(listOf(
            art("y1", "ynet", attack1, at = 1_000_000_000_000L),
            art("k1", "kan", attack2, at = 1_000_000_010_000L),
            art("y2", "ynet", "ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
        ))
        assertEquals(1, threads.size)
        assertEquals(setOf("y1", "k1", "y2"), ids(threads[0]).toSet())
        assertEquals("y2", threads[0].lead.id)
    }

    @Test
    fun `articles more than six hours apart do not link`() {
        val apart = groupStories(listOf(
            art("a", "ynet", attack1, at = 1_000_000_000_000L),
            art("b", "kan", attack2, at = 1_000_000_000_000L + 6 * h + 1),
        ))
        assertEquals(2, apart.size)
        val inside = groupStories(listOf(
            art("a", "ynet", attack1, at = 1_000_000_000_000L),
            art("b", "kan", attack2, at = 1_000_000_000_000L + 6 * h),
        ))
        assertEquals(1, inside.size)
    }

    @Test
    fun `an unread article never joins a thread that was already read`() {
        val threads = groupStories(listOf(
            art("r1", "ynet", attack1, at = 1_000_000_000_000L, isRead = true, readAt = 5L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 5L),
            art("u", "walla", "פיגוע ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
        ))
        assertEquals(2, threads.size)
        val read = threads.first { it.lead.isRead }
        assertEquals(setOf("r1", "r2"), ids(read).toSet())
        assertEquals(listOf("u"), ids(threads.first { !it.lead.isRead }))
    }

    @Test
    fun `articles read at different times stay in separate threads`() {
        val threads = groupStories(listOf(
            art("r1", "ynet", attack1, isRead = true, readAt = 5L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 9L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `lead ties on time are broken by the smaller id`() {
        val threads = groupStories(listOf(
            art("b", "ynet", attack1),
            art("a", "kan", attack2),
        ))
        assertEquals("a", threads[0].lead.id)
    }

    @Test
    fun `threads are capped at twelve members and the older overflow stays single`() {
        val arts = (0 until 15).map { i ->
            art("m$i", "feed$i", if (i % 2 == 0) attack1 else attack2, at = 1_000_000_000_000L + i * 1_000L)
        }
        val threads = groupStories(arts)
        val big = threads.maxByOrNull { it.size }!!
        assertEquals(STORY_MAX_THREAD_SIZE, big.size)
        assertEquals("m14", big.lead.id)
        assertEquals(15 - STORY_MAX_THREAD_SIZE + 1, threads.size)
        assertEquals(15, threads.sumOf { it.size })
    }

    @Test
    fun `output keeps the input order with a thread sitting at its lead`() {
        val input = listOf(
            art("x", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", at = 3_000_000_000_000L),
            art("lead", "kan", attack2, at = 2_000_000_020_000L),
            art("y", "globes", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", at = 2_000_000_015_000L),
            art("member", "ynet", attack1, at = 2_000_000_000_000L),
        )
        val threads = groupStories(input)
        assertEquals(listOf("x", "lead", "y"), threads.map { it.lead.id })
        assertEquals(listOf("member"), threads[1].others.map { it.id })
    }

    @Test
    fun `the partition does not depend on the input order`() {
        val base = listOf(
            art("1", "ynet", attack1, at = 1_000_000_000_000L),
            art("2", "kan", attack2, at = 1_000_000_010_000L),
            art("3", "walla", "ירי בבנימין: פצוע קשה, מצוד אחר המחבל", at = 1_000_000_020_000L),
            art("4", "ynet", "הבורסה בתל אביב ננעלה בירידות חדות", at = 1_000_000_030_000L),
        )
        fun canon(l: List<ArticleItem>) = groupStories(l).map { ids(it).sorted() }.toSet()
        assertEquals(canon(base), canon(base.reversed()))
        assertEquals(canon(base), canon(listOf(base[2], base[0], base[3], base[1])))
    }

    @Test
    fun `filtering whole read classes out never changes the threads that remain`() {
        // Unread thread {u1,u2}, read thread {r1,r2} (same readAt) and an unrelated single. Every
        // filter (All / Unread only / Read only) keeps or drops whole classes, so the callbacks
        // (which see ALL stored articles) and the render (which sees the filtered list) agree.
        val all = listOf(
            art("u1", "ynet", attack1, at = 1_000_000_020_000L),
            art("u2", "kan", attack2, at = 1_000_000_030_000L),
            art("r1", "ynet", attack1, at = 1_000_000_000_000L, isRead = true, readAt = 7L),
            art("r2", "kan", attack2, at = 1_000_000_010_000L, isRead = true, readAt = 7L),
            art("s", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", at = 1_000_000_040_000L),
        )
        fun canon(l: List<ArticleItem>) = groupStories(l).map { ids(it).sorted() }.toSet()
        assertEquals(canon(all).filter { "u1" in it || "s" in it }.toSet(), canon(all.filter { !it.isRead }))
        assertEquals(canon(all).filter { "r1" in it }.toSet(), canon(all.filter { it.isRead }))
    }

    // ---- near misses that must NOT group (real headlines) -------------------------------

    @Test
    fun `same numbers but different meaning do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", "רוכב אופנוע בן 30 נהרג בתאונת דרכים בירושלים"),
            art("b", "kan", "בן 30 נפצע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `two different security events do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", attack1),
            art("b", "kan", "צה\"ל: חוסל מחבל שהטמין מטענים שהובאו מבית חולים בצפון הרצועה", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `same topic but different stories do not group`() {
        val threads = groupStories(listOf(
            art("a", "ynet", "איראן: קיבלנו מידע שארה\"ב מתכננת לתקוף - \"הבסיסים שלה יהיו יעד\""),
            art("b", "kan", "ארה״ב הטילה סנקציות על בורסת הקריפטו ששימשה את איראן לגביית עמלה במיצרי הורמוז", at = 1_000_000_060_000L),
        ))
        assertEquals(2, threads.size)
    }

    @Test
    fun `ungroupedStories wraps every article as its own thread`() {
        val l = listOf(art("a", "f1", "x1 y1 z1"), art("b", "f2", "x1 y1 z1"))
        assertEquals(listOf("a", "b"), ungroupedStories(l).map { it.lead.id })
        assertTrue(ungroupedStories(l).all { it.others.isEmpty() })
    }
}
```

- [ ] **Step 3: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.StoryThreadsTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -5
```
Expected: `Unresolved reference 'storyTokens'` (and others) and `BUILD FAILED`.

- [ ] **Step 4: Create the matcher**

Create `app/src/main/java/com/newsfeed/widget/data/StoryThreads.kt`. The regexes are Kotlin raw strings on purpose (the `\u05F4`-style escapes are interpreted by the regex engine, not by Kotlin):

```kotlin
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
```

- [ ] **Step 5: Run the matcher tests and confirm they pass**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.StoryThreadsTest" 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `BUILD SUCCESSFUL` (23 tests).

- [ ] **Step 6: Write the fixture test** (golden threads from real data, a group-count band, and a review listing written to `app/build/story-threads-review.txt`)

Create `app/src/test/java/com/newsfeed/widget/data/StoryThreadsFixtureTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the matcher over REAL headlines (src/test/resources/story_threads_fixture.tsv: 294 public
 * headlines from 11 feeds captured on 2026-09-20 and 2026-10-07; columns feedKey, publishedAt epoch
 * ms, title). Also writes the group list for human review to app/build/story-threads-review.txt.
 */
class StoryThreadsFixtureTest {

    private val articles: List<ArticleItem> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("story_threads_fixture.tsv")!!
            .bufferedReader(Charsets.UTF_8).readText()
        text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapIndexed { i, line ->
            val (feed, at, title) = line.split("\t", limit = 3)
            ArticleItem(
                id = "fx$i", feedId = feed, feedName = feed, title = title,
                publishedAt = at.toLong(), isRead = false,
            )
        }
    }

    private val threads: List<StoryThread> by lazy { groupStories(articles) }

    private fun find(feed: String, titleStart: String): ArticleItem {
        val hits = articles.filter { it.feedId == feed && it.title.startsWith(titleStart) }
        assertEquals("fixture lookup '$titleStart' in $feed", 1, hits.size)
        return hits[0]
    }

    private fun threadOf(a: ArticleItem) = threads.first { t -> t.members.any { it.id == a.id } }

    @Test
    fun `fixture loads and every article lands in exactly one thread`() {
        assertEquals(294, articles.size)
        assertEquals(294, threads.sumOf { it.size })
        assertEquals(294, threads.flatMap { it.members }.map { it.id }.toSet().size)
        assertTrue(threads.all { it.size <= STORY_MAX_THREAD_SIZE })
    }

    @Test
    fun `real group count stays in the reviewed range`() {
        val multi = threads.count { it.size > 1 }
        // Reviewed on 2026-10-07: 27 groups. A change outside this band means the matcher was
        // retuned - re-run the review (see the plan's Task 2 Step 8) before adjusting the band.
        assertTrue("groups=$multi", multi in 22..32)
    }

    @Test
    fun `the Binyamin shooting reports from six feeds form one thread`() {
        val a = find("ynet-news", "פצוע קשה בפיגוע ירי במעיין בבנימין")
        val b = find("kan-flash", "פצוע קשה בפיגוע ירי בבנימין")
        val c = find("n12-chat", "ניצן שפירא החשד לפיגוע בבנימין")
        val d = find("globes-all", "פיגוע בבנימין: בן 30 נפצע אנוש")
        val e = find("ynet-flash", "הירי בבנימין: גבר נפצע קשה")
        val t = threadOf(a)
        for (x in listOf(b, c, d, e)) assertTrue(x.title, t.members.any { it.id == x.id })
    }

    @Test
    fun `a Kan google-news headline with a source suffix joins its newsflash twin`() {
        val flash = find("kan-flash", "דיווחים בלבנון: צה\"ל תקף בדרום המדינה")
        val gnews = find("kan-gnews", "דיווחים בלבנון: צה\"ל תקף בדרום המדינה - כאן 11")
        assertEquals(threadOf(flash).lead.id, threadOf(gnews).lead.id)
    }

    @Test
    fun `hebrew prefixes do not stop the stock-market flash and the Globes article joining`() {
        val flash = find("ynet-flash", "הבורסה בתל אביב ננעלה בירידות חדות")
        val globes = find("globes-all", "הבורסה ננעלה בירידות חדות")
        assertEquals(threadOf(flash).lead.id, threadOf(globes).lead.id)
    }

    @Test
    fun `Eisenkot quote with different quote marks in rotter and ynet joins`() {
        val rotter = find("rotter", "איזנקוט: ''נתניהו פועל לזיוף האמת")
        val ynet = find("ynet-flash", "איזנקוט: \"נתניהו פועל לזיוף האמת")
        assertEquals(threadOf(rotter).lead.id, threadOf(ynet).lead.id)
    }

    @Test
    fun `the judo final is one thread of four articles even though two feeds repeat`() {
        val judo = articles.filter { "פרימו" in it.title }
        assertEquals(4, judo.size)
        assertEquals(1, judo.map { threadOf(it).lead.id }.toSet().size)
        assertEquals(4, threadOf(judo[0]).size)
    }

    @Test
    fun `a Jenin ramming attempt is not part of the Binyamin shooting thread`() {
        val jenin = find("ynet-flash", "צה\"ל: ניסיון פיגוע דריסה בצפון השומרון")
        val jeninKan = find("kan-gnews", "ניסיון פיגוע שני תוך שעה")
        val binyamin = find("ynet-news", "פצוע קשה בפיגוע ירי במעיין בבנימין")
        assertEquals(threadOf(jenin).lead.id, threadOf(jeninKan).lead.id)
        assertNotEquals(threadOf(jenin).lead.id, threadOf(binyamin).lead.id)
    }

    @Test
    fun `the Yom Kippur fast-times pieces of walla and the N12 channel are one thread, not tied to an unrelated baby story`() {
        val walla = find("walla", "יום כיפור 2026: זמני הצום ברחבי הארץ")
        val n12 = find("n12-tg", "יום כיפור: זמני כניסת ויציאת הצום")
        val unrelated = find("rotter", "בית שמש: בן 3 לא התעורר משנתו")
        assertEquals(threadOf(walla).lead.id, threadOf(n12).lead.id)
        assertNotEquals(threadOf(walla).lead.id, threadOf(unrelated).lead.id)
    }

    @Test
    fun `write the review listing`() {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.ROOT)
        val sb = StringBuilder()
        val multi = threads.filter { it.size > 1 }
        sb.append("${articles.size} articles, ${multi.size} threads, sizes ${multi.map { it.size }.sorted()}\n")
        for (t in multi) {
            sb.append("---\n")
            for (m in t.members) {
                sb.append("  ${if (m === t.lead) "*" else " "} ${fmt.format(Date(m.publishedAt))} ${m.feedId.padEnd(10)} | ${m.title.take(100)}\n")
            }
        }
        val out = File(System.getProperty("user.dir"), "build/story-threads-review.txt")
        out.parentFile!!.mkdirs()
        out.writeText(sb.toString(), Charsets.UTF_8)
        assertTrue(out.exists())
    }
}
```

- [ ] **Step 7: Run it and print the group listing**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.StoryThreads*" 2>&1 | grep -E "BUILD|FAILED|tests completed"
cat /c/Users/PhotoStudio/ClaudeWorkspace/repo/app/build/story-threads-review.txt
```
Expected: `BUILD SUCCESSFUL` (23 + 10 tests) and a first line `294 articles, 27 threads, sizes [2, 2, ..., 3, 3, 4, 5, 12]`, then one block per thread (`*` marks the lead = newest), e.g. the 12-member block is the Binyamin shooting (ynet, Kan, N12 Telegram + chat, Globes, rotter ...), and `איזנקוט: ''נתניהו פועל ...` (rotter) groups with the ynet flash that uses `"` quotes.

- [ ] **Step 8: REVIEW GATE - stop here and show the user the list**

Do NOT continue until the user has seen `app/build/story-threads-review.txt` (paste it; it is ~150 lines) and approved the groups. Things for the user to look at: wrong groups (two different stories in one block), and obvious misses (the same story left ungrouped; some are expected: paraphrases with no shared words, titles with fewer than 3 key words). If the user wants it stricter or looser, the only knobs are the four constants and `STOPWORDS` at the top of `StoryThreads.kt`; after any change re-run Step 7, re-review, and update the `22..32` band in `real group count stays in the reviewed range` plus the unit tests whose assertions change. The known borderline example (an N12 weather forecast linking with a Yom Kippur prayer-times item) is outside the committed fixture window and is documented as a limitation, not a test.

- [ ] **Step 9: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/StoryThreads.kt app/src/test/resources/story_threads_fixture.tsv app/src/test/java/com/newsfeed/widget/data/StoryThreadsTest.kt app/src/test/java/com/newsfeed/widget/data/StoryThreadsFixtureTest.kt
git commit -m "Add the story-thread matcher with a real-headline fixture" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Row layout (`ThreadRows`) and expand-state helpers (`ThreadState`)

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/data/ThreadRows.kt`, `app/src/main/java/com/newsfeed/widget/glance/ThreadState.kt`
- Create: `app/src/test/java/com/newsfeed/widget/data/ThreadRowsTest.kt`, `app/src/test/java/com/newsfeed/widget/glance/ThreadStateTest.kt`

- [ ] **Step 1: Write the layout test**

Create `app/src/test/java/com/newsfeed/widget/data/ThreadRowsTest.kt`:

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadRowsTest {

    private fun art(id: String, isRead: Boolean = false) = ArticleItem(
        id = id, feedId = "f$id", feedName = "F", title = "T$id",
        publishedAt = 1L, isRead = isRead,
    )

    private fun single(id: String) = StoryThread(art(id))
    private fun thread(lead: String, vararg others: String) = StoryThread(art(lead), others.map { art(it) })

    private val threads = listOf(single("a"), thread("b", "b1", "b2"), single("c"), thread("d", "d1"))

    @Test
    fun `collapsed threads cost one row and show their member count`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 100)
        assertEquals(listOf("a", "b", "c", "d"), l.rows.map { it.article.id })
        assertEquals(listOf(ThreadRowKind.SINGLE, ThreadRowKind.LEAD, ThreadRowKind.SINGLE, ThreadRowKind.LEAD), l.rows.map { it.kind })
        assertEquals(listOf(0, 2, 0, 1), l.rows.map { it.otherCount })
        assertEquals(4, l.availableRows)
        // the header counts ARTICLES: 1 + (1+2) + 1 + (1+1)
        assertEquals(7, l.articleCount)
        assertEquals(7, l.unreadCount)
    }

    @Test
    fun `an expanded thread lists lead then members and counts every row`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 100)
        assertEquals(listOf("a", "b", "b1", "b2", "c", "d"), l.rows.map { it.article.id })
        assertEquals(
            listOf(ThreadRowKind.SINGLE, ThreadRowKind.LEAD, ThreadRowKind.MEMBER, ThreadRowKind.MEMBER, ThreadRowKind.SINGLE, ThreadRowKind.LEAD),
            l.rows.map { it.kind },
        )
        assertEquals(true, l.rows[1].expanded)
        assertEquals("b", l.rows[2].leadId)
        assertEquals(6, l.availableRows)
        assertEquals(7, l.articleCount)   // still 7 articles: 1 + 3 (all shown) + 1 + 2 (d collapsed)
    }

    @Test
    fun `an unknown or stale expanded id changes nothing`() {
        val l = layoutThreads(threads, setOf("zzz", "a"), rowLimit = 100)   // "a" is a single, not a thread lead
        assertEquals(listOf("a", "b", "c", "d"), l.rows.map { it.article.id })
    }

    @Test
    fun `the row limit counts rows, so an expanded thread can be cut and Load more finishes it`() {
        val l = layoutThreads(threads, setOf("b"), rowLimit = 3)
        assertEquals(listOf("a", "b", "b1"), l.rows.map { it.article.id })
        assertEquals(6, l.availableRows)
        // b is expanded (its members are rows, not hidden), so only rows are counted
        assertEquals(3, l.articleCount)
    }

    @Test
    fun `a limit of one row still shows the first row and a collapsed lead counts its hidden articles`() {
        val l = layoutThreads(listOf(thread("x", "x1", "x2")), emptySet(), rowLimit = 1)
        assertEquals(1, l.rows.size)
        assertEquals(3, l.articleCount)
    }

    @Test
    fun `unread count includes unread hidden members only`() {
        val t = StoryThread(art("u"), listOf(art("m1"), art("m2", isRead = true)))   // mixed only for the arithmetic
        val l = layoutThreads(listOf(t), emptySet(), rowLimit = 10)
        assertEquals(3, l.articleCount)
        assertEquals(2, l.unreadCount)   // lead + m1
    }

    @Test
    fun `with grouping off (ungrouped threads) the layout is the plain list`() {
        val arts = listOf(art("a"), art("b", isRead = true), art("c"))
        val l = layoutThreads(ungroupedStories(arts), setOf("a"), rowLimit = 2)
        assertEquals(listOf("a", "b"), l.rows.map { it.article.id })
        assertEquals(true, l.rows.all { it.kind == ThreadRowKind.SINGLE })
        assertEquals(3, l.availableRows)
        assertEquals(2, l.articleCount)
        assertEquals(1, l.unreadCount)
    }

    @Test
    fun `non-positive limit gives no rows`() {
        val l = layoutThreads(threads, emptySet(), rowLimit = 0)
        assertEquals(0, l.rows.size)
        assertEquals(0, l.articleCount)
        assertEquals(4, l.availableRows)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.ThreadRowsTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -3
```
Expected: `Unresolved reference 'layoutThreads'`, `BUILD FAILED`.

- [ ] **Step 3: Create `ThreadRows.kt`**

```kotlin
package com.newsfeed.widget.data

/** What a flattened list row is: an ungrouped article, a thread's lead, or an expanded thread's other member. */
enum class ThreadRowKind { SINGLE, LEAD, MEMBER }

/**
 * One row of the widget's article list after grouping.
 *  - [leadId] is the id of this row's thread lead (the article's own id for SINGLE and LEAD).
 *  - [otherCount] / [expanded] / [hiddenUnread] are only meaningful on a LEAD row: how many members
 *    the thread has besides the lead, whether the user expanded it, and (collapsed only) how many
 *    of those hidden members are unread.
 */
data class ThreadRow(
    val article: ArticleItem,
    val kind: ThreadRowKind,
    val leadId: String,
    val otherCount: Int = 0,
    val expanded: Boolean = false,
    val hiddenUnread: Int = 0,
)

/**
 * The visible slice of the list plus the numbers the widget needs:
 *  - [rows]: the first `rowLimit` rows (this is what is rendered);
 *  - [availableRows]: how many rows exist in total (what "Load more" / "Showing X of Y" count);
 *  - [articleCount] / [unreadCount]: ARTICLES represented by [rows] (the header badge), i.e. a
 *    collapsed thread counts its hidden members too, so the badge means exactly what it did
 *    before threads existed.
 */
data class ThreadLayout(
    val rows: List<ThreadRow>,
    val availableRows: Int,
    val articleCount: Int,
    val unreadCount: Int,
)

/**
 * Flattens [threads] into rows: a one-member thread is a SINGLE row; a thread is a LEAD row, plus a
 * MEMBER row per other article when its lead id is in [expandedLeadIds]. Budget rule: a collapsed
 * thread costs ONE row, an expanded thread costs lead + members, and the cut is a plain
 * `take(rowLimit)` on the flat list (an expanded thread can therefore be cut mid-way; "Load more"
 * reveals the rest).
 */
fun layoutThreads(threads: List<StoryThread>, expandedLeadIds: Set<String>, rowLimit: Int): ThreadLayout {
    val flat = ArrayList<ThreadRow>(threads.size)
    for (t in threads) {
        if (t.others.isEmpty()) {
            flat += ThreadRow(t.lead, ThreadRowKind.SINGLE, t.lead.id)
            continue
        }
        val expanded = t.lead.id in expandedLeadIds
        flat += ThreadRow(
            article = t.lead,
            kind = ThreadRowKind.LEAD,
            leadId = t.lead.id,
            otherCount = t.others.size,
            expanded = expanded,
            hiddenUnread = if (expanded) 0 else t.others.count { !it.isRead },
        )
        if (expanded) t.others.forEach { flat += ThreadRow(it, ThreadRowKind.MEMBER, t.lead.id) }
    }
    val rows = flat.take(rowLimit.coerceAtLeast(0))
    val hidden = rows.filter { it.kind == ThreadRowKind.LEAD && !it.expanded }
    return ThreadLayout(
        rows = rows,
        availableRows = flat.size,
        articleCount = rows.size + hidden.sumOf { it.otherCount },
        unreadCount = rows.count { !it.article.isRead } + rows.sumOf { it.hiddenUnread },
    )
}
```

- [ ] **Step 4: Run the layout test and confirm it passes**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.ThreadRowsTest" 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `BUILD SUCCESSFUL` (8 tests).

- [ ] **Step 5: Write the expand-state test** (parse/encode, toggle, prune, cap, collapse cleanup, `storedThreads`, and "a tap-mode switch keeps the expanded threads")

Create `app/src/test/java/com/newsfeed/widget/glance/ThreadStateTest.kt`:

```kotlin
package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadStateTest {

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"

    private fun art(id: String, feed: String, title: String, at: Long) = ArticleItem(
        id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = false,
    )

    // m1 (ynet) + m2 (kan) are one thread led by m2; s is an unrelated single.
    private val articles = listOf(
        art("m2", "kan", attack2, 2_000L),
        art("m1", "ynet", attack1, 1_000L),
        art("s", "walla", "הבורסה בתל אביב ננעלה בירידות חדות", 3_000L),
    )

    private fun config(group: Boolean = true) = WidgetConfig(
        widgetId = 1, groupStories = group,
        feeds = listOf("ynet", "kan", "walla").map { FeedConfig(feedId = it, displayName = it) },
    )

    private fun prefs(group: Boolean = true, withConfig: Boolean = true) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles)
        if (withConfig) it[WidgetStateKey.configJson] = Json.encodeToString(config(group))
    }

    // ---- encode / parse ---------------------------------------------------------------

    @Test
    fun `ids round-trip in insertion order and blanks are dropped`() {
        assertEquals(listOf("a", "b", "c"), parseExpandedThreadIds(encodeExpandedThreadIds(listOf("a", "b", "c"))).toList())
        assertEquals(emptySet<String>(), parseExpandedThreadIds(null))
        assertEquals(emptySet<String>(), parseExpandedThreadIds(""))
        assertEquals(listOf("a", "b"), parseExpandedThreadIds("a\n\nb\n").toList())
    }

    @Test
    fun `an id that contains the separator is not stored`() {
        assertEquals("ok", encodeExpandedThreadIds(listOf("bad\nid", "ok")))
    }

    // ---- toggleThreadExpanded ---------------------------------------------------------

    @Test
    fun `toggling expands then collapses a thread`() {
        val p = prefs()
        assertTrue(toggleThreadExpanded(p, "m2"))
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
        assertFalse(toggleThreadExpanded(p, "m2"))
        assertNull(p[WidgetStateKey.expandedThreadIds])   // key removed when nothing is expanded
    }

    @Test
    fun `stale ids that no longer lead a multi-article thread are pruned`() {
        val p = prefs()
        p[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds(listOf("gone", "s", "m2"))
        toggleThreadExpanded(p, "m2")   // collapses m2 ...
        // ... "gone" (not an article), "s" (a single) and m2 (just collapsed) are all dropped
        assertNull(p[WidgetStateKey.expandedThreadIds])
        assertTrue(toggleThreadExpanded(p, "m2"))
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
    }

    @Test
    fun `toggling a lead that is not a current thread lead does not stay expanded`() {
        val p = prefs()
        assertFalse(toggleThreadExpanded(p, "s"))
        assertNull(p[WidgetStateKey.expandedThreadIds])
    }

    @Test
    fun `collapsing clears an expanded or focused article that is a hidden member`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")                       // expand
        p[WidgetStateKey.expandedArticleId] = "m1"
        p[WidgetStateKey.focusedArticleId] = "m1"
        toggleThreadExpanded(p, "m2")                       // collapse
        assertNull(p[WidgetStateKey.expandedArticleId])
        assertNull(p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `collapsing leaves the expanded or focused lead and unrelated articles alone`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")
        p[WidgetStateKey.expandedArticleId] = "s"
        p[WidgetStateKey.focusedArticleId] = "m2"
        toggleThreadExpanded(p, "m2")
        assertEquals("s", p[WidgetStateKey.expandedArticleId])
        assertEquals("m2", p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `expanding does not touch the tap state`() {
        val p = prefs()
        p[WidgetStateKey.expandedArticleId] = "m1"
        p[WidgetStateKey.lastTappedArticleId] = "m1"
        toggleThreadExpanded(p, "m2")
        assertEquals("m1", p[WidgetStateKey.expandedArticleId])
        assertEquals("m1", p[WidgetStateKey.lastTappedArticleId])
    }

    @Test
    fun `the remembered ids are capped and the oldest drop first`() {
        val p = prefs(withConfig = false)   // no config -> no pruning, so only the cap acts
        p[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds((1..35).map { "x$it" })
        assertTrue(toggleThreadExpanded(p, "new"))
        val kept = parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]).toList()
        assertEquals(EXPANDED_THREADS_MAX, kept.size)
        assertEquals("x7", kept.first())
        assertEquals("new", kept.last())
    }

    @Test
    fun `without a readable config the toggle still flips the id and prunes nothing`() {
        val p = prefs(withConfig = false)
        assertTrue(toggleThreadExpanded(p, "whatever"))
        assertEquals(setOf("whatever"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
        assertFalse(toggleThreadExpanded(p, "whatever"))
    }

    // ---- storedThreads ----------------------------------------------------------------

    @Test
    fun `storedThreads is null when grouping is off or the config is unreadable`() {
        assertNull(storedThreads(prefs(group = false), articles))
        assertNull(storedThreads(prefs(withConfig = false), articles))
        val bad = prefs(); bad[WidgetStateKey.configJson] = "{nope"
        assertNull(storedThreads(bad, articles))
    }

    @Test
    fun `storedThreads only groups articles of feeds still in the config`() {
        val p = prefs()
        val removedFeed = articles.map { if (it.id == "m1") it.copy(feedId = "removedFeed") else it }
        val threads = storedThreads(p, removedFeed)!!
        assertTrue(threads.all { it.others.isEmpty() })
        assertEquals(setOf("m2", "s"), threads.map { it.lead.id }.toSet())
    }

    // ---- interplay with the mode switch -----------------------------------------------

    @Test
    fun `switching tap mode leaves the expanded threads alone`() {
        val p = prefs()
        toggleThreadExpanded(p, "m2")
        p[WidgetStateKey.expandedArticleId] = "m1"
        resetTapState(p)
        assertNull(p[WidgetStateKey.expandedArticleId])
        assertEquals(setOf("m2"), parseExpandedThreadIds(p[WidgetStateKey.expandedThreadIds]))
    }
}
```

- [ ] **Step 6: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.ThreadStateTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -3
```
Expected: `Unresolved reference 'toggleThreadExpanded'` (and others), `BUILD FAILED`.

- [ ] **Step 7: Create `ThreadState.kt`**

```kotlin
package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.StoryThread
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.groupStories
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

// Persisted state and read helpers for story threads (WidgetConfig.groupStories, data/StoryThreads.kt).
// Everything here works on the widget's Glance Preferences so the tap callbacks (which cannot see
// the render's local variables) recompute exactly the threads the render drew.

/** Most expanded threads remembered per widget; the oldest-expanded ids drop first. */
internal const val EXPANDED_THREADS_MAX = 30
private const val SEP = '\n'

/** The expanded lead ids stored in [WidgetStateKey.expandedThreadIds] (insertion order kept). */
fun parseExpandedThreadIds(raw: String?): Set<String> =
    raw.orEmpty().split(SEP).filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

/** Inverse of [parseExpandedThreadIds]; ids containing the separator cannot be stored and are skipped. */
fun encodeExpandedThreadIds(ids: Collection<String>): String =
    ids.filter { it.isNotEmpty() && SEP !in it }.joinToString(SEP.toString())

/**
 * The threads of everything this widget has stored, or null when grouping does not apply (no
 * readable config, `groupStories` off, or no readable article list). Input = ALL stored articles of
 * the feeds still in the config - deliberately before the All / Unread only / Read only filter:
 * threads never mix read classes (see StoryThreads.kt), so a filtered render and this unfiltered
 * recomputation produce the same threads for every article the render can show.
 */
internal fun storedThreads(prefs: Preferences, articles: List<ArticleItem>): List<StoryThread>? {
    val config = prefs[WidgetStateKey.configJson]
        ?.let { runCatching { Json.decodeFromString<WidgetConfig>(it) }.getOrNull() }
        ?: return null
    if (!config.groupStories) return null
    val feedIds = config.feeds.map { it.feedId }.toSet()
    return groupStories(articles.filter { it.feedId in feedIds })
}

internal fun decodeStoredArticles(prefs: Preferences): List<ArticleItem>? =
    prefs[WidgetStateKey.articles]
        ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }

/**
 * Expands / collapses the thread led by [leadId] (the "+N" badge tap, ToggleThreadCallback). Also:
 *  - prunes remembered ids that no longer lead a thread of 2+ articles (a thread that dissolved
 *    away, was read and left, or whose lead changed because a newer article joined it);
 *  - when COLLAPSING, clears the expanded / focused article if it is one of the thread's hidden
 *    members, so no state points at a row that is no longer on screen;
 *  - caps the remembered ids at [EXPANDED_THREADS_MAX].
 * Returns true when the thread is expanded afterwards.
 */
internal fun toggleThreadExpanded(prefs: MutablePreferences, leadId: String): Boolean {
    val ids = parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds]).toMutableSet()
    val expandNow = if (leadId in ids) { ids.remove(leadId); false } else { ids.add(leadId); true }

    val articles = decodeStoredArticles(prefs)
    val threads = articles?.let { storedThreads(prefs, it) }
    if (threads != null) {
        val multiLeads = threads.filter { it.others.isNotEmpty() }.map { it.lead.id }.toSet()
        ids.retainAll(multiLeads)
        if (!expandNow) {
            val hiddenMembers = threads.firstOrNull { it.lead.id == leadId }?.others?.map { it.id }?.toSet().orEmpty()
            if (prefs[WidgetStateKey.expandedArticleId] in hiddenMembers) prefs.remove(WidgetStateKey.expandedArticleId)
            if (prefs[WidgetStateKey.focusedArticleId] in hiddenMembers) prefs.remove(WidgetStateKey.focusedArticleId)
        }
    }
    val kept = ids.toList().takeLast(EXPANDED_THREADS_MAX)
    if (kept.isEmpty()) prefs.remove(WidgetStateKey.expandedThreadIds)
    else prefs[WidgetStateKey.expandedThreadIds] = encodeExpandedThreadIds(kept)
    return expandNow && leadId in kept
}
```

- [ ] **Step 8: Run both tests and confirm they pass**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.ThreadStateTest" --tests "com.newsfeed.widget.data.ThreadRowsTest" 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `BUILD SUCCESSFUL` (13 + 8 tests).

- [ ] **Step 9: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/ThreadRows.kt app/src/main/java/com/newsfeed/widget/glance/ThreadState.kt app/src/test/java/com/newsfeed/widget/data/ThreadRowsTest.kt app/src/test/java/com/newsfeed/widget/glance/ThreadStateTest.kt
git commit -m "Add thread row layout and the per-widget expanded-thread state helpers" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Whole-thread read marking through the two move-away paths

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/ReadOnMoveAway.kt`, `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt`, `app/src/main/java/com/newsfeed/widget/data/GraceRefreshRegistry.kt`
- Create: `app/src/test/java/com/newsfeed/widget/glance/ThreadReadMarkingTest.kt`

The existing `markPreviousTappedRead` / `applyFocusTap` keep their names and `String?` results (thin wrappers) so `ReadOnMoveAwayTest` and `TapRoutingTest` stay untouched and must still pass: without a readable config in the prefs those tests see exactly the old single-article behaviour.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/newsfeed/widget/glance/ThreadReadMarkingTest.kt`:

```kotlin
package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FeedConfig
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whole-thread read marking through the two existing move-away paths (Expand and Focus). */
class ThreadReadMarkingTest {

    private val attack1 = "פצוע קשה בפיגוע ירי בבנימין, מצוד אחר המחבל"
    private val attack2 = "פצוע קשה בפיגוע ירי במעיין בבנימין, מצוד אחר המחבל - כאן 11"
    private val attack3 = "ירי בבנימין: פצוע קשה, מצוד אחר המחבל"

    private fun art(id: String, feed: String, title: String, at: Long, isRead: Boolean = false, readAt: Long? = null) =
        ArticleItem(id = id, feedId = feed, feedName = feed, title = title, publishedAt = at, isRead = isRead, readAt = readAt)

    private val feeds = listOf("ynet", "kan", "walla", "globes").map { FeedConfig(feedId = it, displayName = it) }

    // Thread T = {t3 (walla, lead), t2 (kan), t1 (ynet)}; stand-alone singles x and y.
    private val base = listOf(
        art("t3", "walla", attack3, 3_000L),
        art("t2", "kan", attack2, 2_000L),
        art("t1", "ynet", attack1, 1_000L),
        art("x", "globes", "הבורסה בתל אביב ננעלה בירידות חדות", 4_000L),
        art("y", "globes", "תוכנית חדשה למענקי דיור לזוגות צעירים בפריפריה", 5_000L),
    )

    private fun prefs(
        articles: List<ArticleItem> = base,
        group: Boolean = true,
        last: String? = null,
        focused: String? = null,
        withConfig: Boolean = true,
    ) = mutablePreferencesOf().also {
        it[WidgetStateKey.articles] = Json.encodeToString(articles)
        if (withConfig) it[WidgetStateKey.configJson] = Json.encodeToString(WidgetConfig(widgetId = 1, groupStories = group, feeds = feeds))
        if (last != null) it[WidgetStateKey.lastTappedArticleId] = last
        if (focused != null) it[WidgetStateKey.focusedArticleId] = focused
    }

    private fun stored(p: Preferences) = Json.decodeFromString<List<ArticleItem>>(p[WidgetStateKey.articles]!!)

    // ---- Expand mode: markPreviousTappedReadThread ------------------------------------

    @Test
    fun `leaving any member marks the whole thread read with one shared stamp`() {
        val p = prefs(last = "t2")
        val marked = markPreviousTappedReadThread(p, "x", now = 100L)
        assertEquals(listOf("t2", "t1", "t3"), marked)          // previous first, then the rest by id
        val s = stored(p)
        assertTrue(listOf("t1", "t2", "t3").all { id -> s.first { it.id == id }.let { it.isRead && it.readAt == 100L } })
        assertTrue(listOf("x", "y").none { id -> s.first { it.id == id }.isRead })
    }

    @Test
    fun `moving to another member of the same thread marks nothing`() {
        val p = prefs(last = "t3")
        assertEquals(emptyList<String>(), markPreviousTappedReadThread(p, "t1", now = 100L))
        assertEquals("t1", p[WidgetStateKey.lastTappedArticleId])   // tracking still follows the tap
        assertTrue(stored(p).none { it.isRead })
        // ... and leaving the thread afterwards marks all of it
        assertEquals(setOf("t1", "t2", "t3"), markPreviousTappedReadThread(p, "x", now = 200L).toSet())
    }

    @Test
    fun `a single article outside any thread is marked alone`() {
        val p = prefs(last = "x")
        assertEquals(listOf("x"), markPreviousTappedReadThread(p, "y", now = 100L))
        assertEquals(1, stored(p).count { it.isRead })
    }

    @Test
    fun `with grouping off only the previous article is marked`() {
        val p = prefs(group = false, last = "t2")
        assertEquals(listOf("t2"), markPreviousTappedReadThread(p, "x", now = 100L))
        assertEquals(setOf("t2"), stored(p).filter { it.isRead }.map { it.id }.toSet())
    }

    @Test
    fun `without a readable config the old single-article behaviour is kept`() {
        val p = prefs(withConfig = false, last = "t2")
        assertEquals(listOf("t2"), markPreviousTappedReadThread(p, "x", now = 100L))
        assertEquals("t2", markPreviousTappedRead(prefs(withConfig = false, last = "t2"), "x", now = 100L))
    }

    @Test
    fun `a later unread article joining the story forms its own new thread and the read ones stay read`() {
        val read = base.map { if (it.id.startsWith("t")) it.copy(isRead = true, readAt = 50L) else it }
        val newcomer = art("t4", "globes", "ירי בבנימין: פצוע קשה במעיין, מצוד אחר המחבל", 6_000L)
        val p = prefs(articles = read + newcomer, last = "t4")
        // leaving the newcomer marks ONLY the newcomer (no unread partner), readAt untouched elsewhere
        assertEquals(listOf("t4"), markPreviousTappedReadThread(p, "x", now = 100L))
        val s = stored(p)
        assertEquals(50L, s.first { it.id == "t1" }.readAt)
        assertEquals(100L, s.first { it.id == "t4" }.readAt)
    }

    @Test
    fun `the legacy single-id wrapper returns the previous article`() {
        assertEquals("t2", markPreviousTappedRead(prefs(last = "t2"), "x", now = 100L))
        assertNull(markPreviousTappedRead(prefs(last = "t2"), "t1", now = 100L))   // same thread
    }

    // ---- Focus mode: applyFocusTapThread ----------------------------------------------

    @Test
    fun `focus moving off a member to another article marks the whole thread`() {
        val p = prefs(focused = "t3")
        val marked = applyFocusTapThread(p, "x", now = 100L)
        assertEquals(listOf("t3", "t1", "t2"), marked)
        assertEquals("x", p[WidgetStateKey.focusedArticleId])
        assertEquals(3, stored(p).count { it.isRead && it.readAt == 100L })
    }

    @Test
    fun `focus moving between members of one thread marks nothing`() {
        val p = prefs(focused = "t3")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "t2", now = 100L))
        assertEquals("t2", p[WidgetStateKey.focusedArticleId])
        assertTrue(stored(p).none { it.isRead })
    }

    @Test
    fun `clearing focus by re-tapping marks nothing`() {
        val p = prefs(focused = "t3")
        assertEquals(emptyList<String>(), applyFocusTapThread(p, "t3", now = 100L))
        assertEquals("", p[WidgetStateKey.focusedArticleId])
    }

    @Test
    fun `focus with grouping off marks only the article that lost focus`() {
        val p = prefs(group = false, focused = "t3")
        assertEquals(listOf("t3"), applyFocusTapThread(p, "x", now = 100L))
    }

    @Test
    fun `the legacy applyFocusTap wrapper still returns the article that lost focus`() {
        assertEquals("t3", applyFocusTap(prefs(focused = "t3"), "x", now = 100L))
    }

    // ---- dissolve / grace schedule key ------------------------------------------------

    @Test
    fun `a thread is scheduled under one representative id`() {
        assertEquals("t1", GraceRefreshRegistry.representativeId(listOf("t3", "t1", "t2")))
        assertEquals("only", GraceRefreshRegistry.representativeId(listOf("only")))
        assertNull(GraceRefreshRegistry.representativeId(emptyList()))
        // the tap callback and the render-time resume see the same members, hence the same key
        val fromCallback = GraceRefreshRegistry.representativeId(listOf("t2", "t1", "t3"))
        val fromResume = GraceRefreshRegistry.representativeId(listOf("t3", "t2", "t1"))
        assertEquals(fromCallback, fromResume)
    }

    @Test
    fun `members marked read together form one read class and so one thread afterwards`() {
        val p = prefs(last = "t2")
        markPreviousTappedReadThread(p, "x", now = 100L)
        val threads = storedThreads(p, stored(p))!!
        val readThread = threads.first { it.lead.isRead }
        assertEquals(setOf("t1", "t2", "t3"), readThread.members.map { it.id }.toSet())
        assertFalse(threads.first { it.lead.id == "x" }.lead.isRead)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.ThreadReadMarkingTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -4
```
Expected: `Unresolved reference 'markPreviousTappedReadThread'` / `'applyFocusTapThread'` / `'representativeId'`, `BUILD FAILED`.

- [ ] **Step 3: Implement**

**Edit 1 of 2** in `app/src/main/java/com/newsfeed/widget/glance/ReadOnMoveAway.kt`: replace

```kotlin

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
```

with

```kotlin

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.WidgetStateKey
```

**Edit 2 of 2** in `app/src/main/java/com/newsfeed/widget/glance/ReadOnMoveAway.kt`: replace

```kotlin

/**
 * Expand mode (tapMode = expand): an article is marked read only when the user moves on to a different one,
 * not when pressed. Records [tappedId] as the last-tapped article and, if a different, still
 * unread article was tapped before it, flags that one read (isRead + readAt = [now]) in
 * [prefs]. Returns the id that was newly marked read, or null if nothing changed (first tap,
 * re-tap of the same article, previous already read/missing, or unreadable article JSON).
 * Never re-stamps readAt on an already-read article.
 */
internal fun markPreviousTappedRead(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): String? {
    val prev = prefs[WidgetStateKey.lastTappedArticleId] ?: ""
    prefs[WidgetStateKey.lastTappedArticleId] = tappedId
    if (prev.isBlank() || prev == tappedId) return null

    val articles = prefs[WidgetStateKey.articles]
        ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
        ?: return null
    val target = articles.firstOrNull { it.id == prev } ?: return null
    if (target.isRead) return null

    prefs[WidgetStateKey.articles] = Json.encodeToString(
        articles.map { if (it.id == prev) it.copy(isRead = true, readAt = now) else it }
    )
    return prev
}

/**
```

with

```kotlin

/**
 * What "moving on" marks read. When [fromId] (the article the user is leaving) is unread:
 *  - grouping off / not in a thread: just [fromId];
 *  - in a story thread (data/StoryThreads.kt, WidgetConfig.groupStories): the WHOLE thread - [fromId]
 *    first, then the other members by id - recomputed from [articles], the same threads the render
 *    drew. Exception: if [toId] is another member of that same thread the user has not moved on
 *    from the story, so nothing is marked (returns empty).
 * An already-read or missing [fromId] returns empty.
 */
internal fun idsToMarkReadOnMoveAway(
    prefs: Preferences,
    articles: List<ArticleItem>,
    fromId: String,
    toId: String?,
): List<String> {
    val from = articles.firstOrNull { it.id == fromId } ?: return emptyList()
    if (from.isRead) return emptyList()
    val thread = storedThreads(prefs, articles)?.firstOrNull { t -> t.members.any { it.id == fromId } }
    if (thread == null || thread.others.isEmpty()) return listOf(fromId)
    if (toId != null && thread.members.any { it.id == toId }) return emptyList()
    val rest = thread.members.filter { !it.isRead && it.id != fromId }.map { it.id }.sorted()
    return listOf(fromId) + rest
}

/** Stamps every article whose id is in [ids] read at [now]; everything else is returned unchanged. */
internal fun markIdsRead(articles: List<ArticleItem>, ids: Collection<String>, now: Long): List<ArticleItem> {
    val set = ids.toSet()
    return articles.map { if (it.id in set) it.copy(isRead = true, readAt = now) else it }
}

/**
 * Expand mode (tapMode = expand): an article is marked read only when the user moves on to a different one,
 * not when pressed. Records [tappedId] as the last-tapped article and, if a different, still
 * unread article was tapped before it, flags it read (isRead + readAt = [now]) in [prefs] - together
 * with the rest of its story thread when grouping is on (see [idsToMarkReadOnMoveAway]; every
 * member gets the SAME readAt, so the thread dissolves and leaves Unread only as one). Returns the
 * ids newly marked read, previous article first (empty if nothing changed: first tap, re-tap of the
 * same article, tap within the same thread, previous already read/missing, or unreadable article
 * JSON). Never re-stamps readAt on an already-read article.
 */
internal fun markPreviousTappedReadThread(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): List<String> {
    val prev = prefs[WidgetStateKey.lastTappedArticleId] ?: ""
    prefs[WidgetStateKey.lastTappedArticleId] = tappedId
    if (prev.isBlank() || prev == tappedId) return emptyList()

    val articles = decodeStoredArticles(prefs) ?: return emptyList()
    val ids = idsToMarkReadOnMoveAway(prefs, articles, prev, tappedId)
    if (ids.isEmpty()) return emptyList()

    prefs[WidgetStateKey.articles] = Json.encodeToString(markIdsRead(articles, ids, now))
    return ids
}

/** Single-article view of [markPreviousTappedReadThread]: the previous article's id, or null. */
internal fun markPreviousTappedRead(
    prefs: MutablePreferences,
    tappedId: String,
    now: Long = System.currentTimeMillis(),
): String? = markPreviousTappedReadThread(prefs, tappedId, now).firstOrNull()

/**
```

**Edit 1 of 2** in `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt`: replace

```kotlin
 * article to ANOTHER, the article losing focus is flagged read (stamped [now]) inside
 * articles_json, but only if it was actually unread, so re-tapping through already-read
 * articles doesn't restart a grace period that doesn't apply to them.
 *
 * Returns the id of the article that was just flagged read, or null if none was.
 */
internal fun applyFocusTap(prefs: MutablePreferences, articleId: String, now: Long): String? {
    var articleLosingFocusId: String? = null
    val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
    val movingToAnotherArticle = current.isNotBlank() && current != articleId
```

with

```kotlin
 * article to ANOTHER, the article losing focus is flagged read (stamped [now]) inside
 * articles_json, but only if it was actually unread, so re-tapping through already-read
 * articles doesn't restart a grace period that doesn't apply to them. With story threads on, the
 * article's whole thread is flagged read with the same stamp, unless focus merely moved to another
 * member of that same thread (see idsToMarkReadOnMoveAway).
 *
 * Returns the ids that were just flagged read, the article that lost focus first (empty if none).
 */
internal fun applyFocusTapThread(prefs: MutablePreferences, articleId: String, now: Long): List<String> {
    var marked: List<String> = emptyList()
    val current = prefs[WidgetStateKey.focusedArticleId] ?: ""
    val movingToAnotherArticle = current.isNotBlank() && current != articleId
```

**Edit 2 of 2** in `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt`: replace

```kotlin

    if (movingToAnotherArticle) {
        val articles = prefs[WidgetStateKey.articles]
            ?.let { runCatching { Json.decodeFromString<List<ArticleItem>>(it) }.getOrNull() }
        if (articles != null) {
            val target = articles.firstOrNull { it.id == current }
            if (target != null && !target.isRead) {
                prefs[WidgetStateKey.articles] = Json.encodeToString(
                    articles.map { if (it.id == current) it.copy(isRead = true, readAt = now) else it }
                )
                articleLosingFocusId = current
            }
        }
    }
    return articleLosingFocusId
}

/**
```

with

```kotlin

    if (movingToAnotherArticle) {
        val articles = decodeStoredArticles(prefs)
        if (articles != null) {
            val ids = idsToMarkReadOnMoveAway(prefs, articles, current, articleId)
            if (ids.isNotEmpty()) {
                prefs[WidgetStateKey.articles] = Json.encodeToString(markIdsRead(articles, ids, now))
                marked = ids
            }
        }
    }
    return marked
}

/** Single-article view of [applyFocusTapThread]: the article that lost focus and was marked, or null. */
internal fun applyFocusTap(prefs: MutablePreferences, articleId: String, now: Long): String? =
    applyFocusTapThread(prefs, articleId, now).firstOrNull()

/**
```

**Edit 1 of 1** in `app/src/main/java/com/newsfeed/widget/data/GraceRefreshRegistry.kt`: replace

```kotlin
        fun key(widgetKey: String, articleId: String, readAt: Long): String =
            "$widgetKey|$articleId|$readAt"
    }
}
```

with

```kotlin
        fun key(widgetKey: String, articleId: String, readAt: Long): String =
            "$widgetKey|$articleId|$readAt"

        /**
         * The one article id a group of articles marked read together (a story thread: same readAt)
         * is scheduled under, so the tap callback and the render-time resume derive the SAME key and
         * never schedule the dissolve refreshes twice. A single article is its own representative.
         */
        fun representativeId(articleIds: Collection<String>): String? = articleIds.minOrNull()
    }
}
```

- [ ] **Step 4: Run the new and the existing read-marking tests**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.ThreadReadMarkingTest" --tests "com.newsfeed.widget.glance.ReadOnMoveAwayTest" --tests "com.newsfeed.widget.glance.CollapseExpandedOnOtherTapTest" --tests "com.newsfeed.widget.glance.TapRoutingTest" --tests "com.newsfeed.widget.data.GraceRefreshRegistryTest" 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `BUILD SUCCESSFUL` (14 new tests; `ReadOnMoveAwayTest` 8, `TapRoutingTest` 18 unchanged).

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/glance/ReadOnMoveAway.kt app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt app/src/main/java/com/newsfeed/widget/data/GraceRefreshRegistry.kt app/src/test/java/com/newsfeed/widget/glance/ThreadReadMarkingTest.kt
git commit -m "Mark a whole story thread read when the user moves on from any of its articles" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Callbacks use the thread helpers; `ToggleThreadCallback`; `ReadStatusStore.markReadAll`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/ReadStatusStore.kt`, `glance/ToggleExpandCallback.kt`, `glance/NoOpTapFeedbackCallback.kt`, `glance/SetFocusArticleCallback.kt`
- Create: `app/src/main/java/com/newsfeed/widget/glance/ToggleThreadCallback.kt`

These are Android-bound (DataStore / Glance), so there is no unit test; they are compile-checked here and verified on the device in Task 9 (cases `O-02` to `O-05`).

- [ ] **Step 1: One DataStore write for a whole thread**

**Edit 1 of 1** in `app/src/main/java/com/newsfeed/widget/data/ReadStatusStore.kt`: replace

```kotlin
        }

    suspend fun markRead(articleId: String) {
        context.readStatusDataStore.edit { prefs ->
            val current: List<String> = prefs[READ_IDS_KEY]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = (current + articleId).distinct().takeLast(MAX_STORED)
            prefs[READ_IDS_KEY] = Json.encodeToString(updated)
        }
```

with

```kotlin
        }

    suspend fun markRead(articleId: String) = markReadAll(listOf(articleId))

    /** Marks several articles read in one DataStore write (a whole story thread at once). */
    suspend fun markReadAll(articleIds: Collection<String>) {
        if (articleIds.isEmpty()) return
        context.readStatusDataStore.edit { prefs ->
            val current: List<String> = prefs[READ_IDS_KEY]
                ?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) }
                ?: emptyList()
            val updated = (current + articleIds).distinct().takeLast(MAX_STORED)
            prefs[READ_IDS_KEY] = Json.encodeToString(updated)
        }
```

- [ ] **Step 2: The three tap callbacks mark and schedule for the whole thread**

**Edit 1 of 2** in `app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt`: replace

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

with

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

**Edit 2 of 2** in `app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt`: replace

```kotlin
        // Stamped into readAt AND used as the base for the dissolve/removal refresh delays.
        val markedAt = System.currentTimeMillis()
        var markedReadId: String? = null
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            prefs[WidgetStateKey.expandedArticleId] = if (current != articleId) articleId else ""
            markedReadId = markPreviousTappedRead(prefs, articleId, markedAt)
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, markedReadId, markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

with

```kotlin
        // Stamped into readAt AND used as the base for the dissolve/removal refresh delays.
        val markedAt = System.currentTimeMillis()
        var markedReadIds: List<String> = emptyList()
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            val current = prefs[WidgetStateKey.expandedArticleId] ?: ""
            prefs[WidgetStateKey.expandedArticleId] = if (current != articleId) articleId else ""
            markedReadIds = markPreviousTappedReadThread(prefs, articleId, markedAt)
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
        }
        if (markedReadIds.isNotEmpty()) ReadStatusStore(context).markReadAll(markedReadIds)
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        // A whole story thread shares one readAt, so one schedule (under its representative id) covers it.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, GraceRefreshRegistry.representativeId(markedReadIds), markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

**Edit 1 of 2** in `app/src/main/java/com/newsfeed/widget/glance/NoOpTapFeedbackCallback.kt`: replace

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

with

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

**Edit 2 of 2** in `app/src/main/java/com/newsfeed/widget/glance/NoOpTapFeedbackCallback.kt`: replace

```kotlin
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        val markedAt = System.currentTimeMillis()
        var markedReadId: String? = null
        var collapsed = false
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            collapsed = collapseExpandedOnOtherTap(prefs, articleId)
            markedReadId = markPreviousTappedRead(prefs, articleId, markedAt)
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
        }
        markedReadId?.let { ReadStatusStore(context).markRead(it) }
        // Only re-render when something actually changed (the previous article's unread dot
        // cleared, or the expanded article collapsed) — otherwise a plain tap changes nothing
        // visible, and the native press ripple already fired regardless.
        if (markedReadId != null || collapsed) NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, markedReadId, markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

with

```kotlin
        val articleId = parameters[ARTICLE_ID_KEY] ?: return
        val markedAt = System.currentTimeMillis()
        var markedReadIds: List<String> = emptyList()
        var collapsed = false
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            collapsed = collapseExpandedOnOtherTap(prefs, articleId)
            markedReadIds = markPreviousTappedReadThread(prefs, articleId, markedAt)
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
        }
        if (markedReadIds.isNotEmpty()) ReadStatusStore(context).markReadAll(markedReadIds)
        // Only re-render when something actually changed (the previous article's unread dot
        // cleared, or the expanded article collapsed) — otherwise a plain tap changes nothing
        // visible, and the native press ripple already fired regardless.
        if (markedReadIds.isNotEmpty() || collapsed) NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, GraceRefreshRegistry.representativeId(markedReadIds), markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

**Edit 1 of 2** in `app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt`: replace

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

with

```kotlin
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.ReadStatusStore
import com.newsfeed.widget.data.WidgetStateKey
```

**Edit 2 of 2** in `app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt`: replace

```kotlin
        // doesn't apply to them.
        val markedAt = System.currentTimeMillis()
        var articleLosingFocusId: String? = null
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
            articleLosingFocusId = applyFocusTap(prefs, articleId, markedAt)
        }
        articleLosingFocusId?.let { ReadStatusStore(context).markRead(it) }
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, articleLosingFocusId, markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

with

```kotlin
        // doesn't apply to them.
        val markedAt = System.currentTimeMillis()
        var markedReadIds: List<String> = emptyList()
        var scheduleGrace = true
        updateAppWidgetState(context, glanceId) { prefs ->
            scheduleGrace = shouldScheduleGraceRefreshForConfig(prefs[WidgetStateKey.configJson])
            markedReadIds = applyFocusTapThread(prefs, articleId, markedAt)
        }
        if (markedReadIds.isNotEmpty()) ReadStatusStore(context).markReadAll(markedReadIds)
        NewsFeedWidget().update(context, glanceId)
        // Dissolve/removal only exist under Show = Unread only; skip the 4 delayed renders otherwise.
        // A whole story thread shares one readAt, so one schedule (under its representative id) covers it.
        if (scheduleGrace) UnreadGracePeriod.scheduleRefresh(context, glanceId, GraceRefreshRegistry.representativeId(markedReadIds), markedAt) { c, g -> NewsFeedWidget().update(c, g) }
    }
}
```

- [ ] **Step 3: Create the badge callback**

Create `app/src/main/java/com/newsfeed/widget/glance/ToggleThreadCallback.kt`:

```kotlin
package com.newsfeed.widget.glance

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState

// Story threads (WidgetConfig.groupStories): the "+N" badge on a thread's lead row expands / collapses
// the thread, i.e. shows or hides its other members as rows right under the lead. Only the badge
// routes here; a tap on the row itself still does what the widget's tap mode says. Toggling touches
// nothing but WidgetStateKey.expandedThreadIds (and, on collapse, drops an expanded/focused id that
// points at a now-hidden member): it never marks anything read, because opening a thread is not
// "moving on" from an article.
class ToggleThreadCallback : ActionCallback {
    companion object {
        val LEAD_ID_KEY = ActionParameters.Key<String>("threadLeadId")
    }

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val leadId = parameters[LEAD_ID_KEY] ?: return
        updateAppWidgetState(context, glanceId) { prefs -> toggleThreadExpanded(prefs, leadId) }
        NewsFeedWidget().update(context, glanceId)
    }
}
```

- [ ] **Step 4: Compile**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD"
```
Expected: no `e:` lines, `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/ReadStatusStore.kt app/src/main/java/com/newsfeed/widget/glance/ToggleExpandCallback.kt app/src/main/java/com/newsfeed/widget/glance/NoOpTapFeedbackCallback.kt app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt app/src/main/java/com/newsfeed/widget/glance/ToggleThreadCallback.kt
git commit -m "Wire whole-thread read marking into the tap callbacks and add ToggleThreadCallback" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Render threads in the widget (rows, badge, members, budget, dissolve)

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`, `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`

What the `NewsFeedWidget.kt` edits do, in the order they appear: import the new helpers; read `expandedThreadIds`; replace the old `displayArticles = availableArticles.take(..)` with `groupStories`/`ungroupedStories` -> `layoutThreads` (limit = `visibleCount.coerceAtMost(maxRowsAllowed)`) -> dissolve on every visible row; base `canLoadMoreArticles`, `memoryCapReached` and the two `Showing X of Y` strings on `layout.availableRows` (rows); take the header's unread/total from `layout` (ARTICLES) while the Focus `N/M` keeps counting rows; make the render-time grace resume schedule ONCE PER `readAt` under the representative id (the key the callbacks use); and render each row, members inside a narrow gutter. `FeedItemRow` gets two defaulted parameters and the badge: a `Text` with its OWN `.clickable(...)`, the same nesting as the `Open article ->` / `Share ↗` / `Load full article ↓` pills already inside the row's clickable `Column`/`Row` (child click wins), so a badge tap toggles the thread and does not also fire the row action. Meta-line direct children stay at 9 (RemoteViews allows 10).

- [ ] **Step 1: Edit `NewsFeedWidget.kt`**

**Edit 1 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
```

with

```kotlin
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
```

**Edit 2 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.OrphanCleanup
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.applyFilterAndSort
import com.newsfeed.widget.data.dissolveArticle
import com.newsfeed.widget.data.isDissolving
import com.newsfeed.widget.data.UNREAD_GRACE_PERIOD_MS
import com.newsfeed.widget.data.OrphanCleanupWorker
```

with

```kotlin
import com.newsfeed.widget.data.ArticleItem
import com.newsfeed.widget.data.FilterMode
import com.newsfeed.widget.data.GraceRefreshRegistry
import com.newsfeed.widget.data.OrphanCleanup
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.ThreadRowKind
import com.newsfeed.widget.data.WidgetConfig
import com.newsfeed.widget.data.WidgetStateKey
import com.newsfeed.widget.data.applyFilterAndSort
import com.newsfeed.widget.data.dissolveArticle
import com.newsfeed.widget.data.groupStories
import com.newsfeed.widget.data.isDissolving
import com.newsfeed.widget.data.layoutThreads
import com.newsfeed.widget.data.ungroupedStories
import com.newsfeed.widget.data.UNREAD_GRACE_PERIOD_MS
import com.newsfeed.widget.data.OrphanCleanupWorker
```

**Edit 3 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
    // (both callbacks clear this key on any change of *which* article is focused).
    val focusScale        = prefs[WidgetStateKey.focusScale] ?: AdjustFocusScaleCallback.DEFAULT_SCALE

    val config = configJson
```

with

```kotlin
    // (both callbacks clear this key on any change of *which* article is focused).
    val focusScale        = prefs[WidgetStateKey.focusScale] ?: AdjustFocusScaleCallback.DEFAULT_SCALE
    // Story threads (config.groupStories): lead ids of the threads the user expanded with the "+N"
    // badge. Independent of the tap mode and of expandedArticleId (a thread and an article can both
    // be expanded at once).
    val expandedThreadIds = parseExpandedThreadIds(prefs[WidgetStateKey.expandedThreadIds])

    val config = configJson
```

**Edit 4 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
    // Applied to the article itself so standard rows, Focus rows and the Focus enlarged view
    // (all fed from displayArticles) dissolve identically.
    val displayArticles = availableArticles.take(visibleCount.coerceAtMost(maxRowsAllowed))
        .let { rows ->
            if (config.filter == FilterMode.UNREAD.key) rows.map { dissolveArticle(it, now) } else rows
        }
    // Based on visibleCount (what's been requested), not displayArticles.size (what's
    // actually shown after clamping) — comparing the clamped size against maxRowsAllowed
```

with

```kotlin
    // Applied to the article itself so standard rows, Focus rows and the Focus enlarged view
    // (all fed from displayArticles) dissolve identically.
    //
    // Story threads: when config.groupStories is on, the post-filter/sort list is grouped into
    // threads (data/StoryThreads.kt) and flattened to ROWS (data/ThreadRows.kt). A collapsed thread
    // is ONE row (its lead, with a "+N" badge), an expanded thread is the lead plus one row per
    // other member; maxRowsAllowed / visibleCount / "Load more" all count rows. With the switch off
    // every article is its own single row, i.e. exactly the list this code rendered before threads.
    // Dissolve is applied to every visible row, members included: a thread is read as a whole with
    // one shared readAt, so all of its rows dissolve in lockstep.
    val threads = if (config.groupStories) groupStories(availableArticles) else ungroupedStories(availableArticles)
    val layout = layoutThreads(threads, expandedThreadIds, visibleCount.coerceAtMost(maxRowsAllowed))
    val displayRows = layout.rows.let { rows ->
        if (config.filter == FilterMode.UNREAD.key) rows.map { it.copy(article = dissolveArticle(it.article, now)) } else rows
    }
    val displayArticles = displayRows.map { it.article }
    // Based on visibleCount (what's been requested), not displayArticles.size (what's
    // actually shown after clamping) — comparing the clamped size against maxRowsAllowed
```

**Edit 5 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
    // of only once truly exhausted. visibleCount vs the two ceilings is what actually
    // determines whether tapping "Load more" would reveal anything new.
    val canLoadMoreArticles = visibleCount < maxRowsAllowed && visibleCount < availableArticles.size
    // Complementary "stuck" state: maxRowsAllowed itself (not the requested chunk size) is
    // the binding constraint, so tapping "Load more" would never reveal anything no matter
```

with

```kotlin
    // of only once truly exhausted. visibleCount vs the two ceilings is what actually
    // determines whether tapping "Load more" would reveal anything new.
    val canLoadMoreArticles = visibleCount < maxRowsAllowed && visibleCount < layout.availableRows
    // Complementary "stuck" state: maxRowsAllowed itself (not the requested chunk size) is
    // the binding constraint, so tapping "Load more" would never reveal anything no matter
```

**Edit 6 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
    // bug: "Load more" permanently unreachable at normal/large font sizes).
    val memoryCapReached = displayArticles.size >= maxRowsAllowed &&
        displayArticles.size < availableArticles.size
    // Scoped to displayArticles, not the full accumulated store (which can hold up to
    // 300) — counting the full store made the header badge claim "99+" unread while only
    // a fraction of articles were ever reachable by scrolling, which read as a bug (and
    // was reported as one) rather than the accumulation feature it actually was.
    val unreadCount     = displayArticles.count { !it.isRead }
    // Focus mode only (tapMode = focus, isFocusWidget) — position within what's actually rendered
    // (displayArticles), so the "N / M" indicator always matches what's really on screen.
```

with

```kotlin
    // bug: "Load more" permanently unreachable at normal/large font sizes).
    val memoryCapReached = displayArticles.size >= maxRowsAllowed &&
        displayArticles.size < layout.availableRows
    // Scoped to displayArticles, not the full accumulated store (which can hold up to
    // 300) — counting the full store made the header badge claim "99+" unread while only
    // a fraction of articles were ever reachable by scrolling, which read as a bug (and
    // was reported as one) rather than the accumulation feature it actually was.
    // Counts ARTICLES, not rows: a collapsed thread contributes its hidden members too, so the
    // badge means what it always did (see ThreadLayout).
    val unreadCount     = layout.unreadCount
    // Focus mode only (tapMode = focus, isFocusWidget) — position within what's actually rendered
    // (displayArticles), so the "N / M" indicator always matches what's really on screen.
```

**Edit 7 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
        if (resumeArticles.isNotEmpty()) {
            SideEffect {
                resumeArticles.forEach { a ->
                    UnreadGracePeriod.scheduleRefresh(graceContext, graceGlanceId, a.id, a.readAt!!) { c, g ->
                        NewsFeedWidget().update(c, g)
                    }
                }
            }
```

with

```kotlin
        if (resumeArticles.isNotEmpty()) {
            SideEffect {
                // One schedule per readAt, not per article: a story thread's members share one readAt
                // and one schedule re-renders them all. Keyed by the group's representative id, the
                // same key the tap callbacks register, so the two never double-schedule.
                resumeArticles.groupBy { it.readAt!! }.forEach { (readAt, group) ->
                    UnreadGracePeriod.scheduleRefresh(
                        graceContext, graceGlanceId,
                        GraceRefreshRegistry.representativeId(group.map { it.id }), readAt,
                    ) { c, g -> NewsFeedWidget().update(c, g) }
                }
            }
```

**Edit 8 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
                .padding(0.dp),
        ) {
            WidgetHeader(unreadCount, displayArticles.size, focusedArticleId, focusedIndex, displayArticles.size, isFocusWidget)
            Divider()
```

with

```kotlin
                .padding(0.dp),
        ) {
            // Header counts: unread(total) are ARTICLES (layout.articleCount); the Focus "N/M" position
            // counts the ROWS actually on screen (displayArticles.size).
            WidgetHeader(unreadCount, layout.articleCount, focusedArticleId, focusedIndex, displayArticles.size, isFocusWidget)
            Divider()
```

**Edit 9 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
                    // whether it's the row-highlight bug's exact root cause, which is not yet
                    // independently confirmed on-device.
                    items(displayArticles, itemId = { it.id.hashCode().toLong() }) { article ->
                        val feedConfig = feedMap[article.feedId] ?: return@items
                        val isLast = article == displayArticles.last()
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            FeedItemRow(
                                article           = article,
                                feedConfig        = feedConfig,
                                expandedArticleId = expandedArticleId,
                                fontSize          = config.fontSize,
                                articleFontSize   = config.articleFontSize,
                                articleLength     = config.articleLength,
                                fullArticleId     = fullArticleId,
                                fullArticleText   = fullArticleText,
                                fullArticleShown  = fullArticleShown,
                                useThemeColors    = config.useThemeColors,
                                widgetTheme       = config.widgetTheme,
                                externalApp       = config.externalApp,
                                themeVariant      = config.themeVariant,
                                focusedArticleId  = focusedArticleId,
                                focusScale        = focusScale,
                                isFocusWidget     = isFocusWidget,
                                isDissolving      = config.filter == FilterMode.UNREAD.key && isDissolving(article, now),
                            )
                            if (!isLast) {
                                Box(modifier = GlanceModifier
```

with

```kotlin
                    // whether it's the row-highlight bug's exact root cause, which is not yet
                    // independently confirmed on-device.
                    items(displayRows, itemId = { it.article.id.hashCode().toLong() }) { row ->
                        val article = row.article
                        val feedConfig = feedMap[article.feedId] ?: return@items
                        val isLast = row == displayRows.last()
                        Column(modifier = GlanceModifier.fillMaxWidth()) {
                            // A thread member (shown only while its thread is expanded) gets a narrow
                            // gutter on the left so it reads as nested under the lead; it is otherwise
                            // the same row, with the same tap behaviour for the widget's tap mode.
                            @Composable
                            fun ArticleRow() {
                                FeedItemRow(
                                    article           = article,
                                    feedConfig        = feedConfig,
                                    expandedArticleId = expandedArticleId,
                                    fontSize          = config.fontSize,
                                    articleFontSize   = config.articleFontSize,
                                    articleLength     = config.articleLength,
                                    fullArticleId     = fullArticleId,
                                    fullArticleText   = fullArticleText,
                                    fullArticleShown  = fullArticleShown,
                                    useThemeColors    = config.useThemeColors,
                                    widgetTheme       = config.widgetTheme,
                                    externalApp       = config.externalApp,
                                    themeVariant      = config.themeVariant,
                                    focusedArticleId  = focusedArticleId,
                                    focusScale        = focusScale,
                                    isFocusWidget     = isFocusWidget,
                                    isDissolving      = config.filter == FilterMode.UNREAD.key && isDissolving(article, now),
                                    threadOtherCount  = if (row.kind == ThreadRowKind.LEAD) row.otherCount else 0,
                                    threadExpanded    = row.expanded,
                                )
                            }
                            if (row.kind == ThreadRowKind.MEMBER) {
                                Row(modifier = GlanceModifier.fillMaxWidth()) {
                                    Box(modifier = GlanceModifier
                                        .width(14.dp)
                                        .fillMaxHeight()
                                        .background(GlanceTheme.colors.surfaceVariant)) {}
                                    Column(modifier = GlanceModifier.defaultWeight()) { ArticleRow() }
                                }
                            } else {
                                ArticleRow()
                            }
                            if (!isLast) {
                                Box(modifier = GlanceModifier
```

**Edit 10 of 10** in `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`: replace

```kotlin
                                Text(
                                    text = if (atFontFloor)
                                        "Showing ${displayArticles.size} of ${availableArticles.size} · this device can't show more at once"
                                    else
                                        "Showing ${displayArticles.size} of ${availableArticles.size} · reduce font size to see more",
                                    style = TextStyle(
                                        fontSize   = 11.sp,
```

with

```kotlin
                                Text(
                                    text = if (atFontFloor)
                                        "Showing ${displayArticles.size} of ${layout.availableRows} · this device can't show more at once"
                                    else
                                        "Showing ${displayArticles.size} of ${layout.availableRows} · reduce font size to see more",
                                    style = TextStyle(
                                        fontSize   = 11.sp,
```

- [ ] **Step 2: Edit `FeedItemRow.kt`**

**Edit 1 of 4** in `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`: replace

```kotlin
    // otherwise pass dissolved text to FetchFullArticleCallback).
    isDissolving: Boolean = false,
) {
    val context        = LocalContext.current
```

with

```kotlin
    // otherwise pass dissolved text to FetchFullArticleCallback).
    isDissolving: Boolean = false,
    // Story threads (WidgetConfig.groupStories, see data/StoryThreads.kt): on a thread's LEAD row,
    // how many other articles the thread holds (> 0 shows the "+N" badge in the meta line) and whether
    // the thread is currently expanded. Both stay at their defaults for every other row (single
    // articles, thread members), which then render exactly as before.
    threadOtherCount: Int = 0,
    threadExpanded: Boolean = false,
) {
    val context        = LocalContext.current
```

**Edit 2 of 4** in `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`: replace

```kotlin
                )

                // Feed name is grouped on this same line with the favicon circle, right-
                // justified as a unit. This row is now always the column's full width — the
```

with

```kotlin
                )

                // Story-thread badge ("+3 ▾" collapsed / "+3 ▴" expanded): its OWN clickable inside the
                // row, so tapping it toggles the thread (ToggleThreadCallback) and does not also fire
                // the row's tap action. Nested clickables already work in this list: the "Open article →",
                // "Share ↗" and "Load full article ↓" pills below are clickables inside the same
                // Column/Row clickables. Hidden while the row is dissolving (the whole thread is
                // about to leave). Direct children of the meta Row stay <= 9 (RemoteViews allows 10).
                @Composable
                fun ThreadBadge(spaceBefore: Boolean) {
                    if (threadOtherCount > 0 && !isDissolving) {
                        if (spaceBefore) Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = "+$threadOtherCount " + (if (threadExpanded) "▴" else "▾"),
                            style = TextStyle(
                                fontSize   = metaFontSize,
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                color      = GlanceTheme.colors.onPrimaryContainer,
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier
                                .background(GlanceTheme.colors.primaryContainer)
                                .cornerRadius(6.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable(actionRunCallback<ToggleThreadCallback>(
                                    actionParametersOf(ToggleThreadCallback.LEAD_ID_KEY to article.id)
                                )),
                        )
                    }
                }

                // Feed name is grouped on this same line with the favicon circle, right-
                // justified as a unit. This row is now always the column's full width — the
```

**Edit 3 of 4** in `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`: replace

```kotlin
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    Spacer(GlanceModifier.defaultWeight())
                    if (!article.isRead) {
```

with

```kotlin
                    Text(formatDateTime(article.publishedAt), style = tsStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    ThreadBadge(spaceBefore = false)
                    Spacer(GlanceModifier.defaultWeight())
                    if (!article.isRead) {
```

**Edit 4 of 4** in `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`: replace

```kotlin
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.defaultWeight())
                }
            }
```

with

```kotlin
                    Text(feedConfig.displayName, style = nameStyle, maxLines = 1,
                        modifier = GlanceModifier.defaultWeight())
                    ThreadBadge(spaceBefore = true)
                }
            }
```

- [ ] **Step 3: Compile and run the whole unit-test suite**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD"
bash /c/t/g.sh :app:testDebugUnitTest 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: no `e:` lines. The test run ends `267 tests completed, 1 failed` with the single failure `WidgetThemesTest > colorProvidersFor custom wraps the same scheme rawColorSchemeFor produces FAILED` (known, pre-existing) and `BUILD FAILED` only because of it.

- [ ] **Step 4: Build the APK** (also validates resources/manifest)

```bash
bash /c/t/g.sh :app:assembleDebug 2>&1 | grep -E "^e: |BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt
git commit -m "Render story threads: lead row with +N badge, member rows, row budget, whole-thread dissolve" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 7: The "Group similar stories" switch in Settings

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`

The row goes in SORT & FILTER directly after "When I tap an article" (and its Focus hint). It uses the same `Row(SpaceBetween)` + `Switch` pattern as "Use theme accent colors", so it needs no new imports (`Switch` is already imported) and mirrors under an RTL locale like its siblings. Nothing applies until Save (existing behaviour); Save already writes the whole config into `config_json`, so no save-code change is needed (the tap-mode `resetTapState` path is untouched and deliberately does not clear `expandedThreadIds`).

- [ ] **Step 1: Add the switch**

**Edit 1 of 1** in `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt`: replace

```kotlin
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
```

with

```kotlin
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                // Group similar stories: the same story reported by different feeds
                                // collapses into one row with a "+N" badge (default on, per widget).
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Group similar stories", style = MaterialTheme.typography.bodyMedium)
                                        Text("Same story from different feeds becomes one row; tap its +N badge to expand",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(
                                        checked = config.groupStories,
                                        onCheckedChange = { config = config.copy(groupStories = it) },
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
```

- [ ] **Step 2: Compile**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD"
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Add the Group similar stories switch to widget settings" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Docs, QA plan section O, release note

**Files:** `README.md`, `docs/PRD.md`, `docs/RELEASE_NOTES.md`, `docs/QA_PLAN.md`, `docs/DEBUG_PLAN.md`

- [ ] **Step 1: README, new feature section.** Insert before `### Default feeds`:

Edit `README.md`: replace

```
### Default feeds
```

with

```
### Story threads (group similar stories)

When several feeds report the same news story within a few hours (for example ynet, N12, Kan and a Telegram channel), the widget shows it as **one row**: the newest report, with a small **+N ▾** badge at the end of its meta line (N = the other reports). Tap the badge to list the other reports right under it (the badge turns into **+N ▴**; tap it again to fold them away). Tapping the row itself works as usual for your tap mode.

- **Matching** is by the key words of the headline (punctuation, quotes, niqqud and common Hebrew/English filler words ignored), across **different** feeds, within 6 hours, needing at least 3 shared key words covering 60% of the shorter headline. Up to 12 reports per thread; the newest is the lead. Headlines that are worded very differently are not grouped.
- **Reading:** a thread is marked read as a whole when you move on from any of its articles to something outside it (moving between its own articles marks nothing). Under **Unread only** the whole thread then fades out and disappears together. A later report of the same story starts a new unread thread.
- **Counts:** the header badge still counts articles; the row limit and **Load more** count rows (a folded thread is one row).
- **Switch it off per widget** in Settings → Sort & Filter → **Group similar stories** (on by default). Off shows every article as its own row, as before.

### Default feeds
```

- [ ] **Step 2: README, settings table row.** Replace

```
| When I tap an article | Expand in place · Focus (enlarge) | Per widget; see [Focus Mode](#focus-mode). Switching resets the widget's expanded/focused article (the Focus zoom set with − / + is kept) |
```

with

```
| When I tap an article | Expand in place · Focus (enlarge) | Per widget; see [Focus Mode](#focus-mode). Switching resets the widget's expanded/focused article (the Focus zoom set with − / + is kept) |
| Group similar stories | On (default) · Off | Per widget; see [Story threads](#story-threads-group-similar-stories). Off restores one row per article |
```

- [ ] **Step 3: README, project structure.** Replace

```
│   │   ├── ToggleExpandCallback.kt    # ActionCallback — expand/collapse article (Expand mode)
```

with

```
│   │   ├── ToggleExpandCallback.kt    # ActionCallback — expand/collapse article (Expand mode)
│   │   ├── ToggleThreadCallback.kt    # ActionCallback — the +N badge: expand/collapse a story thread
│   │   ├── ThreadState.kt             # Expanded-thread state (prune/cap), thread recomputation from stored articles for the callbacks
```

and replace

```
│   │   ├── FeedConfig.kt             # Data models (FeedConfig, WidgetConfig, ArticleItem, enums)
```

with

```
│   │   ├── FeedConfig.kt             # Data models (FeedConfig, WidgetConfig, ArticleItem, enums)
│   │   ├── StoryThreads.kt           # Pure matcher: groups the same story from different feeds into threads (key words, 6 h window, union-find)
│   │   ├── ThreadRows.kt             # Flattens threads into list rows; row budget and article counts
```

- [ ] **Step 4: PRD row.** In `docs/PRD.md`, replace

```
| Unread-only 5-second grace period | ✅ | A just-read article stays visible, dimmed, for 5s before disappearing under "Unread only" — no instant, unexplained vanish. |
```

with

```
| Unread-only 5-second grace period | ✅ | A just-read article stays visible, dimmed, for 5s before disappearing under "Unread only" — no instant, unexplained vanish. |
| Story threads (group similar stories) | 🚧 | The same story reported by different feeds collapses into one row with a +N badge; tapping the badge lists the other reports; moving on marks the whole thread read; per-widget switch, on by default. See `docs/superpowers/specs/2026-10-07-story-threads-design.md`. (Flip to ✅ when the release gate passes.) |
```

- [ ] **Step 5: Release note.** Confirm the next free number, then append it:

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
grep -n '^## Note' docs/RELEASE_NOTES.md | tail -2
```
Expected: the last heading is `## Note 11` (Notes 10 and 11 exist, unpushed). If a higher number exists, use that number + 1 below.

```bash
printf '\n## Note 12\n- New: when several feeds report the same story, the widget now shows it as one row with a small +N badge. Tap the badge to see the other reports; reading one marks the whole group read. You can switch this off per widget in Settings under "Group similar stories".\n' >> docs/RELEASE_NOTES.md
tail -4 docs/RELEASE_NOTES.md
```
Expected: the file ends with `## Note 12` and its single bullet (blank line before the heading).

- [ ] **Step 6: QA plan.** Apply these edits to `docs/QA_PLAN.md`.

6a. Contents list: replace

```
   [N Focus as a setting & state cleanup](#n--focus-as-a-setting--state-cleanup)
```

with

```
   [N Focus as a setting & state cleanup](#n--focus-as-a-setting--state-cleanup) ·
   [O Story threads](#o--story-threads)
```

6b. Full run list. Replace

```
4. Section N in full (Focus as a setting, mode switching, orphan cleanup).
```

with

```
4. Sections N and O in full (N: Focus as a setting, mode switching, orphan cleanup; O: story threads, run with Group similar stories ON, the default, plus the OFF cases).
```

and replace

```
2. Matrix `G-M01…G-M12` (filter × sort × tap behaviour) — all 12 rows, each in both tap behaviours.
```

with

```
2. Matrix `G-M01…G-M12` (filter × sort × tap behaviour) — all 12 rows, each in both tap behaviours. Run them with Group similar stories ON (the default); `O-06` covers OFF.
```

6c. Smoke text. Replace

```
41 rows = **36 distinct executions** (`M-01`, `M-02`, `M-10`, `M-40`, `M-41` are the same execution as `F-01/F-02`, `F-04`, `F-12`, `N-30`, `F-23`), ≈ 45–60 min for A–M (≈ 60–80 min with section N). `E-20` and `F-08` are the Focus-mode cases. Section N adds 12 more P0 cases (`N-01…N-07`, `N-09`, `N-14`, `N-18`, `N-27`, `N-30`), so the Smoke subset is 53 rows.
```

with

```
41 rows = **36 distinct executions** (`M-01`, `M-02`, `M-10`, `M-40`, `M-41` are the same execution as `F-01/F-02`, `F-04`, `F-12`, `N-30`, `F-23`), ≈ 45–60 min for A–M (≈ 60–80 min with section N). `E-20` and `F-08` are the Focus-mode cases. Section N adds 12 more P0 cases (`N-01…N-07`, `N-09`, `N-14`, `N-18`, `N-27`, `N-30`) and section O adds 7 more (`O-01…O-07`), so the Smoke subset is 60 rows.
```

6d. Case-count table: replace

```
| N Focus as a setting & state cleanup | 31 | 12 | 19 | 0 |
```

with

```
| N Focus as a setting & state cleanup | 31 | 12 | 19 | 0 |
| O Story threads | 27 | 7 | 19 | 1 |
```

and replace

```
| **Total** | **309** | **53** | **237** | **19** |
```

with

```
| **Total** | **336** | **60** | **256** | **20** |
```

6e. New section O, inserted directly before the `---` that precedes `## Appendix A — Cases added because of a bug`: replace

```
---

## Appendix A — Cases added because of a bug
```

with

```
---

## O — Story threads

**Status: shipped in code (`docs/superpowers/plans/2026-10-07-story-threads.md`); awaiting the on-device release run.** Spec `docs/superpowers/specs/2026-10-07-story-threads-design.md`. Per-widget switch **Group similar stories** (SORT & FILTER, after "When I tap an article"; default **on**, also for widgets saved by older builds: `WidgetConfig.groupStories`). The same story from DIFFERENT feeds (different `feedId`, within 6 h, >= 3 shared key words covering >= 60% of the shorter title, threads never mix read states, max 12 per thread) renders as ONE lead row (the newest article) with a `+N ▾` pill (`+N ▴` expanded) at the end of its meta line; the pill is its own clickable (`ToggleThreadCallback`), tapping the row works as the tap mode says. Expanded members are rows under the lead (14 dp gutter). Moving on from any member marks the WHOLE thread read with one shared `readAt` (moving between members of one thread marks nothing). State: `expanded_thread_ids` (newline-separated lead ids) in the widget's Glance DataStore. Budget: collapsed thread = 1 row, expanded = lead + members; header `U(T)` still counts articles, Focus `N/M` counts rows. Unit tests: `StoryThreadsTest`, `StoryThreadsFixtureTest` (294 real headlines; review listing in `app/build/story-threads-review.txt`), `ThreadRowsTest`, `ThreadStateTest`, `ThreadReadMarkingTest`, `WidgetConfigGroupStoriesTest`. Run the matrix `G-M01…G-M12` with grouping ON (default) as well; the cases below add the thread-specific checks. Needs a day with real overlapping coverage; if the feeds are quiet, plant articles by editing a backed-up `articles_json` (same `publishedAt` window, different `feedId`, similar titles) and restoring afterwards.

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| O-01 | P0 | Switch ON (default), Show All, Sort Newest, >= 3 feeds. Find a story reported by 2+ feeds within 6 h (e.g. a Kan newsflash + its Google-News twin, or ynet + N12) ⇒ ONE row, the **newest** article, with a `+N ▾` pill (N = other reports); the others are not in the list; they appear nowhere else (not at their own time slots) | UI, SS, DS (`articles_json` still holds every article) | UT:StoryThreadsTest, StoryThreadsFixtureTest |
| O-02 | P0 | Tap the `+N ▾` pill ⇒ the other reports appear right under the lead, newest first, indented by the gutter, pill becomes `+N ▴`; `expanded_thread_ids` = the lead id. Tap it again ⇒ they fold away, key removed. The pill tap did **not** expand/enlarge the lead, did not change `expanded_article_id` / `last_tapped_article_id` / `focused_article_id`, and marked nothing read | UI, DS, SS | UT:ThreadStateTest |
| O-03 | P0 | **Expand mode**, Show All: tap the lead (it expands), then tap an unrelated article ⇒ in `articles_json` EVERY member of the thread has `isRead: true` and the **same** `readAt`; `newsfeed_read_status` contains all member ids; the unrelated article is unread; header unread count drops by the thread size | DS, UI | UT:ThreadReadMarkingTest |
| O-04 | P0 | Same as `O-03` in **Focus mode**: focus the lead, then focus an unrelated article ⇒ whole thread read with one `readAt`; re-tapping the focused lead (clearing focus) marks nothing | DS | UT:ThreadReadMarkingTest |
| O-05 | P0 | **Unread only**, Expand and Focus: read a thread as in `O-03` with the thread expanded ⇒ lead AND member rows all dissolve in lockstep (normal, dots, 2/3, 1/3) and leave together at ≈ 5 s; nothing half-removed; no extra renders: logcat shows the same ≤ 5 `updateAppWidget` calls per read as for a single article (one schedule per `readAt`) | TS, DS, LC | UT:ThreadReadMarkingTest (representative id) |
| O-06 | P0 | Switch OFF + Save ⇒ every article is its own row, no pill, the list equals the pre-feature list for the same data (compare row order/count with an OFF-widget on the previous build or with `articles_json`), header `U(T)` unchanged; marking read marks a single article; stored `expanded_thread_ids` ignored. Switch ON + Save ⇒ threads return, and previously expanded threads are expanded again | UI, DS | UT:WidgetConfigGroupStoriesTest, ThreadRowsTest |
| O-07 | P0 | Expand a thread, tap the lead then a **member** (Expand mode) and, in Focus mode, focus the lead then a member ⇒ **nothing** is marked read (no `readAt` anywhere, dots stay); `last_tapped_article_id` / `focused_article_id` follow the tap. Then tap an article outside the thread ⇒ the whole thread is marked | DS | UT:ThreadReadMarkingTest |
| O-08 | P1 | Settings: row **Group similar stories** (+ one-line explanation) directly after "When I tap an article" (and its Focus hint), switch ON on a brand-new widget AND on a widget whose config was saved by an older build (no `groupStories` key); turn off, Save ⇒ `config_json` and `newsfeed_config` carry `"groupStories":false`; back on, Save ⇒ key absent (default); Back without Save changes nothing | UI, DS | UT:WidgetConfigGroupStoriesTest |
| O-09 | P1 | Header `U(T)`: with a collapsed 4-article thread among others, U and T count ARTICLES (the thread contributes 4); expanding it does not change U or T; Focus `N/M` counts the rows on screen (expanded members included) | UI | UT:ThreadRowsTest |
| O-10 | P1 | Budget: a collapsed thread costs one row, an expanded one lead + members. With `visible_article_count` 10, expand a 5-member thread near the top ⇒ the list still shows exactly 10 ROWS (the last rows are pushed out, **Load more** reveals the next 10); `Showing X of Y` (memory cap) and **Load more** counts are in rows | UI, DS | UT:ThreadRowsTest |
| O-11 | P1 | Sort: Newest, Oldest, By feed, Unread first ⇒ each thread sits at its LEAD's slot of that order, members never appear separately, no article is shown twice or lost (count rows + hidden members = articles) | UI, DS | UT:StoryThreadsTest (order) |
| O-12 | P1 | Filter All / Unread only / Read only with threads present ⇒ Read only shows read threads whole (same members as in All); Unread only shows unread threads whole; a thread is never shown with only some of its members; the set of threads for a filter equals the matching threads of All | UI, DS | UT:StoryThreadsTest (filter invariance) |
| O-13 | P1 | A thread is read (marked), then a new article about the same story is fetched ⇒ the new article is an **unread** single/thread of its own; the read articles keep their `readAt` and stay a separate read thread (visible under All/Read only) | DS, UI | UT:StoryThreadsTest, ThreadReadMarkingTest |
| O-14 | P1 | **Expand mode**: tap an expanded member with a description ⇒ it expands under its own row; only one article is expanded at a time (tapping the lead or another article collapses it); the thread stays expanded independently; collapsing the thread while a member is expanded clears `expanded_article_id` | UI, DS | UT:ThreadStateTest |
| O-15 | P1 | **Focus mode**: a member can be focused (enlarges, header `N/M` and − / + appear); collapsing the thread while a member is focused clears `focused_article_id`; the lead can be focused with its pill intact (meta line stays at normal size) | UI, DS, SS | UT:ThreadStateTest |
| O-16 | P1 | Switch the tap mode Expand↔Focus on a widget with an expanded thread, Save ⇒ `expanded_article_id`, `focused_article_id`, `last_tapped_article_id` cleared as in `N-09` but `expanded_thread_ids` **kept**; the thread is still expanded | DS, UI | UT:ThreadStateTest |
| O-17 | P1 | Telegram: an N12 channel post (title = first two lines) and an RSS article of the same story within 6 h form a thread (channel post may be lead or member); a Telegram boilerplate tail "קישור לכתבה" does not prevent it | UI, DS | UT:StoryThreadsFixtureTest |
| O-18 | P1 | Refresh (footer tap, Save, scheduled) with a read thread and an unread expanded thread ⇒ read members stay read with the same `readAt`; the unread thread stays expanded; no member flips back to unread; a refresh that brings a newer matching article makes it the new lead and the thread collapses with the updated `+N` | DS, UI | UT:ArticleMergeTest |
| O-19 | P1 | State hygiene: expand a thread, read it and let it leave (Unread only) or let it age out; expand another ⇒ the stale lead id is pruned from `expanded_thread_ids`; the key disappears when nothing is expanded; never more than 30 ids | DS | UT:ThreadStateTest |
| O-20 | P1 | Memory: Glamour theme, Font 3.0, article font 3.0, a 12-member thread expanded; Focus with scale 2.5 on a member ⇒ renders, no "Can't show content", no `exceeds maximum bitmap memory`; the row cap equals the pre-feature cap | SS, LC | none |
| O-21 | P1 | Quality on live data: run a full day of the default feeds with grouping ON, list every thread (`expanded` each) and note any wrong group (two different stories) and any missed obvious duplicate; record counts. Wrong groups are S3 (recorded, the switch is the escape hatch); a crash or lost article is S1 | UI, SS | UT:StoryThreadsFixtureTest (294 headlines) |
| O-22 | P1 | Tap target and nesting: the pill's tap area is comfortable at Font 0.5, 1.0, 3.0 (uiautomator `bounds` >= 44 dp high at 1.0), works for an RTL feed row (pill physically left, after the time) and an LTR row (pill right), tapping next to it still triggers the row action, and a pill tap never also triggers the row (no expansion, no ripple-only read) | UI (bounds), DS | none |
| O-23 | P1 | While a thread dissolves (Unread only) the pill is hidden and no Load full article / Open article / Share buttons appear on the dissolving rows | TS, UI | none |
| O-24 | P1 | Two widgets (one Expand, one Focus), one with the switch OFF ⇒ independent: expanded threads, read tracking and the setting never leak between them | DS | none |
| O-25 | P2 | Remove a widget that had expanded threads ⇒ its `appWidget-<id>.preferences_pb` (which holds `expanded_thread_ids`) is deleted by the existing cleanup; nothing else to clean | DS | UT:OrphanCleanupTest |
| O-26 | P1 | `docs/RELEASE_NOTES.md` has `## Note 12` (story threads) and it shows in the update dialog | UI | UT:ReleaseNotesFetcherTest |
| O-27 | P1 | Unit tests pass (`StoryThreadsTest` 23, `StoryThreadsFixtureTest` 10, `ThreadRowsTest` 8, `ThreadStateTest` 13, `ThreadReadMarkingTest` 14, `WidgetConfigGroupStoriesTest` 4, existing `ReadOnMoveAwayTest` and `TapRoutingTest` unchanged) and docs are in sync (README feature section + settings row, PRD row, release Note 12, this section, DEBUG_PLAN 14.w) | UT, VIS | UT |

---

## Appendix A — Cases added because of a bug
```

- [ ] **Step 7: Recompute the case counts by script and check them against what you typed**

Create `/c/t/ext/qacount.py` (it counts the table rows of `docs/QA_PLAN.md` per section and priority):

```python
"""Counts QA_PLAN.md case rows per section and priority.  usage: python qacount.py docs/QA_PLAN.md"""
import re
import sys
from collections import OrderedDict

counts = OrderedDict()
for line in open(sys.argv[1], encoding='utf-8'):
    m = re.match(r'^\| ([A-Z])-(M?\d+) \| (P[012]) \|', line)
    if m:
        sec = m.group(1)
        counts.setdefault(sec, {'P0': 0, 'P1': 0, 'P2': 0})[m.group(3)] += 1
tot = {'P0': 0, 'P1': 0, 'P2': 0}
for sec, c in counts.items():
    n = sum(c.values())
    print(f"{sec}: {n:3d}  P0={c['P0']:2d} P1={c['P1']:3d} P2={c['P2']:2d}")
    for k in tot:
        tot[k] += c[k]
print(f"Total: {sum(tot.values())}  P0={tot['P0']} P1={tot['P1']} P2={tot['P2']}")
```

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
mkdir -p /c/t/ext
PYTHONIOENCODING=utf-8 python /c/t/ext/qacount.py docs/QA_PLAN.md
```

Expected last lines: `N:  31  P0=12 P1= 19 P2= 0`, `O:  27  P0= 7 P1= 19 P2= 1`, `Total: 336  P0=60 P1=256 P2=20`. If your numbers differ, fix the table you typed in 6d (and the "60 rows" in 6c) to match the script. (Before this task's edits the same script prints `Total: 309  P0=53 P1=237 P2=19`, matching the existing table.)

- [ ] **Step 8: DEBUG_PLAN pending rows** (release-gate record; `docs/DEBUG_PLAN.md` is shared with the on-device QA session)

First `git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo status --short docs/DEBUG_PLAN.md`: it must print nothing. If it shows changes, the QA session is editing it: do this step later (or ask), do not overwrite. Then in `docs/DEBUG_PLAN.md` replace

```
---

## Reporting
```

with

```
### 14.w Story threads (group similar stories), pending device verification

Feature: `WidgetConfig.groupStories` (default on), `data/StoryThreads.kt`, `data/ThreadRows.kt`, `glance/ThreadState.kt`, `ToggleThreadCallback`, whole-thread read marking. Spec `docs/superpowers/specs/2026-10-07-story-threads-design.md`, plan `docs/superpowers/plans/2026-10-07-story-threads.md`, QA cases `docs/QA_PLAN.md` section O. Fill in build/HEAD/versionCode and the evidence when run on the device (Task 9 of the plan); until then every row is Pending.

| Feature | What must be true | Verified how | Result |
|---|---|---|---|
| Threads form (O-01) | Same story from 2+ feeds = one lead row (newest) with `+N ▾`; members hidden | uiautomator + `articles_json` | Pending |
| Badge toggles, does not fire the row (O-02, O-22) | Pill expands/collapses members, state in `expanded_thread_ids`, no read/tap-state change | uiautomator bounds, DataStore pulls | Pending |
| Whole-thread read, Expand and Focus (O-03, O-04, O-07) | All members `isRead` with one `readAt`; moving inside a thread marks nothing | DataStore pulls | Pending |
| Unread only dissolve (O-05, O-23) | All rows dissolve in lockstep and leave together; ≤ 5 renders per read | timed screenshots, logcat render count | Pending |
| Switch off (O-06, O-08) | Exactly the old list; `"groupStories":false` persisted; on again restores | uiautomator, DataStore | Pending |
| Counts and budget (O-09, O-10, O-20) | Header counts articles; limits and Load more count rows; no memory error | uiautomator, logcat | Pending |
| Sort/filter/read-class (O-11, O-12, O-13) | Thread at lead slot; never half a thread; newcomer is a new thread | uiautomator, DataStore | Pending |
| Tap modes and mode switch (O-14, O-15, O-16) | Members expand/focus; collapse cleans pointers; mode switch keeps expanded threads | DataStore pulls | Pending |
| Refresh / multi-widget / remove (O-18, O-19, O-24, O-25) | Read state and expanded state survive refresh; independent widgets; removal cleans up | DataStore pulls | Pending |
| Quality on a real day (O-21) | No wrong groups of consequence | group listing | Pending |

---

## Reporting
```

- [ ] **Step 9: Sanity-check the docs and commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git diff --stat
git add README.md docs/PRD.md docs/RELEASE_NOTES.md docs/QA_PLAN.md
git status --short docs/DEBUG_PLAN.md
```
If Step 8 was done and `docs/DEBUG_PLAN.md` showed no foreign changes beforehand, also `git add docs/DEBUG_PLAN.md`.

```bash
git commit -m "Document story threads: README, PRD, release note 12, QA plan section O, DEBUG_PLAN rows" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Release gate - on-device QA, security scan, report (no push)

This task is the standing release gate (`docs/DEBUG_PLAN.md` §14, `docs/QA_PLAN.md`). It produces evidence; it pushes nothing.

- [ ] **Step 1: Full unit tests, then build and install the build under test** (QA_PLAN "How to run" §1-§2: back up first)

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
bash /c/t/g.sh :app:testDebugUnitTest 2>&1 | grep -E "BUILD|FAILED|tests completed"
```
Expected: `267 tests completed, 1 failed` (only `WidgetThemesTest > colorProvidersFor custom wraps ...`). Any other failure blocks the gate.

Then follow `docs/QA_PLAN.md` "How to run" §1 (build `assembleDebug`, `adb -s RFCR91J237W install -r`, confirm `versionCode`/`versionName` with `dumpsys package`) and §2 (back up `files/datastore` first). Use the `mobile-qa` agent for the device work.

- [ ] **Step 2: Run QA section O in full** (O-01 to O-27) on two reference widgets (Expand and Focus), plus the matrix `G-M01…G-M12` with grouping ON, plus the Smoke subset (now 60 P0 rows). Evidence per case as the table says (DataStore pulls of `appWidget-<id>.preferences_pb`, uiautomator dumps/bounds, screenshots at 1.2x, timed screenshot series for dissolve, logcat FATAL/bitmap/`Can't show content` check). Specifically verify on the device the three mechanics the unit tests cannot: (a) the pill's own click does not also fire the row action (O-02, O-22), (b) the scroll position and row heights after a pill tap are sane, (c) RemoteViews limits hold (no "cannot have more than 10 elements") with a 12-member thread expanded in an RTL and an LTR feed. If the planted-data route is needed, back up and restore `articles_json` as the QA plan describes.

- [ ] **Step 3: Record results.** Fill `docs/DEBUG_PLAN.md` 14.w (build, HEAD, versionCode, per-row Result and evidence) and add the release block QA_PLAN's "Result recording" section defines. Any bug found that no case would have caught gets a new case in section O (and a ledger line in Appendix A) in the same change as its fix; update the counts with `qacount.py`. Flip the PRD row to ✅ only if everything passed. Restore the backed-up state. Commit the doc updates:

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add docs/DEBUG_PLAN.md docs/QA_PLAN.md docs/PRD.md
git commit -m "Record story threads release-gate results" -m "Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 4: Security review before any push** (standing rule; this app parses untrusted remote RSS and now runs a matcher over remote titles)

Run `/security-review` (or the code-reviewer agent) on `git diff origin/main..HEAD`. Points specific to this diff: no secrets/tokens in the diff (`git diff origin/main..HEAD | grep -nEi "ghp_|github_pat|api[_-]?key|secret|token"` should show nothing new), `git remote -v` has no embedded credential, the matcher is bounded (<= 300 articles, O(n^2) with set lookups, 12-member cap, no recursion), regexes in `StoryThreads.kt` have no nested quantifiers (no catastrophic backtracking on a hostile 10,000-character title: titles are capped by the parsers at 200 chars for Telegram and are plain text from RSS), `expanded_thread_ids` is bounded (30 ids) and only ever written from ids the app itself produced, no new permissions/dependencies/network calls.

- [ ] **Step 5: Report to the user**: what passed with evidence, what is Unverified (say so plainly), any S3 wrong-group findings from `O-21`, and ask for the go-ahead. Do not call it release-ready if the device was offline or any P0 is unverified.

---

### Task 10: Push (only with the user's explicit go-ahead)

- [ ] **Step 1:** Do nothing until the user says to push. Docs-only pushes are exempt from the device gate but a code push is a release (CI publishes every push to `main` as the rolling `latest` release that installed apps self-update from).
- [ ] **Step 2:** With the go-ahead: re-run Task 9 Step 4 on the final diff, `git push origin main`, then check the CI run is green (`gh run list --workflow "Build APK" -L 3`) and that `gh release view latest` shows the new build.

---

## Risks and open points (found while planning)

1. **Glance badge click mechanics are only proven by analogy.** The nested-clickable precedent (`Open article ->`, `Share ↗`, `Load full article ↓` inside the row's clickable `Column`/`Row`) is verified on device, but the badge sits in the always-visible meta line of every lead row and is a small target; `O-02`/`O-22` must confirm on the Fold (RTL and LTR rows, large fonts) that the badge click does not also fire the row action and that the tap area is usable. If a badge tap ever also triggers the row, the fallback is to move the badge out of the clickable `Column` into a sibling child of the outer row `Row`.
2. **Filter vs whole-thread read** is resolved by the read-class barrier (threads never mix read states), so callbacks recompute the render's threads from all stored articles. Cost: two reads of the same story at different times are two threads under Read only / All. An article that arrives later can never join a read thread (by design).
3. **Lead changes collapse a thread** (the stored lead id no longer matches) when a newer matching article arrives; the user has to re-expand. Accepted; pruning removes the stale id on the next toggle.
4. **Moving between members of one thread marks nothing** (a spec addition). Without it, tapping a member of an expanded thread would immediately mark the whole thread read and, under Unread only, dissolve the rows being read.
5. **Downgrade**: an older build decoding `"groupStories":false` with `WidgetContent`'s strict `Json` would blank the widget. Self-update only moves forward, so this is accepted, same as any new config key.
6. **Wrong/missed groups** are inherent to word matching (documented limitations in the spec); the per-widget switch is the escape hatch, and `O-21` measures it on a real day.
7. **Row heights**: the pill adds ~4 dp of vertical padding to the meta line of lead rows; if the Fold shows noticeable growth, reduce `vertical = 4.dp` in `FeedItemRow.kt` (keep the tap target >= 44 dp in `O-22`).
