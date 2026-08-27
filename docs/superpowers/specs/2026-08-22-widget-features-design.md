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

**Verification note:** local `gradlew assembleDebug` could not be run in the environment these fixes were made in — the Gradle daemon couldn't establish its loopback IPC connection. This turned out to be fixable (see below), and a real device QA pass against the fixed build found two more Critical bugs that source review alone had missed — logged in the next section.

---

## Bug fixes and improvements (shipped 2026-08-26, verified on-device)

The local build environment was fixed (root cause: `TMP`/`TEMP` pointing at a long path this JDK's NIO socket code couldn't handle, plus an unrelated malformed `sdk.dir` in `local.properties`) — no firewall changes were actually needed despite an earlier wrong theory. This unblocked a real build → install → QA loop, which re-verified items 1, 2, 4, 5, 7, 9, 10 from the 2026-08-25 table above as genuinely fixed on-device, and surfaced two more issues:

| # | Bug | Fix |
|---|---|---|
| 1 | (Was item 3, "critical tap does nothing," previously not reproducible in code review) Tapping "Open article" did nothing — logcat showed `InvisibleActionTrampolineActivity` created and torn down within ~100-300ms without ever firing the intent | Article rows live inside a `LazyColumn`, which routes their clicks through Glance's list-adapter trampoline. A custom `ActionCallback` calling `context.startActivity()` manually doesn't reliably survive that extra hop under Android 12+ background-activity-start rules — confirmed against [Android's Glance interaction docs](https://developer.android.com/develop/ui/compose/glance/user-interaction), which specify `actionStartActivity()` for exactly this. Rebuilt the "Open article" button to construct its `Intent` at compose time and use `actionStartActivity()` directly; removed `OpenExternalCallback.kt`. Read-status marking (previously done there) moved to `ToggleExpandCallback` — expanding an article now marks it read, since the new click path can't run a suspend body. |
| 2 | New: configuring several image-thumbnail feeds with the Glamour theme (per-article headline bitmaps) could push a widget update's total RemoteViews bitmap payload over Android's ~1.2MB cap, crashing to a permanent "Can't show content" state with no visible recovery path | Reduced the rendered-article cap in `NewsFeedWidget.kt` from 50 to 15; unread-count badge now counts against the full article set rather than just the visible slice, so the cap doesn't under-report unread items. |
| 3 | (Was item 6, font size reverts) still reproducible ~1 in 3 attempts, correlated with a system config change (dark-mode toggle) happening around a settings save | **Not fixed this round** — plausible race between `WidgetConfigActivity`'s `lifecycleScope`-bound save and an Activity recreation triggered by the same config change, but not confirmed with a logcat trail. Left for dedicated investigation. |
| 4 | A leftover `com.readyou.widget` test install on the QA emulator was occasionally getting tested by mistake instead of the real app — two identically-branded "NewsFeed" entries appeared in the widget picker | Uninstalled from the test emulator; not a code change. |

A follow-up QA pass against a fresh rebuild confirmed item 1 (article open, Browser mode) genuinely works end-to-end, including mark-read-on-expand — but reproduced item 2's crash again, with theme (Glamour vs Simple) making no difference, which was the key clue that pointed away from the per-headline bitmap rendering entirely:

| # | Bug | Fix |
|---|---|---|
| 5 | Item 2's crash was still reproducible after the 50→15 article-cap mitigation, identically under Glamour and Simple themes | The cap wasn't the real driver. Root cause: article-thumbnail downloads were saved at 300px (a prior change bumped this up for visual quality without accounting for RemoteViews' ~1.2MB total bitmap-memory budget) — a single 300px bitmap decodes to 360KB, so as few as ~4 thumbnails in one update blew the budget. The math lined up almost exactly with QA's measured overage. Dropped the thumbnail cap to 100px (~40KB decoded, no visible quality loss at the widget's actual display size) and fixed the stale-cache migration check that was miscalibrated for the new size. **Re-verified clean** under both the original repro proportion and a worst-case all-8-feeds-with-images scenario — confirmed via logcat (no exceptions) and by measuring cached thumbnail files directly (100×56–66px, 2.8–4.5KB each). |
| 6 | "Open article in: Share sheet" did nothing — logcat showed `ActivityNotFoundException: No Activity found to handle null`, with the launched PendingIntent's data replaced by an internal `glance-action:/` placeholder | **Fixed, on the third attempt** — see below. |

Share sheet mode took three attempts to actually fix, each verified on-device rather than assumed:

1. Dropping the `Intent.createChooser()` wrapper (theory: it didn't survive `actionStartActivity()`'s handling) — **re-tested, still failed identically.**
2. Giving each row's `ACTION_SEND` intent a distinct `data` field, since Glance's own `actionStartActivity()` docs warn actions are "conflated unless the underlying intents are distinct" and `Intent.filterEquals()` (the distinctness check) ignores extras — every row's intent had the same action+type and differed only in `EXTRA_TEXT`, so all rows looked identical to Glance. **Routing improved** (taps now correctly reached `InvisibleActionTrampolineActivity`, matching Browser mode) **but the trampoline started silently self-finishing without ever launching anything** — no exception this time, just silence.
3. **Actually fixed**: rather than continue fighting Glance's handling of an inherently ambiguous multi-target `ACTION_SEND`/chooser intent from a widget `PendingIntent` context, added `ShareRelayActivity` — a tiny `Theme.NoDisplay` activity in our own app (mirroring the original pre-refactor `DeepLinkActivity`'s internal-forwarding pattern). Share mode's button now targets it via an explicit-component intent (still with a distinct `data` per row, to avoid the same conflation issue hitting the new relay target); once it's a real running Activity, it builds and launches the chooser normally, sidestepping the widget-context restrictions entirely. **Verified on 3 distinct articles** (including after a cold `force-stop`), each producing the correct, distinct share URL with no cross-row conflation, and a clean logcat with no trampoline-self-finish pattern.

**Known remaining issues, not investigated:**
- The QA pass noted article expand/collapse state appeared to reset intermittently between taps during one earlier (later-flagged-as-degraded-environment) testing session — not reproduced in the final clean-environment pass, likely was emulator flakiness rather than a real bug, but not conclusively ruled out.
- A small numeric badge in the widget's top-right corner (values like "90", "89", "98" observed) ticks down over time with no visible label — looks like a debug/countdown artifact left in. Not investigated.
- Twice during scripted large/fast synthetic swipes near the top of the Settings scroll view, `WidgetConfigActivity` unexpectedly exited (`DeadObjectException`) and unsaved changes were lost, with the widget itself briefly showing "Can't load widget" before self-recovering. Judged likely a synthetic-input artifact (gentler swipes never triggered it) rather than a real bug from human touch, not filed — but noted in case it recurs.

---

## Bug found and fixed (shipped 2026-08-26, spotted directly by the user)

| # | Bug | Fix |
|---|---|---|
| 7 | Glamour theme's Hebrew headlines rendered as huge, stretched, single-word-per-line text — illegible, and not visibly the intended handwriting-style font | Root cause (confirmed via temporary debug logging): the widget instance under test was sized at its resize minimum (`minResizeWidth="130dp"`), left over from earlier resize testing, on top of an unusually low-density test screen. At that width, a 52dp-wide thumbnail was eating ~40% of the row, leaving only ~59px for the Glamour headline bitmap's internal text layout — which wrapped correctly narrow for that width, but then got scaled *up* by `Image(fillMaxWidth(), ContentScale.Fit)` to match the actual, much wider display column. Since `130dp` is a size the app claims to support, fixed rather than just resetting the test widget: headlines now fall back to the existing plain-`Text()` rendering path (previously only used when font loading failed) when the computed width drops below 120px — native wrap/ellipsis degrades far more gracefully than the custom bitmap approach at extreme widths. **Verified**: default-size widget renders Hebrew correctly and legibly in both Light and Dark variants (ynet, Walla, rotter.net, N12, כאן headlines, including mixed Hebrew+digit+English strings); the minimum-width case now shows small-but-legible plain text instead of the broken bitmap-stretch result; a full images-off/all-8-feeds/Glamour stress test at default size stayed crash-free. |

**Process note:** the QA pass that hit this took the zoomed close-up screenshot exactly as asked, but didn't critically judge it before moving on — the user caught the defect directly from a live screenshot. Updated `~/.claude/agents/mobile-qa.md` with a "Visual Quality Review" section requiring the agent to actually look at what it captures (not just capture it), give custom-rendered content extra scrutiny, and treat visual defects as bugs even with a clean logcat.

| # | Bug | Fix |
|---|---|---|
| 8 | Feed name appeared stranded mid-row instead of grouped with the avatar circle on the right edge (RTL meta row) — only the timestamp should be pinned left, per design | Direct side effect of item 2 above (the BUG-001/010 overflow fix): that fix moved `defaultWeight()` onto the name `Text` itself, replacing a dedicated weighted `Spacer` that had been positioned *before* the whole dot+name+circle group and was what pushed the group to hug the right edge. Restored that spacer; gave the name a fixed max width (`70dp * fontSize`) instead of `defaultWeight()` for the overflow guard, since one Row can't have two different elements each meaningfully "weighted" for two different jobs. Verified visually: every row now shows time on the physical left with a clear gap, then name grouped tightly against its avatar on the right. |

**Second process note:** neither the RTL-grouping bug above nor the "not actually handwriting" font question (below) were caught by QA verification tasks that asked it to confirm "Hebrew renders correctly, legibly, RTL-ordered, proportionate" — that's a real but *lower* bar than "the specific elements are positioned/styled exactly as described." Added guidance to `~/.claude/agents/mobile-qa.md`: when asked to verify a specific claimed property (a named style, a stated element arrangement), check the actual claim, not a related-but-easier one — "a distinctive font is rendering" doesn't verify "this looks like handwriting," and "content is legible and RTL-ordered" doesn't verify "element A is grouped with B on the right, only C is pinned left."

**Open question, not resolved:** Miriam Libre Bold (the current Glamour headline font) is a clean bold sans-serif, not a handwriting/cursive style — the feature has never actually delivered on its "handwriting font" description. Searched Google Fonts' metadata for family Hebrew-subset + Handwriting-category and found no matches. Found two genuinely open-licensed "flowing script"/"informal handwriting" candidates via the Open Siddur Project's font pack (`github.com/aharonium/fonts`): **Solitreo** (Isaac Gantwerk Mayer, OFL license) and **Refoyl** (Refoyl Finkl, GPL+Font-Exception) — both distinct from the more common ancient/religious Rashi-script style also found there.

**Attempted a real on-device visual comparison three times; all three were invalidated by the same environment issue, not resolved:**

Every attempt hit `LocalSize.current.width` (used by `FeedItemRow`'s Glamour rendering to size the headline bitmap — see item 7 in the 2026-08-26 table above) reporting the widget's configured *minimum* resize width (130dp) even when the widget visually measured ~300dp wide in screenshots. Below 120px computed text width, the row correctly falls back to plain text (per that same item 7 fix) — meaning **both font builds kept silently rendering the same plain-text fallback**, never actually exercising either candidate font, regardless of which was configured. Confirmed via targeted debug logging (since removed) across three independent test rounds, including one where the widget instance was freshly removed and re-placed and its size independently verified at ~300dp by pixel-measuring a screenshot — `LocalSize` still reported 130dp for that same instance moments later.

This looks like a mismatch between what Glance/the launcher *reports* to the app (130dp) and what it actually *draws* on screen (~300dp), specific to this AVD's unusual 320×640px / 1.0x-density screen configuration — not something a real modern device (density ≥ 2x) would likely hit. Not fixed or investigated further; the safety fallback itself (item 7) is doing the right thing given the input it's receiving, so this isn't being treated as a new bug in that fix.

`Solitreo.ttf` and `Refoyl.ttf` are sitting in `app/src/main/assets/fonts/` and `app/src/main/res/font/` (untracked, not wired into any code) for a future comparison attempt — ideally on a more standard-density AVD or a real device, where this environment issue shouldn't apply. `TextBitmapHelper.kt` remains on Miriam Libre Bold; no font decision was actually made, since neither candidate was validated.
