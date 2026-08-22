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
val externalApp: String = "browser"   // "readyou" | "browser" | "share"
```

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
   - `"readyou"`: `Intent(ACTION_VIEW, uri).setPackage("me.ash.reader")`, fall back to browser.
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
- `"readyou"` → "Open in Read You"
- `"browser"` → "Open in browser"
- `"share"` → "Share article"

### Config UI for external app

In `WidgetConfigActivity` Sort & Filter section, add an "Open article in" row with a dropdown matching the refresh/sort/filter dropdowns. Options: "Read You", "Browser", "Share sheet".

### Removing `DeepLinkActivity`

- Remove `DeepLinkActivity.kt`.
- Remove its `<activity>` entry from `AndroidManifest.xml`.
- Remove `me.ash.reader` / `io.github.ashinch.readyou` from `<queries>` only if no other code references them — keep `me.ash.reader` since `OpenExternalCallback` still needs it for the "Read You" external open option.
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
