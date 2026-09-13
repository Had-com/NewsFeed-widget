# Share button Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated per-article "Share ↗" button (alongside "Open article →", not instead of it) and a footer-level "Share" button that offers a choice between sharing the app itself or a direct download link.

**Architecture:** Both additions route through the existing `ShareRelayActivity` — a tiny relay Activity that already exists because Glance can't launch an ambiguous `ACTION_SEND` chooser directly from a widget. It gains a second mode, chosen by which Intent extra is present: the existing `EXTRA_ARTICLE_URL` mode shares immediately with no UI (unchanged); a new `EXTRA_SHOW_APP_SHARE_CHOICE` mode shows a two-item `AlertDialog` first, then shares whichever URL was picked.

**Tech Stack:** Kotlin, Jetpack Glance (`androidx.glance.appwidget:1.1.0`), plain `android.app.AlertDialog` (no AppCompat dependency in this project).

**Design reference:** `docs/superpowers/specs/2026-09-13-share-button-design.md` — read it first if anything below is ambiguous; this plan implements it task-by-task and does not repeat its rationale in full. One correction to the spec, found while planning (see Task 3): `ShareRelayActivity`'s manifest theme must change from `Theme.NoDisplay` to `Theme.Translucent.NoTitleBar`, since `Theme.NoDisplay` requires the Activity to `finish()` before `onResume()` completes — showing an `AlertDialog` and waiting for a tap violates that and crashes. The spec didn't call this out; this plan fixes it.

---

### Task 1: Per-article "Share ↗" button in `FeedItemRow.kt`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt` (lines 847-876)

**Context for the implementer:** the expanded-row action area already has an "Open article →" button, built from a hoisted `openIntent` (defined earlier in the same composable, around line 577 — read it, don't re-derive it) that already points at `ShareRelayActivity` when "Open article in" is set to Share, and at a plain `ACTION_VIEW` when it's set to Browser. This task adds a SECOND, independent button next to it that always shares the article, regardless of that setting — but only shown when it would add something new (i.e., when "Open article in" is Browser; when it's already Share, "Open article →" does the exact same thing this new button would, so showing both would be pure redundant clutter).

**Important pattern to preserve**: the existing `openIntent`'s share-mode branch calls `.setData(Uri.parse(article.articleUrl))` on the `Intent` before `.putExtra(...)` (see line 580) — this is NOT decorative. Multiple rows in the same `LazyColumn` can otherwise have Glance treat their `ShareRelayActivity`-targeting intents as indistinguishable from each other (a real, previously-hit bug in this exact codebase, documented in `ShareRelayActivity.kt`'s own comment). The new Share button's Intent MUST include the same `.setData(...)` call for the same reason — this is called out explicitly in the code below, don't drop it.

- [ ] **Step 1: Replace the "Open article →" button block**

In `app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt`, find:

```kotlin
                if (openIntent != null) {
                    Spacer(GlanceModifier.height(6.dp))
                    // Article rows live inside a LazyColumn, so clicks route through Glance's
                    // list-adapter trampoline (InvisibleActionTrampolineActivity). Building the
                    // Intent at compose time and using actionStartActivity() (rather than a custom
                    // ActionCallback manually calling context.startActivity()) is what makes Browser
                    // mode (a plain ACTION_VIEW) work reliably. Share mode does not: an ACTION_SEND
                    // intent is inherently ambiguous (multiple apps can match), and two different
                    // attempts to fix it directly — dropping Intent.createChooser(), then giving each
                    // row's intent a distinct `data` field to dodge Glance's action-conflation — both
                    // still failed (the second differently: the trampoline now fires but silently
                    // self-finishes without ever launching anything). Rather than keep fighting
                    // Glance's handling of ambiguous/chooser intents, Share mode now targets
                    // ShareRelayActivity — a real, single, unambiguous target within our own app —
                    // which then builds and launches the actual chooser from a proper Activity
                    // context that isn't subject to any of this. (openIntent itself is built once,
                    // above, and shared with the per-chunk OpenInBrowserLink links.)
                    Text(
                        text = "Open article →",
                        style = TextStyle(
                            fontSize   = (9f * fontSize).sp,
                            fontFamily = FontFamily.SansSerif,
                            color      = accentProvider,
                        ),
                        modifier = GlanceModifier
                            .background(GlanceTheme.colors.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .clickable(actionStartActivity(openIntent)),
                    )
                }
```

Replace with:

```kotlin
                if (openIntent != null) {
                    Spacer(GlanceModifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Article rows live inside a LazyColumn, so clicks route through Glance's
                        // list-adapter trampoline (InvisibleActionTrampolineActivity). Building the
                        // Intent at compose time and using actionStartActivity() (rather than a custom
                        // ActionCallback manually calling context.startActivity()) is what makes Browser
                        // mode (a plain ACTION_VIEW) work reliably. Share mode does not: an ACTION_SEND
                        // intent is inherently ambiguous (multiple apps can match), and two different
                        // attempts to fix it directly — dropping Intent.createChooser(), then giving each
                        // row's intent a distinct `data` field to dodge Glance's action-conflation — both
                        // still failed (the second differently: the trampoline now fires but silently
                        // self-finishes without ever launching anything). Rather than keep fighting
                        // Glance's handling of ambiguous/chooser intents, Share mode now targets
                        // ShareRelayActivity — a real, single, unambiguous target within our own app —
                        // which then builds and launches the actual chooser from a proper Activity
                        // context that isn't subject to any of this. (openIntent itself is built once,
                        // above, and shared with the per-chunk OpenInBrowserLink links.)
                        Text(
                            text = "Open article →",
                            style = TextStyle(
                                fontSize   = (9f * fontSize).sp,
                                fontFamily = FontFamily.SansSerif,
                                color      = accentProvider,
                            ),
                            modifier = GlanceModifier
                                .background(GlanceTheme.colors.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .clickable(actionStartActivity(openIntent)),
                        )
                        // New, independent share action - only shown when "Open article in" is
                        // Browser. When it's already Share, "Open article ->" above does the
                        // exact same thing this button would, so showing both would be pure
                        // redundant clutter, not a new capability.
                        if (externalApp == "browser") {
                            Spacer(GlanceModifier.width(6.dp))
                            Text(
                                text = "Share ↗",
                                style = TextStyle(
                                    fontSize   = (9f * fontSize).sp,
                                    fontFamily = FontFamily.SansSerif,
                                    color      = accentProvider,
                                ),
                                modifier = GlanceModifier
                                    .background(GlanceTheme.colors.primaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .clickable(actionStartActivity(
                                        // .setData(...) is required, not decorative - see this
                                        // file's own header comment on ShareRelayActivity's
                                        // per-row distinctness requirement (a real, previously
                                        // hit bug: Glance can otherwise treat multiple rows'
                                        // identical-looking ShareRelayActivity intents as one).
                                        Intent(context, ShareRelayActivity::class.java)
                                            .setData(Uri.parse(article.articleUrl))
                                            .putExtra(ShareRelayActivity.EXTRA_ARTICLE_URL, article.articleUrl)
                                    )),
                            )
                        }
                    }
                }
```

`Row`, `Alignment`, `Intent`, `Uri`, `context` (a local `val` defined earlier in this same composable via `LocalContext.current`) are all already imported/in scope in this file — no new imports needed.

- [ ] **Step 2: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

(`gradlew.bat` alone is known-broken in this environment — missing wrapper jar — use the cached Gradle distribution path above directly. Single `:app` module, no product flavors.)

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/FeedItemRow.kt
git commit -m "Add a per-article Share button alongside Open article"
```

Use a PLAIN `git commit` — never `git commit --amend`.

---

### Task 2: Footer "Share" button in `NewsFeedWidget.kt`

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt` (the `WidgetFooter` composable, lines 498-544)

**Context for the implementer:** `WidgetFooter` is a single shared composable used by BOTH widget types (`NewsFeedWidget` and `NewsFeedFocusWidget`) — this change automatically applies to both, no extra work needed. It currently renders a refresh countdown (left) and a ⚙ settings icon (right), separated by a flexible spacer. This task adds a "Share" text label between them.

- [ ] **Step 1: Replace the footer's `Row` contents**

In `app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt`, find:

```kotlin
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = "⚙",
            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(4.dp)
                .clickable(actionStartActivity(settingsIntent)),
        )
    }
}
```

(This is the tail end of `WidgetFooter` — the closing `}` after the gear `Text` closes the `Row`, and the one after that closes the function. Confirm you're editing the right spot by checking the line just above matches `Spacer(GlanceModifier.defaultWeight())` immediately following the countdown `Text` block.)

Replace with:

```kotlin
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = "Share",
            style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(4.dp)
                .clickable(actionStartActivity(
                    Intent(context, ShareRelayActivity::class.java)
                        .putExtra(ShareRelayActivity.EXTRA_SHOW_APP_SHARE_CHOICE, true)
                )),
        )
        Spacer(GlanceModifier.width(4.dp))
        Text(
            text = "⚙",
            style = TextStyle(fontSize = 14.sp, color = GlanceTheme.colors.primary),
            modifier = GlanceModifier
                .padding(4.dp)
                .clickable(actionStartActivity(settingsIntent)),
        )
    }
}
```

`ShareRelayActivity` lives in the same package (`com.newsfeed.widget.glance`) as `NewsFeedWidget.kt` — no import needed. `context` is already a local `val` defined earlier in `WidgetFooter` via `LocalContext.current` (see the existing `settingsIntent` construction just above this block) — reuse it, don't redeclare it.

- [ ] **Step 2: Confirm it compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: FAIL — `ShareRelayActivity.EXTRA_SHOW_APP_SHARE_CHOICE` doesn't exist yet (that's Task 3). Confirm the ONLY error is that unresolved reference, in this file, pointing at the constant you just referenced — nothing else.

- [ ] **Step 3: Commit anyway — Task 3 immediately restores a compiling tree**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/NewsFeedWidget.kt
git commit -m "Add a footer Share button for sharing the app itself"
```

---

### Task 3: Extend `ShareRelayActivity` with the app-share dialog mode

**Files:**
- Modify: `app/src/main/java/com/newsfeed/widget/glance/ShareRelayActivity.kt` (full file, currently 35 lines)
- Modify: `app/src/main/AndroidManifest.xml`

**Context for the implementer — read this before writing any code**: `ShareRelayActivity` is currently declared in the manifest with `android:theme="@android:style/Theme.NoDisplay"`. That theme carries a hard platform requirement: the Activity MUST call `finish()` or start another activity before `onResume()` completes, or the system throws and crashes it. The activity's CURRENT behavior satisfies this trivially (it builds an Intent and calls `finish()` synchronously in `onCreate()`, never showing any of its own UI). The new app-share mode this task adds does NOT satisfy this — it shows an `AlertDialog` and then WAITS for the user to tap one of two options before finishing, which is exactly the pattern `Theme.NoDisplay` forbids. **This will crash on-device if the manifest theme isn't also changed.** This exact problem doesn't show up at compile time — it's a runtime crash, so don't skip verifying it on-device in Task 4.

- [ ] **Step 1: Replace `ShareRelayActivity.kt`'s full contents**

```kotlin
package com.newsfeed.widget.glance

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle

/**
 * A share button tapped from the widget can't call Intent.createChooser() + startActivity()
 * directly and reliably — Glance's actionStartActivity() only reliably launches a single,
 * unambiguous target from a widget's PendingIntent context (confirmed: plain ACTION_VIEW
 * works, but ACTION_SEND with a chooser or multiple share targets does not, even once each
 * row's intent is made distinct to avoid Glance's action-conflation). This tiny relay
 * activity is itself a single, unambiguous target Glance can launch cleanly; once it's
 * actually running as a real foreground Activity, it has full standing to build and launch the
 * chooser normally, the same way any ordinary "Share" button in a full app would.
 *
 * Two independent modes, chosen by which Intent extra is present:
 * - EXTRA_ARTICLE_URL: share that URL immediately, no UI ever shown (the original,
 *   per-article mode - unchanged from before this class gained a second mode).
 * - EXTRA_SHOW_APP_SHARE_CHOICE: show a two-option dialog first (share the app itself vs.
 *   share a direct download link), then share whichever URL the user picked.
 */
class ShareRelayActivity : Activity() {
    companion object {
        const val EXTRA_ARTICLE_URL = "articleUrl"
        const val EXTRA_SHOW_APP_SHARE_CHOICE = "showAppShareChoice"
        private const val APP_URL = "https://github.com/Had-com/NewsFeed-widget"
        private const val DOWNLOAD_URL = "https://github.com/Had-com/NewsFeed-widget/releases/tag/latest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleUrl = intent.getStringExtra(EXTRA_ARTICLE_URL)
        if (!articleUrl.isNullOrBlank()) {
            shareUrl(articleUrl)
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_APP_SHARE_CHOICE, false)) {
            AlertDialog.Builder(this)
                .setTitle("Share NewsFeed")
                .setItems(arrayOf("Share the app", "Share the download link")) { _, which ->
                    shareUrl(if (which == 0) APP_URL else DOWNLOAD_URL)
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        finish()
    }

    private fun shareUrl(url: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                "Share",
            )
        )
    }
}
```

Note what changed from the original file: the inline `Intent.createChooser(...)` call that used to live directly in `onCreate()` is now extracted into a small private `shareUrl(url: String)` function, called from BOTH modes — this is what lets the new dialog mode reuse the exact same chooser-building logic instead of duplicating it. The per-article mode's own behavior (build the chooser, launch it, finish) is otherwise byte-for-byte identical to before.

- [ ] **Step 2: Fix the manifest theme**

In `app/src/main/AndroidManifest.xml`, find:

```xml
        <activity
            android:name=".glance.ShareRelayActivity"
            android:exported="false"
            android:theme="@android:style/Theme.NoDisplay"
            android:excludeFromRecents="true" />
```

Replace with:

```xml
        <activity
            android:name=".glance.ShareRelayActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true" />
```

(`Theme.Translucent.NoTitleBar` is already used elsewhere in this exact codebase for the same purpose — a real, displayable-but-visually-invisible window that CAN legitimately stay open waiting for something like a dialog, unlike `Theme.NoDisplay`. `exported="false"` and `excludeFromRecents="true"` stay unchanged — this activity must remain non-launchable by other apps and shouldn't appear as its own entry in the recents list.)

- [ ] **Step 3: Confirm the whole module compiles**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL — this was the last missing piece (`EXTRA_SHOW_APP_SHARE_CHOICE`), so the whole module should now compile with zero errors.

- [ ] **Step 4: Self-review**

Confirm `git status` shows exactly `ShareRelayActivity.kt` and `AndroidManifest.xml` modified. Confirm `grep -n "Intent.createChooser" app/src/main/java/com/newsfeed/widget/glance/ShareRelayActivity.kt` shows exactly ONE occurrence (inside `shareUrl()`) — if there are two, the extraction in Step 1 wasn't done correctly.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/newsfeed/widget/glance/ShareRelayActivity.kt app/src/main/AndroidManifest.xml
git commit -m "Add an app-share dialog mode to ShareRelayActivity"
```

Use a PLAIN `git commit` — never `git commit --amend`.

---

### Task 4: On-device verification + `docs/BUGS.md` entry

**Files:** none (verification only) until Step 6's doc commit.

This project's established convention (see `docs/DEBUG_PLAN.md` and every prior feature in `docs/BUGS.md`) is that any UI flow involving a real Activity/dialog is verified on a real device, not assumed from a clean compile — especially here, since Task 3's manifest-theme fix addresses a crash that a clean compile cannot detect at all (it's a runtime-only failure mode).

- [ ] **Step 1: Build and install**

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
export TEMP="C:\t"; export TMP="C:\t"
mkdir -p /c/t
cd "C:\Users\PhotoStudio\ClaudeWorkspace\repo"
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon :app:assembleDebug
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Per-article Share button — visibility condition**

With Settings → "Open article in" set to **Browser** (the default), expand an article with a real URL. Confirm BOTH "Open article →" and "Share ↗" appear side by side, with no visual overlap, at the default font size and at least one larger font size (Settings → Font size slider). Switch "Open article in" to **Share**, Save, re-expand an article — confirm "Share ↗" is now GONE and only "Open article →" remains (which itself now shares, per the pre-existing, unchanged behavior).

- [ ] **Step 3: Per-article Share button — actually shares**

With "Open article in" back on Browser, tap "Share ↗" on an expanded article. Confirm the real Android system share sheet opens with that article's actual URL as the shared text (check by sharing to a note-taking app or similar and confirming the exact URL). Confirm tapping "Open article →" right next to it still opens the browser, unaffected.

- [ ] **Step 4: Footer Share button — appears on both widget types, shows the dialog**

On the standard "NewsFeed" widget, tap the new "Share" label in the footer (between the countdown and the gear). Confirm a real Android dialog titled "Share NewsFeed" appears with two options: "Share the app" and "Share the download link". Repeat on a "NewsFeed Focus" widget — confirm the same footer button and dialog appear there too (this composable is shared between both widget types, so this is mainly confirming that sharing assumption holds, not expecting a difference).

- [ ] **Step 5: Footer Share button — both dialog choices work, and cancelling doesn't crash**

Tap "Share the app" — confirm the system share sheet opens with `https://github.com/Had-com/NewsFeed-widget` as the shared text. Reopen the dialog and tap "Share the download link" — confirm the system share sheet opens with `https://github.com/Had-com/NewsFeed-widget/releases/tag/latest` instead. Reopen the dialog a third time and dismiss it WITHOUT picking anything (tap outside the dialog, or the system back gesture) — confirm the app returns cleanly to the home screen with no crash, no stuck/invisible activity, and no share sheet opens. Check `adb logcat -d | grep -iE "com.newsfeed.widget"  | grep -iE "exception|fatal|crash"` after this whole sequence — must be clean, given this is exactly the scenario the manifest-theme fix in Task 3 exists to prevent.

- [ ] **Step 6: Update `docs/BUGS.md`**

Add a "Feature additions" entry (matching this file's existing format — check the two most recent entries for the exact style) describing the share button feature: the per-article button and its visibility condition, the footer app-share button and its two-choice dialog, and the `Theme.NoDisplay` → `Theme.Translucent.NoTitleBar` manifest fix this planning process caught before it could ship as a crash. Commit this doc update on its own:

```bash
git add docs/BUGS.md
git commit -m "Document the share button feature"
```

---

## Self-review notes (for whoever executes this plan)

- **Spec coverage:** every "Components" entry in the design doc maps to a task — the per-article button (Task 1), the footer button (Task 2), `ShareRelayActivity`'s new mode (Task 3). The spec's own code sample for `ShareRelayActivity` and the footer button both used `.putExtra(...)` without `.setData(...)` on the per-article Share button's Intent — this plan adds that `.setData(...)` call (Task 1), since it's required to avoid a real, previously-hit Glance row-distinctness bug that the spec's simplified sample didn't carry over from the neighboring `openIntent` code it was modeled on.
- **A gap found during planning, not present in the spec:** the `Theme.NoDisplay` → `Theme.Translucent.NoTitleBar` manifest change (Task 3) — the spec described `ShareRelayActivity`'s new dialog behavior but didn't address the manifest theme's incompatibility with it. Documented explicitly here and in Task 3's own context, and called out again in Task 4 Step 5's verification (a crash here would only surface on-device, never at compile time).
- **Type consistency:** `EXTRA_SHOW_APP_SHARE_CHOICE`, `EXTRA_ARTICLE_URL`, `APP_URL`, `DOWNLOAD_URL` are defined once in Task 3 and referenced identically (same names) in Tasks 1 and 2's call sites, written before Task 3 lands — Task 2 is explicitly expected to leave the tree non-compiling for one commit until Task 3 completes, matching this plan's own bite-sized-task sequencing (same pattern used successfully in this repo's two prior feature plans).
- **Out of scope, confirmed not touched by any task:** the existing "Open article in: Browser | Share" setting's own behavior; any deep-linking into WhatsApp/Telegram/SMS specifically (the system share chooser already surfaces those); a vector "three connected circles" icon (explicitly declined in the spec in favor of text labels).
