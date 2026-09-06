# NewsFeed Widget

A standalone Android home screen widget that fetches and displays RSS/Atom feeds directly on your home screen — no companion app required. Built for full **RTL Hebrew support** and designed for the **Galaxy Z Fold** inner display.

One app, two widgets: **NewsFeed** (standard) and **NewsFeed Focus** (adds a tap-to-enlarge reading mode). Both are offered from the same "Add widget" picker and can be placed side by side.

---

## Screenshots

<table>
  <tr>
    <td align="center"><b>Amethyst theme</b></td>
    <td align="center"><b>Lavender theme</b></td>
    <td align="center"><b>Config panel</b></td>
  </tr>
  <tr>
    <td><img src="screenshots/widget-dark.svg" width="220" alt="Widget Amethyst theme"/></td>
    <td><img src="screenshots/widget-light.svg" width="220" alt="Widget Lavender theme"/></td>
    <td><img src="screenshots/config-panel.svg" width="260" alt="Config panel"/></td>
  </tr>
  <tr>
    <td align="center"><sub>Mixed Hebrew RTL + English LTR<br>feeds with per-feed accent colors</sub></td>
    <td align="center"><sub>Same layout in Lavender theme</sub></td>
    <td align="center"><sub>Long-press widget → Edit widget<br>Drag ⠿ to reorder · × to remove</sub></td>
  </tr>
</table>

---

## How it works

The widget is **fully standalone** — it fetches RSS/Atom feeds directly over the network using OkHttp. You add feed URLs yourself, search for feeds by topic, or import them from an OPML file exported by Feedly, Reeder, or any other RSS reader.

---

## Features

### Feed display
- Shows latest RSS/Atom articles directly on the home screen
- **Colored circle icon** with the feed's initial letter at the start of each article row
- Unread/total count badge in the widget header, scoped to what's actually on screen (not the full accumulated history)
- Unread dot indicator per article
- Per-feed colored left/right accent stripe matching the circle icon
- Article **date and time** — shows `HH:mm` for today's articles, `dd/MM HH:mm` for older ones
- Refresh countdown in the footer (`↻ in Xmin` / `↻ <1min`) — auto-updates every 60 seconds without any interaction; tap to refresh immediately
- **Settings button** (⚙) in the footer row opens the widget config screen directly from the widget

### Scrollable article list
The widget uses a scrollable list — swipe up and down within the widget to browse articles without leaving the home screen. The number of rows shown at once is computed from real per-row memory cost (not a flat guess), and a **Load more articles ↓** button at the bottom reveals more of your accumulated history in chunks as you need them. When "Showing X of Y" appears at the bottom instead, that's a real device memory ceiling — try a smaller font size to raise it.

### Tap to read inline
Tapping an article title expands it inside the widget:
- Article description or full page content appears below the title
- **Open article →** button appears to open the full article externally
- Tap the expanded article again to collapse it

*(On a **NewsFeed Focus** widget, tapping instead enlarges the article — see [Focus Mode](#focus-mode) below.)*

### Article length modes
| Mode | Setting label | Description |
|---|---|---|
| Short | Subtitle only | Up to 100 characters of the RSS excerpt |
| Medium | First paragraph | Up to 400 characters of the RSS excerpt (default) |
| Full | Full article | Fetches the full web page content on demand — tap **Load full article ↓** to fetch |

In **Full** mode, the widget fetches the article's web page and extracts the real body content using a proper HTML parser (Jsoup): it strips navigation, scripts, ads/promo blocks, and embedded widgets by both tag and by class/id pattern-matching, then pulls text only from actual paragraph/heading/list elements — not whatever happens to be near them in the page markup. Content reveals in chunks — tap **Load more ↓** to keep reading, with an **Open in browser ↗** link next to every chunk so you can jump to the source without scrolling back down. The fetched content is cached for the current widget session.

### Open article externally
The "Open article in" setting controls where the Open button sends you:
- **Browser** — opens in your default web browser (default)
- **Share sheet** — share the article URL to any app

Tapping Open also **marks the article as read** and triggers a widget refresh.

### Sort options
| Option | Description |
|---|---|
| Newest first | Latest articles at the top (default) |
| Oldest first | Oldest articles at the top |
| By feed | Round-robin interleave — one article per feed per round, equal representation |
| Unread first | Unread articles always shown above read ones |

### Filter options
| Option | Description |
|---|---|
| All | Show every article |
| Unread only | Show only articles you haven't read yet |
| Read only | Show only articles you've already read |

### Find feeds by topic
A search box in Settings looks up feeds by topic, site name, or keyword (via Feedly's public feed-search index) and lists title, description, and subscriber count for each result — tap **+ Add** to add one directly, no need to already know its URL.

### Per-feed customization
Every feed can be configured independently:

#### Accent color
Each feed is automatically assigned a distinct color from a 12-color palette (the palette itself changes to match your chosen widget theme) when first added. You can change it manually in Settings by tapping the color swatch. The color is used for the circle icon, article stripe, source label, and unread dot — unless "Use theme accent colors" is on, which hides all per-feed colors in favor of one theme-wide accent.

#### Font
| Option | Best for |
|---|---|
| Default | General news, system sans-serif |
| Serif | Editorial / newspaper-style feeds |
| Mono | Tech, code, or developer feeds |

#### Text style
Apply any combination of **Bold**, *Italic*, and <u>Underline</u> to a feed's headlines.

#### Text direction (RTL / LTR)
Each feed has its own direction toggle. RTL flips the entire card: stripe moves to the right, source name aligns right, timestamp moves left. Mix RTL and LTR feeds in the same widget simultaneously — this is independent of your device's system language.

#### Display mode (TXT / IMG)
In image mode, a thumbnail is pre-fetched from the RSS feed's image tags (`<media:thumbnail>`, `<media:content>`, `<enclosure>`, or the first `<img>` in the article description) and cached locally. The thumbnail scales with your font size setting.

### Font size (two independent sliders)
- **Font size** — scales headlines, meta text, and the header/footer from 50% to 300%.
- **Article font size** — scales the expanded article body text (description or full-article content) independently, also 50% to 300%. Kept separate because a comfortable headline size and a comfortable reading size for paragraphs of body text aren't usually the same number.

### Widget themes
Eight built-in options, each with a distinct light and dark variant selectable independently of the system theme:

| Theme | Character | Default |
|---|---|---|
| Auto | Follows the system light/dark setting (Material You) | |
| Lavender | Soft lavender editorial — light purple palette | |
| Amethyst | Rich amethyst — deep purple dark palette | |
| Glassy | Frosted glass with 3D depth, semi-transparent surface | |
| Simple | Pure black and white, no color | |
| Aerospace | Amber on near-black charcoal — mission-control feel | |
| Data Science | Teal-mint on deep navy — silicon-lab precision | |
| Glamour | Warm cream/beige with Playpen Sans Hebrew handwriting headlines | ★ |

**Default on first install:** Glamour theme · Light variant · Accent colors on.

Only Glamour renders headlines and article text as custom bitmaps (see below) — every other theme uses plain system-font text with no bitmap-memory cost, so their row limit is governed only by a flat safety ceiling (300 rows) rather than the more conservative, size-dependent budget Glamour's bitmaps need.

### Glamour Hebrew handwriting font
Glamour uses **Playpen Sans Hebrew** (from [Google Fonts](https://fonts.google.com/specimen/Playpen+Sans+Hebrew), by TypeTogether, bundled in both `res/font/` and `assets/fonts/`) for article headlines and body text — a marker-style handwriting face with full native coverage of both Hebrew and Latin scripts in the same family, in real regular and bold weights. English text embedded mid-sentence (site names, abbreviations) renders in the exact same face and weight as the surrounding Hebrew — no fallback typeface needed.

Because Jetpack Glance/RemoteViews cannot load `R.font` resources directly, headlines and body text are rendered to a `Bitmap` via Android Canvas (`StaticLayout` + `TextPaint`) and displayed as an `Image` composable inside the widget, tinted to the theme's ink color at display time rather than baked into the bitmap. Text direction is set explicitly via `TextDirectionHeuristics.RTL`/`LTR` with `Layout.Alignment.ALIGN_NORMAL`. The bitmap renderer uses a 40-entry LRU cache keyed on text + size + width + direction + weight.

### Focus Mode

**NewsFeed Focus** is a second widget offered by the same app, for browsing by tapping through articles one at a time rather than scrolling a list.

- **Tap any article** to focus it: that row enlarges, every other row shrinks (how much is set by the **Background rows size** slider in Settings, Focus-only), and the article's description/full text auto-expands inline — no separate expand tap needed.
- **Header controls** (only present on a Focus widget):
  - **▲ / ▼** — step focus to the previous/next article without needing to land a tap on a specific (possibly now-shrunk) row.
  - **N/M** — a position indicator showing where the focused article sits among what's currently on screen.
  - **✕** — clears focus, returning every row to its normal size. More reliable than tapping the focused row again, since focusing reflows the whole list and the row you meant to re-tap may no longer be where you left it.
  - **− / +** — adjust the focused row's own enlargement (0.75× – 2.5×) live, per article. This resets to a default whenever focus moves to a different article — it's a look-at-this-one-now adjustment, not a standing preference.
- Everything else (feeds, sort, filter, theme, refresh, self-update) is shared with the standard widget — Focus Mode only changes how you browse, not what's fetched or shown.

### Self-updating

Both widgets can check for and install a newer build directly, without manually re-downloading the APK.

- **Automatic daily check** — runs once a day in the background; if a newer build is available, you get a system notification. Tapping it downloads the update and hands it straight to Android's own install screen.
- **Manual check** — the **APP UPDATE** section in Settings shows your current build number and a **Check now** button that checks immediately instead of waiting for the daily cycle.
- The very first time you install an update this way, Android will show its own "install unknown apps" permission screen (and possibly a Google Play Protect "app not recognized" prompt) — this is expected for an app outside the Play Store and only needs granting once per widget package.
- On Android 13+, checking for updates also requests notification permission the first time (needed only for the daily check's notification) — declining it is fine, the manual "Check now" button still works either way.

### Feed management
- **Add by URL** — paste any RSS or Atom feed URL; the widget fetches and validates the feed title automatically
- **Find feeds** — search by topic/keyword instead of already knowing a URL (see above)
- **Import OPML** — import feeds from any OPML file (grouped and flat OPML supported)
- **Export OPML** — share your current feed list as a standard OPML 2.0 file
- **Edit** — tap a feed's name to change its display name or URL
- **Drag to reorder** — drag feeds in the config screen (via the ⠿ handle) to control their display order
- **Remove** — tap × on any feed row to delete it

### Article accumulation
The widget merges freshly fetched articles with previously stored ones (up to 300 total, deduplicated by ID, sorted by date). Articles stay available even between refreshes. A feed's very first fetch pulls its whole available backlog rather than just the newest handful, so newly added feeds don't start with an artificially short history. A **"Keep articles for"** setting (Forever / 1 day / 3 days / 1 week / 2 weeks / 1 month) controls how long an article stays in the accumulated list, independent of the 300-item cap, which always applies regardless.

### Hebrew / RTL news site compatibility
Feeds are fetched with browser-like HTTP headers so Israeli news sites (ynet, rotter.net, N12, כאן, וואלה, גלובס) do not block the request. Charset encoding is auto-detected for both RSS feeds and full-article page fetches — including sites (like rotter.net) that declare their encoding only via an in-page `<meta charset>` tag rather than the HTTP header, which a header-only charset check would miss entirely.

---

## Widget sizes

| | |
|---|---|
| Minimum (initial placement) | 250×200dp |
| Default cell target | 4×4 |
| Minimum after resize | 130×200dp |
| Maximum after resize | 500×600dp |

The widget is fully resizable in both directions — drag its edges on the home screen to adjust. Both NewsFeed and NewsFeed Focus share identical sizing.

---

## Settings manual

Open Settings by long-pressing the widget → **Edit widget**, or by tapping the **⚙** button in the widget's footer. Changes take effect after tapping **Save** (top right) — nothing is applied live before that.

The screen is one scrolling list with these sections, top to bottom:

### 1. Sort & Filter
The main app-wide preferences, all in one block:

| Control | Options | Notes |
|---|---|---|
| Sort by | Newest first · Oldest first · By feed · Unread first | See [Sort options](#sort-options) |
| Show | All · Unread only · Read only | See [Filter options](#filter-options) |
| Refresh every | 15 min · 30 min · 1h · 2h · 4h · 6h · 12h | Background auto-refresh interval; 15 min is the floor (Android WorkManager's own minimum) |
| Keep articles for | Forever · 1 day · 3 days · 1 week · 2 weeks · 1 month | Independent of the 300-article accumulation cap, which always applies |
| Open article in | Browser · Share sheet | Where the "Open article →"/"Open in browser ↗" buttons send you |
| Font size (slider) | 50%–300% | Headlines, meta text, header/footer |
| Article font size (slider) | 50%–300% | Expanded article body text only, independent of the slider above |
| Background rows size (slider) | 25%–100% | **NewsFeed Focus only** — how small every non-focused row renders |
| *(live preview card)* | — | Shows a sample headline + description rendered with your current theme/font choices, updating as you adjust settings above |
| Expanded article | Subtitle only · First paragraph · Full article | See [Article length modes](#article-length-modes) |
| Widget theme | Auto · Lavender · Amethyst · Glassy · Simple · Aerospace · Data Science · Glamour | See [Widget themes](#widget-themes) |
| Theme variant | Light · Dark | Independent of the system theme |
| Use theme accent colors (switch) | On/off | When on, hides every feed's own accent color in favor of one theme-wide accent |
| Background opacity (slider) | 0%–100% | Widget card transparency |

### 2. Add Feed
- **RSS or Atom feed URL** field + **Add** button — validates and fetches the feed's title automatically.
- **Import OPML** — pick an `.opml` file from your device.
- **Export OPML** — share your current feed list as a file.

### 3. Find Feeds
- **Topic, site name, keyword…** search field + **Search** button.
- Results list title, description, and subscriber count; tap **+ Add** on any result (already-added feeds show **Added** instead and can't be re-added).

### 4. Feed order & style
One row per feed, in your chosen display order:

| Control | Effect |
|---|---|
| ⠿ (drag handle) | Drag to reorder feeds |
| Color swatch | Tap to open a 12-color picker for this feed's accent (hidden when "Use theme accent colors" is on) |
| Feed name (tap) | Opens an edit dialog for the feed's display name and URL |
| × | Removes the feed |
| RTL / LTR | Toggles this feed's layout direction |
| TXT / IMG | Toggles whether this feed shows a thumbnail image |
| Font dropdown | Default · Serif · Mono, for this feed's headlines |
| B / I / U | Toggle Bold / Italic / Underline on this feed's headlines, any combination |

### 5. App Update
- Shows your currently installed build number.
- **Check now** — checks immediately and, if a newer build exists, downloads it and hands it to Android's install screen (see [Self-updating](#self-updating)).

---

## Background refresh

The widget polls feeds automatically using WorkManager, on the interval set in Settings (see the manual above). The countdown to the next refresh is shown in the widget footer and updates every minute automatically. A separate daily background check looks for app updates on its own schedule, independent of the feed-refresh interval.

---

## Installation

### Step 1 — Download the APK

1. Go to the [latest release](https://github.com/Had-com/NewsFeed-widget/releases/tag/latest) of this repo
2. Under **Assets**, download **`NewsFeed-latest.apk`**
3. Transfer the `.apk` to your Android phone if you downloaded it on a computer (via USB, Google Drive, WhatsApp to yourself, etc.)

> After this first install, you generally won't need to repeat this step — use **Check for updates** in the widget's Settings screen instead (see [Self-updating](#self-updating)).

### Step 2 — Allow installation from unknown sources

Android blocks apps not downloaded from the Play Store by default. You need to allow your file manager (or browser) to install APKs:

**On Samsung Galaxy (One UI):**
1. Open **Settings → Apps**
2. Tap the **⋮ menu** (top right) → **Special access**
3. Tap **Install unknown apps**
4. Find the app you will use to open the APK (e.g. **My Files** or **Chrome**) and toggle **Allow from this source** ON

**On stock Android:**
1. Open **Settings → Apps & notifications → Special app access → Install unknown apps**
2. Select the app you will use to open the APK and enable it

> **Samsung Auto Blocker:** On newer Samsung devices, a feature called **Auto Blocker** may prevent installation even after allowing unknown sources. To disable it:
> **Settings → Security and privacy → Auto Blocker** → toggle it **OFF**

### Step 3 — Install the APK

1. Open the `.apk` file using your file manager (e.g. **My Files** on Samsung)
2. A warning screen will appear:

   > *"This type of file can harm your device. Do you want to keep [filename]?"*  
   > or  
   > *"Install blocked — Google Play Protect doesn't recognize this app"*

3. **Look for a small, often grey or understated "Install anyway" or "More details" link** near the bottom of the warning screen — it is intentionally de-emphasized. Tap it.
4. On the next screen tap **Install**
5. Wait for the installation to complete, then tap **Done**

> ⚠️ The widget is open-source and safe. The warning appears because it is not distributed through the Play Store. You can review all source code in this repository.

### Step 4 — Add a widget

1. Long-press an empty area of your home screen
2. Tap **Widgets**
3. Search for or scroll to find **NewsFeed** — you'll see two entries, **NewsFeed** and **NewsFeed Focus** (see [Focus Mode](#focus-mode)); add either or both
4. Drag the widget to your home screen
5. The settings screen opens automatically — add your first feed URL, search by topic, or import an OPML file

---

## RTL Hebrew support

- System locale `iw` (Hebrew) automatically loads Hebrew UI strings
- Each feed's layout direction is controlled independently, regardless of system locale
- **Glamour theme** uses **Playpen Sans Hebrew** — a Hebrew/Latin handwriting font — for article headlines and body text, rendered via Canvas bitmap (see [Glamour Hebrew handwriting font](#glamour-hebrew-handwriting-font))
- Other themes use Android system fonts for Hebrew glyphs
- The config screen itself also supports RTL when the device locale is Hebrew
- Hebrew news sites (ynet, rotter.net, N12, כאן, וואלה, גלובס) are fetched with browser-like headers to bypass bot detection

---

## Project structure

```
app/src/main/
├── assets/
│   └── default_feeds.opml            # Default Hebrew news feeds loaded on first launch
├── java/com/newsfeed/widget/
│   ├── glance/
│   │   ├── NewsFeedWidget.kt          # Both GlanceAppWidget classes (standard + Focus), both
│   │   │                              #   GlanceAppWidgetReceivers, shared composables, and the
│   │   │                              #   updateNewsFeedWidget() cross-widget-type update router
│   │   ├── FeedItemRow.kt             # Per-article row (circle icon, expand/collapse, focus scaling, thumbnail, bitmap headline)
│   │   ├── TextBitmapHelper.kt        # Canvas bitmap renderer for Glamour Hebrew headlines/body (Playpen Sans Hebrew)
│   │   ├── WidgetWorker.kt            # WorkManager refresh job (both widget types) + article merge + thumbnail download
│   │   ├── WidgetThemes.kt            # 8 colour schemes + rawColorSchemeFor() + fontFamilyFor()
│   │   ├── BootReceiver.kt            # Reschedules WorkManager, update-check, and clock ticks after device reboot
│   │   ├── RefreshNowCallback.kt      # ActionCallback — immediate refresh on footer tap
│   │   ├── ToggleExpandCallback.kt    # ActionCallback — expand/collapse article (standard widget)
│   │   ├── SetFocusArticleCallback.kt # ActionCallback — tap-to-focus an article (Focus widget)
│   │   ├── FocusStepCallback.kt       # ActionCallback — ▲/▼ header buttons (Focus widget)
│   │   ├── ClearFocusCallback.kt      # ActionCallback — ✕ header button (Focus widget)
│   │   ├── AdjustFocusScaleCallback.kt # ActionCallback — −/+ header buttons (Focus widget)
│   │   ├── FetchFullArticleCallback.kt # ActionCallback — two-phase loading (description → full web content via Jsoup, charset-sniffed)
│   │   ├── LoadMoreArticleCallback.kt  # ActionCallback — reveal next chunk of one article's full text
│   │   ├── LoadMoreArticlesCallback.kt # ActionCallback — reveal next chunk of the article list
│   │   ├── NoOpTapFeedbackCallback.kt  # ActionCallback — tap feedback for description-less articles
│   │   └── ShareRelayActivity.kt      # Invisible relay activity for the Share-sheet "Open article in" mode
│   ├── update/
│   │   ├── UpdateManager.kt          # Checks the GitHub Release, downloads, and launches Android's install screen
│   │   ├── UpdateCheckWorker.kt      # Daily WorkManager job — notifies only, doesn't install directly
│   │   └── UpdateRelayActivity.kt    # Invisible relay activity the update notification taps into
│   ├── config/
│   │   ├── WidgetConfigActivity.kt   # Settings screen with live theme preview (sort, filter, feeds, theme, OPML, updates)
│   │   ├── FeedConfigRow.kt          # Per-feed controls row (color, font, B/I/U, RTL/LTR, IMG/TXT)
│   │   └── ColorPickerGrid.kt        # 12-color preset picker, one palette per theme
│   ├── data/
│   │   ├── FeedConfig.kt             # Data models (FeedConfig, WidgetConfig, ArticleItem, enums)
│   │   ├── WidgetConfigStore.kt      # DataStore — widget config persistence
│   │   ├── WidgetStateKey.kt         # Glance DataStore preference keys
│   │   ├── ReadStatusStore.kt        # DataStore — read article ID persistence
│   │   ├── NewsFeedRepository.kt     # RSS/Atom fetching, feed search, charset fix, image extraction, thumbnails
│   │   ├── ThumbnailHelper.kt        # Shared cache file path helper for thumbnails
│   │   ├── FaviconHelper.kt          # Shared cache file path helper for feed favicons
│   │   └── OpmlManager.kt            # OPML 2.0 import/export
└── res/
    ├── drawable/
    │   ├── ic_launcher_foreground.xml   # RSS + N vector icon foreground
    │   └── ic_launcher_background.xml  # Purple icon background
    ├── font/                            # Also duplicated under assets/fonts/ — Glance/RemoteViews
    │   │                                #   can't load R.font directly, so TextBitmapHelper loads
    │   │                                #   its own copy from assets/ at runtime; res/font/ is used
    │   │                                #   only by the Settings screen's live preview (a plain
    │   │                                #   Compose Activity, not RemoteViews-constrained). Both
    │   │                                #   directories also carry a few unused legacy font files
    │   │                                #   from earlier Glamour-font candidates, not referenced
    │   │                                #   by any code — only the two below are actually used.
    │   ├── playpen_sans_hebrew.ttf       # Hebrew/Latin handwriting font, regular (Glamour theme body text)
    │   └── playpen_sans_hebrew_bold.ttf  # Same font, bold (Glamour theme headlines)
    ├── mipmap-anydpi-v26/
    │   ├── ic_launcher.xml             # Adaptive icon (Android 8+)
    │   └── ic_launcher_round.xml       # Round adaptive icon
    ├── values/strings.xml              # English strings (app name + both widgets' picker labels)
    ├── values-iw/strings.xml           # Hebrew strings (עברית)
    ├── xml/appwidget_info.xml          # Standard widget metadata
    ├── xml/appwidget_info_focus.xml    # Focus widget metadata
    └── xml/file_paths.xml              # FileProvider paths (OPML export, downloaded updates)

keystore/
└── newsfeed-debug.keystore          # Committed debug-only signing key, shared by every CI build
                                      # so a self-updated APK can always install over the running one
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `androidx.glance:glance-appwidget` | 1.1.0 | Widget framework (Compose-based) |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Background refresh, daily update check |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Config + read-status persistence |
| `kotlinx-serialization-json` | 1.6.3 | Config and articles JSON serialization |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | RSS/Atom feed fetching, full-article fetch, self-update downloads |
| `org.jsoup:jsoup` | 1.17.2 | Real HTML parsing for full-article content extraction |
| `sh.calvin.reorderable` | 2.3.0 | Drag-to-reorder in config screen |

---

## Requirements

- Android 8.0 (API 26) or higher
- Internet permission (for RSS fetching and self-updates)

---

## License

MIT — see [LICENSE](LICENSE). The bundled Playpen Sans Hebrew font (`res/font/playpen_sans_hebrew*.ttf`, `assets/fonts/playpen_sans_hebrew*.ttf`) is licensed separately under the [SIL Open Font License 1.1](https://openfontlicense.org/) — free to use, modify, and redistribute, including in this repository.
