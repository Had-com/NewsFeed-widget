# Release notes on self-update — design

## Context

NewsFeed self-updates by polling a rolling GitHub Release's `version.json` for a newer
`versionCode`, then (when the user confirms, or automatically for the daily background
check) downloading and installing the new APK. Today there is **no confirmation step at
all** for a manual update — `UpdateManager.checkAndUpdate(context, notifyOnly = false)`
goes straight from "a newer build exists" to downloading and launching the system
installer. The user asked: when updating, tell them what changed and why it's worth
updating.

## Decisions made during brainstorming

- **Timing: shown before installing**, as a genuinely new confirmation step (there isn't
  one today) — "here's what's new, Update Now or Later" — rather than an after-the-fact
  notice once the update has already happened.
- **Content source: hand-written per release**, the same rhythm already used for
  `docs/BUGS.md`'s "Feature additions" entries — a short, plain-language, user-facing
  summary added whenever something worth telling the user about ships. Not auto-generated
  from commit messages, which are written for a developer audience.
- **Notes are keyed by a sequential note ID, not `versionCode`.** This repo's CI assigns a
  new `versionCode` on every single push to `main` — most of them mid-feature commits with
  nothing user-facing to report (confirmed by this project's own build history: dozens of
  `versionCode`s in a row for one feature's individual review-and-fix commits). Keying notes
  to `versionCode` would mean most versions have no entry, and the exact `versionCode` a
  push will land on isn't known until after CI finishes — awkward to write against. A simple
  integer note ID that only increments when there's actually something to say sidesteps both
  problems.
- **Tracking "already seen" is on-device, not tied to `versionCode` either.** A stored
  `lastSeenReleaseNoteId` advances once notes have been shown to the user (whether they
  update immediately or tap "Later") — so skipping several updates in a row still surfaces
  everything missed (concatenated, oldest-of-the-unseen-batch first), but tapping "Later"
  doesn't make the same notes reappear on the next check.

## Architecture

`docs/RELEASE_NOTES.md` in the repo holds the plain-language entries, most recent last (Sort
increasing note IDs is the natural checkpoint the app compares its stored
`lastSeenReleaseNoteId` against). The app fetches this file's raw content directly from
GitHub (no CI/build-pipeline change needed — it's just another file in the repo, fetched the
same way `TelegramFeedParser` scrapes an ordinary web page), parses out every entry whose ID
is greater than the on-device `lastSeenReleaseNoteId`, and — only on the manual/notification
"update" paths, not the silent daily background check — shows them in a small confirmation
screen before proceeding with the existing download-and-install flow.

## Components

### `docs/RELEASE_NOTES.md` (new file)

Plain Markdown, oldest first (natural writing order — new entries appended at the end),
parsed by a simple heading-based split, not a general Markdown renderer:

```markdown
# Release notes

## Note 1
- Add public Telegram channels as a feed source, right alongside your RSS feeds.

## Note 2
- New "Custom" theme: pick your own font and background colors for the widget.
```

Each entry is a `## Note <n>` heading (n strictly increasing, no gaps required) followed by
one or more `- ` bullet lines, ending at the next `## Note` heading or end of file.

### `data/ReleaseNotesStore.kt` (new file)

A tiny, separate DataStore file (`release_notes.preferences_pb`), mirroring
`ConfigBackup.kt`'s existing pattern of a small dedicated store rather than piggybacking on
per-widget `WidgetConfig` — `lastSeenReleaseNoteId` is a single, app-wide value, not tied to
any one widget instance (a device can have multiple placed widgets, but there's only one
"has this device's user seen the notes" answer).

```kotlin
object ReleaseNotesStore {
    suspend fun lastSeenId(context: Context): Int
    suspend fun markSeen(context: Context, upToId: Int)
}
```

### `update/ReleaseNotesFetcher.kt` (new file)

Fetches and parses `docs/RELEASE_NOTES.md`'s raw content from
`https://raw.githubusercontent.com/Had-com/NewsFeed-widget/main/docs/RELEASE_NOTES.md`
(same `OkHttpClient` shared elsewhere in this app), and exposes:

```kotlin
data class ReleaseNote(val id: Int, val bullets: List<String>)

object ReleaseNotesFetcher {
    // Returns every note with id > sinceId, oldest first. Empty list (not null) on any
    // fetch/parse failure, or when there's genuinely nothing new - both look identical to
    // the caller, which is correct: either way, there's nothing to show.
    suspend fun fetchUnseenNotes(sinceId: Int): List<ReleaseNote>
}
```

### `update/UpdateManager.kt` — split the existing flow

`checkAndUpdate`'s current body goes straight from "found a newer build" to downloading.
Split into:

- `checkForUpdate(context): UpdateCheckResult` (sealed: `UpToDate`, `Available(versionCode)`,
  `CheckFailed`) — just the existing `fetchLatestVersionCode()` comparison, no side effects.
- `proceedWithUpdate(context)` — the existing download-APK-and-launch-installer body,
  unchanged, extracted as its own function so both the new confirmation screen's "Update Now"
  button and (unchanged) the daily background path can call it.
- The daily background check (`notifyOnly = true`) keeps its existing behavior exactly —
  silent system notification, no release notes shown at check time (that would be
  interruptive for a background poll); notes are shown once the user actually acts on it via
  the notification tap or the manual "Check for updates" button, which is when the new
  confirmation step is inserted.

### Confirmation UI

Two entry points, both showing the same content (extracted into one shared composable to
avoid duplicating it):

- **Manual "Check for updates" row** (`WidgetConfigActivity`, existing "APP UPDATE" section):
  on `Available`, fetch unseen notes and show an in-app `AlertDialog` (matching this screen's
  existing dialog patterns, e.g. the Edit-feed dialog) with the bullet list and "Update Now" /
  "Later" buttons.
- **"Update available" notification tap** (`UpdateRelayActivity`): currently an invisible
  relay Activity with no UI at all. Becomes a real, minimal Compose screen showing the same
  content, since it must work standalone (the app may not already be open). Reuses the same
  shared composable as the dialog above.

In both cases: "Later" calls `ReleaseNotesStore.markSeen(context, latestNoteId)` and closes,
without downloading anything. "Update Now" calls the same `markSeen`, then
`UpdateManager.proceedWithUpdate(context)`.

## Error handling

- A failed fetch of `RELEASE_NOTES.md` (network error, 404, malformed content) yields an
  empty note list, not an error shown to the user — the update flow proceeds exactly as it
  does today (no notes shown, straight to Update Now/Later with just the version number),
  rather than blocking an update on a docs-file fetch failing. Matches this app's existing
  tolerant-degradation convention (e.g. `TelegramFeedParser.summarize()`'s null/empty
  fallbacks).
- A `RELEASE_NOTES.md` entry with malformed bullets or an unparseable heading is skipped
  individually rather than aborting the whole parse — one bad entry shouldn't hide every
  other legitimate one.

## Out of scope

- Any change to `.github/workflows/build.yml` or how `version.json` is generated — release
  notes are fetched as a separate, independent file, not embedded into the existing
  version-check response.
- Auto-generating notes from commit messages (explicitly rejected — see Decisions above).
- Showing notes during the silent daily background check — only when the user actually acts
  on an update (tap the notification, or the manual Settings button).
- Localizing/translating release notes — plain English only, matching this app's existing UI
  copy conventions (the app already mixes Hebrew feed content with English UI chrome; the
  release notes are UI chrome, not feed content).

## Testing / verification

`ReleaseNotesFetcher`'s Markdown-splitting logic (turning raw file text into a list of
`ReleaseNote(id, bullets)`, filtered by `sinceId`) is a pure function over a `String` input —
unit-testable with plain JUnit, following this project's now-established pattern (avoid
Android-framework calls inside the parsing logic itself, matching `TelegramFeedParser`'s and
`WidgetThemes.parseHexColor`'s own testability notes). The actual network fetch,
`ReleaseNotesStore`'s DataStore read/write, and the two UI entry points are verified
on-device only, matching every other network/UI code in this app.
