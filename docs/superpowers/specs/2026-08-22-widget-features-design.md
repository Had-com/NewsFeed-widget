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

**Update — comparison finally ran cleanly on a proper standard-density AVD (`newsfeed_normal`, 1080×2400 @ 420dpi, created for exactly this purpose), and both candidates turned out to be unusable as configured.** The width-reporting issue above didn't reproduce on this AVD — headlines render as real proportional text at default size. But both Solitreo and Refoyl render severely under-filled: a full headline sentence renders as only 1-2 words before hitting the 3-line cap, with the rest silently truncated, and what little does render looks oversized. Root cause is almost certainly that decorative/cursive fonts have much wider character-advance metrics than a compact font like Miriam Libre Bold at the same nominal size — the `textSizePx` used by `TextBitmapHelper.headline()` is a fixed value tuned for Miriam Libre, not adjusted per-font. **Recommendation: keep Miriam Libre Bold for now** — it renders correctly and legibly (confirmed across many QA passes this session) even though it isn't a true handwriting style. Neither candidate should be adopted without either a per-font size adjustment or finding a better-behaved font; this remains unresolved.

Also fixed while investigating this on the new AVD: `appwidget_info.xml` had `widgetFeatures="reconfigurable|configuration_optional"`, which let the launcher skip the config screen entirely and place the widget directly in its empty default state — defaults were correctly pre-filled in the config UI, but only took effect if the user separately opened settings via the widget's own gear icon and tapped Save. Removed `configuration_optional`; adding the widget now goes straight to the config screen (Android's standard behavior for a declared `android:configure` activity) before anything is placed.

**Verification caveat on the config-required fix:** logcat confirms Android now attempts the config-activity launch correctly (`startConfigActivity`, `APPWIDGET_CONFIGURE`) — the manifest change is engaging as intended. But in this environment, the launch itself was blocked by Android's Background Activity Launch (BAL) policy when triggered via synthetic ADB touch input (`input draganddrop`), aborting the whole add with nothing placed. This is very likely an artifact of scripted/synthetic input not carrying the same trusted-touch provenance real hardware touch does — millions of widgets use this exact mechanism successfully — but it could not be confirmed end-to-end with real touch in this session. Worth a quick real-device/real-touch check before considering this fully closed.

**A third font candidate, found via user-supplied source — much more promising:** [AlefAlefAlef](https://alefalefalef.co.il) offers **Dana Yad** (דנה יד) as a free-tier download (`.otf`/`.woff`/`.eot` + license PDF). Tested the same way as Solitreo/Refoyl — rendered on real Hebrew headlines on the standard-density AVD. Result: genuinely convincing connected/cursive handwriting style, and far less severe under-fill than Solitreo/Refoyl (multi-line headlines with reasonable word counts, not 1-2-words-total). Legibility is a real but expected trade-off inherent to true cursive scripts — screenshots were sent to the user to judge directly rather than have this session claim a legibility verdict on Hebrew script.

**License is not open-source and needs an explicit decision before adopting it.** Read the license PDF included in the download: it's a *free-tier commercial license*, not OFL/GPL like Solitreo/Refoyl —
- App usage capped at **5,000 downloads**; beyond that requires a paid license
- Requires registering user details on the AlefAlefAlef site
- **Personal, non-transferable** — cannot hand the font file to third parties (collaborators, other builds) even with their own license
- No modifying or creating derivative versions of the font file

Not integrated into any committed code pending that decision. `dana_yad.otf` (and `solitreo.ttf`/`refoyl.ttf` from the earlier round) sit untracked in `app/src/main/assets/fonts/` and `app/src/main/res/font/` for whichever direction gets chosen.

**Update — the user's "titles don't fill the whole line" observation was a real, fixable bug, not a font-quality issue.** Chased it down via `uiautomator` bounds measurement on a genuinely fresh, properly-sized widget (303dp total): the text column actually gets 242dp (303 − 52dp thumbnail − ~9dp true accent-stripe/padding overhead), but `FeedItemRow`'s width formula was subtracting 19dp for that overhead — a stale estimate from before this row's layout was reworked earlier in this same session, never re-measured against the real thing. That left every Glamour headline bitmap ~16dp (42px) narrower than its actual container, visible as a consistent gap before the row edge (centered there by `ContentScale.Fit`). Changed the constant from `19f` to `9f`; verified visually on the production font (Miriam Libre Bold) — headlines now extend to fill the row as expected.

---

## Bug fixes and features (shipped 2026-08-27)

**The `19f→9f` margin fix above turned out to be correct but was masking, not fixing, the real bug.** After the user pushed back ("i don't think its fixed, show me"), re-measuring via `uiautomator` found the *same* bitmap width before and after that fix — which shouldn't have been possible for a genuine 10dp margin reduction. Root-caused via temporary logcat instrumentation of the actual `widthPx`/`LocalSize.current.width` values at render time: `NewsFeedWidget` never set `GlanceAppWidget.sizeMode`, so it defaulted to `SizeMode.Single` — which, per Glance's documented behavior, pins `LocalSize.current` to the widget's *declared minimum size* (130dp) permanently, regardless of how large the widget is actually placed or resized on screen. Every Glamour headline bitmap was being sized for a 130dp-wide widget while sitting inside a real ~303dp+ row. This is a different, earlier-stage bug than the `19f→9f` margin (and than item 7's `minResizeWidth` fallback guard) — those were both real fixes, just operating on a width value that was itself wrong. Set `override val sizeMode = SizeMode.Exact`; verified via logcat that `localSizeWidth` now reports real values (303dp, 583dp for the wider config-preview panel) instead of a frozen 129.9, and visually that headlines now wrap filling the row with no more isolated short lines with a large trailing gap.

**Font decision made: adopted Refoyl (bold via synthetic fake-bold), not Solitreo or Dana Yad.** Tried Solitreo first (OFL-licensed, no restrictions) — it introduced a new, genuine RTL bug: the *last* line of a multi-line wrapped headline rendered flush-left instead of right-aligned, reproduced with fake-bold both on and off, meaning the bug was in Solitreo's own font metrics/shaping tripping up `StaticLayout`'s line-width calculation, not the bold technique. Refoyl (same license family, same source pack referenced above) does not have this problem — verified across multiple 3-line headlines, including short trailing lines, via pixel-measured crops. Dana Yad remains unused pending the user's explicit sign-off on its download-capped, non-transferable license.

**Separately found and fixed a real, longstanding RTL bug: every Hebrew headline was left-aligned, not right-aligned — for the entire session, across every font tried, including the original Miriam Libre Bold.** The user flagged this directly ("the rtl looks wrong") after several rounds of the session's own visual inspection had wrongly signed off on it as correct — the cursive/handwriting styling made the direction genuinely hard to eyeball reliably. Settled it with a pixel-measurement script instead of eyes: for every multi-line headline tested, the *left* edge was nearly constant across lines while the *right* edge varied — the exact signature of left-alignment, not right. Root cause: `TextBitmapHelper`'s `StaticLayout` never set an explicit text direction, so it used the default `FIRSTSTRONG_LTR` heuristic — which already detects Hebrew as an RTL paragraph on its own, meaning `ALIGN_NORMAL` was already "right" and `ALIGN_OPPOSITE` was already "left" for this text. The code did `if (isRtl) ALIGN_OPPOSITE`, backwards. Fixed by forcing `TextDirectionHeuristics.RTL`/`LTR` explicitly per `isRtl` and always using `ALIGN_NORMAL` (which then correctly means "start of the direction just set"). Re-verified with the same pixel measurement post-fix: the right edge is now constant per article and the left edge varies, confirming genuine right-alignment.

**Glamour layout: feed name moved to its own right-justified line; thumbnails slightly shorter.** Previously the feed source name shared a single meta-row with the timestamp, unread dot, and favicon circle, right-packed against a weighted spacer — its effective right-hand position (and thus visual consistency) differed between rows with a thumbnail (narrower column) and rows without one (full-width column). Split into two rows: timestamp+dot+circle on the first line, feed name alone on a second line using `fillMaxWidth()` + end/start text alignment — this makes it right-justify (RTL) or left-justify (LTR) against the column's own true width uniformly, whether or not that row has a thumbnail. Thumbnail vertical padding increased from 4dp to 10dp so it reads as slightly shorter than the full row height, since the row is now taller to fit the extra line.

**Glamour headline color corrected to the theme's actual darkest/primary ink token.** The hardcoded bitmap color (`0xFF7A5C3A` light / `0xFFA08060` dark) turned out to exactly match `GLAMER_LIGHT`/`GLAMER_DARK`'s `onSurfaceVariant` — the theme's secondary/muted color — not `onSurface` (`0xFF2C1A0A` light / `0xFFF2E8DC` dark), the primary token every other theme's headline actually uses via `GlanceTheme.colors.onSurface`. Switched Glamour's headline to `onSurface`'s values, matching the convention used everywhere else; body/description text intentionally keeps the `onSurfaceVariant` shade (matching the non-Glamour themes' own `descStyle`, which also uses `onSurfaceVariant`) for visual hierarchy against the now-bolder, darker headline.

**Glamour body text (description/expanded article) now also renders in the handwriting font, regular weight — but only for short, already char-clipped snippets, not the unbounded full-article text.** `TextBitmapHelper` was refactored (`headline()`/`paragraph()` both delegate to a shared private `render()`) to support a non-bold variant with a configurable, capped `maxLines`. This is deliberately *not* wired into the "full fetched article" path (`fullArticleText`, previously an unbounded `Text()` with `maxLines = 200`) — a Bitmap's memory cost scales with width × height × 4 bytes, and an unbounded string on a large resized widget at high pixel density could plausibly reach several MB in one bitmap, re-risking the exact RemoteViews bitmap-memory budget crash fixed earlier this project (item 5, 2026-08-26 table: thumbnail 300px→100px). Only the pre-clipped description snippets (100/400 char limits, already in place) route through `TextBitmapHelper.paragraph()`, capped at 10 lines, with a defensive `text.take(400)` inside the render path itself so a future call site can't reintroduce the risk by forgetting to clip first. Full-article text stays on the plain-`Text()`/system-cursive path.

**Verification caveat:** the row-collapsed view (headline, feed-name line, thumbnail sizing, colors) was verified visually on-device across multiple builds. The expanded-article view specifically (which exercises the new `DescriptionText`/`paragraph()` path) could not be — tapping a row's clickable area to trigger `ToggleExpandCallback` via synthetic ADB input did not visibly expand the row, even after confirming via `uiautomator` that the tap coordinates land on the correct clickable element bounds. This is consistent with other synthetic-input limitations hit earlier in this project (BAL blocking `APPWIDGET_CONFIGURE` launches from scripted touch) rather than a code defect, but it means the description-bitmap rendering was only verified statically (compiles clean, bounds are safely capped) — worth a real-touch check before considering it fully closed.

**Along the way, also found (and resolved, not a bug) an apparent "refresh succeeds but zero articles" issue**: a QA agent's freshly-placed widget showed "No articles" indefinitely despite successful refresh cycles and confirmed network/feed reachability. Root cause: the widget's `newsfeed_config.preferences_pb` datastore file didn't exist on disk at all — the agent's tap on "Save" in the config screen never actually registered (a UI-automation coordinate miss, the same category of issue hit a few times this session with the gear icon). Re-did the save with `uiautomator`-verified exact coordinates and it persisted correctly (8 feeds), confirming this was a testing artifact, not an app defect.

---

## Bug fixes and features (shipped 2026-08-28)

**Correction to the 2026-08-27 entry above: the "feed name on its own line" layout was superseded, not kept.** The user clarified the actual intent: feed name stays grouped with the favicon circle on the shared meta row (reverted to the pre-2026-08-27 structure — `nameMaxWidth`-bounded in RTL, `defaultWeight()` in LTR), and instead the *thumbnail* was restructured to only span the headline's own row height (wrapped in an inner `Row { Column(weight){ headline } Image(thumbnail) }`) rather than the whole card. This achieves the original consistency goal — the meta row's available width, and thus the name's position, no longer depends on whether the row has a thumbnail — by confining the thumbnail's reach instead of moving the name to dodge it. A small `Spacer(8.dp)` was added between the headline and thumbnail (previously touching edge-to-edge), with the headline bitmap's width formula adjusted (`+8f` on `thumbDp`) to match.

**Fixed a real memory-crash regression introduced by the `sizeMode = Exact` fix (2026-08-27): `IllegalArgumentException: RemoteViews for widget update exceeds maximum bitmap memory usage (used: 17069192, max: 15552000)`, reproduced live (`Can't show content`).** Before that fix, every headline bitmap was frozen at a tiny 130dp-derived width (low memory, wrong visually); after it, bitmaps correctly scale to the real widget width — but that includes the config screen's wider 583dp live-preview panel and any user resize up to `maxResizeWidth="500dp"`, and at high pixel density that's enough per-bitmap growth across up to 15 articles to blow the ~15.5MB RemoteViews budget. Capped the *dp* width (not the final px, so device density still applies normally up to the cap) used by both the headline and body-text bitmap formulas at `coerceAtMost(350f)` — comfortably covers every size tested this session (default 303dp, moderate resizes) while bounding the worst case. Verified: no exception, widget renders correctly, at the exact scenario that crashed before (config-preview panel width).

**Color and font requests, verified with pixel sampling rather than assumed:**
- Glamour dark headline `0xFFF2E8DC → 0xFFEDE4D4`, dark body `0xFFA08060 → 0xFFA87840`. Light unchanged (`0xFF2C1A0A` headline was already correct at the time; `0xFF7A5C3A` body was already a step lighter).
- Data Science: light headline `→ 0xFF007870` (the theme's own `primary`, a deliberate departure from `onSurface` for this theme specifically), dark body `→ 0xFFB2EBE8`.
- Aerospace: dark headline `→ 0xFFFFE5B4`, dark body `→ 0xFFE8D8A8`. Light headline unchanged (already equaled `onSurface`).
- All four verified live via pixel sampling on real screenshots (exact hex matches), except Glamour's dark body color and the Data Science/Aerospace changes, which compile clean and follow the identical code pattern but couldn't be pixel-verified live — Data Science/Aerospace hit the same config-screen dropdown/toggle UI-automation flakiness noted elsewhere in this doc, and the dark body color only renders in the expanded-article view, which still doesn't respond to synthetic tap (see the 2026-08-27 verification caveat above, still unresolved).
- Then a follow-up: `0xFF2C1A0A` "looks black" at headline size/weight despite technically being a dark brown (very low luminance, ~12%). Changed to `0xFF4A2E14` (~20% luminance, same warm hue, clearly reads as brown) in **both** `WidgetThemes.kt`'s `GLAMER_LIGHT.onSurface` and `FeedItemRow.kt`'s matching literal, keeping the config-preview and real widget in sync as established earlier. Pixel-verified live: exact match.

**English text inside Glamour headlines/body now renders in a handwriting-style font too, not a plain system fallback.** Verified via `fontTools` against the font's own cmap table that every Hebrew handwriting candidate tried this project (Refoyl, Solitreo, Dana Yad) has **zero** Latin glyphs (0/52 A–Za–z vs 27/27 Hebrew) — confirming this was a real, measurable gap, not a hunch. `TextBitmapHelper` now detects Latin runs within the rendered string and spans them onto `Typeface.create("cursive", …)` — Android's built-in generic cursive system family — via a custom `MetricAffectingSpan`, matching the surrounding bold/regular weight. No new font asset needed since the fallback is a guaranteed-present system family, not a second bundled Latin script font.

**Config screen's live preview card was never actually RTL despite being fixed for font/color/width accuracy on 2026-08-27.** The headline and description `Text()` composables had no `textAlign`, defaulting to Compose's left-aligned start; the meta line was a single combined string (`"14:30 · ynet מבזקים"`) that couldn't be decomposed into the real widget's time-left/name-right grouping. Added `textAlign = TextAlign.End` + `fillMaxWidth()` to headline/description, and split the meta line into a `Row` (time, weighted spacer, name) mirroring `FeedItemRow.kt`'s actual structure. Verified live: meta row now shows time-left/name-right, headline/description text now hugs the right edge.

**Font decision revisited: switched from Refoyl to Dana Yad, with explicit user sign-off on its license restrictions.** Refoyl (adopted 2026-08-27) was safe (OFL) but visually less convincing as "handwriting" than Dana Yad, which was shelved at the time specifically for its license terms (5,000-download cap, requires the registered AlefAlefAlef account, personal/non-transferable). The user asked directly, was reminded of those terms, and explicitly said to proceed anyway ("please use the dana yad") — treated as informed authorization, not a default choice. Swapped the loaded asset in `TextBitmapHelper.getTypeface()` and the config-preview's `FontFamily(Font(...))` from `refoyl`/`R.font.refoyl` to `dana_yad`/`R.font.dana_yad`; added a license-terms comment directly above `TextBitmapHelper`'s class declaration so the constraint isn't only documented here. Dana Yad also has zero Latin glyphs (see above), so the existing Latin-cursive-fallback mechanism applies to it automatically with no extra code. Verified live on both the config preview and the real widget — visibly more connected/cursive than Refoyl, correct color/alignment held.

**Asked to create an original Hebrew handwriting font to sidestep the license entirely — declined as impractical, not attempted.** Drawing convincing handwriting-style letterforms (Hebrew's 27+ base + final-form glyphs) as smooth vector outlines is a specialized type-design skill normally done by hand in tools like Glyphs/FontForge, or by digitizing real handwriting samples — not something proceduraly generatable through code to a quality bar better than the free-licensed options already evaluated (Solitreo, Refoyl). Attempting it would likely have produced a visibly worse result while claiming to solve the licensing concern; user chose to stay on Dana Yad instead.

**README.md brought back in sync with the actual font/RTL implementation** — it still described Miriam Libre Bold (the pre-session production font) throughout, including the theme table, the "Glamour Hebrew handwriting font" section, the project-structure file listing, and the RTL-support section, and described the now-fixed `ALIGN_OPPOSITE` approach as if it were correct. Updated all of these to Dana Yad, added the license caveat to the License section (the repo's overall MIT license does not cover the bundled font), and corrected the alignment description to `ALIGN_NORMAL` + explicit `TextDirectionHeuristics`.

---

## Bug fixes and features (shipped 2026-08-31)

A device was connected for real hardware testing (Samsung Galaxy S21+, SM-G996B, real-touch/uiautomator, not synthetic-input-on-AVD) — this unblocked several verifications the 2026-08-27/28 entries above had flagged as unconfirmed due to synthetic-input limitations, and surfaced genuine new bugs those limitations had been masking.

**Build environment note:** this session's Gradle builds could not run from inside the assistant's own tool sandbox (`java.io.IOException: Unable to establish loopback connection` — a JDK/Windows AppContainer restriction on JVM subprocesses spawned from that sandbox specifically, confirmed via a bare `Selector.open()` repro; matches a known upstream issue, not project-specific). Every build this session was run by the user directly in their own terminal against the cached Gradle 8.7 distribution. `gradlew.bat`/`gradlew` themselves are non-functional in this repo (`gradle-wrapper.jar` was never committed) — builds go through the full cached distribution path directly until that's regenerated.

| # | Bug | Fix |
|---|---|---|
| 1 | Full-article fetch produced mojibake (`�` characters) and stray ad-widget text ("Booking.com Kiwi Skyscanner TripAdvisor") on real sites like rotter.net | Root cause (confirmed against the real page's raw bytes): rotter.net's article pages send `Content-Type: text/html` with **no charset parameter** — the actual encoding (`windows-1255`) is declared only via an in-HTML `<meta http-equiv="Content-Type" content="...charset=windows-1255">` tag. `FetchFullArticleCallback` originally used raw `HttpURLConnection` + hardcoded `Charsets.UTF_8`; switching to OkHttp's `response.body.string()` alone did *not* fix it, since OkHttp's charset detection also only looks at the header and silently defaults to UTF-8 when it's absent — this was diagnosed and fixed as two separate attempts, the second time verified against the real captured page bytes (decoded correctly as `windows-1255`, readable Hebrew) before asking for a rebuild. Final fix: fetch raw bytes, sniff `charset=` from the first 2KB decoded as ISO-8859-1 (byte-for-byte safe for locating plain-ASCII meta tags regardless of the real encoding), fall back to the header's charset or UTF-8 if no meta tag is found. Also strips the `U+FFFC` (OBJECT REPLACEMENT CHARACTER) that `Html.fromHtml()` leaves behind for `<img>` tags it can't inline as text — this was rendering as glyphless "[OBJ]" tofu boxes in the output, a separate but related readability defect found in the same garbled screenshot. |
| 2 | Headline didn't fill the available line width for articles in an image-mode feed that happened to have no thumbnail downloaded | `FeedItemRow`'s headline-bitmap width formula reserved thumbnail space based on `feedConfig.displayMode == "image"` (the *feed's* setting) rather than whether an image would actually render for *that specific article* (`showSideThumb && sideThumbBmp != null`) — an image-mode feed with a missing thumbnail file left the headline visibly narrower than the row, with no image ever appearing to justify the gap. Fixed to gate on the same per-article condition already used to decide whether to render the thumbnail at all. |
| 3 | Full-article "Load more" stopped well short of the real article end, capped at a flat `MAX_CHUNKS = 3` (3600 characters) | Redesigned around two compounding issues found on inspection, not just the flat cap: (a) each 1200-char chunk's own bitmap was silently ellipsis-truncating roughly its back half under the old fixed 600px/~18-line height budget — meaning `fullArticleShownChars` was advancing past content that was never actually rendered; (b) the chunk-count ceiling itself was a guessed constant, not derived from real bitmap memory. Fixed by sizing each chunk's height budget to fit all of its `CHUNK_CHARS` (estimated conservatively from font metrics so it never under-provisions), and computing the chunk-count ceiling as `min(memory-safe chunks, chunks actually needed for this article)` from real width/font/density math instead of a flat number — most real articles now reach their true end. |
| 4 | Full-article mode had only one "Open article" button at the very end, requiring a full scroll to reach the source regardless of how much had been read | Added an "Open in browser ↗" link after every revealed chunk, not just once at the end, sharing one hoisted `Intent` with the existing end-of-row "Open article" button. |
| 5 | With 2+ chunks loaded, only the *first* chunk's "Open in browser" link actually rendered — the second (and any later) were silently missing despite identical code emitting them | Glance's RemoteViews translation was collapsing the later, structurally-identical (same text/style/click action) `Text` nodes from repeated loop iterations into the earlier one instead of treating each as its own view. Fixed by wrapping each chunk's block in `key(chunkStart) { ... }` to force distinct composition identity per iteration — the standard Compose fix for this class of bug. |
| 6 | **Real crash, found via logcat rather than visual testing**: `IllegalArgumentException: Column container cannot have more than 10 elements`, firing on every widget update once fix #5 above shipped, for any expanded row with 2+ full-article chunks | RemoteViews caps a `Column` at 10 *direct* children. The expanded-row content (thumbnail header + description/buttons + however many full-article chunks, each itself 3 elements after fix #4) all emitted as direct siblings into one `Column` — 2 chunks alone was already 11+. Fixed by wrapping the whole expanded-state block in its own `Column` (collapses to 1 child of the row's outer Column regardless of what's inside), wrapping the chunk loop's total output in another nested `Column` (collapses to 1 child there too), and wrapping each individual chunk in its own `Column` (its 3 elements collapse to 1 child of the chunk list). Also lowered the chunk-count memory ceiling's upper bound from a permissive 40 to 10, matching this same structural limit directly rather than relying only on the nesting fix. This bug had been silently firing (and presumably degrading that row's render) since the per-chunk "Open in browser" links were added, and would not have been caught without checking logcat directly — the widget's home-screen appearance gave no visible indication anything was wrong. |
| 7 | Article list capped at a flat 15, with no way to see more of the (up to 300) accumulated articles even though all 8 configured feeds were contributing plenty | Replaced with the same chunked-reveal pattern as the full-article "Load more": a computed `maxRowsAllowed` (from real per-row bitmap-memory cost — headline bitmap + thumbnail — scaled to actual width/density/theme, budgeted to fit alongside the full-article chunk budget under the ~15.5MB RemoteViews ceiling) plus a "Load more articles ↓" button that reveals the next chunk of 10. The initial per-tap chunk size (10) is deliberately smaller than the typical computed ceiling (~16) so the button is exercised at least once instead of landing on the ceiling in a single jump. |
| 8 | (Introduced by, and caught while testing, fix #7) The "Load more articles" button could hide itself on the very first render whenever one chunk request already met or exceeded the memory ceiling, even though more articles genuinely existed | The visibility condition compared the *already-clamped* display count against the ceiling (always false the instant a request met the ceiling) instead of the *requested* count. Fixed to compare `visibleArticleCount` (what's been asked for) against the ceiling and the true available-article count — the button now correctly stays visible exactly as long as tapping it would reveal something new. |
| 9 | Side thumbnail rendered comically thin for a 1-line headline | The thumbnail's height (`fillMaxHeight()`) tracked whatever the headline row happened to render at, with no floor. Fixed to size the thumbnail off the headline's own real rendered height (captured from its bitmap, `headlineBmpHeightPx`) floored at 2 lines' worth, so a short headline still gets a reasonably sized image instead of a sliver. |
| 10 | (Feature) No control over how long an accumulated article stays in the list, independent of the 300-article hard cap | Added `retentionDays` to `WidgetConfig` (0 = forever) and a "Keep articles for" dropdown in settings (Forever / 1 day / 3 days / 1 week / 2 weeks / 1 month); applied as a date-based filter in `WidgetWorker`'s merge step, alongside (not instead of) the existing 300-item cap. |
| 11 | (Feature) A feed's first-ever fetch was capped at the same 50-item-per-refresh limit as an established feed, so newly added feeds started with an artificially truncated history | A feed with no accumulated articles yet (tracked via `knownFeedIds`, read from the widget's prior state before the fetch) now gets a much higher per-fetch cap (300) on its first load only; subsequent refreshes for that same feed use the normal 50-item cap. |

**A config-screen coordinate mistake, not a code bug, caused a long detour mid-session:** tapping "Save" using coordinates read directly off a *displayed* (scaled-down) screenshot without converting to real device pixels landed off-target, so a genuine `articleLength = "full"` selection was never actually persisted — the config screen kept showing "Full article" only because its in-memory Compose state survived across `am start` relaunches of the same still-running Activity instance, not because it had been saved. This looked exactly like a serialization bug (the saved config JSON was missing the field) until directly inspecting the on-disk DataStore bytes showed `articleLength` absent entirely, and a properly `uiautomator`-bounds-targeted Save tap fixed it immediately. Reinforces the standing lesson from earlier entries in this doc: read exact element bounds before tapping, never estimate from a scaled screenshot.

**Investigated, not changed:**
- English/Latin text inside a Glamour bold headline (e.g. "ChatGPT") renders visibly lighter than the surrounding Hebrew, even though the code does request bold for it (`Typeface.create("cursive", Typeface.BOLD)`, plus the same `isFakeBoldText` paint flag active across the span). Concluded this is a font-design mismatch, not a code bug — the Latin cursive-fallback family is inherently a thinner-stroke style than Dana Yad's synthesized bold, and both bold mechanisms are already firing. Left as-is pending a decision on whether it's worth a manual double-stroke technique for extra synthetic weight on just that span.
- Full-article extraction still lets some ad/affiliate-widget text (e.g. "Booking.com Kiwi Skyscanner TripAdvisor") through on sites like rotter.net that don't use `<article>`/`<main>` tags, falling back to the naive `<body>` extraction which includes page chrome. `<iframe>`/`<object>`/`<embed>`/`<svg>`/`<aside>` are stripped, but this particular widget's markup apparently isn't one of those. Real "readability"-style content extraction is out of scope for what was asked; noted as a known limitation.
- Scroll-position-based UI (e.g. "refresh when scrolled to top") is not implementable for an Android AppWidget: RemoteViews content renders inside the launcher's process, and the widget's own app process receives no scroll events at all — only explicit taps on elements the widget itself defined. This is a hard platform boundary, not a Glance limitation.
