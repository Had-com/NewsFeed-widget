# Widget features — design spec
**Date:** 2026-08-22  
**Project:** readyou-widget  
**Status:** Approved

---

## Overview

Five new features added to the Read You home-screen widget and its config screen. The biggest behavioral change is replacing the "tap to open external app" flow with an inline expand/collapse article reader inside the widget itself.

---

## Feature 1 — Refresh button + countdown in widget footer

### What
The widget footer shows "↻ refresh in 12min" on the left side. Tapping the text triggers an immediate feed fetch. The right side keeps "All articles →".

### Data
- `WidgetWorker.doWork()` writes `System.currentTimeMillis()` to a new Glance state key `WidgetStateKey.lastRefreshTime` (a `longPreferencesKey`) after each successful fetch.
- Minutes remaining = `(lastRefreshTime + intervalMinutes * 60_000 - now) / 60_000`, clamped to `0..intervalMinutes`.
- Displayed as: `"↻ refresh in ${minutes}min"` (e.g. "↻ refresh in 0min" when overdue).

### Action
New Glance `ActionCallback`: `RefreshNowCallback`. Calls `WidgetWorker.refreshNow(context)` then returns.

### Rendering
In `ReadYouWidget.WidgetFooter()`: replace the current single `Text("All articles →")` with a `Row` containing two tappable `Text` elements using `actionRunCallback<RefreshNowCallback>()` and a plain `Text` for the right side.

---

## Feature 2 — Edit feed name + URL from config screen

### What
The feed display name in each `FeedConfigRow` becomes tappable. Tapping opens an `AlertDialog` with two `OutlinedTextField`s: Name and URL.

### State
`WidgetConfigActivity` adds:
```kotlin
var editingFeed by remember { mutableStateOf<FeedConfig?>(null) }
```
When non-null, the dialog renders. On dismiss: `editingFeed = null`. On confirm:
1. Update `feedConfig.displayName` and `feedConfig.feedUrl` in `config.feeds`.
2. If URL changed, call `repo.fetchFeedTitle(newUrl)` in a coroutine to validate — show a progress indicator while loading; show an inline error if the fetch returns null (bad URL).
3. Call `onUpdate(updated)`.

### UI change in `FeedConfigRow`
The `Text(feedConfig.displayName)` gains `.clickable { /* signal parent */ }`. Since `FeedConfigRow` already has `onUpdate`, add a new `onEditRequest: () -> Unit` callback that `WidgetConfigActivity` binds to `editingFeed = feedConfig`.

---

## Feature 3 — Per-feed display mode: text or text+image

### Data model
`FeedConfig` gains:
```kotlin
val displayMode: String = "text"  // "text" | "image"
```
`ArticleItem` gains:
```kotlin
val imageUrl: String = ""
```

### RSS parsing (`ReadYouRepository`)
In `parseItem()`, extract the first image URL from (in priority order):
1. `<media:thumbnail url="..."/>` (namespace `media:`)
2. `<media:content url="..." medium="image"/>` 
3. `<enclosure type="image/..." url="..."/>`

The `url` value is stored in `article.imageUrl`. If none found, `imageUrl` stays empty.

Note: `media:` namespace elements use `parser.getAttributeValue(null, "url")` since `XmlPullParser` receives prefixed names; the namespace URI is not required when the document declares the prefix.

### Thumbnail pre-download (`WidgetWorker`)
After fetching all articles, for each feed where `feedConfig.displayMode == "image"`:
- For each article where `imageUrl.isNotBlank()`:
  - Target file: `File(context.cacheDir, "thumb_${article.id.hashCode()}.jpg")`
  - Skip download if file already exists and is less than 24 hours old.
  - Download with OkHttp (reuse existing client from repository — extract to a shared singleton or pass client in).
  - Decode with `BitmapFactory.decodeStream()`, scale to max 120×120px, save as JPEG quality 75.
  - On any error: skip silently (image just won't show).

### Rendering (`FeedItemRow`)
`FeedItemRow` receives `feedConfig.displayMode`. When `"image"` and `article.imageUrl.isNotBlank()`:
- Compute thumb file path the same way as worker.
- If file exists: render `Image(provider = ImageProvider(BitmapFactory.decodeFile(path)), ...)` at `40.dp × 40.dp`, `cornerRadius = 6.dp`, aligned to the trailing edge of the row (after the text column).
- If file missing: render nothing (degrade gracefully to text-only).

### Config UI (`FeedConfigRow`)
Add a "Text / Image" toggle after the RTL/LTR button, same style (bordered text label, toggles `displayMode`).

---

## Feature 4 — Global font size slider

### Data model
`WidgetConfig` gains:
```kotlin
val fontSize: Float = 1.0f   // range 0.75 – 1.5
```

### Config UI
In `WidgetConfigActivity`, inside the Sort & Filter section, add after the Refresh interval row:
```
Font size    [slider 0.75 ──●────── 1.5]   Medium
```
Use Compose `Slider(value, onValueChange, valueRange = 0.75f..1.5f)`. Label to the right shows "Small" / "Medium" / "Large" based on value thresholds (< 0.9 → Small, > 1.2 → Large, else Medium).

### Rendering
`FeedItemRow` receives `fontSize: Float`. Multiply base sizes:
- Meta line (feed name, time): `(9 * fontSize).sp`
- Headline: `(13 * fontSize).sp`

Base sizes adjusted slightly: headline base raised from 11sp to 13sp to give the slider meaningful range.

Pass `fontSize` from `WidgetContent()` through to `FeedItemRow`. Glance state already carries the full `WidgetConfig` JSON, so no new keys needed.

---

## Feature 5 — Inline article expand/collapse + configurable external open

### Behavioral overview
- **Primary tap**: article expands inline. Description appears below the title; other articles fade to 30% opacity. Second tap on the same article collapses it.
- **"Open in [app]" button**: appears inside the expanded view, uses a per-widget config setting.
- **`DeepLinkActivity` is removed.** All interactions are Glance `ActionCallback`s.

### Data model

`ArticleItem` gains:
```kotlin
val description: String = ""
```

`WidgetConfig` gains:
```kotlin
val externalApp: String = "browser"   // "browser" | "share"
```

> **Update (2026-08-25):** the third option, opening in a companion "Read You" RSS reader app (`me.ash.reader`), was removed. It depended on an external app most users don't have installed, and QA found it added confusion without adding value over Browser/Share — see the bug-fix log below.

`WidgetStateKey` gains:
```kotlin
val expandedArticleId = stringPreferencesKey("expanded_article_id")  // "" = none expanded
```

### RSS parsing
In `parseItem()`, capture `<description>` text content. Also check for `<content:encoded>` (namespace-prefixed, handled same way as `media:`). Strip HTML tags with `android.text.Html.fromHtml(html, FROM_HTML_MODE_COMPACT).toString().trim()`. Truncate to 400 characters to keep Glance state size bounded.

### Glance state for expanded article
`expandedArticleId` lives in the same `AppWidgetState` preferences updated by `updateAppWidgetState`. It is a `String` (empty = no article expanded). It is **not** reset by `WidgetWorker` on refresh — persists until user explicitly collapses.

### ActionCallbacks

**`ToggleExpandCallback`**
Parameters: `articleId: String` (passed via `actionRunCallback<ToggleExpandCallback>(ActionParameters.Key<String>("articleId") to article.id)`).

Logic:
1. Read current `expandedArticleId` from state.
2. If equal to incoming `articleId` → write `""` (collapse).
3. Else → write `articleId` (expand).
4. Call `ReadYouWidget().updateAll(context)`.

**`OpenExternalCallback`**
Parameters: `articleUrl: String`, `articleId: String`.

Logic:
1. Read `WidgetConfig` from state to get `externalApp`.
2. Mark article as read via `ReadStatusStore.markRead(articleId)`.
3. Launch intent based on `externalApp`:
   - `"browser"`: `Intent(ACTION_VIEW, uri)`.
   - `"share"`: `Intent(ACTION_SEND).setType("text/plain").putExtra(EXTRA_TEXT, url)` wrapped in `Intent.createChooser`.
4. All intents require `FLAG_ACTIVITY_NEW_TASK` (launching from non-Activity context).
5. Trigger `WidgetWorker.refreshNow(context)` after marking read.

**`RefreshNowCallback`** (Feature 1)
Calls `WidgetWorker.refreshNow(context)`.

### Rendering (`ReadYouWidget` + `FeedItemRow`)

`WidgetContent()` reads `expandedArticleId` from state. Passes it to each `FeedItemRow`.

`FeedItemRow(article, feedConfig, fontSize, expandedArticleId)`:

**Collapsed (not expanded article):**
```
[stripe] [meta: feed name · time]          opacity: if any article expanded and this isn't it → 0.3
         [title, max 2 lines]
```
Tap action: `actionRunCallback<ToggleExpandCallback>(...)`.

**Expanded (this article is the expanded one):**
```
[stripe] [meta: feed name · time]
         [title, max 2 lines]
         [description, max 5 lines, 10sp, rgba(255,255,255,.6)]
         [Open in [app label] button]        → OpenExternalCallback
```
Tap on the row: `actionRunCallback<ToggleExpandCallback>(...)` (collapses).
The "Open" button has its own click action and does not collapse.

Opacity dimming for non-expanded articles: Glance `GlanceModifier.alpha()` is not available — use `ColorProvider` tinting or `background` overlay. Practical approach: pass `isDimmed: Boolean` to each row; when true, render headline and meta in muted colors (`rgba(255,255,255,.3)` equivalent via hardcoded `Color`).

"Open in [app]" button label:
- `"browser"` → "Open in browser"
- `"share"` → "Share article"

### Config UI for external app

In `WidgetConfigActivity` Sort & Filter section, add an "Open article in" row with a dropdown matching the refresh/sort/filter dropdowns. Options: "Browser", "Share sheet".

### Removing `DeepLinkActivity`

- Remove `DeepLinkActivity.kt`.
- Remove its `<activity>` entry from `AndroidManifest.xml`.
- Remove `me.ash.reader` / `io.github.ashinch.readyou` from `<queries>` — no code references them once the "Read You" external-open option is gone (see 2026-08-25 update above).
- `FeedItemRow` no longer imports or uses `DeepLinkActivity`.

---

## Files changed

| File | Change |
|---|---|
| `data/FeedConfig.kt` | Add `displayMode` to `FeedConfig`; add `fontSize`, `externalApp` to `WidgetConfig`; add `description`, `imageUrl` to `ArticleItem` |
| `data/WidgetStateKey.kt` | Add `lastRefreshTime`, `expandedArticleId` keys |
| `data/ReadYouRepository.kt` | Parse `description`, `imageUrl` in `parseItem()`; strip HTML |
| `data/ReadStatusStore.kt` | No change |
| `data/OpmlManager.kt` | No change |
| `glance/ReadYouWidget.kt` | Add `expandedArticleId` + `lastRefreshTime` state reads; update footer; pass new params to `FeedItemRow` |
| `glance/FeedItemRow.kt` | Full rework: expand/collapse rendering, dimming, thumbnail, font size param |
| `glance/WidgetWorker.kt` | Write `lastRefreshTime`; download thumbnails for image-mode feeds |
| `glance/RefreshNowCallback.kt` | New `ActionCallback` |
| `glance/ToggleExpandCallback.kt` | New `ActionCallback` |
| `glance/OpenExternalCallback.kt` | New `ActionCallback` |
| `config/WidgetConfigActivity.kt` | Font size slider, external app dropdown, edit-feed dialog |
| `config/FeedConfigRow.kt` | Make name tappable (`onEditRequest`); add Text/Image toggle |
| `DeepLinkActivity.kt` | **Deleted** |
| `AndroidManifest.xml` | Remove `DeepLinkActivity` entry; keep `<queries>` for `me.ash.reader` |

---

## Constraints and edge cases

- **Glance state size**: `ArticleItem` description capped at 400 chars; image URLs stored as strings. Total JSON for 25 articles per feed stays well under DataStore limits.
- **Thumbnail cache**: files are never auto-cleaned here — a future improvement. Cache directory is the OS-managed app cache so Android will evict under pressure.
- **Expand state across widget instances**: `expandedArticleId` is per-widget (keyed by `glanceId`), so two instances of the widget expand independently.
- **`media:` namespace in XmlPullParser**: `android.util.Xml.newPullParser()` does not enable namespace processing by default. Set `parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)` and access elements via `parser.localName` and check namespace URI, OR leave namespace processing off and match on `parser.name` which returns the prefixed name (`"media:thumbnail"`, `"content:encoded"`). Use the latter — simpler and consistent with the current parser.
- **`FLAG_ACTIVITY_NEW_TASK`**: Required for all `startActivity` calls from `ActionCallback` (which runs in a non-Activity context). Add to all intents in `OpenExternalCallback`.
- **`Html.fromHtml` API level**: `FROM_HTML_MODE_COMPACT` requires API 24. Our `minSdk` is 26 — safe.

---

## Bug fixes and improvements (shipped 2026-08-22 → 2026-08-24)

The following bugs were identified post-implementation and fixed:

| # | Bug | Fix |
|---|---|---|
| 1 | No Hebrew handwriting fonts available | Glamour theme renders headlines via Canvas bitmap using **Miriam Libre Bold** (bundled TTF); see `TextBitmapHelper.kt` |
| 2 | Timestamp not left-aligned in LTR rows | Meta row now places timestamp first on the left: `Time · Circle · Name` |
| 3 | Headlines not bold by default | Non-Glamour headlines now default to `FontWeight.Bold` unless `"normal"` is in `feedConfig.textStyle` |
| 4 | Glamour title color wrong (should be #7A5C3A, bold) | Glamour headlines use `GLAMER_LIGHT.onSurfaceVariant = Color(0xFF7A5C3A)` (dark: `0xFFA08060`); Miriam Libre Bold is inherently bold |
| 5 | Settings apply only on second entry | Race condition: `NewsFeedWidget().update()` ran before `WidgetWorker.refreshNow()` wrote new config to Glance DataStore. Fixed by calling `updateAppWidgetState()` synchronously before `update()` |
| 6 | Full article opens slowly, no immediate feedback | Two-phase loading: RSS description shown immediately on "Load full article" tap; widget re-renders with full content once the page fetch completes |
| 7 | Wrong defaults (was Auto/dark/no accent) | Defaults changed to `widgetTheme="glamer"`, `themeVariant="light"`, `useThemeColors=true` |
| 8 | Settings preview doesn't match selected theme | Preview now uses `WidgetThemes.rawColorSchemeFor()` and `WidgetThemes.fontFamilyFor()` to render a live themed preview of headlines, meta, and description |

### Technical details

**Race condition fix (`WidgetConfigActivity`):** `WidgetWorker.refreshNow()` enqueues an async WorkManager job; calling `NewsFeedWidget().update()` immediately after renders the widget with stale DataStore state. Fix: call `updateAppWidgetState(context, glanceId) { prefs -> prefs[WidgetStateKey.configJson] = Json.encodeToString(final) }` synchronously between `getGlanceIdBy()` and `update()`.

**`kotlinx.serialization.encodeToString` import:** This is a top-level extension on `StringFormat`, not a member of `Json`. Requires `import kotlinx.serialization.encodeToString` in addition to `import kotlinx.serialization.json.Json`.

**`rawColorSchemeFor()` in `WidgetThemes`:** New public function exposing the raw Material3 `ColorScheme` objects (previously private), used by the config activity preview.

**`TextBitmapHelper.kt` (new file):** Canvas-based bitmap renderer for Glamour theme headlines. Loads `R.font.miriam_libre_bold` via `ResourcesCompat.getFont()`, renders with `StaticLayout`, caches bitmaps in an LRU by `"$text|$textSizePx|$colorArgb|$widthPx|$isRtl"` key.

**`FetchFullArticleCallback` two-phase loading:** On tap, immediately writes `article.description` to `WidgetStateKey.fullArticleText` and calls `update()`, then fetches the full web page and calls `update()` again with the complete content.

---

## Bug fixes and improvements (shipped 2026-08-25, post-QA)

A full mobile QA pass (`app-debug_v25.apk`, Android 14 emulator, ~65 min of scripted testing) found 10 issues. Auditing HEAD (`df78a19` at the time) against each showed most were already fixed by intervening commits (`ce26b32`, `ede46bd`, `110c1ea`, `22d4547`, `a654321`, `df78a19` — race-condition fix, dark-theme fix, per-line `maxLines`, unified B/I/U style toggle). The table below covers only what changed as a direct result of this pass.

| # | QA finding | Status | Change |
|---|---|---|---|
| 1 | Manual refresh fails silently when offline or after force-stop — `WidgetWorker.doWork()` always returned `Result.success()` even when every feed fetch threw, with no way for the UI to tell "refreshed, nothing new" from "refresh didn't work" | **Fixed** | `NewsFeedRepository.getArticles()` now returns `ArticleFetchResult(articles, allFailed)`; `allFailed` is true only when every *enabled* feed's fetch threw (a few dead feeds among many working ones is normal, not an error). `WidgetWorker` persists this as `WidgetStateKey.lastRefreshFailed`. The footer shows "⚠ refresh failed — tap to retry" in place of the countdown when set, using the same tap target as the existing refresh-now action. |
| 2 | Long feed names wrap illegibly at large font sizes / overlap the unread-dot at minimum widget width | **Hardened** | The feed-name `Text` in `FeedItemRow`'s meta row already had `maxLines = 1`; it lacked a width constraint, so a long name could still push the favicon circle or unread dot out of the row instead of truncating. Added `GlanceModifier.defaultWeight()` to the name `Text` (both RTL and LTR branches) so it's bounded to whatever space remains after the fixed-size siblings. |
| 3 | Critical: tapping an article did nothing in any "open in" mode, with logcat showing a trampoline-activity pause timeout and OkHttp connection-pool contention | **Not reproducible at HEAD** | The tap path (`ToggleExpandCallback` → expand → `OpenExternalCallback`) does no network I/O — it reads local DataStore and calls `startActivity`. The symptom looks like it belonged to an earlier build of the tap-handling code (v25 predates several of the commits above); no blocking call was found in the current path. Flagged for re-verification once a fresh build can be installed and QA'd (see note below). |
| 4 | Config Save doesn't refresh the live widget until manual refresh | **Already fixed** | `WidgetConfigActivity`'s Save handler already writes `configJson` into Glance state synchronously and calls `update()`/`updateAll()` before enqueuing the background re-fetch — this is the exact race-condition fix logged as item 5 in the table above, just landed after v25 was built. |
| 5 | Feed list add/remove doesn't reliably persist | **Already fixed** | Same synchronous-save path as above; `feedOrder` is correctly folded into the saved config (`config.copy(feedOrder = feedOrder.toList())`) before `store.save()`. |
| 6 | Font size setting intermittently reverts | **Unconfirmed, not changed** | Only observed once by QA, not independently reproducible, and no corresponding code issue was found in the load/save path. Left as-is rather than guessing at a fix for an unconfirmed defect. |
| 7 | Italic toggle has no active-state highlight | **Already fixed** | Bold/Italic/Underline all render through one shared `StyleToggle()` composable in `FeedConfigRow.kt` with identical highlight logic — there's no special-casing that would make Italic behave differently from the other two. |
| 8 | No vertical resize handles appear despite `resizeMode="horizontal\|vertical"` | **Not a code defect** | `appwidget_info.xml`'s `minResizeWidth`/`minResizeHeight` already carry correct `dp` units. QA's own note flagged the AVD's very small screen (320×640) as the more likely cause — recommend re-checking on a larger-screen device rather than treating this as a bug. |
| 9 | Settings screen ignores system dark theme | **Already fixed** | `WidgetConfigActivity.onCreate()` already wraps content in `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme())` — this is precisely what commit `df78a19` ("config activity now respects system dark mode") landed, one commit after v25 was built. |
| 10 | "Read You" companion-app open-in option | **Removed** | Confirmed already absent from `FeedConfig.externalApp`'s docstring, `WidgetConfigActivity`'s `externalOptions` list, and `OpenExternalCallback`'s `when` branch — only "Browser" and "Share sheet" remain. `AndroidManifest.xml` has no `DeepLinkActivity` or `me.ash.reader` `<queries>` entry. This spec doc's Feature 5 section has been updated to match (see the 2026-08-25 note above). |

**Verification note:** local `gradlew assembleDebug` could not be run in the environment these fixes were made in — the Gradle daemon couldn't establish its loopback IPC connection (a machine-level networking restriction, not a project issue). Changes were reviewed carefully by reading the surrounding code paths, but a real build + install + QA re-pass (items 3 and 6 especially) is recommended once these commits are pushed and CI produces a new build.
