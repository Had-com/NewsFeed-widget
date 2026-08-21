# Read You Widget

A standalone Android home screen widget that fetches and displays RSS/Atom feeds directly, styled to match the [Read You](https://github.com/Ashinch/ReadYou) RSS reader's Material You card design. Built for the **Galaxy Z Fold 8 Ultra** inner display with full **RTL Hebrew support**.

---

## Screenshots

<table>
  <tr>
    <td align="center"><b>Dark mode</b></td>
    <td align="center"><b>Light mode</b></td>
    <td align="center"><b>Config panel</b></td>
  </tr>
  <tr>
    <td><img src="screenshots/widget-dark.svg" width="220" alt="Widget dark mode — Hebrew RTL feeds with accent colors"/></td>
    <td><img src="screenshots/widget-light.svg" width="220" alt="Widget light mode — Hebrew RTL feeds with accent colors"/></td>
    <td><img src="screenshots/config-panel.svg" width="260" alt="Config panel — drag to reorder, per-feed color, font, B/I/U, RTL/LTR"/></td>
  </tr>
  <tr>
    <td align="center"><sub>Mixed Hebrew RTL + English LTR<br>feeds with per-feed accent colors</sub></td>
    <td align="center"><sub>Same layout in system light theme</sub></td>
    <td align="center"><sub>Long-press widget → Edit widget<br>Drag ⠿ to reorder · × to remove</sub></td>
  </tr>
</table>

---

## How it works

The widget is **fully standalone** — it fetches RSS/Atom feeds directly over the network using OkHttp. It does **not** require access to the Read You app's internal database. You add feed URLs yourself (or import them from an OPML file exported by Read You or any other RSS reader).

---

## Features

### Feed display
- Shows your latest RSS/Atom articles directly on the home screen
- Unread count badge in the widget header
- Unread dot indicator per article
- Per-feed colored left/right accent stripe
- Relative timestamps (5m, 2h, 3d)
- Tap any article to open it in Read You or your browser

### Tap to open + mark as read
Tapping an article:
1. Opens the article URL in the **Read You app** (if installed). Falls back to your default browser if Read You is not installed or the URL cannot be handled.
2. **Marks the article as read** — the unread dot disappears and read status is remembered across widget refreshes (stored locally via DataStore, keeping up to 500 recent entries).
3. Triggers an immediate widget refresh so the unread count updates right away.

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

### Feed management
- **Add by URL** — paste any RSS or Atom feed URL; the widget fetches and validates the feed title automatically
- **Import OPML** — import feeds from any OPML file (supports grouped and flat OPML, as exported by Read You, Feedly, Reeder, etc.)
- **Export OPML** — share your current feed list as a standard OPML 2.0 file
- **Drag to reorder** — drag feeds in the config screen to control their display order in the widget
- **Remove** — tap × on any feed row to delete it from the widget

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
2. **Refresh every** — choose how often the widget polls for new articles (15 min / 30 min / 1 h / 2 h / 4 h / 6 h / 12 h)
3. **Add Feed** — enter a feed URL and tap **Add**, or use **Import OPML** / **Export OPML**
4. **Feed order & style** — one draggable row per feed with:
   - Grip handle (drag ⠿ to reorder)
   - × button to remove
   - Color swatch (tap to open color picker)
   - Font dropdown
   - **B** / *I* / <u>U</u> style toggles
   - RTL / LTR direction toggle

Changes take effect immediately after tapping **Save**.

---

## Background refresh

The widget polls your feeds automatically using WorkManager. You can configure the interval in the settings screen:

| Interval | Notes |
|---|---|
| 15 minutes | Minimum (Android WorkManager floor) — default |
| 30 minutes | |
| 1 hour | |
| 2 hours | |
| 4 hours | |
| 6 hours | |
| 12 hours | Maximum battery-friendly |

Changing the interval takes effect immediately on next Save. The last refresh time is shown at the bottom of the widget.

---

## Installation

### Option A — Download from GitHub Actions (no build tools needed)
1. Go to the [Actions tab](../../actions) of this repo
2. Click the latest **Build APK** run
3. Download the **ReadYouWidget-debug** artifact
4. Unzip and transfer the `.apk` to your phone
5. On your Galaxy Z Fold: **Settings → Apps → Special app access → Install unknown apps** → allow your file manager
6. Tap the `.apk` to install
7. Long-press your home screen → **Widgets** → find **Read You Feeds** → drag to place it
8. The config screen opens automatically — add your first feed URL or import an OPML file

### Option B — Build from source
```bash
git clone https://github.com/Had-com/readyou-widget.git
cd readyou-widget
./gradlew assembleDebug
# APK is at: app/build/outputs/apk/debug/app-debug.apk
```

---

## Importing feeds from Read You

The easiest way to populate the widget with your existing Read You subscriptions:

1. In Read You: **Settings → Data & backup → Export as OPML** → save the file
2. In the widget config screen: tap **Import OPML** → select the file
3. All your feeds are added automatically (duplicates are skipped)

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
│   │   ├── FeedItemRow.kt            # Per-article Glance composable
│   │   └── WidgetWorker.kt           # WorkManager refresh job
│   ├── config/
│   │   ├── WidgetConfigActivity.kt   # Settings screen
│   │   ├── FeedConfigRow.kt          # Per-feed controls row
│   │   └── ColorPickerGrid.kt        # 12-color preset picker
│   ├── data/
│   │   ├── FeedConfig.kt             # Data models (FeedConfig, WidgetConfig, ArticleItem)
│   │   ├── WidgetConfigStore.kt      # DataStore — widget config persistence
│   │   ├── ReadStatusStore.kt        # DataStore — read article ID persistence
│   │   ├── ReadYouRepository.kt      # RSS/Atom fetching (OkHttp + XmlPullParser)
│   │   ├── OpmlManager.kt            # OPML 2.0 import/export
│   │   └── WidgetStateKey.kt         # Glance state keys
│   └── DeepLinkActivity.kt           # Tap handler: open URL, mark read, refresh
└── res/
    ├── values/strings.xml            # English strings
    ├── values-iw/strings.xml         # Hebrew strings (עברית)
    ├── xml/appwidget_info.xml        # Widget metadata
    └── xml/file_paths.xml            # FileProvider paths (OPML export)
```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `androidx.glance:glance-appwidget` | 1.1.0 | Widget framework (Compose-based) |
| `androidx.work:work-runtime-ktx` | 2.9.0 | Background refresh |
| `androidx.datastore:datastore-preferences` | 1.1.1 | Config + read-status persistence |
| `kotlinx-serialization-json` | 1.6.3 | Config JSON serialization |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | RSS/Atom feed HTTP fetching |
| `sh.calvin.reorderable` | 2.3.0 | Drag-to-reorder in config screen |

---

## Requirements

- Android 8.0 (API 26) or higher
- Internet permission (for RSS fetching)
- [Read You](https://github.com/Ashinch/ReadYou) app — **optional**: tap-to-open falls back to the system browser if Read You is not installed
- Galaxy Z Fold inner display recommended (widget scales to any screen size)

---

## License

MIT — see [LICENSE](LICENSE)
