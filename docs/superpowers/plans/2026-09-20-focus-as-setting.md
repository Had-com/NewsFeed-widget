# Focus as a Per-Widget Setting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fold the separate "NewsFeed Focus" widget into the single "NewsFeed" widget as a per-placed-widget setting ("When I tap an article: Expand in place / Focus (enlarge)", default Expand). In Focus mode only the tapped article enlarges; every other row keeps its normal size. Delete `NewsFeedFocusWidget` / `NewsFeedFocusWidgetReceiver` entirely (already-placed Focus widgets disappear on update; the user accepted this) and clean up state left behind by removed widget ids.

**Architecture:** `WidgetConfig.tapMode` (String key, default `"expand"`) is the single source of truth. `WidgetContent` derives `isFocusWidget` from the decoded config instead of from the widget class, so the existing `FeedItemRow` / `WidgetHeader` Focus code paths keep working unchanged apart from removing the background-row shrink. Small pure helpers (`tapActionFor`, `resetTapState`, `tapModeChanged`, `rowFontScale`, orphan-id selection) carry the new logic so it is unit-testable. Orphan cleanup is a per-refresh sweep in `WidgetWorker` plus an `onDeleted` hook on the one remaining receiver.

**Tech Stack:** Kotlin 2.0.21, Jetpack Glance 1.1.0, WorkManager 2.9.0, DataStore Preferences 1.1.1, kotlinx-serialization-json 1.6.3, JUnit 4, Gradle 8.7 (Windows / Git Bash).

**Spec:** `docs/superpowers/specs/2026-09-20-focus-as-setting-design.md` (commit `f943b63`, approved).

---

## Environment notes (read first)

- Windows 11, **Git Bash**. `gradlew.bat` is broken; use the Gradle 8.7 distribution directly (helper below). Java is JDK 17.
- **Known pre-existing failing test to ignore:** `WidgetThemesTest` "`colorProvidersFor custom wraps...`" (`android.graphics.Color` not mocked). Because of it, an unfiltered `testDebugUnitTest` reports `BUILD FAILED` with 1 failed test; every per-task command below filters to specific test classes so it succeeds. The unfiltered run in Task 9 checks that this is the *only* failure.
- **Commits** are plain new commits on `main` (never amend), one per task, each ending with the trailer `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`. Use `git add <explicit paths>`, never `git add -A` (untracked plan files under `docs/superpowers/plans/` must stay untracked).
- **No CI / `UpdateManager` change is needed:** CI already builds one non-flavor APK and publishes only `NewsFeed-latest.apk` + `version.json`, and `UpdateManager` hard-codes those two URLs, so self-update keeps working for every installed `com.newsfeed.widget` build. The stale `NewsFeed-focusMode-latest.apk` / `NewsFeed-standard-latest.apk` assets on the release page are unreferenced leftovers and are left alone (deleting them is a manual GitHub action for the user).
- **Never push before the release gate** (Task 8 on-device QA + Task 9 security scan + explicit user go-ahead).
- **Line endings:** most Kotlin/XML files in this repo are CRLF, `WidgetConfigActivity.kt` and the docs are LF. All edits below go through the `rr.sh` helper, which preserves the target file's line-ending style and refuses to run unless its anchors match exactly once. Do not run `dos2unix`; mixed endings are normalised by git.
- Line numbers quoted in this plan are the current (pre-plan) numbers and shift as tasks land. The regex anchors are what the edit commands use.

### Step 0: create the scratch helpers (outside the repo, no commit)

- [ ] **Create `/c/t/g.sh` (Gradle runner) and `/c/t/rr.sh` (safe range-replace)**

```bash
mkdir -p /c/t
cat > /c/t/g.sh <<'EOF'
#!/bin/bash
# usage: [REPO=/path/to/checkout] bash /c/t/g.sh <gradle args...>
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot" TEMP="C:\t" TMP="C:\t"
cd "${REPO:-/c/Users/PhotoStudio/ClaudeWorkspace/repo}"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon "$@"
EOF
cat > /c/t/rr.sh <<'EOF'
#!/bin/bash
# usage: rr.sh FILE START_RE START_ADJ END_RE END_ADJ REPL_FILE
#   Replaces lines [line(START_RE)+START_ADJ .. line(END_RE)+END_ADJ] with the contents of
#   REPL_FILE. END_RE may be __EOF__ (through the last line). Each regex must match exactly
#   one line, otherwise nothing is changed. Keeps CRLF if the target file uses CRLF.
f="$1"; s="$2"; sa="$3"; e="$4"; ea="$5"; r="$6"
sc=$(grep -c -- "$s" "$f")
[ "$sc" = 1 ] || { echo "ABORT: start regex matches $sc lines (need 1): $s"; exit 1; }
sl=$(( $(grep -n -- "$s" "$f" | cut -d: -f1) + sa ))
if [ "$e" = "__EOF__" ]; then el=$(wc -l < "$f"); else
  ec=$(grep -c -- "$e" "$f")
  [ "$ec" = 1 ] || { echo "ABORT: end regex matches $ec lines (need 1): $e"; exit 1; }
  el=$(( $(grep -n -- "$e" "$f" | cut -d: -f1) + ea ))
fi
[ "$sl" -ge 1 ] && [ "$sl" -le "$el" ] || { echo "ABORT: bad range $sl-$el"; exit 1; }
echo "replacing lines $sl-$el ($((el-sl+1)) lines) in $(basename "$f")"
echo "  first: $(sed -n "${sl}p" "$f" | tr -d '\r' | cut -c1-100)"
echo "  last:  $(sed -n "${el}p" "$f" | tr -d '\r' | cut -c1-100)"
if grep -q $'\r' "$f"; then sed 's/$/\r/' "$r" > "$r.crlf"; rr="$r.crlf"; else rr="$r"; fi
{ head -n $((sl-1)) "$f"; cat "$rr"; tail -n +$((el+1)) "$f"; } > "$f.new" && mv "$f.new" "$f"
EOF
: > /c/t/empty.txt
echo ok
```
Expected: prints `ok`.

- [ ] **Confirm the build works and capture the baseline**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.ReadOnMoveAwayTest" 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`.

Every `rr.sh` call below is run from any directory with absolute paths; define `J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget` at the top of each bash block that uses it (shell state does not persist between tool calls).

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` | Modify | `TapMode` enum; `WidgetConfig.tapMode`; `focusBackgroundScale` kept as deprecated/ignored |
| `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt` | Create | `TapAction`, `tapActionFor`, `tapModeChanged`, `resetTapState`, `rowFontScale` (pure helpers) |
| `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` | Modify | Derive focus-ness from config; budget without background scale; delete Focus widget class + receiver; `onDeleted` |
| `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt` | Modify | Non-focused rows at normal size via `rowFontScale`; tap routing via `tapActionFor` |
| `app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt`, `AdjustFocusScaleCallback.kt` | Modify | Update `NewsFeedWidget` instead of the Focus class |
| `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` | Modify | "When I tap an article" dropdown; drop Background-rows slider; reset tap state on mode change |
| `app/src/main/java/com/newsfeed/widget/glance/BootReceiver.kt`, `WidgetWorker.kt`, `update/UpdateManager.kt` | Modify | Remove Focus id/receiver references; `WidgetWorker` runs the orphan sweep and stops jobs when no widget is placed |
| `app/src/main/AndroidManifest.xml`, `res/xml/appwidget_info_focus.xml`, `res/values/strings.xml` | Modify / Delete | Remove the Focus receiver, provider XML, picker label |
| `app/src/main/java/com/newsfeed/widget/data/OrphanCleanup.kt` | Create | Pure orphan-id selection + the cleanup of config/backup/Glance-state/stale-alarm |
| `app/src/main/java/com/newsfeed/widget/data/WidgetConfigStore.kt`, `ConfigBackup.kt` | Modify | `knownWidgetIds()`, `ConfigBackup.delete/knownIds` |
| `app/src/test/java/com/newsfeed/widget/data/WidgetConfigTapModeTest.kt` | Create | tapMode default / old-JSON decode |
| `app/src/test/java/com/newsfeed/widget/glance/TapRoutingTest.kt` | Create | routing matrix, mode-switch reset, row scale |
| `app/src/test/java/com/newsfeed/widget/data/OrphanCleanupTest.kt` | Create | orphan-id selection, key/file-name parsing |
| `README.md`, `docs/PRD.md`, `docs/DEBUG_PLAN.md`, `docs/RELEASE_NOTES.md`, `docs/superpowers/specs/2026-09-05-merge-focus-mode-design.md` | Modify | Docs, `## Note 8`, cross-pointer, QA plan |

---

### Task 1: `TapMode` + `WidgetConfig.tapMode` (+ deprecated `focusBackgroundScale`)

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt` (lines 43-47 block; enums at ~78)
- Create: `app/src/test/java/com/newsfeed/widget/data/WidgetConfigTapModeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.newsfeed.widget.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigTapModeTest {

    @Test
    fun `config saved before tapMode existed reads as expand`() {
        // Default Json (no ignoreUnknownKeys), exactly what WidgetContent uses.
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"fontSize":1.5}""")
        assertEquals(TapMode.EXPAND.key, cfg.tapMode)
        assertEquals(TapMode.EXPAND, TapMode.fromKey(cfg.tapMode))
    }

    @Test
    fun `config that still carries focusBackgroundScale decodes with the default Json`() {
        val cfg = Json.decodeFromString<WidgetConfig>("""{"widgetId":7,"focusBackgroundScale":0.25}""")
        assertEquals(7, cfg.widgetId)
        assertEquals(TapMode.EXPAND.key, cfg.tapMode)
    }

    @Test
    fun `focus mode survives a JSON round trip`() {
        val json = Json.encodeToString(WidgetConfig(widgetId = 3, tapMode = TapMode.FOCUS.key))
        val back = Json.decodeFromString<WidgetConfig>(json)
        assertEquals(TapMode.FOCUS.key, back.tapMode)
        assertEquals(TapMode.FOCUS, TapMode.fromKey(back.tapMode))
    }

    @Test
    fun `fromKey maps known keys and degrades anything else to expand`() {
        assertEquals(TapMode.EXPAND, TapMode.fromKey("expand"))
        assertEquals(TapMode.FOCUS, TapMode.fromKey("focus"))
        assertEquals(TapMode.EXPAND, TapMode.fromKey(null))
        assertEquals(TapMode.EXPAND, TapMode.fromKey(""))
        assertEquals(TapMode.EXPAND, TapMode.fromKey("zoom"))
    }
}
```
Save as `app/src/test/java/com/newsfeed/widget/data/WidgetConfigTapModeTest.kt`.

- [ ] **Step 2: Run it and confirm it fails to compile**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -5
```
Expected: `Unresolved reference 'TapMode'` (or `'tapMode'`) and `BUILD FAILED`.

- [ ] **Step 3: Add the field and enum**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
cat > /c/t/r1.txt <<'EOF'
    // Unused since 2026-09-20 (Focus no longer shrinks the other rows). Kept ONLY so saved config
    // JSON that still carries this key keeps decoding: WidgetContent decodes configJson with the
    // default Json (no ignoreUnknownKeys), so deleting the property would make that decode throw
    // and the widget would render as an empty default config. New code must never read or write it.
    @Deprecated("Unused since 2026-09-20; kept so old saved JSON still decodes.")
    val focusBackgroundScale: Float = 0.5f,
    // What a tap on an article row does: TapMode.EXPAND.key ("expand", the default, and what every
    // config saved before this field existed reads as) or TapMode.FOCUS.key ("focus"). Always read
    // it through TapMode.fromKey() so an unknown value degrades to EXPAND.
    val tapMode: String = TapMode.EXPAND.key,
EOF
bash /c/t/rr.sh $J/data/FeedConfig.kt 'Focus Mode only (BuildConfig.FOCUS_MODE build flavor).*how small every row other than' 0 'val focusBackgroundScale: Float = 0.5f' 0 /c/t/r1.txt
cat > /c/t/r2.txt <<'EOF'
enum class TapMode(val key: String, val label: String) {
    EXPAND("expand", "Expand in place"),
    FOCUS("focus", "Focus (enlarge)");

    companion object {
        fun fromKey(key: String?): TapMode = entries.firstOrNull { it.key == key } ?: EXPAND
    }
}

enum class SortOrder(val key: String, val labelRes: String) {
EOF
bash /c/t/rr.sh $J/data/FeedConfig.kt '^enum class SortOrder(val key: String, val labelRes: String) {' 0 '^enum class SortOrder(val key: String, val labelRes: String) {' 0 /c/t/r2.txt
```
Expected: two `replacing lines ...` reports (the first covering 5 lines, first line `// Focus Mode only (BuildConfig.FOCUS_MODE build flavor)...`, last `val focusBackgroundScale: Float = 0.5f,    // 0.25 ...`; the second 1 line).

- [ ] **Step 4: Run the test and confirm it passes**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "BUILD|tests completed|FAILED" | head
```
Expected: `BUILD SUCCESSFUL` (a Kotlin deprecation warning for `focusBackgroundScale` uses in `WidgetConfigActivity.kt`/`NewsFeedWidget.kt` is expected until Tasks 3-4 remove them).

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/FeedConfig.kt app/src/test/java/com/newsfeed/widget/data/WidgetConfigTapModeTest.kt
git commit -m "Add WidgetConfig.tapMode and TapMode; deprecate focusBackgroundScale" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 2: Pure helpers: `tapActionFor`, `tapModeChanged`, `resetTapState`, `rowFontScale`

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt`
- Create: `app/src/test/java/com/newsfeed/widget/glance/TapRoutingTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapRoutingTest {

    // ---- routing matrix: 2 modes x (description / no description) ----

    @Test
    fun `expand mode with a description toggles expand`() =
        assertEquals(TapAction.TOGGLE_EXPAND, tapActionFor(TapMode.EXPAND, hasDescription = true))

    @Test
    fun `expand mode without a description is a no-op tap`() =
        assertEquals(TapAction.NO_OP, tapActionFor(TapMode.EXPAND, hasDescription = false))

    @Test
    fun `focus mode always sets focus even without a description`() {
        assertEquals(TapAction.SET_FOCUS, tapActionFor(TapMode.FOCUS, hasDescription = true))
        assertEquals(TapAction.SET_FOCUS, tapActionFor(TapMode.FOCUS, hasDescription = false))
    }

    // ---- mode-switch detection ----

    @Test
    fun `tapModeChanged compares modes not raw strings`() {
        assertTrue(tapModeChanged("expand", "focus"))
        assertTrue(tapModeChanged("focus", "expand"))
        assertFalse(tapModeChanged("expand", "expand"))
        assertFalse(tapModeChanged("focus", "focus"))
        // an unknown stored value is treated as expand, so expand -> "junk" is not a switch
        assertFalse(tapModeChanged("junk", "expand"))
    }

    // ---- resetTapState ----

    @Test
    fun `resetTapState clears exactly the four transient keys and nothing else`() {
        val prefs = mutablePreferencesOf().also {
            it[WidgetStateKey.expandedArticleId] = "a"
            it[WidgetStateKey.focusedArticleId] = "b"
            it[WidgetStateKey.lastTappedArticleId] = "c"
            it[WidgetStateKey.focusScale] = 2.0f
            it[WidgetStateKey.articles] = """[{"id":"a"}]"""
            it[WidgetStateKey.configJson] = """{"widgetId":1}"""
            it[WidgetStateKey.fullArticleId] = "a"
            it[WidgetStateKey.visibleArticleCount] = 30
        }
        resetTapState(prefs)
        assertFalse(prefs.contains(WidgetStateKey.expandedArticleId))
        assertFalse(prefs.contains(WidgetStateKey.focusedArticleId))
        assertFalse(prefs.contains(WidgetStateKey.lastTappedArticleId))
        assertFalse(prefs.contains(WidgetStateKey.focusScale))
        // read flags live inside articles_json: it must be byte-for-byte untouched
        assertEquals("""[{"id":"a"}]""", prefs[WidgetStateKey.articles])
        assertEquals("""{"widgetId":1}""", prefs[WidgetStateKey.configJson])
        assertEquals("a", prefs[WidgetStateKey.fullArticleId])
        assertEquals(30, prefs[WidgetStateKey.visibleArticleCount])
    }

    @Test
    fun `resetTapState on empty prefs is harmless`() {
        val prefs = mutablePreferencesOf()
        resetTapState(prefs)
        assertTrue(prefs.asMap().isEmpty())
    }

    // ---- rowFontScale: only the focused row in focus mode is ever scaled ----

    @Test
    fun `focused row in focus mode gets the focus scale`() =
        assertEquals(2.0f, rowFontScale(isFocusMode = true, isFocusedRow = true, anyFocused = true, focusScale = 2.0f), 0.0f)

    @Test
    fun `non-focused rows are exactly normal size in every state`() {
        // something else is focused
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = false, anyFocused = true, focusScale = 2.5f), 0.0f)
        // nothing focused
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = false, anyFocused = false, focusScale = 2.5f), 0.0f)
        assertEquals(1.0f, rowFontScale(true, isFocusedRow = true, anyFocused = false, focusScale = 2.5f), 0.0f)
    }

    @Test
    fun `expand mode ignores a stale focused id`() =
        assertEquals(1.0f, rowFontScale(isFocusMode = false, isFocusedRow = true, anyFocused = true, focusScale = 2.5f), 0.0f)
}
```
Save as `app/src/test/java/com/newsfeed/widget/glance/TapRoutingTest.kt`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.TapRoutingTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -5
```
Expected: `Unresolved reference 'tapActionFor'` (etc.) and `BUILD FAILED`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt`:

```kotlin
package com.newsfeed.widget.glance

import androidx.datastore.preferences.core.MutablePreferences
import com.newsfeed.widget.data.TapMode
import com.newsfeed.widget.data.WidgetStateKey

/** What a tap on an article row does; FeedItemRow maps each value to its ActionCallback. */
enum class TapAction { SET_FOCUS, TOGGLE_EXPAND, NO_OP }

/**
 * The tap-routing matrix. Focus wins regardless of description (a focused row auto-expands, so
 * a description-less article is still focusable). Expand mode falls back to a no-op ripple for
 * articles with no description (NoOpTapFeedbackCallback).
 */
fun tapActionFor(mode: TapMode, hasDescription: Boolean): TapAction = when {
    mode == TapMode.FOCUS -> TapAction.SET_FOCUS
    hasDescription        -> TapAction.TOGGLE_EXPAND
    else                  -> TapAction.NO_OP
}

/** True when saving [savedKey] over [loadedKey] actually switches the widget's tap mode. */
fun tapModeChanged(loadedKey: String, savedKey: String): Boolean =
    TapMode.fromKey(loadedKey) != TapMode.fromKey(savedKey)

/**
 * Called when a placed widget's tap mode is switched (either direction): drops the old mode's
 * transient state so nothing stale leaks into the new mode. It deliberately does NOT mark any
 * article read: read means "the user moved on to another article", and a mode switch is not
 * that, so the pending article simply stays unread and is handled normally later. Read flags
 * live inside articles_json, which this never touches.
 */
internal fun resetTapState(prefs: MutablePreferences) {
    prefs.remove(WidgetStateKey.expandedArticleId)
    prefs.remove(WidgetStateKey.focusedArticleId)
    prefs.remove(WidgetStateKey.lastTappedArticleId)
    prefs.remove(WidgetStateKey.focusScale)
}

/**
 * Multiplier applied to a row's font sizes: only the focused row, in focus mode, is ever
 * scaled; every other row (including all rows when nothing is focused, and every row in
 * expand mode even if a stale focused id is still stored) renders at exactly 1.0.
 */
fun rowFontScale(isFocusMode: Boolean, isFocusedRow: Boolean, anyFocused: Boolean, focusScale: Float): Float =
    if (isFocusMode && anyFocused && isFocusedRow) focusScale else 1f
```

- [ ] **Step 4: Run the tests and confirm they pass**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.TapRoutingTest" --tests "com.newsfeed.widget.glance.ReadOnMoveAwayTest" 2>&1 | grep -E "BUILD|FAILED" | head
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/glance/TapRouting.kt app/src/test/java/com/newsfeed/widget/glance/TapRoutingTest.kt
git commit -m "Add tap routing, mode-switch reset and row scale helpers" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 3: Render from config; only the focused row enlarges; budget without background scale

After this task `NewsFeedFocusWidget` / `NewsFeedFocusWidgetReceiver` still exist (deleted in Task 5) but both widget classes render the same config-driven content, so the project compiles and behaves correctly for a standard widget set to Focus.

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` (lines ~58, 88, 106, 131, 152-154, 184-196, 298, 366)
- Modify: `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt` (lines ~45, 68, 85-99, 100-111, 120, 189-200)
- Modify: `app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt` (lines 62-63), `AdjustFocusScaleCallback.kt` (line 49)

- [ ] **Step 1: `NewsFeedWidget.kt`: derive `isFocusWidget` from config, drop the parameter**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/NewsFeedWidget.kt
printf '                WidgetContent()\n' > /c/t/r.txt
bash /c/t/rr.sh $F 'WidgetContent(isFocusWidget = false)' 0 'WidgetContent(isFocusWidget = false)' 0 /c/t/r.txt
bash /c/t/rr.sh $F 'WidgetContent(isFocusWidget = true)' 0 'WidgetContent(isFocusWidget = true)' 0 /c/t/r.txt
printf 'private fun WidgetContent() {\n' > /c/t/r.txt
bash /c/t/rr.sh $F 'private fun WidgetContent(isFocusWidget: Boolean) {' 0 'private fun WidgetContent(isFocusWidget: Boolean) {' 0 /c/t/r.txt
printf 'import com.newsfeed.widget.data.FilterMode\nimport com.newsfeed.widget.data.TapMode\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.FilterMode' 0 '^import com.newsfeed.widget.data.FilterMode' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
        ?: WidgetConfig(widgetId = -1)

    // Focus (tap-to-enlarge) is a per-widget setting, not a widget class: every placed widget
    // renders through this one path and asks its own saved config. Kept under the old local
    // name so FeedItemRow / WidgetHeader keep their existing isFocusWidget parameter.
    val isFocusWidget = TapMode.fromKey(config.tapMode) == TapMode.FOCUS
EOF
bash /c/t/rr.sh $F '?: WidgetConfig(widgetId = -1)' 0 '?: WidgetConfig(widgetId = -1)' 0 /c/t/r.txt
```
Expected: five `replacing lines N-N (1 lines)` reports.

- [ ] **Step 2: `NewsFeedWidget.kt`: budget, grace lambda, dropped argument**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/NewsFeedWidget.kt
cat > /c/t/r.txt <<'EOF'
    // Focus mode only: the focused row can render at up to focusScale× (default 1.25×, up to
    // 2.5×) config.fontSize. Every other row is at normal size (1×), since Focus no longer
    // shrinks them, so a uniform worst case is the larger of focusScale and 1. This is the same
    // deliberately-overestimating shape as the "3-line worst case" comment below: it protects
    // against the "RemoteViews for widget update exceeds maximum bitmap memory usage" crash.
    // (It could later be tightened to n-1 rows at 1× plus one at focusScale; that would change
    // the on-screen row counts the on-device "Can't show content" fix was tuned against, so it
    // is intentionally left as the uniform bound.)
    val worstCaseRowScale = if (isFocusWidget) maxOf(focusScale, 1f) else 1f
EOF
bash /c/t/rr.sh $F 'Focus Mode only.*one row can render at up to focusScale' 0 'maxOf(focusScale, config.focusBackgroundScale, 1f) else 1f' 0 /c/t/r.txt
# the delayed grace-period refresh always updates the one widget class now
printf '                        NewsFeedWidget().update(c, g)\n' > /c/t/r.txt
bash /c/t/rr.sh $F 'if (isFocusWidget) NewsFeedFocusWidget().update(c, g) else NewsFeedWidget().update(c, g)' 0 'if (isFocusWidget) NewsFeedFocusWidget().update(c, g) else NewsFeedWidget().update(c, g)' 0 /c/t/r.txt
# the FeedItemRow(...) call no longer passes a background scale
bash /c/t/rr.sh $F 'focusBackgroundScale = config.focusBackgroundScale,' 0 'focusBackgroundScale = config.focusBackgroundScale,' 0 /c/t/empty.txt
```
Expected reports: the budget replacement `replacing lines N-M (13 lines)` (first line `// Focus Mode only — one row can render at up to focusScale× ...`, last line `maxOf(focusScale, config.focusBackgroundScale, 1f) else 1f`), then two `(1 lines)` reports.

- [ ] **Step 3: `FeedItemRow.kt`: tap routing, normal-size non-focused rows**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/FeedItemRow.kt
# imports
printf 'import com.newsfeed.widget.data.TapMode\nimport com.newsfeed.widget.data.ThumbnailHelper\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.ThumbnailHelper' 0 '^import com.newsfeed.widget.data.ThumbnailHelper' 0 /c/t/r.txt
# drop the focusBackgroundScale parameter
bash /c/t/rr.sh $F '^    focusBackgroundScale: Float = 0.5f,' 0 '^    focusBackgroundScale: Float = 0.5f,' 0 /c/t/empty.txt
# comment block above the shadowing
cat > /c/t/r.txt <<'EOF'
    // Focus mode only (isFocusWidget, derived from WidgetConfig.tapMode). Only the focused row
    // enlarges: rowFontScale() is focusScale for that row and exactly 1.0 for every other row
    // (all rows when nothing is focused, and all rows in expand mode). The fontSize /
    // articleFontSize shadows below pick that up, so every size derived from them (headlineSize,
    // thumbWidth, metaFontSize, ...) follows automatically. focusScale is live, on-widget
    // adjustable via the +/- header buttons (AdjustFocusScaleCallback), per article.
EOF
bash /c/t/rr.sh $F 'Focus widget only (isFocusWidget.*see NewsFeedFocusWidget' 0 'standing preference\.' 0 /c/t/r.txt
# rowScale + baseFontSize
cat > /c/t/r.txt <<'EOF'
    val rowScale = rowFontScale(isFocusWidget, article.id == focusedArticleId, focusedArticleId.isNotBlank(), focusScale)
    val baseFontSize = fontSize
EOF
bash /c/t/rr.sh $F '^    val baseFontSize = fontSize' 0 '^    val baseFontSize = fontSize' 0 /c/t/r.txt
# the two shadows
printf '    val fontSize = fontSize * rowScale\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^    val fontSize = if (isFocusWidget && focusedArticleId.isNotBlank()) {' 0 '^    } else fontSize' 0 /c/t/r.txt
printf '    val articleFontSize = articleFontSize * rowScale\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^    val articleFontSize = if (isFocusWidget && focusedArticleId.isNotBlank()) {' 0 '^    } else articleFontSize' 0 /c/t/r.txt
# meta-row comment
printf '    // content being zoomed into, so it has no reason to grow past normal.\n' > /c/t/r.txt
bash /c/t/rr.sh $F 'grow past normal\. Background rows' 0 'else, exactly as before\.' 0 /c/t/r.txt
# tap routing
cat > /c/t/r.txt <<'EOF'
    val tapAction = tapActionFor(if (isFocusWidget) TapMode.FOCUS else TapMode.EXPAND, article.description.isNotBlank())
    val toggleAction = if (tapAction == TapAction.SET_FOCUS)
EOF
bash /c/t/rr.sh $F '^    val toggleAction = if (isFocusWidget)' 0 '^    val toggleAction = if (isFocusWidget)' 0 /c/t/r.txt
printf '    else if (tapAction == TapAction.TOGGLE_EXPAND)\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^    else if (article.description.isNotBlank())' 0 '^    else if (article.description.isNotBlank())' 0 /c/t/r.txt
sed -i 's/alongside shrinking every other row to browse by size, so this branch is checked first/alongside enlarging one row to browse by size, so this branch is checked first/; s/focus\/background scale/focus scale/' $F
```
Expected reports (line counts): import 1; param 1; comment block 11 (last line `// standing preference.`); rowScale 1; fontSize shadow 3 (last line `} else fontSize`); articleFontSize shadow 3; meta comment 4 (last line `// else, exactly as before.`); toggleAction 1; else-if 1.

- [ ] **Step 4: Point the two Focus callbacks at `NewsFeedWidget`**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
sed -i 's/NewsFeedFocusWidget()/NewsFeedWidget()/g' $J/glance/SetFocusArticleCallback.kt $J/glance/AdjustFocusScaleCallback.kt
grep -n "NewsFeedFocusWidget\|NewsFeedWidget()" $J/glance/SetFocusArticleCallback.kt $J/glance/AdjustFocusScaleCallback.kt | tr -d '\r'
```
Expected: three lines (SetFocus: `NewsFeedWidget().update(context, glanceId)` and the `scheduleRefresh` lambda `NewsFeedWidget().update(c, g)`; AdjustFocus: `NewsFeedWidget().update(context, glanceId)`), no `NewsFeedFocusWidget`.

- [ ] **Step 5: Compile and run the helper tests**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD" | head
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.TapRoutingTest" --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "BUILD|FAILED" | head
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo diff --stat
```
Expected: no `e:` lines, `BUILD SUCCESSFUL` twice. `diff --stat` shows only `NewsFeedWidget.kt`, `FeedItemRow.kt`, `SetFocusArticleCallback.kt`, `AdjustFocusScaleCallback.kt`, each with a small change (tens of lines, never hundreds; if a file shows hundreds of deletions an `rr.sh` range went wrong, so `git checkout -- <file>` and redo that file).

- [ ] **Step 6: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt app/src/main/java/com/newsfeed/widget/glance/SetFocusArticleCallback.kt app/src/main/java/com/newsfeed/widget/glance/AdjustFocusScaleCallback.kt
git commit -m "Render Focus from WidgetConfig.tapMode; only the focused row enlarges" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 4: Settings UI: "When I tap an article", drop the slider, reset on mode change

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt` (LF file; imports ~88-90; state ~145-190; Save ~527-545; Sort & Filter section ~633-647; Display section ~684-703)

- [ ] **Step 1: Imports, state, load and save wiring**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/config/WidgetConfigActivity.kt
printf 'import com.newsfeed.widget.data.TapMode\nimport com.newsfeed.widget.data.WidgetStateKey\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.WidgetStateKey' 0 '^import com.newsfeed.widget.data.WidgetStateKey' 0 /c/t/r.txt
printf 'import com.newsfeed.widget.glance.resetTapState\nimport com.newsfeed.widget.glance.tapModeChanged\nimport com.newsfeed.widget.glance.updateNewsFeedWidget\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.glance.updateNewsFeedWidget' 0 '^import com.newsfeed.widget.glance.updateNewsFeedWidget' 0 /c/t/r.txt
# the receiver-class check is gone (mode comes from config now)
bash /c/t/rr.sh $F 'Which widget type THIS specific instance is' 0 'NewsFeedFocusWidgetReceiver::class.java.name' 0 /c/t/empty.txt
# state
cat > /c/t/r.txt <<'EOF'
                var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }
                // The tapMode this widget had when the screen opened (from its saved config). Save
                // compares it with the chosen one to decide whether live tap state must be reset.
                var loadedTapMode by remember { mutableStateOf(TapMode.EXPAND.key) }
EOF
bash /c/t/rr.sh $F 'var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }' 0 'var config by remember { mutableStateOf(WidgetConfig(widgetId = appWidgetId)) }' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
                    val saved = store.configFlow(appWidgetId).first()
                    loadedTapMode = saved.tapMode
EOF
bash /c/t/rr.sh $F 'val saved = store.configFlow(appWidgetId).first()' 0 'val saved = store.configFlow(appWidgetId).first()' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
                var showExternalMenu by remember { mutableStateOf(false) }
                var showTapModeMenu  by remember { mutableStateOf(false) }
EOF
bash /c/t/rr.sh $F 'var showExternalMenu by remember' 0 'var showExternalMenu by remember' 0 /c/t/r.txt
# save
cat > /c/t/r.txt <<'EOF'
                                    val final = config.copy(feedOrder = feedOrder.toList())
                                    val loadedAtSave = loadedTapMode
EOF
bash /c/t/rr.sh $F 'val final = config.copy(feedOrder = feedOrder.toList())' 0 'val final = config.copy(feedOrder = feedOrder.toList())' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
                                                prefs[WidgetStateKey.configJson] = Json.encodeToString(final)
                                                // Switching Expand <-> Focus on a live widget: drop the old mode's
                                                // transient state (expanded / focused / last-tapped / focus scale).
                                                // Read flags are untouched, see resetTapState().
                                                if (tapModeChanged(loadedAtSave, final.tapMode)) resetTapState(prefs)
EOF
bash /c/t/rr.sh $F 'prefs\[WidgetStateKey.configJson\] = Json.encodeToString(final)' 0 'prefs\[WidgetStateKey.configJson\] = Json.encodeToString(final)' 0 /c/t/r.txt
```
Expected: each report `(1 lines)` except the receiver-class check (`replacing lines N-M (7 lines)`, first line `// Which widget type THIS specific instance is — ...`, last `?.className == NewsFeedFocusWidgetReceiver::class.java.name`).

- [ ] **Step 2: Add the dropdown row after "Open article in"**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/config/WidgetConfigActivity.kt
cat > /c/t/r.txt <<'EOF'
                                                    onClick = { config = config.copy(externalApp = key); showExternalMenu = false })
                                            }
                                        }
                                    }
                                }

                                // When I tap an article: per widget, Expand in place (default) or Focus (enlarge).
                                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                    Text("When I tap an article", style = MaterialTheme.typography.bodyMedium)
                                    androidx.compose.foundation.layout.Box {
                                        val tapLabel = TapMode.fromKey(config.tapMode).label
                                        TextButton(onClick = { showTapModeMenu = true }) { Text("$tapLabel ▾", fontSize = 13.sp) }
                                        DropdownMenu(showTapModeMenu, { showTapModeMenu = false }) {
                                            TapMode.entries.forEach { mode ->
                                                DropdownMenuItem(text = { Text(mode.label) },
                                                    onClick = { config = config.copy(tapMode = mode.key); showTapModeMenu = false })
                                            }
                                        }
                                    }
                                }
                                if (TapMode.fromKey(config.tapMode) == TapMode.FOCUS) {
                                    Text(
                                        "Tap enlarges the article; use − / + in the widget header to resize it.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
EOF
bash /c/t/rr.sh $F 'externalApp = key); showExternalMenu = false })' 0 'externalApp = key); showExternalMenu = false })' 4 /c/t/r.txt
```
Expected: `replacing lines N-M (5 lines)`, first line `onClick = { config = config.copy(externalApp = key); showExternalMenu = false })`, last line `}` (the closing brace of the "Open article in" `Row`, 32 spaces deep). If `last:` shows anything else, do not proceed: `git checkout -- $F` and re-derive the offset.

- [ ] **Step 3: Remove the Focus-only "Background rows size" slider**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/config/WidgetConfigActivity.kt
printf '                                // Live preview\n' > /c/t/r.txt
bash /c/t/rr.sh $F 'Focus Mode only. How small every row' 0 '^                                // Live preview' 0 /c/t/r.txt
```
Expected: `replacing lines N-M (20 lines)`, first `// Focus Mode only. How small every row other than the focused`, last `// Live preview`.

- [ ] **Step 4: Compile and test**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD" | head
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.TapRoutingTest" 2>&1 | grep -E "BUILD|FAILED" | head
grep -n "focusBackgroundScale\|isFocusWidget" $J/config/WidgetConfigActivity.kt | head
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo diff --stat
```
Expected: no `e:` lines; `BUILD SUCCESSFUL` twice; the `grep` prints nothing (the Activity no longer references either name); `diff --stat` shows only `WidgetConfigActivity.kt` with a change of a few dozen lines.

- [ ] **Step 5: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/config/WidgetConfigActivity.kt
git commit -m "Add the When I tap an article setting; remove Background rows size" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 5: Delete the Focus widget (class, receiver, provider XML, label, references)

**Files:**
- Modify: `NewsFeedWidget.kt`, `WidgetWorker.kt`, `BootReceiver.kt`, `update/UpdateManager.kt`, `WidgetConfigActivity.kt`, `AndroidManifest.xml`, `res/values/strings.xml`, `SetFocusArticleCallback.kt`, `data/WidgetStateKey.kt`, `AdjustFocusScaleCallback.kt`
- Delete: `app/src/main/res/xml/appwidget_info_focus.xml`

- [ ] **Step 1: `NewsFeedWidget.kt`: remove the Focus class, simplify the update helper, remove the Focus receiver, simplify `onDisabled`**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/NewsFeedWidget.kt
# Focus class + provider-lookup helper (lines ~94-128) -> one simple helper
cat > /c/t/r.txt <<'EOF'
/**
 * Re-renders the widget instance owning [glanceId]. Kept as a single named entry point for the
 * ActionCallbacks and the settings screen. There is only one widget class now (Focus is a
 * per-widget setting, see WidgetConfig.tapMode), so there is no provider lookup.
 */
suspend fun updateNewsFeedWidget(context: Context, glanceId: GlanceId) {
    NewsFeedWidget().update(context, glanceId)
}
EOF
bash /c/t/rr.sh $F ' \* The Focus widget .*same rendering path' -1 '^        NewsFeedWidget().update(context, glanceId)' 2 /c/t/r.txt
# onDisabled: the shared jobs are cancelled unconditionally now
cat > /c/t/r.txt <<'EOF'
        // NewsFeedWidgetReceiver is the only receiver now, so its widget count reaching zero
        // means no widget of the app is left: stop the shared periodic jobs.
        WidgetWorker.cancel(context)
        UpdateCheckWorker.cancel(context)
EOF
bash /c/t/rr.sh $F '// Two receivers now share WidgetWorker' 0 '^        if (!focusWidgetsRemain) {' 3 /c/t/r.txt
# Focus receiver (and the blank line + doc comment before it) through EOF
bash /c/t/rr.sh $F " \* The Focus widget's receiver" -2 '__EOF__' 0 /c/t/empty.txt
# now-unused imports
bash /c/t/rr.sh $F '^import androidx.glance.appwidget.GlanceAppWidgetManager' 0 '^import androidx.glance.appwidget.GlanceAppWidgetManager' 0 /c/t/empty.txt
bash /c/t/rr.sh $F '^import android.content.ComponentName' 0 '^import android.content.ComponentName' 0 /c/t/empty.txt
grep -n "NewsFeedFocus\|focusWidgetsRemain\|standardWidgetsRemain" $F | head
```
Expected reports: helper `(35 lines)` (first `/**`, last `}`); `onDisabled` `(12 lines)`; Focus receiver from the blank line before its `/**` through `}` at EOF (~67 lines); two `(1 lines)`. The final `grep` prints nothing.

- [ ] **Step 2: `WidgetWorker.kt`, `UpdateManager.kt`, `BootReceiver.kt`**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
# WidgetWorker: only standard ids
printf '        val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)\n' > /c/t/r.txt
bash /c/t/rr.sh $J/glance/WidgetWorker.kt '// Both widget types share this one periodic refresh job' 0 'manager.getGlanceIds(NewsFeedFocusWidget::class.java)' 0 /c/t/r.txt
bash /c/t/rr.sh $J/glance/WidgetWorker.kt '^        NewsFeedFocusWidget().updateAll(context)' 0 '^        NewsFeedFocusWidget().updateAll(context)' 0 /c/t/empty.txt
# UpdateManager
cat > /c/t/r.txt <<'EOF'
        val widgetIds = GlanceAppWidgetManager(context).let { manager ->
            manager.getGlanceIds(NewsFeedWidget::class.java).map { manager.getAppWidgetId(it) }
        }
EOF
bash /c/t/rr.sh $J/update/UpdateManager.kt '^        val widgetIds = GlanceAppWidgetManager(context).let { manager ->' 0 'manager.getGlanceIds(NewsFeedFocusWidget::class.java).map' 1 /c/t/r.txt
bash /c/t/rr.sh $J/update/UpdateManager.kt '^import com.newsfeed.widget.glance.NewsFeedFocusWidget' 0 '^import com.newsfeed.widget.glance.NewsFeedFocusWidget' 0 /c/t/empty.txt
# BootReceiver: rewrite whole file
cat > $J/glance/BootReceiver.kt <<'EOF'
package com.newsfeed.widget.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.newsfeed.widget.update.UpdateCheckWorker
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        MainScope().launch {
            try {
                val widgetIds = GlanceAppWidgetManager(context).getGlanceIds(NewsFeedWidget::class.java)
                if (widgetIds.isNotEmpty()) {
                    NewsFeedWidgetReceiver.scheduleClockTick(context)
                    // UpdateCheckWorker used to silently never resume its daily check after a
                    // device reboot until a widget was removed and re-added; reschedule both
                    // periodic jobs here.
                    WidgetWorker.ensureScheduled(context)
                    UpdateCheckWorker.schedule(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
EOF
```
Expected reports: WidgetWorker `(5 lines)` then `(1 lines)`; UpdateManager `(4 lines)` then `(1 lines)`.

- [ ] **Step 3: `WidgetConfigActivity.kt`, manifest, strings, provider XML**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
R=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main
F=$J/config/WidgetConfigActivity.kt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.glance.NewsFeedFocusWidget$' 0 '^import com.newsfeed.widget.glance.NewsFeedFocusWidget$' 0 /c/t/empty.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.glance.NewsFeedFocusWidgetReceiver$' 0 '^import com.newsfeed.widget.glance.NewsFeedFocusWidgetReceiver$' 0 /c/t/empty.txt
cat > /c/t/r.txt <<'EOF'
                                            // updateNewsFeedWidget() initialises the Glance DataStore
                                            // subscription for this widget instance; updateAll() then
                                            // re-renders every placed widget (matches the existing
                                            // "refresh everything, not just this one" behavior).
                                            updateNewsFeedWidget(this@WidgetConfigActivity, glanceId)
                                            NewsFeedWidget().updateAll(this@WidgetConfigActivity)
EOF
bash /c/t/rr.sh $F '// updateNewsFeedWidget() initialises the Glance DataStore' 0 'NewsFeedFocusWidget().updateAll(this@WidgetConfigActivity)' 0 /c/t/r.txt
# manifest: the whole <receiver> block for the Focus widget plus the blank line after it
bash /c/t/rr.sh $R/AndroidManifest.xml 'android:name=".glance.NewsFeedFocusWidgetReceiver"' -1 'android:resource="@xml/appwidget_info_focus" />' 2 /c/t/empty.txt
bash /c/t/rr.sh $R/res/values/strings.xml 'widget_label_focus' 0 'widget_label_focus' 0 /c/t/empty.txt
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo rm -q app/src/main/res/xml/appwidget_info_focus.xml
```
Expected reports: imports `(1 lines)` x2; settings comment `(9 lines)` (first `// updateNewsFeedWidget() initialises...`, last `NewsFeedFocusWidget().updateAll(this@WidgetConfigActivity)`); manifest `(13 lines)` (first `<receiver`, last a blank line); strings `(1 lines)`.

- [ ] **Step 4: Comment tidy (stale "flavor" wording)**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
cat > /c/t/r.txt <<'EOF'
// Focus mode only (WidgetConfig.tapMode == "focus", see FeedItemRow.kt's row scaling).
// Tapping a row sets it as the focused article (that row enlarges, every other row keeps its
// normal size); tapping the already-focused row again clears focus.
EOF
bash /c/t/rr.sh $J/glance/SetFocusArticleCallback.kt 'Focus Mode only (BuildConfig.FOCUS_MODE build flavor' 0 'configured size\.' 0 /c/t/r.txt
sed -i 's/Focus Mode only (BuildConfig.FOCUS_MODE build flavor)\./Focus mode only (WidgetConfig.tapMode == "focus")./' $J/glance/AdjustFocusScaleCallback.kt
cat > /c/t/r.txt <<'EOF'
    // Focus mode only (WidgetConfig.tapMode == "focus", see FeedItemRow's row scaling and
    // SetFocusArticleCallback): which article, if any, is currently shown enlarged. Every other
    // row keeps its normal size. Empty string = nothing focused. Cleared by resetTapState() when
    // a widget's tap mode is switched.
EOF
bash /c/t/rr.sh $J/data/WidgetStateKey.kt 'Focus Mode only (BuildConfig.FOCUS_MODE build flavor' 0 '^    // does\.' 0 /c/t/r.txt
sed -i 's/Standard widget only: the article the user tapped most recently/Expand mode only: the article the user tapped most recently/' $J/data/WidgetStateKey.kt
sed -i 's/^\/\/ Standard widget only\. Tapping a row expands/\/\/ Expand mode only. Tapping a row expands/' $J/glance/ToggleExpandCallback.kt
```
Expected: two rr reports (SetFocus 4 lines, WidgetStateKey 5 lines).

- [ ] **Step 5: Compile, confirm nothing refers to the deleted widget, run tests**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD" | head
grep -rn "NewsFeedFocus\|widget_label_focus\|appwidget_info_focus\|CLOCK_TICK_FOCUS" /c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src | head
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.glance.TapRoutingTest" --tests "com.newsfeed.widget.glance.ReadOnMoveAwayTest" --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "BUILD|FAILED" | head
```
Expected: no `e:` lines and `BUILD SUCCESSFUL`; the `grep` prints nothing; tests `BUILD SUCCESSFUL`. (If the compile step fails with a merged-manifest error mentioning the missing receiver, run `bash /c/t/g.sh :app:clean` once and retry: stale `app/build/intermediates/*manifest*` still list it.)

- [ ] **Step 6: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main app/src/test
git status --short | head -20
git commit -m "Remove the separate NewsFeed Focus widget (receiver, provider, label, references)" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
Check `git status --short` before committing: it must list only files under `app/src/main` (modified, plus the deleted `appwidget_info_focus.xml`) and untracked `docs/superpowers/plans/*` entries, nothing else staged.

---

### Task 6: Orphan cleanup (`onDeleted`, sweep, job cancel)

**Files:**
- Create: `app/src/main/java/com/newsfeed/widget/data/OrphanCleanup.kt`
- Create: `app/src/test/java/com/newsfeed/widget/data/OrphanCleanupTest.kt`
- Modify: `data/WidgetConfigStore.kt`, `data/ConfigBackup.kt`, `glance/NewsFeedWidget.kt`, `glance/WidgetWorker.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.newsfeed.widget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanCleanupTest {

    @Test
    fun `orphans are known ids that are not live`() {
        assertEquals(setOf(1, 3), orphanedIds(known = setOf(1, 2, 3), live = setOf(2)))
    }

    @Test
    fun `nothing is orphaned when every known id is live or nothing is known`() {
        assertTrue(orphanedIds(known = setOf(2, 4), live = setOf(2, 4, 9)).isEmpty())
        assertTrue(orphanedIds(known = emptySet(), live = setOf(2)).isEmpty())
    }

    @Test
    fun `with no live widgets every known id is an orphan`() {
        assertEquals(setOf(5, 6), orphanedIds(known = setOf(5, 6), live = emptySet()))
    }

    @Test
    fun `a live id is never orphaned no matter how many stores know it`() {
        // the same live id present in config, backup and a state file collapses to one set entry
        val known = setOf(7) + setOf(7) + setOf(7, 8)
        assertEquals(setOf(8), orphanedIds(known, live = setOf(7)))
    }

    @Test
    fun `widget id is parsed from a config key`() {
        assertEquals(42, widgetIdFromConfigKey("widget_42"))
        assertNull(widgetIdFromConfigKey("widget_"))
        assertNull(widgetIdFromConfigKey("widget_x"))
        assertNull(widgetIdFromConfigKey("articles_json"))
    }

    @Test
    fun `widget id is parsed from a Glance state file name`() {
        assertEquals(17, widgetIdFromStateFileName("appWidget-17.preferences_pb"))
        assertNull(widgetIdFromStateFileName("appWidget-17.preferences_pb.tmp"))
        assertNull(widgetIdFromStateFileName("appWidget-.preferences_pb"))
        assertNull(widgetIdFromStateFileName("newsfeed_config.preferences_pb"))
    }
}
```
Save as `app/src/test/java/com/newsfeed/widget/data/OrphanCleanupTest.kt`.

- [ ] **Step 2: Run it and confirm it fails**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.OrphanCleanupTest" 2>&1 | grep -E "Unresolved reference|BUILD" | head -5
```
Expected: `Unresolved reference 'orphanedIds'` and `BUILD FAILED`.

- [ ] **Step 3: Implement `OrphanCleanup.kt`**

```kotlin
package com.newsfeed.widget.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Ids that some store still knows about but that are not placed on the home screen any more. */
internal fun orphanedIds(known: Set<Int>, live: Set<Int>): Set<Int> = known - live

/** `widget_<id>` (WidgetConfigStore / ConfigBackup key) -> id, or null for any other key. */
internal fun widgetIdFromConfigKey(name: String): Int? =
    if (name.startsWith("widget_")) name.removePrefix("widget_").toIntOrNull() else null

private val STATE_FILE = Regex("""appWidget-(\d+)\.preferences_pb""")

/** `appWidget-<id>.preferences_pb` (Glance per-widget state file) -> id, or null. */
internal fun widgetIdFromStateFileName(name: String): Int? =
    STATE_FILE.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Removes per-widget leftovers for widget ids that no longer exist. Two entry points:
 *  - [removeIds]: the standard receiver's onDeleted(); drops the saved config and its backup
 *    (Glance's own receiver already deletes the appWidget-<id> state file for these).
 *  - [sweep]: run on every WidgetWorker refresh. Covers ids that vanished WITHOUT onDeleted, most
 *    importantly the removed NewsFeed Focus widgets (their receiver no longer exists, so nothing
 *    is called for them on update). Deletes config, backup and Glance state for every id not
 *    registered to the live receiver, and cancels the removed receiver's stale clock alarm.
 * Both swallow failures (except cancellation): cleanup must never fail a refresh.
 */
object OrphanCleanup {
    private const val REMOVED_RECEIVER = "com.newsfeed.widget.glance.NewsFeedFocusWidgetReceiver"
    private const val REMOVED_CLOCK_ACTION = "com.newsfeed.widget.CLOCK_TICK_FOCUS"
    private const val REMOVED_CLOCK_RC = 1002

    suspend fun removeIds(context: Context, ids: Set<Int>) {
        try {
            delete(context, ids, includeGlanceState = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    suspend fun sweep(context: Context, liveIds: Set<Int>) {
        try {
            val known = WidgetConfigStore(context).knownWidgetIds() +
                ConfigBackup.knownIds(context) + stateFileIds(context)
            delete(context, orphanedIds(known, liveIds), includeGlanceState = true)
            cancelRemovedFocusAlarm(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun stateDir(context: Context) = File(context.filesDir, "datastore")

    private fun stateFileIds(context: Context): Set<Int> =
        stateDir(context).listFiles()?.mapNotNull { widgetIdFromStateFileName(it.name) }?.toSet()
            ?: emptySet()

    private suspend fun delete(context: Context, ids: Set<Int>, includeGlanceState: Boolean) {
        if (ids.isEmpty()) return
        val store = WidgetConfigStore(context)
        for (id in ids) {
            store.delete(id)
            ConfigBackup.delete(context, id)
            if (includeGlanceState) File(stateDir(context), "appWidget-$id.preferences_pb").delete()
        }
    }

    // The removed Focus receiver's CLOCK_TICK_FOCUS alarm (request code 1002). Its receiver is
    // gone so a firing alarm is a silent no-op that is never re-armed, but cancel it anyway.
    // FLAG_NO_CREATE: only cancels a PendingIntent that already exists, never creates one.
    private fun cancelRemovedFocusAlarm(context: Context) {
        val intent = Intent(REMOVED_CLOCK_ACTION)
            .setComponent(ComponentName(context.packageName, REMOVED_RECEIVER))
        val pi = PendingIntent.getBroadcast(
            context, REMOVED_CLOCK_RC, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }
}
```
Save as `app/src/main/java/com/newsfeed/widget/data/OrphanCleanup.kt`.

- [ ] **Step 4: Store support (`knownWidgetIds`, `ConfigBackup.delete/knownIds`)**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
# WidgetConfigStore
printf 'import kotlinx.coroutines.flow.Flow\nimport kotlinx.coroutines.flow.first\n' > /c/t/r.txt
bash /c/t/rr.sh $J/data/WidgetConfigStore.kt '^import kotlinx.coroutines.flow.Flow' 0 '^import kotlinx.coroutines.flow.Flow' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
    /** Every widget id that currently has a saved config entry (keys named widget_<id>). */
    suspend fun knownWidgetIds(): Set<Int> =
        context.dataStore.data.first().asMap().keys
            .mapNotNull { widgetIdFromConfigKey(it.name) }.toSet()

    suspend fun delete(widgetId: Int) {
EOF
bash /c/t/rr.sh $J/data/WidgetConfigStore.kt '^    suspend fun delete(widgetId: Int) {' 0 '^    suspend fun delete(widgetId: Int) {' 0 /c/t/r.txt
# ConfigBackup
cat > /c/t/r.txt <<'EOF'
    suspend fun delete(context: Context, widgetId: Int) {
        context.backupDataStore.edit { prefs -> prefs.remove(keyFor(widgetId)) }
    }

    suspend fun knownIds(context: Context): Set<Int> =
        context.backupDataStore.data.first().asMap().keys
            .mapNotNull { widgetIdFromConfigKey(it.name) }.toSet()

    // Called on every WidgetWorker refresh cycle, not just right after an update — cheap
EOF
bash /c/t/rr.sh $J/data/ConfigBackup.kt '// Called on every WidgetWorker refresh cycle, not just right after an update' 0 '// Called on every WidgetWorker refresh cycle, not just right after an update' 0 /c/t/r.txt
```
Expected: three `(1 lines)` reports. Confirm with `git diff app/src/main/java/com/newsfeed/widget/data/ConfigBackup.kt` that the diff is purely additive (the original `// Called on every WidgetWorker refresh cycle ... — cheap` line reappears unchanged as context).

- [ ] **Step 5: `onDeleted` on the standard receiver**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/NewsFeedWidget.kt
printf 'import com.newsfeed.widget.data.FilterMode\nimport com.newsfeed.widget.data.OrphanCleanup\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.FilterMode' 0 '^import com.newsfeed.widget.data.FilterMode' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        // Glance's own receiver removes the per-widget Glance state file; drop the saved config
        // and its backup too, so they no longer leak for every widget the user removes.
        val ids = appWidgetIds.toSet()
        val pending = goAsync()
        MainScope().launch {
            try { OrphanCleanup.removeIds(context, ids) }
            finally { pending.finish() }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
EOF
bash /c/t/rr.sh $F '^    override fun onReceive(context: Context, intent: Intent) {' 0 '^    override fun onReceive(context: Context, intent: Intent) {' 0 /c/t/r.txt
```
Expected: two `(1 lines)` reports (the `onReceive` anchor is unique now that Task 5 removed the Focus receiver; if it reports `matches 2 lines`, Task 5 was not completed).

- [ ] **Step 6: `WidgetWorker`: sweep at the start of `doWork`, stop the jobs when nothing is placed**

```bash
J=/c/Users/PhotoStudio/ClaudeWorkspace/repo/app/src/main/java/com/newsfeed/widget
F=$J/glance/WidgetWorker.kt
printf 'import android.appwidget.AppWidgetManager\nimport android.content.ComponentName\nimport android.content.Context\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import android.content.Context' 0 '^import android.content.Context' 0 /c/t/r.txt
printf 'import com.newsfeed.widget.data.NewsFeedRepository\nimport com.newsfeed.widget.data.OrphanCleanup\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.NewsFeedRepository' 0 '^import com.newsfeed.widget.data.NewsFeedRepository' 0 /c/t/r.txt
printf 'import com.newsfeed.widget.data.retainWithPerFeedGuarantee\nimport com.newsfeed.widget.update.UpdateCheckWorker\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^import com.newsfeed.widget.data.retainWithPerFeedGuarantee' 0 '^import com.newsfeed.widget.data.retainWithPerFeedGuarantee' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
        val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)

        // The system's own list of placed widgets is the source of truth. Housekeeping first:
        // drop config/backup/Glance-state left by ids that are gone (notably the removed NewsFeed
        // Focus widgets, which vanish on update without any callback), then, if nothing is placed
        // at all, stop the shared periodic jobs so nothing keeps polling dead ids. Placing a
        // widget again re-arms both via NewsFeedWidgetReceiver.onEnabled().
        val liveIds = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, NewsFeedWidgetReceiver::class.java)).toSet()
        OrphanCleanup.sweep(context, liveIds)
        if (liveIds.isEmpty()) {
            cancel(context)
            UpdateCheckWorker.cancel(context)
            return Result.success()
        }
EOF
bash /c/t/rr.sh $F 'val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)' 0 'val widgetIds = manager.getGlanceIds(NewsFeedWidget::class.java)' 0 /c/t/r.txt
```
Expected: four `(1 lines)` reports.

- [ ] **Step 7: Compile, run the tests, confirm the diff is additive where expected**

```bash
bash /c/t/g.sh :app:compileDebugKotlin 2>&1 | grep -E "^e: |BUILD" | head
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.data.OrphanCleanupTest" --tests "com.newsfeed.widget.glance.TapRoutingTest" --tests "com.newsfeed.widget.data.WidgetConfigTapModeTest" 2>&1 | grep -E "BUILD|FAILED" | head
git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo diff --stat
```
Expected: no `e:` lines; `BUILD SUCCESSFUL` twice; `diff --stat` lists `ConfigBackup.kt`, `WidgetConfigStore.kt`, `NewsFeedWidget.kt`, `WidgetWorker.kt` with only small (mostly insertion) changes.

- [ ] **Step 8: Commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git add app/src/main/java/com/newsfeed/widget/data/OrphanCleanup.kt app/src/main/java/com/newsfeed/widget/data/WidgetConfigStore.kt app/src/main/java/com/newsfeed/widget/data/ConfigBackup.kt app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt app/src/main/java/com/newsfeed/widget/glance/WidgetWorker.kt app/src/test/java/com/newsfeed/widget/data/OrphanCleanupTest.kt
git commit -m "Clean up config and state for deleted and removed-Focus widget ids" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

### Task 7: Docs (README, PRD, DEBUG_PLAN, release note, spec cross-pointer)

**Files:**
- Modify: `README.md`, `docs/PRD.md`, `docs/DEBUG_PLAN.md`, `docs/RELEASE_NOTES.md`, `docs/superpowers/specs/2026-09-05-merge-focus-mode-design.md` (all LF)

- [ ] **Step 1: `README.md`**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
F=README.md
cat > /c/t/r.txt <<'EOF'
One widget, two tap behaviors: **NewsFeed** expands an article in place when you tap it, or, per widget in Settings, enlarges it (**Focus** mode) for reading one article at a time. There is a single "NewsFeed" entry in the "Add widget" picker.
EOF
bash /c/t/rr.sh $F '^One app, two widgets:' 0 '^One app, two widgets:' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
*(With **When I tap an article** set to **Focus (enlarge)** in Settings, tapping instead enlarges the article — see [Focus Mode](#focus-mode) below.)*
EOF
bash /c/t/rr.sh $F '^\*(On a \*\*NewsFeed Focus\*\* widget, tapping instead enlarges' 0 '^\*(On a \*\*NewsFeed Focus\*\* widget, tapping instead enlarges' 0 /c/t/r.txt
sed -i 's/(expanding a different article, or focusing a different one on the Focus widget)/(expanding a different article, or focusing a different one in Focus mode)/' $F
cat > /c/t/r.txt <<'EOF'
### Focus Mode

Focus is a per-widget setting, not a separate widget. In **Widget settings → When I tap an article**, choose **Focus (enlarge)** (the default is **Expand in place**). It is for browsing by tapping through articles one at a time rather than scrolling a list. Switching the setting on a placed widget takes effect on **Save** and resets that widget's expanded/focused article.

> **Upgrading from the separate NewsFeed Focus widget:** that widget no longer exists, and the update removes any placed Focus widget from your home screen. Add the **NewsFeed** widget again and set **When I tap an article** to **Focus (enlarge)**.

- **Tap any article** to focus it: that row enlarges, every other row stays at its normal size, and the article's description/full text auto-expands inline — no separate expand tap needed. Tapping the focused row again clears focus.
- **Header controls** (only shown in Focus mode):
  - **N/M** — a position indicator showing where the focused article sits among what's currently on screen.
  - **− / +** — adjust the focused row's own enlargement (0.75× – 2.5×) live, per article. This resets to a default whenever focus moves to a different article — it's a look-at-this-one-now adjustment, not a standing preference.
- Everything else (feeds, sort, filter, theme, refresh, self-update) is shared with Expand mode — Focus only changes how you browse, not what's fetched or shown.
EOF
bash /c/t/rr.sh $F '^### Focus Mode' 0 '^- Everything else (feeds, sort, filter, theme, refresh, self-update)' 0 /c/t/r.txt
sed -i 's/Both NewsFeed and NewsFeed Focus share identical sizing\./Sizing is identical in Expand and Focus mode./' $F
printf '| Open article in | Browser · Share sheet | Where the "Open article →"/"Open in browser ↗" buttons send you |\n| When I tap an article | Expand in place · Focus (enlarge) | Per widget; see [Focus Mode](#focus-mode). Switching resets the widget'"'"'s expanded/focused article |\n' > /c/t/r.txt
bash /c/t/rr.sh $F '^| Open article in | Browser · Share sheet |' 0 '^| Open article in | Browser · Share sheet |' 0 /c/t/r.txt
bash /c/t/rr.sh $F '^| Background rows size (slider) |' 0 '^| Background rows size (slider) |' 0 /c/t/empty.txt
cat > /c/t/r.txt <<'EOF'
3. Search for or scroll to find **NewsFeed** and add it. To browse in Focus mode, set **When I tap an article** to **Focus (enlarge)** in its Settings (see [Focus Mode](#focus-mode))
EOF
bash /c/t/rr.sh $F "^3. Search for or scroll to find \*\*NewsFeed\*\* — you'll see two entries" 0 "^3. Search for or scroll to find \*\*NewsFeed\*\* — you'll see two entries" 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
│   │   ├── NewsFeedWidget.kt          # The GlanceAppWidget, its GlanceAppWidgetReceiver (onDeleted cleanup, clock tick), and the
│   │   │                              #   shared composables (Focus vs Expand is read from WidgetConfig.tapMode)
│   │   ├── TapRouting.kt              # Pure helpers: tap routing matrix, mode-switch state reset, focused-row scale
EOF
bash /c/t/rr.sh $F 'NewsFeedWidget.kt          # Both GlanceAppWidget classes' 0 'updateNewsFeedWidget() cross-widget-type update router' 0 /c/t/r.txt
sed -i 's/refresh job (both widget types) + article merge/refresh job + orphaned-state sweep + article merge/; s/(Focus widget)$/(Focus mode)/; s/ActionCallback — expand\/collapse article (standard widget)/ActionCallback — expand\/collapse article (Expand mode)/; s/# Standard widget metadata/# Widget metadata/; s/English strings (app name + both widgets. picker labels)/English strings (app name + widget picker label)/' $F
bash /c/t/rr.sh $F '^    ├── xml/appwidget_info_focus.xml' 0 '^    ├── xml/appwidget_info_focus.xml' 0 /c/t/empty.txt
grep -n "NewsFeed Focus\|Background rows\|two widgets\|Focus widget" $F
```
Expected: `grep` prints only the intentional line in the "Upgrading from the separate NewsFeed Focus widget" note (and its bold widget name), nothing else. If the README's Focus widget tree lines still show `(Focus widget)`, fix them by hand to `(Focus mode)`. Note the `xml/appwidget_info_focus.xml` tree row uses `├──`; if the anchor reports `matches 0 lines`, its box character differs, so grep the exact line with `grep -n appwidget_info_focus README.md` and delete that line.

- [ ] **Step 2: `docs/PRD.md`**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
F=docs/PRD.md
cat > /c/t/r.txt <<'EOF'
everything runs on-device. One widget with a per-widget tap behavior: expand an article in place, or
**Focus** it (a tap-to-enlarge reading mode). Built with first-class RTL Hebrew
EOF
bash /c/t/rr.sh $F 'Two widget types from one app' 0 'NewsFeed Focus\*\* (adds a tap-to-enlarge' 0 /c/t/r.txt
sed -i 's/^## Focus Mode (NewsFeed Focus widget)/## Focus Mode (tap-behavior setting)/; s/while others shrink, without opening a separate view/while others stay the same size, without opening a separate view/' $F
cat > /c/t/r.txt <<'EOF'
| Adjustable focus scale (+/-) | ✅ | User controls how large the focused row gets, in the moment. |
| Per-widget setting | ✅ | "When I tap an article" in Widget settings picks Expand in place (default) or Focus (enlarge) for each placed widget; switching resets that widget's expanded/focused article. |
EOF
bash /c/t/rr.sh $F '^| Adjustable focus scale (+/-)' 0 '^| Adjustable focus scale (+/-)' 0 /c/t/r.txt
grep -n "NewsFeed Focus\|shrink" $F
```
Expected: `grep` prints nothing. If the README/PRD sentence spans differently, re-read the surrounding lines (`sed -n 8,13p docs/PRD.md`) and adjust the two-line replacement to keep the paragraph grammatical.

- [ ] **Step 3: `docs/DEBUG_PLAN.md`**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
F=docs/DEBUG_PLAN.md
cat > /c/t/r.txt <<'EOF'
**Before starting:** install the current build fresh (uninstall any prior version first if its signature might not match — see §0), and place **two "NewsFeed" widgets** before beginning: leave one on **When I tap an article: Expand in place** and set the other to **Focus (enlarge)** in its Settings, since several checks (§7, §8) compare the two modes. There is only one widget type in the picker now.
EOF
bash /c/t/rr.sh $F '^\*\*Before starting:\*\* install the current build fresh' 0 '^\*\*Before starting:\*\* install the current build fresh' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
- [ ] Open the system "Add widget" picker → confirm exactly **one** "NewsFeed" entry under the app (no "NewsFeed Focus"), with the correct label. Cross-check: `adb shell cmd package query-receivers --brief -a android.appwidget.action.APPWIDGET_UPDATE | grep newsfeed` lists only `NewsFeedWidgetReceiver`.
EOF
bash /c/t/rr.sh $F '^- \[ \] Open the system "Add widget" picker' 0 '^- \[ \] Open the system "Add widget" picker' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
- [ ] **When I tap an article** — confirm the row is present after **Open article in**, offers **Expand in place** (default) and **Focus (enlarge)**, shows the one-line "− / +" hint only while Focus is selected, and that there is **no** "Background rows size" slider in either mode.
EOF
bash /c/t/rr.sh $F '^- \[ \] \*\*Background rows size slider\*\*' 0 '^- \[ \] \*\*Background rows size slider\*\*' 0 /c/t/r.txt
cat > /c/t/r.txt <<'EOF'
- [ ] Confirm the build number shown in Settings is the installed build on every placed widget (there is one APK and one widget type now).
EOF
bash /c/t/rr.sh $F '^- \[ \] Repeat the "finds an update" checks above for' 0 '^- \[ \] Repeat the "finds an update" checks above for' 0 /c/t/r.txt
sed -i 's/(Focus widget only) Glamour + Font size 3.0×/(Focus mode only) Glamour + Font size 3.0×/' $F
cat > /c/t/dbg.txt <<'EOF'
## 7. Focus mode (per-widget setting: When I tap an article → Focus (enlarge))

- [ ] A newly placed widget defaults to **Expand in place**, and every widget that existed before this feature is still Expand.
- [ ] Set a widget to **Focus (enlarge)** and Save. Tap an article: it enlarges, its description/full text **auto-expands inline**, and **every other row stays exactly the same size** as in Expand mode (compare `uiautomator dump` bounds or screenshots of the same widget before and after the tap).
- [ ] Header controls in Focus mode: **N/M** shows the focused article's position and is accurate after each tap; **− / +** change the focused row's scale, clamped to 0.75×–2.5×, and the scale **resets to default** when focus moves to a different article. None of these controls ever appear on an Expand-mode widget. (The ▲/▼/✕ buttons were removed earlier and are not expected.)
- [ ] Tap the already-focused row again: focus clears and the widget looks like Expand mode.
- [ ] **Switch live, Expand → Focus:** on a widget with one article expanded, open Settings (⚙), switch to Focus, Save. Pull `files/datastore/appWidget-<id>.preferences_pb` (`adb exec-out run-as com.newsfeed.widget cat ...`) and confirm `expanded_article_id`, `last_tapped_article_id`, `focused_article_id`, `focus_scale` are all absent, the article count of `"isRead":true` in `articles_json` is unchanged, and the widget shows no expanded row.
- [ ] **Switch live, Focus → Expand:** with one article focused and a custom scale, switch to Expand, Save. Same DataStore check; no row enlarged; the previously focused article is **not** marked read by the switch itself.
- [ ] **Read on move-away, Expand mode:** tap article A then B: only A becomes read (`isRead` + `readAt` in DataStore), B stays unread. Repeat with an article that has no description (e.g. ynet flash / rotter) as A, and as B: same rule.
- [ ] **Read on move-away, Focus mode:** focus A then B: only A becomes read. Focus a description-less article and move away from it: it is marked read too.
- [ ] Old-config decoding (config JSON still carrying `focusBackgroundScale`): covered by `WidgetConfigTapModeTest`; not reproducible on-device (no surviving widget carries the key), record ⚠️ "unit-test only".
- [ ] Tapping a focused article's **Load more ↓** / **Open article →** / **Open in browser ↗** controls works exactly as on an Expand-mode widget.

---

## 8. Shared background work (single widget type) and orphan cleanup

- [ ] With one or more widgets placed: `NewsFeedRefresh` and `NewsFeedUpdateCheck` are scheduled (`adb shell dumpsys jobscheduler`), a `com.newsfeed.widget.CLOCK_TICK` alarm is pending and **no** `CLOCK_TICK_FOCUS` alarm exists (`adb shell dumpsys alarm | grep -c CLOCK_TICK_FOCUS` prints 0).
- [ ] With two widgets placed, remove one: jobs and `CLOCK_TICK` survive. Remove the last: the jobs are cancelled and `CLOCK_TICK` is gone.
- [ ] After removing a widget, its `files/datastore/appWidget-<id>.preferences_pb` is gone and its `widget_<id>` key is gone from `newsfeed_config` and `newsfeed_config_backup` (`grep -a -o "widget_[0-9]*"` on each file) — the `onDeleted` cleanup.
- [ ] **Update over a build that had a Focus widget placed** (no uninstall): the Focus widget disappears from the home screen, the standard widget keeps its feeds and renders, logcat has no `FATAL EXCEPTION` and no repeating error from the app. After one refresh (Save in Settings triggers it) the removed widget's `appWidget-<id>` file, config keys and `CLOCK_TICK_FOCUS` alarm are gone.
- [ ] If the only widgets before the update were Focus widgets: after the update and one worker run, `NewsFeedRefresh` and `NewsFeedUpdateCheck` are no longer scheduled; placing a NewsFeed widget re-arms them.
- [ ] Reboot with a widget placed: refresh, update-check and the `CLOCK_TICK` alarm resume without opening the app.

---

EOF
bash /c/t/rr.sh $F '^## 7\. Focus Mode' 0 '^## 9\. RTL' -1 /c/t/dbg.txt
grep -n "NewsFeed Focus\|Background rows\|CLOCK_TICK_FOCUS\|Focus widget" $F | cut -c1-140
```
Expected: `rr.sh` reports the replaced range starting at `## 7. Focus Mode (NewsFeed Focus widget only)` and ending at the blank line before `## 9. RTL / Hebrew locale`. The final `grep` shows only the intentional mentions (the picker check, the `CLOCK_TICK_FOCUS` checks in §8, and release-log history rows); any other stale mention should be reworded the same way.

- [ ] **Step 4: Release note and spec cross-pointer**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
printf '\n## Note 8\n- NewsFeed Focus is now a setting of the NewsFeed widget: choose "When I tap an article" then "Focus (enlarge)" in Widget settings, and only the article you tap grows while the others keep their size. This update removes any separate NewsFeed Focus widget from your home screen, so add the NewsFeed widget again and switch it to Focus.\n' >> docs/RELEASE_NOTES.md
tail -4 docs/RELEASE_NOTES.md | cut -c1-120
cat > /c/t/r.txt <<'EOF'
**Status:** Approved. The "two widgets in one app" decision was superseded on 2026-09-20 by `2026-09-20-focus-as-setting-design.md`: Focus is now a per-widget setting of the single NewsFeed widget.
EOF
bash /c/t/rr.sh docs/superpowers/specs/2026-09-05-merge-focus-mode-design.md '^\*\*Status:\*\* Approved' 0 '^\*\*Status:\*\* Approved' 0 /c/t/r.txt
```
Expected: `tail` shows the previous note's last bullet, a blank line, `## Note 8`, and the single-line bullet (one physical line, per the file's convention). `rr.sh` reports `(1 lines)`.

- [ ] **Step 5: Verify the release-notes parser still reads the file, then commit**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
bash /c/t/g.sh :app:testDebugUnitTest --tests "com.newsfeed.widget.update.ReleaseNotesFetcherTest" 2>&1 | grep -E "BUILD|FAILED" | head -3
git add README.md docs/PRD.md docs/DEBUG_PLAN.md docs/RELEASE_NOTES.md docs/superpowers/specs/2026-09-05-merge-focus-mode-design.md
git commit -m "Document Focus as a per-widget setting; add release note 8 and QA plan" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
Expected: `BUILD SUCCESSFUL`.

---

### Task 8: Release gate: on-device QA, recorded results

This is a user-facing feature, so per `docs/DEBUG_PLAN.md` §14 nothing is pushed until it is verified on the real device with hard evidence and the results are written into the debug plan. Set `ADB=/c/Android/platform-tools/adb` in every block. If the device is offline or a check is impossible, say so, record ⚠️, and do **not** call it release-ready.

**Files:**
- Modify: `docs/DEBUG_PLAN.md` (append a release-log subsection)

- [ ] **Step 1: Device present**

```bash
ADB=/c/Android/platform-tools/adb
$ADB devices
```
Expected: one device with state `device` (not `offline`/`unauthorized`). Otherwise STOP and report; do not continue to Task 9.

- [ ] **Step 2: Build and install the previous build (baseline) so a Focus widget can exist**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git worktree add /c/t/baseline b16ce45
cp local.properties /c/t/baseline/
REPO=/c/t/baseline bash /c/t/g.sh :app:assembleDebug -PbuildVersionCode=900 2>&1 | grep -E "BUILD"
ADB=/c/Android/platform-tools/adb
$ADB install -r /c/t/baseline/app/build/outputs/apk/debug/app-debug.apk
```
Expected: `BUILD SUCCESSFUL`, then `Success`. `b16ce45` is the last code commit before the spec work. If `install -r` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (installed app is signed with a different key, e.g. a CI build), **STOP and ask the user** before uninstalling: uninstalling deletes their widgets and data.

- [ ] **Step 3: On the baseline, place one NewsFeed widget and one NewsFeed Focus widget, and snapshot their state**

Place both from the launcher's widget picker (drive it with `adb shell uiautomator dump` + `adb shell input tap`, as in earlier sessions; take screenshots with `$ADB exec-out screencap -p > /c/t/qa_NN.png` and read the PNGs). Give the Focus widget's Settings a distinctive value (Background rows size 25%) and Save; focus one article in it. Then:

```bash
ADB=/c/Android/platform-tools/adb
$ADB shell dumpsys appwidget | grep -B1 -A2 "com.newsfeed.widget" | head -30
$ADB shell run-as com.newsfeed.widget ls files/datastore
$ADB shell dumpsys alarm | grep -c CLOCK_TICK
```
Record the two appWidget ids (STD_ID, FOCUS_ID), the `appWidget-*.preferences_pb` listing, and the alarm count (expect both `CLOCK_TICK` and `CLOCK_TICK_FOCUS` present). Note the standard widget's feed count from its Settings.

- [ ] **Step 3: Update in place (no uninstall) to the new build and check the Focus widget removal**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
bash /c/t/g.sh :app:assembleDebug -PbuildVersionCode=901 2>&1 | grep -E "BUILD"
ADB=/c/Android/platform-tools/adb
$ADB logcat -c
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
$ADB shell dumpsys appwidget | grep -c NewsFeedFocusWidgetReceiver
$ADB shell dumpsys appwidget | grep -c NewsFeedWidgetReceiver
$ADB logcat -d | grep -E "FATAL EXCEPTION|AndroidRuntime" | head
$ADB exec-out screencap -p > /c/t/qa_after_update.png
```
Expected: `BUILD SUCCESSFUL`, `Success`, `0` (Focus receiver gone), a positive number (standard widget still registered), no `FATAL` lines. Read `/c/t/qa_after_update.png`: the Focus widget is gone from the home screen and the standard widget still renders with its feeds.

- [ ] **Step 4: Orphan cleanup after one refresh**

Open the standard widget's Settings (⚙), tap **Save** (this triggers `WidgetWorker.refreshNow`), wait ~20 s, then:

```bash
ADB=/c/Android/platform-tools/adb
$ADB shell run-as com.newsfeed.widget ls files/datastore
for f in newsfeed_config newsfeed_config_backup; do echo "== $f"; $ADB exec-out run-as com.newsfeed.widget cat files/datastore/$f.preferences_pb | grep -a -o "widget_[0-9]*" | sort -u; done
$ADB shell dumpsys alarm | grep -c CLOCK_TICK_FOCUS
$ADB shell dumpsys alarm | grep -c "com.newsfeed.widget.CLOCK_TICK"
$ADB logcat -d | grep -E "FATAL EXCEPTION" | head
```
Expected: the listing has **no** `appWidget-<FOCUS_ID>.preferences_pb`; both config files list only STD_ID (and any other live standard widget ids); `CLOCK_TICK_FOCUS` count `0`; the standard `CLOCK_TICK` still present; no fatal exceptions.

- [ ] **Step 5: Feature QA on the new build (DEBUG_PLAN §7)**

Work through every item of §7 of `docs/DEBUG_PLAN.md` on the standard widget (place a second NewsFeed widget so one stays Expand and one is Focus). Evidence to capture per item:
- Non-focused rows unchanged in size while one is focused: `$ADB shell uiautomator dump /sdcard/u.xml && $ADB pull /sdcard/u.xml /c/t/u.xml` before and after the tap; compare the row bounds of the non-focused rows.
- Live switch both directions: DataStore keys before/after (`$ADB exec-out run-as com.newsfeed.widget cat files/datastore/appWidget-<id>.preferences_pb | grep -a -o "expanded_article_id\|focused_article_id\|last_tapped_article_id\|focus_scale" | sort -u`, expect empty after the switch) and read-flag counts before/after (`... | grep -a -o '"isRead":true' | wc -l`, expect equal).
- Read on move-away in both modes, including a description-less article (compare the `isRead`/`readAt` of the A and B article ids in the pulled DataStore).
- N/M and −/+ (range 0.75×–2.5×, reset on focus change).
- Config restore/persistence: the standard widget's feed list after the update equals the count noted in Step 3.
- Picker: `$ADB shell cmd package query-receivers --brief -a android.appwidget.action.APPWIDGET_UPDATE | grep newsfeed` lists exactly one receiver, `NewsFeedWidgetReceiver`.
- Jobs after reboot: `$ADB reboot`, wait for boot, then `$ADB shell dumpsys jobscheduler | grep -A2 NewsFeedUpdateCheck` and the `CLOCK_TICK` alarm are present again.
- Logcat clean for the whole session: `$ADB logcat -d -b crash` empty, and no repeating app errors in `$ADB logcat -d | grep -i "newsfeed" | grep -E " E "`.

- [ ] **Step 6: Remove the baseline worktree**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git worktree remove --force /c/t/baseline
git worktree list
```
Expected: only the main worktree listed.

- [ ] **Step 7: Record the results in `docs/DEBUG_PLAN.md` and commit**

Append after the last table of the "Release log" section (before the `Known unverified:` line) a subsection built from the template below. Replace **every** `RESULT` marker with what was actually observed (✅ / ❌ with a short note / ⚠️ with why it could not be tested). Never mark ✅ without observing it.

```markdown
#### Focus as a per-widget setting (2026-09-20)

| Feature | What must be true | Verified how | Result |
|---|---|---|---|
| Single widget in picker | Exactly one NewsFeed entry; no NewsFeed Focus | `pm query-receivers` + launcher picker screenshot | RESULT |
| Update from previous build removes Focus widget | No crash, standard widget intact, no log spam | `adb install -r` over baseline `b16ce45`, dumpsys appwidget, logcat, screenshot | RESULT |
| Orphan cleanup | Removed Focus id's appWidget file, config keys, CLOCK_TICK_FOCUS gone after one refresh | `run-as ls files/datastore`, `grep -a -o widget_[0-9]*`, `dumpsys alarm` | RESULT |
| Default and existing widgets are Expand | New and pre-existing standard widgets default to Expand | Settings screenshot | RESULT |
| Focus: only focused row enlarges | Non-focused rows same size as in Expand mode | `uiautomator dump` bounds before/after | RESULT |
| N/M and −/+ header controls | Correct position, 0.75×–2.5×, reset on focus change | Screenshots + DataStore | RESULT |
| Live mode switch both directions | Transient keys cleared, read flags unchanged | DataStore before/after | RESULT |
| Read on move-away, both modes incl. description-less | Only the previous article marked read | DataStore isRead/readAt | RESULT |
| Old config with focusBackgroundScale 0.25 | Decodes and renders as normal | Unit test only (not reproducible on device) | RESULT |
| Config persistence and reboot | Feeds intact; jobs and CLOCK_TICK resume | Settings feed count, jobscheduler, alarm | RESULT |
```

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
grep -c "RESULT" docs/DEBUG_PLAN.md
git add docs/DEBUG_PLAN.md
git commit -m "Record Focus-as-setting on-device QA in the debug plan" -m "Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```
Expected: the `grep -c` prints `0` before committing. Then **report the QA results to the user, including anything not verified.** Only continue to Task 9 if nothing is ❌ and the user agrees the unverified items (at least the ⚠️ old-config item) are acceptable.

---

### Task 9: Pre-push checks, push, verify CI

**Files:** none (verification and push only).

- [ ] **Step 1: Full unit-test run: only the known failure may remain**

```bash
bash /c/t/g.sh :app:testDebugUnitTest --continue 2>&1 | grep -E "BUILD|tests completed" | head
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
grep -l "<failure" app/build/test-results/testDebugUnitTest/*.xml
```
Expected: `BUILD FAILED` with `tests completed, 1 failed`, and the `grep -l` prints exactly `.../TEST-com.newsfeed.widget.glance.WidgetThemesTest.xml` (the known `colorProvidersFor custom wraps...` failure). Any other failing class blocks the push.

- [ ] **Step 2: Security review of everything that would be pushed**

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git fetch origin main 2>&1 | tail -1
git log origin/main..HEAD --oneline
git diff --name-only origin/main..HEAD | grep -Ei '\.(jks|keystore|apk|properties|env|pem|p12)$|secret|token|credential' ; echo "sensitive-file grep exit: $?"
git diff origin/main..HEAD | grep -nEi 'ghp_[A-Za-z0-9]{20,}|github_pat_|gho_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|Authorization: (Bearer|token) [A-Za-z0-9]|(api[_-]?key|secret|passwd|password)[^A-Za-z0-9]{1,4}[A-Za-z0-9]{16,}' ; echo "secret-pattern grep exit: $?"
git remote -v | sed -E 's#(https://)[^@/]+@#\1***@#'
git status --short
```
Expected: the commit list shows the spec, plan-independent commits from Tasks 1-8 (plus the two earlier spec commits); `sensitive-file grep exit: 1` and `secret-pattern grep exit: 1` (no matches); the remote URL is `https://github.com/Had-com/NewsFeed-widget.git` with no embedded credentials (a `***@` after masking means a token is embedded in the remote: STOP and tell the user, this repo has a prior token-leak incident); `git status --short` shows only the untracked `docs/superpowers/plans/*` files (none of them staged or committed with a secret). Skim `git diff origin/main..HEAD --stat` and confirm the file list matches the File Structure table above.

- [ ] **Step 3: Get the user's go-ahead, then push**

Present: the Task 8 result table, the ⚠️ items, and the security-scan result. Push only on an explicit yes.

```bash
cd /c/Users/PhotoStudio/ClaudeWorkspace/repo
git push origin main
git log -1 --format=%H
```
Expected: push succeeds; note the head SHA.

- [ ] **Step 4: Verify CI is green**

```bash
SHA=$(git -C /c/Users/PhotoStudio/ClaudeWorkspace/repo log -1 --format=%H)
curl -s "https://api.github.com/repos/Had-com/NewsFeed-widget/actions/runs?branch=main&per_page=3" | grep -E '"(head_sha|status|conclusion|run_number)"' | head -12
```
Expected: the newest run's `head_sha` equals `$SHA`. Re-run the command about every 2 minutes (the build takes several minutes) until that run shows `"status": "completed"` and `"conclusion": "success"`. Then confirm the rolling release picked up the run number:

```bash
curl -sL https://github.com/Had-com/NewsFeed-widget/releases/download/latest/version.json
```
Expected: `{"versionCode": <run_number of that run>}`. If the run fails, read its logs, fix forward with a new commit (never amend or force-push), and re-verify.
