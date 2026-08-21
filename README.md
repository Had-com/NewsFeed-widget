# Read You Widget

A customizable Android home screen widget for the [Read You](https://github.com/Ashinch/ReadYou) RSS reader app, designed for the **Galaxy Z Fold 8 Ultra** inner display. Built with Jetpack Compose Glance and full **RTL Hebrew support**.

---

## Screenshot

> Widget shown in dark mode with mixed Hebrew RTL and English LTR feeds, each with individual accent colors and text styles.

---

## Features

### Feed display
- Shows your latest RSS articles directly on the home screen
- Unread count badge in the widget header
- Unread dot indicator per article
- Per-feed colored left/right accent stripe
- Relative timestamps (5m, 2h, 3d)
- Tap any article to open it in the Read You app
- "All articles" shortcut at the bottom

### Sort options
| Option | Description |
|---|---|
| Newest first | Latest articles at the top (default) |
| Oldest first | Oldest articles at the top |
| By feed | Articles grouped by their source feed |
| Unread first | Unread articles always shown above read ones |

### Filter options
| Option | Description |
|---|---|
| All | Show every article |
| Unread only | Show only articles you haven't read yet |
| Read only | Show only articles you've already read |

### Per-feed customization
Every feed can be configured independently:

#### Accent color
Choose from 12 preset colors (violet, purple, blue, teal, green, amber, orange, red, pink, blue-grey, brown, dark grey). The color is used for the article stripe, source label, and unread dot.

#### Font
| Option | Best for |
|---|---|
| Default | General news, system sans-serif |
| Serif | Editorial / newspaper-style feeds |
| Mono | Tech, code, or developer feeds |

#### Text style
Apply any combination of:
- **Bold** — heavier weight for important feeds
- *Italic* — lighter editorial feel
- <u>Underline</u> — visual emphasis

#### Text direction (RTL / LTR)
Each feed has its own direction toggle:
- **RTL** — right-to-left layout for Hebrew, Arabic, and other RTL languages. The entire card flips: stripe moves to the right, source name aligns right, timestamp moves left.
- **LTR** — standard left-to-right layout for English and other LTR languages.

You can mix RTL and LTR feeds in the same widget simultaneously.

### Feed order
Drag and drop feeds in the config screen to control the order they appear in the widget.

---

## Widget sizes

| Size | Cells | Best for |
|---|---|---|
| Compact | 2×4 | Cover display / sidebar |
| Standard | 4×4 | Inner display (recommended) |
| Large | 5×4 | Maximum article count |

The widget is fully resizable — drag its edges on the home screen to adjust.

---

## Configuration

Long-press the widget on your home screen → tap **Edit widget** to open the settings screen.

### Settings screen layout
1. **Sort & Filter** — global sort order and read/unread filter
2. **Feed order & style** — one row per feed with:
   - Grip handle (drag to reorder)
   - Color swatch (tap to open color picker)
   - Font dropdown
   - **B** / *I* / <u>U</u> style toggles
   - RTL / LTR direction toggle

Changes take effect immediately after tapping **Save**.

---

## Background refresh

The widget refreshes automatically every **15 minutes** using WorkManager. This is the minimum interval Android allows for background tasks. The last refresh time is shown at the bottom of the widget.

---

## Installation

### Option A — Download from GitHub Actions (no build tools needed)
1. Go to the [Actions tab](../../actions) of this repo
2. Click the latest **Build APK** run
3. Download the **ReadYouWidget-debug** artifact
4. Transfer the `.apk` to your phone
5. On your Galaxy Z Fold: **Settings → Apps → Special app access → Install unknown apps** → allow your file manager
6. Tap the `.apk` to install
7. Long-press your home screen → **Widgets** → find **Read You Feeds** → drag to place it

### Option B — Build from source
```bash
git clone https://github.com/Had-com/readyou-widget.git
cd readyou-widget
./gradlew assembleDebug
# APK is at: app/build/outputs/apk/debug/app-debug.apk
```

---

## Connecting to Read You data

The widget currently ships with a placeholder data layer (`ReadYouRepository.kt`). To show your actual RSS feeds and articles, choose one of these integration paths:

### Option A — Integrate inside the Read You app (recommended)
Add this widget module directly to a fork of Read You. Replace the `TODO` stubs in `ReadYouRepository.kt` with calls to Read You's existing Room DAO:

```kotlin
// Replace in ReadYouRepository.kt:
fun getFeeds(): List<FeedConfig> {
    return feedDao.getAll().map { it.toFeedConfig() }
}
```

No ContentProvider needed — the widget shares the same database directly.

### Option B — Standalone APK via ContentProvider
Fork Read You, add a `ContentProvider` that exposes feeds and articles, then query it from `ReadYouRepository.kt` using `context.contentResolver.query(...)`.

---

## RTL Hebrew support

- System locale `iw` (Hebrew) automatically loads Hebrew UI strings from `res/values-iw/strings.xml`
- Each feed's layout direction is controlled independently via `LocalLayoutDirection`
- No custom fonts required — Hebrew glyphs are handled by Android system fonts
- The config screen itself also supports RTL when the device locale is Hebrew

---

## Project structure

```
app/src/main/
├── java/com/readyou/widget/
│   ├── glance/
│   │   ├── ReadYouWidget.kt          # Glance widget + receiver
│   │   ├── FeedItemRow.kt            # Per-article composable
│   │   └── WidgetWorker.kt           # WorkManager refresh job
│   ├── config/
│   │   ├── WidgetConfigActivity.kt   # Settings screen
│   │   ├── FeedConfigRow.kt          # Per-feed controls row
│   │   └── ColorPickerGrid.kt        # 12-color preset picker
│   ├── data/
│   │   ├── FeedConfig.kt             # Data models
│   │   ├── WidgetConfigStore.kt      # DataStore persistence
│   │   ├── ReadYouRepository.kt      # Data bridge (fill in TODOs)
│   │   └── WidgetStateKey.kt         # Glance state keys
│   └── DeepLinkActivity.kt           # Article tap handler
└── res/
    ├── values/strings.xml            # English strings
    ├── values-iw/strings.xml         # Hebrew strings (עברית)
    └── xml/appwidget_info.xml        # Widget metadata
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `androidx.glance:glance-appwidget` | 1.1.0 | Widget framework (Compose-based) |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Background refresh |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Config persistence |
| `kotlinx-serialization-json` | 1.6.3 | Config JSON serialization |
| `sh.calvin.reorderable` | 2.3.0 | Drag-to-reorder in config screen |

---

## Requirements

- Android 8.0 (API 26) or higher
- [Read You](https://github.com/Ashinch/ReadYou) app installed
- Galaxy Z Fold inner display recommended (widget scales to any screen size)

---

## License

MIT — see [LICENSE](LICENSE)
