# NewsFeed — Product Requirements

Product-level feature index for the NewsFeed Android home-screen widget. This tracks *what the
product does and why*, at a glance — not implementation detail (see `README.md` for the
user-facing manual, `docs/BUGS.md` for the engineering/QA history, and
`docs/superpowers/specs/` for the full design rationale behind each feature listed here).

**Product**: a standalone Android home-screen widget that fetches and displays RSS/Atom feeds
(and public Telegram channels) directly on the home screen, with no companion app or server —
everything runs on-device. Two widget types from one app: **NewsFeed** (standard) and
**NewsFeed Focus** (adds a tap-to-enlarge reading mode). Built with first-class RTL Hebrew
support as a founding requirement, not an afterthought.

Status legend: ✅ Shipped · 🚧 In progress · 📋 Planned (spec approved, not yet built) ·
💭 Queued (not yet designed)

## Core feed experience

| Feature | Status | Requirement |
|---|---|---|
| RSS/Atom feed fetching | ✅ | Fetch and display articles from any user-added RSS/Atom URL, on-device, no server. |
| Telegram channels as a feed source | ✅ | A public Telegram channel can be added the same way as an RSS URL, converted to the same article shape. |
| Feed search by topic | ✅ | Find feeds without knowing a URL up front. |
| OPML import/export | ✅ | Move a feed list in or out of another reader without retyping URLs. |
| Sort (Newest/Oldest/By feed/Unread first) | ✅ | User controls article ordering independent of fetch order. |
| Filter (All/Unread/Read) | ✅ | User can narrow the visible list to what they haven't seen yet. |
| Unread-only 5-second grace period | ✅ | A just-read article stays visible, dimmed, for 5s before disappearing under "Unread only" — no instant, unexplained vanish. |
| Full-article extraction | ✅ | "Full article" mode fetches and cleans the real page body (Jsoup-based), not raw HTML. |
| Article accumulation (300-article cap, per-feed floor) | ✅ | A low-frequency feed's articles can't be crowded out by high-frequency neighbors. |
| Per-feed customization (color, RTL/LTR, font, style, thumbnail) | ✅ | Each feed can look and read correctly regardless of source language/direction. |

## Appearance

| Feature | Status | Requirement |
|---|---|---|
| 10 built-in widget themes | ✅ | User picks a visual style; every theme fully re-colors chrome, not just accents. |
| Custom theme (user-picked font/background color via RGB sliders) | ✅ | User isn't limited to the 9 preset palettes. |
| Glamour Hebrew handwriting font | ✅ | A distinct, high-fidelity Hebrew-native visual option. |
| Independent font-size controls (headline vs. article body) | ✅ | Readability tuning without coupling the two. |
| Background opacity | ✅ | Widget can blend with the wallpaper instead of always being opaque. |

## Focus Mode (NewsFeed Focus widget)

| Feature | Status | Requirement |
|---|---|---|
| Tap-to-enlarge focus | ✅ | One article can be read at a larger size while others shrink, without opening a separate view. |
| Read-marking on focus-away | ✅ | An article is marked read only once the user has moved on to another one, not the instant it's tapped. |
| Adjustable focus scale (+/-) | ✅ | User controls how large the focused row gets, in the moment. |

## Reliability & maintenance

| Feature | Status | Requirement |
|---|---|---|
| Self-updating (GitHub Release polling) | ✅ | User gets new builds without a Play Store listing. |
| Release notes before installing an update | ✅ | User sees what changed and why, before committing to an update — not a blind "Update Now." |
| Crash detection & local bug log | ✅ | On-device crash capture with a shareable report, no server involved (Phase 1 of a larger bug-logging roadmap). |
| Background refresh (WorkManager, 15min–12h configurable) | ✅ | Widget content stays current without the user opening it. |

## Roadmap

| Feature | Status | Requirement |
|---|---|---|
| Share button (per-article + app-wide) | 🚧 | A dedicated share action, available regardless of the "Open article in" setting; a way to share the app itself from the widget. See `docs/superpowers/specs/2026-09-13-share-button-design.md`. |
| Bug Logger Phase 2 (shared server-side dedup) | 💭 | Crash reports from all users deduplicated into one shared GitHub issue per signature, on by default with opt-out. See `docs/superpowers/specs/2026-09-11-server-backend-roadmap.md` (future planning only, not an approved spec). |
| "Instant updates" (true push) | 💭 | Push a new-article notification without waiting for the next ≥15-minute background poll — requires a new server component and an explicit, opt-in privacy disclosure (this app is otherwise fully standalone). Same roadmap doc as above. |
| RemoteViews rewrite (drop Jetpack Glance) | 💭 | Render all ~300 accumulated articles via a real on-demand list adapter instead of Glance's upfront-composed `LazyColumn`, which currently caps visible rows well below 300 for memory reasons. Long-deferred, large architectural change. |

## Non-goals

- **No server-side component today.** Every shipped feature runs entirely on-device; nothing in
  "Shipped" ever sends a user's feed list or reading activity anywhere. (The two 💭 roadmap
  items above are the only features that would change this, and both are explicitly gated
  behind an opt-in/opt-out disclosure decision, not silently bundled in.)
- **No Play Store distribution.** Self-update via GitHub Releases is the only distribution
  channel by design.
