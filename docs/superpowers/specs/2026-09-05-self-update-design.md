# Self-updating apps — design spec
**Date:** 2026-09-05
**Project:** NewsFeed widget (both flavors: `standard` + `focusMode`)
**Status:** Approved

---

## Overview

Neither flavor is distributed via the Play Store — both are sideloaded APKs built by GitHub Actions. There is currently no way to know a newer build exists, or to get it, other than manually re-downloading and reinstalling. This adds an in-app update checker: a daily background check plus a manual "Check for updates" row, both converging on one shared check-and-update flow that downloads a newer APK and hands it to Android's own installer.

**Hard platform constraint that shapes this whole design:** a normal (non-rooted, non-system, non-device-owner) Android app can never install an APK with zero user interaction — the OS always shows its own "Install" confirmation dialog, and this cannot be suppressed from app code. Everything up to that point (checking, downloading) can be fully silent; the install step always needs exactly one tap on a system-owned dialog, not an app screen.

A second constraint: Android 10+ blocks a plain background job (e.g. a `WorkManager` worker with no preceding user interaction) from launching a new Activity — including the install dialog. A notification tap counts as the required user-interaction signal. This is why the flow below is notification-tap-triggered rather than attempting to pop the install dialog straight out of the daily background check.

---

## Part 1 — Signing consistency (prerequisite)

### Problem
Android refuses to install an "update" whose APK signature doesn't match the currently-installed app's signature. CI runs on a fresh `ubuntu-latest` machine every time; if Gradle's implicit per-machine debug keystore is used (the current setup), each CI build could end up signed with different, randomly-generated key material, silently breaking every self-update attempt with no useful error surfaced to the user.

### Fix
- Generate one dedicated debug-only keystore for this project (not a Play Store release key — nothing sensitive it protects, it only needs to be *consistent*).
- Commit it to the repo, e.g. `keystore/newsfeed-debug.keystore`.
- In `app/build.gradle.kts`, add an explicit `signingConfigs { debug { ... } }` pointing both flavors' `debug` build type at this file (store/key password and alias are fixed, ordinary values — nothing secret to protect here), replacing Gradle's implicit per-machine debug signing.

### Verification
After this change, `standard` and `focusMode` from the same build (and across different CI runs) must all report the same signing certificate (`apksigner verify --print-certs`).

---

## Part 2 — CI: real version numbers + a rolling release

### `versionCode` from the CI run number
`versionCode` has stayed at `1` across all 72 builds so far — nothing has ever incremented it, so there's currently no monotonically-increasing number an update checker could compare against.

- `app/build.gradle.kts`: `versionCode = (project.findProperty("buildVersionCode") as String?)?.toIntOrNull() ?: 1`
- `.github/workflows/build.yml`: pass `-PbuildVersionCode=${{ github.run_number }}` to the `gradle assembleDebug` invocation.
- Local/manual builds keep defaulting to `1` — only CI builds get real, ever-increasing version codes. Both flavors built in the same CI run share the same `versionCode` (they're always released together).

### Publish a single rolling "latest" release
After a successful build, the workflow publishes (or overwrites) one GitHub Release tagged `latest`:
- Requires `permissions: contents: write` added to the `build` job.
- Uses an action (e.g. `softprops/action-gh-release`) with `tag_name: latest`, `make_latest: true`, overwriting existing assets rather than accumulating a release per push.
- Uploads three assets: the two renamed flavor APKs (already produced by the existing `Rename APK` step) plus a small `version.json`:
  ```json
  { "versionCode": 73 }
  ```
  (a tiny dedicated manifest asset, rather than requiring the app to regex-parse a filename for the version number).

---

## Part 3 — App-side update flow

No new Activity. One shared suspend function does the real work; three different triggers call into it.

### `UpdateManager.checkAndUpdate(context, silent: Boolean)`
New file, e.g. `app/src/main/java/com/newsfeed/widget/update/UpdateManager.kt`:
1. Fetch `https://github.com/Had-com/NewsFeed-widget/releases/download/latest/version.json` (public repo, no auth needed).
2. Compare its `versionCode` to `BuildConfig.VERSION_CODE`. If not newer: if `silent` is false (manual check), show a brief "You're up to date" message; if `silent` is true (daily worker), do nothing.
3. If newer: pick the APK asset matching this flavor (`BuildConfig.FLAVOR`, i.e. `standard` vs `focusMode` — the two release assets are named accordingly), and:
   - If `!packageManager.canRequestPackageInstalls()`: post a notification whose tap action opens `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this exact package (`Uri.fromParts("package", context.packageName, null)`). Stop here — this is a one-time grant per flavor/package; once granted, later updates skip straight past this check.
   - Otherwise: download the APK (reusing whichever HTTP client `NewsFeedRepository`/`FetchFullArticleCallback` already use) to `context.cacheDir`, with a short-lived "Downloading update…" progress notification.
   - On download completion: build a `FileProvider` URI for the downloaded file (new `<cache-path>` entry in the existing `file_paths.xml`) and `startActivity(Intent(ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_GRANT_READ_URI_PERMISSION))` — this pops Android's own Install dialog. (Google Play Protect may additionally show its own "app not recognized" interstitial here on some devices/builds — unavoidable from app code; see Part 4.)

### Three entry points, one function
1. **Daily background check** — a new `UpdateCheckWorker : CoroutineWorker`, scheduled the same way `WidgetWorker` already schedules its own periodic work (same `WorkManager` infra, same reliability profile). Calls `checkAndUpdate(context, silent = true)`, except step 3 changes slightly here: instead of downloading immediately, it posts an **"Update available: vX → vY"** notification. Tapping that notification is what actually triggers the download + install-dialog steps (see the platform constraint in the Overview — this tap is what makes the subsequent Activity launch legal on Android 10+).
2. **Manual "Check for updates" row** — added to the existing Settings screen (`WidgetConfigActivity`), likely in a small "About" section near the bottom. Calls `checkAndUpdate(context, silent = false)` directly (already running in a foreground, user-initiated context, so no notification-tap detour needed here — this path can go straight to download + install-dialog).
3. **Tapping the "Update available" notification** — routes to a small invisible relay `Activity` (same pattern this codebase already uses for `ShareRelayActivity`: `Theme.NoDisplay`, `excludeFromRecents="true"`, does its work then finishes itself) that runs the download + install-dialog half of `checkAndUpdate` directly, since the version check already happened when the notification was created. Deliberately not a `BroadcastReceiver`: `goAsync()` has a hard ~10-second execution budget, which isn't safe for a multi-second APK download over a slow connection — an `Activity`'s own lifecycle scope has no such limit.

### Manifest changes
- `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`
- New relay `<activity>` for the notification-tap handler (see Part 3, point 3), `exported="false"`, `Theme.NoDisplay`, `excludeFromRecents="true"` — same shape as the existing `ShareRelayActivity` entry.
- `WorkManager` periodic work needs no manifest entry (same as `WidgetWorker` today).
- New `<cache-path>` entry in `res/xml/file_paths.xml` for the downloaded APK's subfolder, alongside the existing `opml_share` entry.

---

## Part 4 — Permission gates & OS-level warnings (not fully suppressible)

- **"Install unknown apps" per-package toggle** — one-time grant, per flavor (each is a separate installed package). Handled above via a direct link to that exact package's settings screen.
- **Android 10+ background-activity-launch restriction** — the reason the daily-check path is notify-then-tap rather than attempting a silent end-to-end install; a notification tap is a recognized user-interaction exemption, a bare background worker is not.
- **Google Play Protect's own scan warning** — separate from "install unknown apps"; can still appear on the system installer screen itself (e.g. "app not recognized, install anyway?"), especially for a package/signature Play Protect hasn't seen before. Cannot be suppressed from app code. Mitigation: a one-time explanatory line of text next to the "Check for updates" row the first time it's used, so it isn't a surprise mid-flow.
- **Battery optimization / OEM background-kill of `WorkManager` jobs** — inherited as-is from the existing `WidgetWorker` daily-refresh infrastructure; not a new risk this feature introduces, and not specially mitigated beyond what that existing infra already does.

---

## Testing

- `aapt dump badging` on two successive CI-built APKs (either flavor) to confirm `versionCode` actually increments per run, and that `standard`/`focusMode` from the same run share the same value.
- `apksigner verify --print-certs` on APKs from two different CI runs to confirm identical signing certificates after the keystore change.
- Confirm the `latest` GitHub Release's assets are overwritten (not accumulated) across two successive pushes.
- On-device, both flavors independently:
  - Manually edit the published `version.json` to simulate a newer build; confirm the daily worker's next run posts the "Update available" notification, and that the manual "Check for updates" row detects it immediately without waiting for the worker.
  - Confirm tapping the notification downloads and pops the system Install dialog, and that it only ever offers this flavor's own APK asset, never the other flavor's.
  - With "install unknown apps" not yet granted for a given package, confirm the flow routes to that exact package's settings screen; after granting once, confirm later updates skip straight past that step.
  - Confirm a same-version check reports "up to date" (manual) / does nothing (daily) — no download, no notification.
- Full clean install of both flavors from scratch after the signing-key change lands, before layering the update feature on top, to confirm nothing about the existing app broke.
