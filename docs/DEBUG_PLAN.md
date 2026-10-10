# NewsFeed Widget — Full Debug Plan

Derived directly from `README.md` — every checklist item below maps to a feature or setting the README documents as working. This plan exists to verify that claim on a real device, not to re-derive requirements from the code.

**Test device:** a real phone (this project's own history shows RTL/locale bugs that never appeared on an emulator or in English-locale testing — a real device with a Hebrew-locale toggle is required, not optional).

**Before starting:** install the current build fresh (uninstall any prior version first if its signature might not match — see §0), and place **two "NewsFeed" widgets** before beginning: leave one on **When I tap an article: Expand in place** and set the other to **Focus (enlarge)** in its Settings, since several checks (§7, §8) compare the two modes. There is only one widget type in the picker now.

**Reporting convention:** for each item, record ✅ Pass / ❌ Fail (with repro steps + screenshot) / ⚠️ Couldn't test (with why). Don't mark anything ✅ without actually observing it — several items below exist specifically because a past session's own optimistic "should work" assumption turned out wrong on-device.

---

## 0. Installation & first run

- [ ] Download `NewsFeed-latest.apk` from the [latest release](https://github.com/Had-com/NewsFeed-widget/releases/tag/latest); confirm the "install unknown apps" and (if shown) Play Protect prompts appear and can be gotten past — this is expected, not a bug.
- [ ] Fresh install (no prior data) → place a widget → confirm `default_feeds.opml`'s feeds (Hebrew news, Telegram channels, English AI news) load automatically as the starting feed list.
- [ ] Confirm the widget's **default** appearance matches the documented default: **Glamour theme, Light variant, accent colors on**.
- [ ] Open the system "Add widget" picker → confirm exactly **one** "NewsFeed" entry under the app (no "NewsFeed Focus"), with the correct label. Cross-check: `adb shell cmd package query-receivers --brief -a android.appwidget.action.APPWIDGET_UPDATE | grep newsfeed` lists only `NewsFeedWidgetReceiver`.
- [ ] Place a widget of each type simultaneously → confirm both render independently without interfering with each other.

---

## 1. Sort & Filter section

Settings is now split into three sections (Sort & Filter / Display / Appearance) rather than one mega-section — the checks below are grouped to match the real screen order. For each control, change it, tap **Save**, and confirm the *widget itself* reflects the change — not just the Settings screen's own state.

- [ ] **Sort by** — Newest first, Oldest first, By feed (verify true round-robin interleave, not just grouped-by-feed), Unread first (verify unread articles float above read ones regardless of date).
- [ ] **Show** — All, Unread only (read articles genuinely absent, not just dimmed), Read only.
- [ ] **Refresh every** — cycle all 7 options (15m/30m/1h/2h/4h/6h/12h); confirm via `adb shell dumpsys jobscheduler | grep -A5 NewsFeedRefresh` that the scheduled interval actually changed each time (waiting out the real interval isn't practical for the longer options — the job-scheduler period is the verifiable proxy).
- [ ] **Keep articles for** — set to "1 day", let the widget accumulate articles older than that, refresh, and confirm they're pruned while the 300-article cap still applies independently (test "Forever" too — confirm no pruning happens regardless of article age).
- [ ] **Open article in** — Browser (opens default browser) vs Share sheet (opens Android's share chooser); confirm both mark the article read and trigger a refresh.

### Display

- [ ] **Font size slider** — drag to each labeled tier (Tiny/Small/Medium/Large/Huge); confirm headline/meta/header/footer text visibly scales.
- [ ] **Article font size slider** — same tiers, confirm it scales *only* expanded body text and does **not** move the headline size (the two sliders must be provably independent — change one, confirm the other's rendered size doesn't shift).
- [ ] **When I tap an article** — confirm the row is present after **Open article in**, offers **Expand in place** (default) and **Focus (enlarge)**, shows the one-line "− / +" hint only while Focus is selected, and that there is **no** "Background rows size" slider in either mode.
- [ ] **Live preview card** — confirm it visibly updates in real time as you touch the theme, font size, article font size, and article-length controls above it, without needing to Save first.
- [ ] **Expanded article** (length mode) — Subtitle only (~100 chars), First paragraph (~400 chars), Full article (fetches the real page, see §2).

### Appearance

- [ ] **Widget theme** — cycle all 10 (Auto, Lavender, Amethyst, Glassy, Simple, Aerospace, Data Science, Glamour, Black & White, Custom); confirm each renders its own documented palette/character, and confirm only Glamour shows the handwriting-font bitmap headlines (every other theme should show plain, crisp system-font text — if any non-Glamour theme shows the handwriting font, that's a real regression).
- [ ] **Theme variant** (Light/Dark) — confirm independent of the device's system dark-mode setting.
- [ ] **Custom theme colors** (Widget theme = Custom) — drag the font-color and background-color RGB sliders; confirm the live swatch and the widget itself pick up the resulting hex color, confirm Light/Dark variant swaps which color is text vs. background, and confirm all 11 consumed color slots update (not just background/text but also the gear icon, footer text, unread badge, dividers, and per-row accent dots — a prior bug left 5 of these stuck at stock Material3 purple).
- [ ] **Use theme accent colors** switch — on: every feed's accent collapses to one theme color; off: each feed's own chosen color reappears.
- [ ] **Background opacity slider** — 0% (fully see-through to wallpaper) through 100% (fully opaque); confirm smooth scaling, not just endpoints.

---

## 2. Article length / full-article fetch (Expanded article = "Full article")

- [ ] Tap **Load full article ↓** on a real article — confirm the RSS description shows immediately, then is replaced by real fetched page content shortly after (two-phase load, not one round-trip).
- [ ] Confirm the fetched text is genuinely the article body — not nav/ads/related-content boilerplate (this is what the Jsoup-based extraction rewrite specifically targets; check at least one site known for heavy sidebar/related-article clutter).
- [ ] Tap **Load more ↓** repeatedly — confirm each tap reveals another chunk, each chunk carries its own **Open in browser ↗** link, and it correctly stops (no dangling button) once the article's real end is reached.
- [ ] Try this on a Hebrew-language site (e.g. ynet) — confirm no mid-word breaks, no charset mangling (garbled characters), correct RTL rendering of the fetched text.
- [ ] Try it on rotter.net specifically — confirm charset is correctly detected even though the site declares it only via an in-page `<meta charset>` tag, not the HTTP header.

---

## 3. Find Feeds (search)

- [ ] Search a broad topic keyword (e.g. "technology") — confirm results list title, description, and subscriber count.
- [ ] Tap **+ Add** on a result — confirm it's added to your feed list and the button becomes **Added** (disabled) for that same result if you search again.
- [ ] Search something with no matches — confirm a clear "no feeds found" state, not a silent empty list or a crash.
- [ ] Search with the device offline — confirm a reasonable failure state, not a hang or crash.

## 4. Add Feed (manual)

- [ ] Add a valid RSS URL — confirm auto-fetched title, feed appears in the list.
- [ ] Add a valid Atom URL — same.
- [ ] Add an invalid/unreachable URL — confirm the inline error message, not a silent failure.
- [ ] Add a URL already in your list — confirm sensible handling (no silent duplicate).
- [ ] Add a public Telegram channel URL (`t.me/s/<channel>` or `t.me/<channel>` without `/s/`) — confirm it's recognized as a Telegram source (not treated as a broken RSS URL), auto-fetches the channel's display name, and articles later show at least two lines of headline text (a single-line-only headline was a live-reported bug, fixed by joining the post's first two lines).
- [ ] Import an OPML file exported from a real reader (Feedly/Reeder) — confirm feeds import with correct names/URLs, both flat and grouped/nested OPML structures.
- [ ] Export OPML — confirm the exported file opens correctly in another RSS reader.

## 5. Feed order & style (per-feed controls)

For one representative feed, exercise every control; spot-check the rest for at least the toggle-based ones.

- [ ] **Drag to reorder** (⠿ handle) — reorder feeds, Save, reopen Settings, confirm the new order persisted.
- [ ] **Color swatch** — tap to open the picker, confirm it shows the **12-color palette matching the currently selected widget theme** (switch theme, reopen the picker, confirm the palette itself changed); pick a color, confirm it applies to that feed's circle icon, accent stripe, source-name text, and unread dot everywhere in the widget.
- [ ] **Feed name (tap)** — opens the edit dialog; change the display name only (URL unchanged) — confirm no re-fetch needed; change the URL to a different valid feed — confirm it re-fetches and validates; change the URL to an invalid one — confirm the inline error and that the dialog doesn't silently save garbage.
- [ ] **× (remove)** — confirm the feed and its articles disappear from the widget after Save.
- [ ] **RTL / LTR toggle** — **do this specifically with the device's system locale set to Hebrew**, not just English — confirm the toggle's effect (stripe side, meta-row order, timestamp position) is driven purely by this per-feed setting and is NOT flipped or XOR'd by the system locale (this exact bug shipped once before).
- [ ] **TXT / IMG toggle** — IMG: confirm a thumbnail appears (sourced from `<media:thumbnail>`/`<media:content>`/`<enclosure>`/first `<img>` in the description, in that preference order if you can identify which one a given feed uses); TXT: confirm no thumbnail space is reserved.
- [ ] **Font dropdown** (Default/Serif/Mono) — confirm visible font-family change on that feed's headlines specifically, not other feeds, for every non-Glamour theme (Glamour always uses its own handwriting bitmap regardless of this setting — confirm that's still true, i.e. the dropdown has no visible effect while Glamour is the active widget theme).
- [ ] **B / I / U toggles** — each individually and at least one combination (e.g. Bold+Italic together) — confirm all apply simultaneously without one overriding another.

---

## 6. App Update section (self-update)

- [ ] With the device on an older build than the current release, open Settings → **APP UPDATE** → confirm "Currently on build N" shows the correct installed build number.
- [ ] Tap **Check now** → confirm a "Downloading update…"-style Toast, then Android's own install screen appears; complete the install; confirm the build number shown in Settings afterward matches the new build.
- [ ] With the device already on the latest build, tap **Check now** → confirm a "You're up to date (build N)" Toast and that nothing downloads (`adb shell ls -la /data/data/com.newsfeed.widget/cache/updates/` should show no freshly-modified file).
- [ ] Double-tap **Check now** quickly — confirm the button swaps to a spinner and back, and that a fast double-tap does **not** trigger two concurrent downloads (this was a real bug, fixed — worth a regression check).
- [ ] On Android 13+: with notification permission not yet granted, tap **Check now** — confirm the system permission prompt appears; deny it — confirm "Check now" still works via Toast regardless.
- [ ] Revoke "install unknown apps" for the package (`adb shell appops set com.newsfeed.widget REQUEST_INSTALL_PACKAGES deny`), tap **Check now** with an update available — confirm it routes you to that exact package's "install unknown apps" settings screen rather than failing silently; grant it, tap **Check now** again — confirm it proceeds straight through this time.
- [ ] Confirm the daily background check is actually scheduled: `adb shell dumpsys jobscheduler | grep -A2 NewsFeedUpdateCheck`. If you can force-fire it with a genuinely newer build available, confirm a system notification appears and that tapping it downloads + prompts install without requiring you to open the app first.
- [ ] Confirm the build number shown in Settings is the installed build on every placed widget (there is one APK and one widget type now).

---

## 7. Focus mode (per-widget setting: When I tap an article → Focus (enlarge))

- [ ] A newly placed widget defaults to **Expand in place**, and every widget that existed before this feature is still Expand.
- [ ] Set a widget to **Focus (enlarge)** and Save. Tap an article: it enlarges, its description/full text **auto-expands inline**, and **every other row stays exactly the same size** as in Expand mode (compare `uiautomator dump` bounds or screenshots of the same widget before and after the tap).
- [ ] Header controls in Focus mode: **N/M** shows the focused article's position and is accurate after each tap; **− / +** change the focused row's scale, clamped to 0.75×–2.5×, and the chosen scale is **remembered** (BUG-024): it stays when focus moves to a different article, when focus is cleared and re-set, after a refresh and after force-stop (check `focus_scale` in the DataStore). None of these controls ever appear on an Expand-mode widget. (The ▲/▼/✕ buttons were removed earlier and are not expected.)
- [ ] Tap the already-focused row again: focus clears and the widget looks like Expand mode.
- [ ] **Switch live, Expand → Focus:** on a widget with one article expanded, open Settings (⚙), switch to Focus, Save. Pull `files/datastore/appWidget-<id>.preferences_pb` (`adb exec-out run-as com.newsfeed.widget cat ...`) and confirm `expanded_article_id`, `last_tapped_article_id`, `focused_article_id` are absent (`focus_scale`, if it was set, is unchanged), the article count of `"isRead":true` in `articles_json` is unchanged, and the widget shows no expanded row.
- [ ] **Switch live, Focus → Expand:** with one article focused and a custom scale, switch to Expand, Save. Same DataStore check (the custom `focus_scale` is kept); no row enlarged; the previously focused article is **not** marked read by the switch itself.
- [ ] **Read on move-away, Expand mode:** tap article A then B: only A becomes read (`isRead` + `readAt` in DataStore), B stays unread. Repeat with an article that has no description (e.g. ynet flash / rotter) as A, and as B: same rule.
- [ ] **Read on move-away, Focus mode:** focus A then B: only A becomes read. Focus a description-less article and move away from it: it is marked read too.
- [ ] Old-config decoding (config JSON still carrying `focusBackgroundScale`): covered by `WidgetConfigTapModeTest`; not reproducible on-device (no surviving widget carries the key), record ⚠️ "unit-test only".
- [ ] Tapping a focused article's **Load more ↓** / **Open article →** / **Open in browser ↗** controls works exactly as on an Expand-mode widget.

---

## 8. Shared background work (single widget type) and orphan cleanup

- [ ] With one or more widgets placed: `NewsFeedRefresh` and `NewsFeedUpdateCheck` are scheduled (`adb shell dumpsys jobscheduler`), a `com.newsfeed.widget.CLOCK_TICK` alarm is pending and **no** `CLOCK_TICK_FOCUS` alarm exists (`adb shell dumpsys alarm | grep -c CLOCK_TICK_FOCUS` prints 0).
- [ ] With two widgets placed, remove one: jobs and `CLOCK_TICK` survive. Remove the last: the jobs are cancelled and `CLOCK_TICK` is gone.
- [ ] After removing a widget, its `files/datastore/appWidget-<id>.preferences_pb` is gone and its `widget_<id>` key is gone from `newsfeed_config` and `newsfeed_config_backup` (`grep -a -o "widget_[0-9]*"` on each file) — the `onDeleted` cleanup.
- [ ] **Update over a build that had a Focus widget placed** (no uninstall): the Focus widget disappears from the home screen, the standard widget keeps its feeds and renders, logcat has no `FATAL EXCEPTION` and no repeating error from the app. After one refresh (Save in Settings triggers it) the removed widget's `appWidget-<id>` file, config keys and `CLOCK_TICK_FOCUS` alarm are gone.
- [ ] If the only widgets before the update were Focus widgets: after the update and one worker run, `NewsFeedRefresh` and `NewsFeedUpdateCheck` are no longer scheduled; placing a NewsFeed widget re-arms them.
- [ ] Reboot with a widget placed: refresh, update-check and the `CLOCK_TICK` alarm resume without opening the app.

---

## 9. RTL / Hebrew locale

Switch the device's **system language** to Hebrew for this section specifically (Settings → General management → Language) — do not skip this by assuming English-locale testing generalizes; it has not, twice, in this project's history.

- [ ] Open the Settings screen itself — confirm it renders correctly RTL (labels, dropdowns, layout mirrored appropriately for a Hebrew-locale Android UI).
- [ ] With the system locale set to Hebrew, confirm a feed explicitly set to **LTR** still renders LTR, and a feed explicitly set to **RTL** still renders RTL — i.e., confirm the per-feed setting is NOT being flipped by the system locale in either direction.
- [ ] Confirm Hebrew news sites (ynet, rotter.net, N12, כאן, וואלה, גלובס) fetch successfully — if any comes back empty/blocked, check whether it's a bot-detection issue (the browser-like header spoofing not working for that specific site).
- [ ] Confirm article timestamps, the header's unread/total badge, and Focus Mode's N/M indicator all still read correctly (numerals aren't reversed or misplaced) under Hebrew locale.

---

## 10. Memory / scale edge cases

These map directly to real crashes/behaviors this project has hit and fixed before — they're regression checks, not speculative stress tests.

- [ ] Glamour theme + Font size slider at maximum (3.0×) — confirm no "Can't show content" / RemoteViews bitmap-memory crash; confirm the row-count message at the bottom ("Showing X of Y…") appears and is accurate rather than the widget silently failing to render.
- [ ] (Focus mode only) Glamour + Font size 3.0× + focus scale at its own maximum (2.5×) simultaneously on the focused row — the historically worst-case combination — confirm the same: no crash, a real (even if small) row count still renders.
- [ ] A non-Glamour theme (e.g. Simple or Data Science) with 200+ accumulated articles — confirm the widget can scroll to reach close to the real total (up to 300), not capped at a flat 60 the way Glamour legitimately is.
- [ ] With articles near the 300-item accumulation cap, confirm "Load more articles ↓" and the "Showing X of Y" messaging both make sense and match what's actually reachable.

## 11. Widget resizing

- [ ] Resize a placed widget down to its minimum (130×200dp) — confirm layout doesn't clip/overlap in a broken way.
- [ ] Resize up to its maximum (500×600dp) — confirm content scales/reflows sensibly rather than leaving large dead space or breaking the header/footer.
- [ ] Repeat in both tap modes (Expand and Focus).

## 12. Known-bug regression spot-checks

Quick confirmations that specific, previously-reported-and-fixed bugs haven't resurfaced:

- [ ] Hebrew headline text shows normal word spacing (no oversized gaps from a stray justification mode).
- [ ] After tapping a different article in Focus mode, the *previously*-focused row's highlight/size does not stick — only the newly-focused row is enlarged.
- [ ] The header's unread/total badge reflects only what's currently visible/scrollable, not the full up-to-300 accumulated store.
- [ ] Meta-row (feed name/timestamp) and article preview text are comfortably legible at default settings — not the thin/small rendering from before the readability fix.
- [ ] Non-Glamour headline text never renders in the Playpen Sans Hebrew handwriting font under any theme selection.

---

## 13. Bug Reports section (crash detection)

- [ ] With no crashes ever recorded, open Settings → **BUG REPORTS** → confirm "No crashes detected on this device." shows, not an error or blank section.
- [ ] Force a crash (e.g. temporarily throw from a callback, or use any reliable repro) → relaunch the app → open Settings → **BUG REPORTS** → confirm the crash is summarized (exception type/message, first/last seen) rather than showing a raw stack trace inline.
- [ ] Tap **Share crash report** → confirm Android's share sheet opens with a file attachment (not inline text) — this exists specifically to avoid a `TransactionTooLargeException` on large crash histories.
- [ ] Trigger the same crash signature again on an older build, then update to a newer build without hitting it again → confirm it's shown as solved (hasn't recurred since the update) rather than perpetually "open."
- [ ] Confirm this section's data is local-only — no network call happens when viewing or sharing it (there is no server component yet; this is Phase 1 only).

---

## 14. Release gate (standing rule, set 2026-09-20)

CI publishes every push to `main` as the rolling "latest" release that installed apps self-update from, so **a push that ships a new or changed user-facing feature is a release.** Before it:

1. **Verify each new feature on the real device** with hard evidence — DataStore pulls (`run-as ... cat files/datastore/appWidget-<id>.preferences_pb`), screenshots, `uiautomator dump`, logcat — never "it compiles" or "the review passed".
2. **Run the full QA plan** in [`docs/QA_PLAN.md`](QA_PLAN.md) (all features, all options, and their combinations; any bug it did not already test gets a new case added there) and record the results in this plan, one subsection per release below, in the format that plan defines.
3. **Check fully**: the feature itself, its edge cases, regressions in neighbouring features, **both tap modes (Expand and Focus)**, logcat clean.
4. **Report** results to the user, including anything not verified. If the device is offline or a check is impossible, say so and do not call it release-ready.
5. Security review before the push, and confirm the CI run is green after it.

Docs-only / CI-only pushes are exempt from 1–4 but still need step 5's CI check.

### Release log

#### Batch builds #127–#139 (2026-09-11 → 2026-09-20)

| Feature | What must be true | Verified how | Result |
|---|---|---|---|
| Unread-only 5s grace period | Just-read article stays ~5s then disappears, both widgets; `readAt` survives a refresh | DataStore + timed screenshots (2026-09-12) | ✅ |
| Focus header cleanup + read on focus-away | No ▲▼✕; −/+ bigger; A marked read only when B tapped | DataStore (2026-09-12, 2026-09-20) | ✅ |
| Mark read on expand-away (standard widget) | Press marks nothing; expanding another marks the previous; collapse marks nothing; description-less still marks on tap | DataStore + Unread-only timing (2026-09-20) | ✅ |
| Release notes before update | Manual check shows dialog with unseen notes; Later persists seen id; notification-tap screen | Dialog + DataStore verified (2026-09-13); notification-tap screen not witnessed | ⚠️ partial — see below |
| Share buttons | Per-article Share ↗ only when Open-in = Browser; footer Share dialog; no crash; narrow-width footer keeps ⚙ | On-device incl. failure-state narrow width (2026-09-13) | ✅ |
| CI fix (setup-android replaced) | Builds pass and publish | Runs #137–#139 green | ✅ |
| New default feeds (11, from Downloads/feeds.opml) | A brand-new widget starts with exactly these 11 feeds, Telegram ones fetch, no duplicates | Asset + built APK both have 11 outlines, one walla; runtime fresh-add NOT verified (would disturb home screen) | ⚠️ |
| Release notes Note 2 + Note 3 visible in update dialog | Dialog lists both unseen bullets | Update dialog (build 139) listed Notes 1, 2, 3 | ✅ |
| Read on move-away, all tap paths (`de130cb`) | Article marked read only when a different one is tapped, incl. description-less; Focus unchanged | Device: first tap/re-tap unread, tap other marks previous (DataStore readAt), Unread-only grace, refresh keeps state, Focus OK, logcat clean | ✅ |
| Known: intermittent widget placeholder after reinstall | Glance `No session available` once after `adb install -r`; recovered on tap | Not reproduced further | ⚠️ |
| Default feeds controls (Add / Reset) (`5bbd263`,`8474b57`) | Add merges without duplicates, idempotent; Reset confirms and replaces with 11; drafts until Save | Device: 6 added/5 skipped, 2nd tap 0/11, Reset dialog Cancel/Reset, no persist without Save, Save persists, config restored. Colors only verified from code | ✅ (⚠️ colors) |
| Known: removed feeds' articles linger in cache after Save (BUG-003) | Not fixed, cosmetic/unclear | Observed 48 orphan articles ~10s after Save | ⚠️ |
| Dissolve before removal (`0be0ea3`) | Just-read article: normal 2.5s, half dots, all dots, gone; only under Unread only; standard + Focus | Timed screenshots: PASS all; renders fire ~0.5s late (gone ~5.9s); title shrinks/row collapses at dissolve stages (cosmetic) | ✅ |
| Dots-then-erase dissolve + timing anchored to readAt (`e139cb5`,`6adfd3b`) | Full dots at 2.5s, 2/3 at 3.33s, 1/3 at 4.17s, gone ~5.6s; slow rows don't skip stages | Device timed frames + logcat: PASS all (heavy Kan row, light row, 2 articles, Focus, All, updates ≤5/article). Known: reinstall mid-dissolve can leave a row dotted; rows below jump up | ✅ |
| Dissolving row hides action buttons; dissolve resumes after process restart (`bdf1d8a`) | No Load/Open/Share on a dissolving row; row not stuck dotted after kill; bounded updates | Device: buttons hidden in 24 frames; kill -9 at +1.7s/+3.4s/+60s + CLOCK_TICK re-render → row gone, ≤3 updates, none after. Known: after `am force-stop`, tapping the host placeholder once left the dotted row until next tap (Glance `No session available`, same quirk as after reinstall); without a re-render trigger a killed process can't self-wake | ✅ (⚠️ force-stop) |

#### Focus as a per-widget setting (2026-09-20, HEAD 396b459, versionCode 901, device RFCR91J237W Android 15 One UI Home)

Update path: baseline `b16ce45` (versionCode 900) installed, then `adb install -r` of HEAD over it (same signature, no uninstall). Widgets on the device: 15 (standard, 8 feeds, Show = Unread only, Sort = By feed) and 18 (Focus).

| Feature | What must be true | Verified how | Result |
|---|---|---|---|
| Single widget in picker | Exactly one NewsFeed entry; no NewsFeed Focus | `pm query-receivers` lists only `NewsFeedWidgetReceiver`; `dumpsys appwidget` has 0 Focus receiver lines; One UI picker search "NewsFeed" shows one entry (count 1), screenshot p4.png | ✅ |
| Update from previous build removes Focus widget | No crash, standard widget intact, no log spam | `install -r` over 900 in 6.6 s; `dumpsys appwidget`: id 15 only, Focus receiver count 0; widget 15 rendered unchanged; no FATAL/ANR in the update window; only a transient system `AppWidgetSupplier: Couldn't find any provider` line | ✅ |
| Orphan cleanup | Removed Focus id's appWidget file and config keys gone; no pending CLOCK_TICK_FOCUS after one refresh | After Save on widget 15: `appWidget-18.preferences_pb` deleted, `newsfeed_config` keys widget_15 only (5092 -> 2591 bytes), only one pending `CLOCK_TICK` alarm (CLOCK_TICK_FOCUS appears only in alarm history). `appWidgetLayout-16/-18/-19` files were NOT cleaned at this point (leak, low); fixed afterwards: OrphanCleanup now also removes `appWidgetLayout-<id>` (BUG-021 fix, verified on device in the subsection below) | ✅ (layout-file leak fixed later) |
| Orphan sweep (N-26) / live widget never deleted (N-27) | Planted `appWidget-99999` is deleted; widget 15 untouched | Planted a copy, Save, 15 s later file gone, widget 15 config_json byte-identical | ✅ |
| Default and existing widgets are Expand | New and pre-existing widgets default to Expand; no Background rows size slider; hint only in Focus | Settings uiautomator on widget 15 and on a new widget 19: "Expand in place" default, row after "Open article in", no slider between DISPLAY and APPEARANCE, hint text appears only after choosing Focus | ✅ |
| Focus: only focused row enlarges | Non-focused rows same size as in Expand mode | uiautomator row heights before/after tap: non-focused 248/325/169 unchanged, focused 248 -> 610 | ✅ |
| N/M and -/+ header controls | Visible only in Focus with a focus; step 0.15, clamp 0.75, reset on focus change | Header shows N/M and - + only while focused; `focus_scale` 1.4, 1.55, 1.4 (steps), 0.75 after four "-" (clamp), key gone after focus changes; controls gone after focus cleared | ✅ (upper clamp 2.5 not exercised) |
| Live mode switch both directions | Transient keys cleared, read flags unchanged, pending not marked; Save without change keeps state | Expand->Focus: expanded/last_tapped keys gone, read count 62 -> 62; Focus->Expand with a focused article: focused/scale keys gone, read count unchanged; Save without change kept `focused_article_id`; final config_json byte-identical to original | ✅ |
| Read on move-away, both modes incl. description-less | Only previous article marked | Focus: focus B marks A (read 62 -> 63), focusing a description-less article works and marks previous (-> 64), tapping the focused row clears focus, marks nothing. Expand: first tap no mark (last_tapped = A), other tap marks A (readAt set), description-less B then C marks B (readAt set) | ✅ |
| Unread only dissolve in Focus mode | normal -> dots -> shrinking -> gone | Timed frames (~0.42 s apart): normal until ~3.2 s, full dots ~3.7 s, shorter dots 4.2 s / 4.7 s, gone in the next render (~9 s screenshot); matches `readAt` + 2.5 s within render latency | ✅ |
| Show = All / Read only | No dotting | All: article marked read stayed visible, 9 frames, no dots. Read only: listed read articles, no dots. Show restored to Unread only | ✅ |
| Two widgets, independent modes/state | 15 Expand and 19 Focus keep separate state | Added a second NewsFeed via the One UI picker (widget 19, Focus, 11 default feeds). Tapping/focusing in 19 changed only 19's DataStore keys; widget 15 tap-state dump identical before/after | ✅ |
| Removing the extra widget | Config/state files cleaned, widget 15 untouched | After Remove: `appWidget-19` and `widget_19` config key gone, widget 15 config byte-identical; BUT the process crashed (BUG-021 below) | ❌ crash |
| Telegram post: full text, no Load full article (`c718e74`) | Full post text shown; no Load button on Telegram; RSS still has Load | On widget 19 (Telegram N12chat/N12_News feeds) at focus scale 0.75: whole post shown (title lines + body), only Open article / Share buttons, no Load full article. Stored Telegram descriptions are 31-355 chars, so the >2000-char case was not exercised. Widget 15 has no Telegram feed and was not changed | ✅ (⚠️ no long post) |
| Load full article on RSS still works | Button present; result not replaced by junk | Present on Google News rows and ynet articles. Pressing it on a Google News row (redirect page) kept the original description, hid the button and showed "Open in browser". A successful real-page fetch was not witnessed | ⚠️ partial |
| Default-feeds buttons | Present, draft only | "Add default feeds" and "Reset to defaults" present; Add on widget 19 reported "Added 0 (11 already present)"; nothing saved on widget 15 | ✅ |
| Update dialog lists Notes up to 9 | Check now -> What's new | Check now returned "You're up to date (build 901)" (no newer release), so no dialog; note 9 present in docs but not seen in the dialog | ⚠️ NOT VERIFIED |
| Old config with `focusBackgroundScale` 0.25 | Decodes, renders normal | Unit test only; the on-device widget 18 had it but was removed by the update | ⚠️ NOT VERIFIED on device |
| Reboot resumes jobs and clock alarm | Jobs and CLOCK_TICK re-armed | Not run (no reboot per instructions scope) | ⚠️ NOT VERIFIED |

Bug found: **BUG-021 (High)** `NewsFeedWidgetReceiver.onDeleted` calls `goAsync()` a second time (Glance's own `onDeleted` already did), gets null, and `pending.finish()` in the `finally` throws `NullPointerException` at `NewsFeedWidget.kt:624`, a FATAL EXCEPTION in the app process every time a widget is removed. Cleanup itself completes before the crash; the in-app Bug reports section shows "NullPointerException 1x build 901". Repro: remove any NewsFeed widget from the home screen (long-press, Remove) and read `adb logcat -b crash`. Not a release candidate until fixed.

Restore statement: widget 15 ends with Show = Unread only, tap mode Expand (default), 8 feeds, `config_json` byte-identical to the backup in `C:	k4\`; extra widget 19 removed; read flags changed (62 -> 77 read) by the testing; widget 18 (Focus) was removed by the update as accepted.

Known unverified: `UpdateRelayActivity` screen from a live notification tap (Android notification dedup made this untestable via adb).

### BUG-021 fix verification (build 902 from HEAD b35ccbf, 2026-09-20)

Device RFCR91J237W, Android 15, One UI Home. Installed as versionCode 902 (the plain build is versionCode 1 and is rejected as a downgrade over 901; no uninstall was done).

| Test | Result | Evidence |
|---|---|---|
| N-30 remove extra widget, run 1 (widget 20, Focus, saved) | PASS | Before removal: `appWidget-20`, `appWidgetLayout-20`, `widget_20` key present. After: all gone; `logcat -b crash` empty; app pid 30127 unchanged; `OrphanCleanupWorker` SUCCESS |
| N-30 run 2 (widget 21, default settings) | PASS | Same result; crash buffer empty, pid unchanged, worker SUCCESS (2 of 2, deterministic) |
| Widget 15 untouched | PASS | `newsfeed_config` md5 identical to backup (whole file), `widget_15` value unchanged, still renders 10(10) Unread only; settings screen still shows Unread only / Expand in place |
| Leftover files of removed ids | PASS | None remain. Stale `appWidgetLayout-16/18/19` left from earlier runs were also swept by the cleanup |
| CLOCK_TICK alarm | PASS | Exactly one pending `CLOCK_TICK` alarm after each removal |
| Periodic work | PASS | WorkManager db: `NewsFeedRefresh` (15 min) and `NewsFeedUpdateCheck` (24 h) ENQUEUED; refresh tap on widget 15 ran `WidgetWorker` SUCCESS |
| In-app Bug reports | PASS | Still only the old entry (NullPointerException 1x, last seen build 901, 21:28); no new entry from build 902 |
| Tap-mode Expand behavior on widget 15 | NOT VERIFIED | Not exercised, to avoid changing the user's read state |

BUG-021 fix: VERIFIED on device. Restore: widget 15 `config_json` byte-identical; all four datastore files (config, read_status, GlanceAppWidgetManager, release_notes) have md5 equal to the backup in `C:	k5\`; test widgets 20 and 21 removed.

### Telegram rendering, worker guard, regression smoke (build 903 from HEAD e131be9, 2026-09-20)

Device RFCR91J237W, Android 15, One UI Home. Installed as versionCode 903 over 902 (`-PbuildVersionCode=903`, no uninstall). Extra widget 22 added through the One UI picker, saved with default feeds, then removed.

| Test | Result | Evidence |
|---|---|---|
| Fresh widget default feeds | PASS | Settings list: exactly 11 feeds, one walla, N12chat and N12_News present; DataStore had 40 Telegram articles (20 per channel), 204 total |
| Telegram stored description (`2962a97`) | PASS | No Telegram article has its title inside its description; max description 160 chars (cap 4096); 200-char headlines end with an ellipsis and the overflow continues in the description |
| Telegram row expand (First paragraph and Full) | PASS with note | Headline once, body only the remainder; no Load full article in Full mode either; a description-less post (N12_News) is not expandable and shows its full text in the title |
| RSS Load full article | PASS | Full mode, ynet row: button present; after pressing it the description was kept (not replaced) and Open in browser appeared |
| Worker guard (`d5bde3e`) | PASS | Refresh on widget 15: `WidgetWorker` SUCCESS; `NewsFeedRefresh` and `NewsFeedUpdateCheck` ENQUEUED before and after; after removing widget 22 both still ENQUEUED, 1 CLOCK_TICK alarm pending |
| Remove extra widget | PASS | `logcat -b crash` empty, pid 1407 unchanged, `appWidget-22`/`appWidgetLayout-22` gone, `OrphanCleanupWorker` SUCCESS, `newsfeed_config` md5 identical to backup (whole file) |
| Read on move-away, incl. description-less | PASS | First tap and collapse of the same row: still unread; tapping another row marked the previous one (DataStore `isRead`), description-less kan row too |
| Unread only dissolve | PASS | Timed frames: normal to ~3.3 s, dots ~4.1 s, fewer dots 5.0/5.8 s, gone ~6.6 s; header count 26 -> 25 -> 26 |
| Focus via second widget | PASS | Only the focused row enlarges, header 3/21 with - and +, + enlarges only that row, - restores |
| Update dialog notes | NOT VERIFIED on device | Check now: "You're up to date (build 903)"; Notes 8 and 9 in `docs/RELEASE_NOTES.md` read correctly |
| Logcat | PASS | No FATAL, no ANR, crash buffer empty throughout |

Observations: (1) N12chat posts end with an `@N12chat` signature line, so every N12chat post is expandable with a body that is only that handle; a 1-2 line post is therefore not always non-expandable (N12_News is). (2) Tap-to-expand took about 1.7 to 2.5 s to show after an adb tap (frames at 1.0 s and 1.7 s unchanged), collapse about 1.7 s. (3) The header count briefly showed 3/19 while a focus row was enlarged (row budget), then returned to 21. (4) The read store is shared across widgets, so reading in the extra widget also hides those articles in widget 15 under Unread only.

Restore: `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984 identical to the backup in `C:	k6\`. `newsfeed_read_status` gained 3 read marks from widget 22 and was rewritten from the backup (force-stop, write, md5 3089e962... verified); the app was restarted with the exported CLOCK_TICK broadcast. Widget 15's own cached article list was refreshed by the required refresh test, so its expanded ynet article from before the run is no longer shown.

### 14.x Build 904 verification: BUG-022 (collapse on description-less tap), 2026-09-21

Setup: Unread only, Expand mode, expanded article P above a description-less ynet flash A (P = ynet "Gil" article, last_tapped = P). Harness: continuous screenshots + uiautomator dumps + DataStore snapshots.

| Scenario | Result | Evidence |
|---|---|---|
| S3c run 1 (taps 0 / 3.8 s, same position) | PASS (fix) | Tap 1: last_tapped = A, expanded cleared, P isRead (readAt +105 ms). Layout settled once at ~1.5 s (A moved up ~440 px). No large slide at ~5 s; P left Unread only at ~5.7-6.1 s and the rows below shifted down 127 px (list clamped at its end). Tap 2 fell in the gap above "Load more": no action, no mark |
| S3c run 2 (taps 0 / 6.5 s, same position) | Expected consequence, not a regression | A had already moved up, so the same screen position now hits the row below (rotter B): B last_tapped, A marked read and left. Tapping A at its new position (run 4, y 1200 at 3.8 s) keeps last_tapped = A and A unread |
| S1 (description A, taps 0 / 1.2 s) | PASS | A expands at ~1.0 s, collapses after tap 2, P read at tap 1 and gone ~5 s |
| S2 (double tap 0.3 s, description-less A) | PASS | last_tapped = A, A not read |
| S3 (description-less, no expanded article, taps 0 / 1.2 s) | PASS | last_tapped set, no visual change, no reads |
| Show = All | PASS | Tap on description-less A collapses P, layout settles at ~0.65 s, nothing vanishes |
| Logcat | PASS | No FATAL, no ANR, crash buffer empty |

Observations: after tap 1 the layout shift is not instant (tap to updateAppWidget about 1.5 s in Unread only); the position of A changes at that render, so a second tap at the OLD screen position lands on a different row. This is inherent to collapsing an expanded row above the tapped one. Restore: `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984, widget 15 datastores rewritten from the backup.

### 14.y Perf finding: extra grace/dissolve renders under All / Read only, 2026-09-21

Device measurement (`C:	\qa9\summary_rows.json`): every read-marking tap scheduled 4 extra full widget re-renders (+2.6 / 3.4 / 4.3 / 5.1 s, each also rewriting `grace_check_tick`) via `UnreadGracePeriod.scheduleRefresh`, regardless of the Show filter. The dissolve and the 5 s removal only exist under Show = Unread only, so under All / Read only those renders changed nothing on screen: 5 renders per tap instead of the 1 needed (wasted work and battery). Not a functional bug.

Fix: `ToggleExpandCallback`, `NoOpTapFeedbackCallback` and `SetFocusArticleCallback` now read the saved config (`configJson`) inside their existing state update and call `scheduleRefresh` only when `shouldScheduleGraceRefreshForConfig()` is true (filter key = `unread`; missing/undecodable config keeps the old behaviour of scheduling). The render-time resume in `NewsFeedWidget` was already Unread-only. Switching to Unread only mid-grace is covered by that resume on the next render. Tests: `GraceRefreshPolicyTest`; QA case `I-14`. On-device re-verification pending.

### 14.z Build 905 verification: grace refreshes only under Unread only (c58baa2), 2026-09-21

Device RFCR91J237W (Android 15, One UI Home), widget 15, `logcat -v epoch`, `updateAppWidget` counted per read-marking tap (tap A, then tap B so A is marked read; each run = two taps, 9-11 s apart).

| Scenario | Renders per tap | Follow-ups at +2.6/3.4/4.3/5.1 s | grace_check_tick rewrites | Result |
|---|---|---|---|---|
| Show = All | 1 (whole run: 2) | none | none (appWidget-15 mtime changes only at the tap) | PASS |
| Show = Read only | 1 (whole run: 2) | none | none | PASS |
| Show = Unread only | 5 (1 + 4) at about +1.5/+3.3/+4.2/+5.1/+5.9 s (first tap) | yes | yes (4) | PASS |
| Unread only dissolve | frames: normal, full dots (+3.6 s), shrinking dots (+5.0/+5.7 s), gone (+6.4 s, count 10(10) to 9(10) to 10(10)) | | | PASS |
| All, mark A, switch to Unread only + Save at about +2.2 s | A stays visible until the post-Save render (+5.4 s), then shows at full-dots, gone by about +6.7 s | | | PASS |
| Unread only, mark A, switch to All + Save at about +3.4 s | dissolve stops, All list rendered at +6.2 s with no dots, no crash | | | PASS |

Notes: Save triggers a feed refresh, so the widget re-render after Save lags 1.4-3.2 s on this device (GC and DNS in logcat), during which the pre-Save frame stays visible (a dissolving row can still show dots after Save to All). Read only cannot mark an unread article (none visible), so its count comes from a tap that marked the stale previously-tapped article. Read-on-move-away semantics unchanged; logcat FATAL/ANR: none, crash buffer empty. Single widget only, so Focus-mode two-widget check skipped.

### 14.aa Build 906 verification: Telegram link URL (BUG-023, 7376d1b) and persistent Focus zoom (BUG-024, 636772c), 2026-10-07

Device RFCR91J237W (Android 15, One UI Home). Installed as versionCode 906 over 905 (`-PbuildVersionCode=906`, no uninstall), HEAD 636772c. Extra widget 17 added through the One UI picker (default feeds; later reduced to the N12_News feed only to reach older posts), then removed.

| Test | Result | Evidence |
|---|---|---|
| BUG-023 stored text | PASS | 11 of 11 Telegram posts with the label have `label (URL)`, e.g. `... קישור לכתבה (https://www.mako.co.il/news-israel/2026_q4/liveblog-4fbf25dc62711a1027.htm)`; no duplicate label, no bare label without URL |
| BUG-023 expanded row | PASS | N12_News post 48083 (Full): `קישור לכתבה (https://mobile.mako.co.il/news-reporters_chat)` visible once, only Open article and Share buttons, no Load full article |
| BUG-023 side effect (new bug) | FAIL | A long unbroken URL makes `TextBitmapHelper` auto-shrink the whole headline to a tiny size (widest word wider than the column). Every N12_News post whose headline contains the mako link renders tiny with the URL on its own line. N12chat signature became `@N12chat (https://t.me/N12chat)` |
| BUG-024 persistence | PASS | `focus_scale` 1.25 to 1.70 after three +; focusing a different article, clearing focus, footer refresh, force-stop + 2 CLOCK_TICK, Expand then Focus switch: all 1.70 |
| BUG-024 render | PASS | Second focused row uses the same line pitch (~96 px) as the first at 1.70 |
| BUG-024 clamp | PASS | `-` 1.70 to 1.55; `+` repeatedly gives 2.5 (clamped, stays 2.5); `-` repeatedly gives 0.75 (clamped, stays 0.75) |
| Read on move-away (Focus) | PASS | Moving focus 48089 to 48090 to 48091 marked each previous article isRead with readAt |
| Unread only dissolve | PASS | 48091 left Unread only after focus moved: normal to ~2.4 s, dots ~3.5-4.6 s, gone ~6.6 s, count 18 to 17 |
| Remove temp widget | PASS | First attempt; `appWidget-17` and `appWidgetLayout-17` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty |

Restore: `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984 identical; `newsfeed_read_status`, `appWidget-15`, `appWidgetLayout-15` rewritten from backup (force-stop, write, two CLOCK_TICK) and md5-identical.

### 14.ab Build 907 verification: Telegram link URL moved out of the headline (BUG-025, ebdb82e), 2026-10-07

Device RFCR91J237W (Android 15, One UI Home). Installed as versionCode 907 over 906 (`-PbuildVersionCode=907`, no uninstall), HEAD ebdb82e. Extra widget 18 added through the One UI picker (defaults: Glamour theme, Expanded article = First paragraph; later reduced to the two N12 feeds on that widget only, Sort = Newest first), then removed.

| Test | Result | Evidence |
|---|---|---|
| Stored text, all 40 Telegram posts | PASS | 0 titles contain `http`; 0 posts contain `(https://t.me/N12chat)`; N12_News posts with the mako link: 11, URL appears in description exactly once, in none of the titles. Two shapes: title ends `... קישור לכתבה` with bare URL in description (48091), and (chat-style posts, 48093) title without label with description `קישור לכתבה (https://mobile.mako.co.il/news-reporters_chat)` |
| N12chat signature | PASS | description is `@N12chat` (also after the body text for long posts) |
| Collapsed headline size, Glamour | PASS | 48091 (22:13) and 48093 (22:57) render at the same size and line pitch as neighbouring rows (old build: tiny, `q11_fixA_tiny_title.png`) |
| Collapsed headline size, Simple theme | PASS | 48087 and 48086 end `... קישור לכתבה`, same size as neighbours |
| Expanded, Glamour, First paragraph | PASS | 48091: URL under the image wrapped over 2 lines, headline unchanged |
| Expanded, Glamour, Full | PASS | 48093: `(https://mobile.mako.co.il/news-reporters_chat) קישור לכתבה` on one line, normal size |
| Expanded, Simple, Full and First paragraph | PASS | 48087: URL wraps over 2 lines (second line holds only `.htm`), headline unchanged |
| No Load full article on Telegram | PASS | only Open article and Share on 48091, 48093, 48087, 228927 |
| Open article | PASS | VIEW intent for `https://t.me/N12_News/48091` into Chrome, about 0.15 s |
| Logs | PASS | crash buffer empty, no FATAL or ANR, `crash_log.json` md5 unchanged |
| Remove temp widget | PASS | First attempt; `appWidget-18` and `appWidgetLayout-18` gone, `newsfeed_config` back to 720a2dd2... on its own |

Notes: the widget caps rows ("Showing 26 of 40 · reduce font size to see more"), so a 22:13 post is only reachable by reducing feeds or sorting; in the One UI feed list the red x only responds near the glyph (tap x of about 786), tapping the centre of the reported bounds opens Edit feed.

Restore: `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984 identical; `newsfeed_read_status`, `appWidget-15`, `appWidgetLayout-15`, `GlanceAppWidgetManager`, `release_notes` rewritten from backup (force-stop, write, two CLOCK_TICK) and md5-identical.

### 14.ac Story threads (group similar stories), device verification 2026-10-08

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD f195f15, installed as versionCode 908 over 907 (`-PbuildVersionCode=908`, no uninstall). Feature: `WidgetConfig.groupStories` (default on), `data/StoryThreads.kt`, `data/ThreadRows.kt`, `glance/ThreadState.kt`, `ToggleThreadCallback`, whole-thread read marking. Spec `docs/superpowers/specs/2026-10-07-story-threads-design.md`, plan `docs/superpowers/plans/2026-10-07-story-threads.md`, QA cases `docs/QA_PLAN.md` section O. Run by the `mobile-qa` agent as the plan's Task 9 device gate (P0 cases O-01 to O-07 and most of the P1 cases); the full unit-test run, the security review and the remaining P1/P2 cases are not part of this entry. Widget 15 (Unread only, Expand, no `groupStories` key = ON) was only observed and then restored; all interaction was on temporary widget 19 (added through the One UI picker, default 11 feeds, Glamour, Sort changed to Newest first, later Show / tap mode / theme / font changed per test), removed at the end. Screenshots and harness output are in `C:\t\` (`o03_after.png`, `o05_sheet.png`, `o05f_sheet.png`, `o05all_sheet.png`, `o05n_sheet.png`, `o15_focus_lead.png`, `o15_focus_member.png`, `o02_exp.png`, `o06_off.png`, `theme_simple.png`, `o20_exp.png`, `o20_fonts.png`, `rows_*.json`).

| Feature | What must be true | Verified how | Result |
|---|---|---|---|
| Threads form (O-01) | Same story from 2+ feeds = one lead row (newest) with `+N ▾`; members hidden | uiautomator + `articles_json` | PASS: Sort Newest, Show All, 228 stored articles. Lead N12_News 23:47 `+1 ▾` is newer than its member N12chat 23:41, and the member appears at no other slot. Header counts articles (17 = 10 rows + `+1` + `+6`). Real threads in the list below |
| Badge toggles, does not fire the row (O-02, O-22) | Pill expands/collapses members, state in `expanded_thread_ids`, no read/tap-state change | uiautomator bounds, DataStore pulls | PARTIAL: behaviour PASS, size FAIL. About 60 badge taps: only `expanded_thread_ids` changed (also with a stale `last_tapped_article_id` set and with read articles present; no read change, no `expanded_article_id` / `focused_article_id` change). Taps at both inside corners of the bounds toggled; taps 12 px outside left, right and below hit the row instead. Bounds 77 x 51 px = 27 x 18 dp (450 dpi) at default font, 158 x 113 px = 56 x 40 dp at font 3.0, both under the 44 dp in O-22 (finding F-1). LTR row not verified (no English cross-feed duplicates in the live data) |
| Whole-thread read, Expand and Focus (O-03, O-04, O-07) | All members `isRead` with one `readAt`; moving inside a thread marks nothing | DataStore pulls | PASS. Expand: lead, member, lead taps marked nothing (`last_tapped`/`expanded_article_id` followed); tapping an unrelated article then marked lead 48096 + member 228930 with one readAt 1791408243507, both ids in `newsfeed_read_status`. A 7-article thread (+6) got one shared readAt 1791408557956. Focus: lead, member, lead, clear marked nothing; focusing an unrelated article then marked a 3-article thread with one readAt 1791408778379, and a second one (48092/228926/48093) with 1791408870523 |
| Unread only dissolve (O-05, O-23) | All rows dissolve in lockstep and leave together; ≤ 5 renders per read | timed screenshots, logcat render count | PASS with one render-count deviation. Expand, +6 thread expanded: lead and member rows normal to about 2.2 s, dots about 3.3 s, shrinking 4.2 to 5.0 s, gone about 5.6 s, together; badge hidden during dissolve; 5 `updateAppWidget` renders in total (the tap render at 1.0 s plus 4). Focus, +2 thread: same stages, gone about 5.8 s, 6 renders in total (1.77, 2.04, 3.24, 4.27, 5.09, 5.95 s; the non-thread Focus run had 5), so one more than the single-article case that O-05 asks to match (finding F-9). Show All: a read thread (+1) kept its rows and badge for the whole capture, 1 render, no dissolve. Non-thread article in Focus: dots, gone about 6.3 s (unchanged). No Load full article / Open article / Share buttons seen on dissolving rows |
| Switch off (O-06, O-08) | Exactly the old list; `"groupStories":false` persisted; on again restores | uiautomator, DataStore | PASS with one caveat. New widget: switch ON by default; Back without Save left the config untouched (no key). OFF + Save: `config_json` and `newsfeed_config` carry `"groupStories":false`; 21 flat rows equal to the top 21 unread by newest computed from `articles_json`, in order; 0 badges, 0 indented rows; header `21(21)`; `expanded_thread_ids` kept in DataStore. ON + Save: key absent again in both files, badges back. Not shown visually: that an unread expanded thread comes back expanded (only the stored ids were checked) |
| Counts and budget (O-09, O-10, O-20) | Header counts articles; limits and Load more count rows; no memory error | uiautomator, logcat | PARTIAL. Header counts articles (collapsing three threads moved `11(26)` to `15(35)`); `Showing 26 of 209` (Glamour) and the 30-row Simple cap count rows including expanded members; Load more went 10 to 20 to 30. Finding F-3: with `visible_article_count` 10 and the lead as the 10th row, tapping the badge showed `+6 ▴` with no members (all cut) and the header fell from 17 to 11. O-20: Glamour, font 3.0 / article font 3.0, thread expanded: renders, no memory or "Can't show content" error. Not verified: a 12-member thread (largest seen is 7 rows) and Focus scale 2.5 on a member |
| Sort/filter/read-class (O-11, O-12, O-13) | Thread at lead slot; never half a thread; newcomer is a new thread | uiautomator, DataStore | PARTIAL. O-11: Newest and Unread first checked (thread at the lead slot, members never separate); Oldest and By feed not run. O-12 PASS: Read only lists read threads whole (+1, +6, +1, +2 with all members), Unread only lists unread threads whole, a read thread is absent as a whole from Unread only. O-13 NOT VERIFIED |
| Tap modes and mode switch (O-14, O-15, O-16) | Members expand/focus; collapse cleans pointers; mode switch keeps expanded threads | DataStore pulls | PASS for what was run. O-14: expanding a member moved `expanded_article_id` from the lead to the member while the thread stayed in `expanded_thread_ids`. O-15: member focused (`o15_focus_member.png`), lead focused with `+2 ▴` intact at normal size (`o15_focus_lead.png`). O-16: Expand to Focus + Save cleared `expanded_article_id` and `last_tapped_article_id`, kept `expanded_thread_ids`. Not run: collapsing a thread while one of its members is expanded or focused |
| Refresh / multi-widget / remove (O-18, O-19, O-24, O-25) | Read state and expanded state survive refresh; independent widgets; removal cleans up | DataStore pulls | PARTIAL. O-18: footer refresh (234 articles to 234) and force-stop + 2 CLOCK_TICK kept `expanded_thread_ids` and the rendered expansion; read marks kept their readAt; "newer matching article becomes the new lead" not observed. O-19: finding F-5, ids of read threads are not pruned, cap 30 not tested. O-24: widget 15 keys (`expanded_article_id`, `last_tapped_article_id`, no `expanded_thread_ids`) unchanged by all work on widget 19. O-25: after removing widget 19, `appWidget-19` and `appWidgetLayout-19` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty |
| Quality on a real day (O-21) | No wrong groups of consequence | group listing | PASS with S3 notes: 7 real threads reviewed (below), 6 correct, 1 over-grouped event thread, several missed duplicates. No crash, no lost article. Only a partial listing (threads inside the top rows of the widget), not a full day |

Real threads seen (lead, members, feeds), live data of 2026-10-07 and 2026-10-08, Sort Newest:

1. N12 `איזנקוט: "ערוץ המשטר התעלם באופן מביש מטקס המשפחות..."` 23:47 + N12chat 23:41 (Eisenkot leaving the families' ceremony). Correct.
2. N12chat `מבזקן 12 בנט ואיזנקוט יושבים זה לצד זה בטקס הזיכרון הלאומי` 23:10 (+6): N12chat 22:15 (applause for Omri Shaproni), N12chat 22:14 (same text), N12chat 21:49 (Rabbi Kalmanson Kaddish), N12chat 21:44 (the crowd), ynet-flash 21:18 (ceremony started), N12 21:17 (live blog). One event, several sub-stories: over-grouped, S3 false positive (the 22:14 / 22:15 pair are true duplicates).
3. Globes-all `אלי שרעבי: "רק אם נזכור באמת..."` 23:00 + Globes-Israel 22:27, same article id in two feeds. Correct.
4. N12 `עמית אטיאס @ צ'אט כתבים ... "אתם נותנים לי את הכוח להמשיך לחיות"` 22:57 (+2) + N12chat 22:57 + N12chat 22:57. Correct.
5. ynet-news `הדרישה לוועדת חקירה, בטקס 7/10: "לזכור מה קרה..."` 00:13 (+2) + ynet-flash 22:51 (`אלי שרעבי בסיום הטקס: "צריך לחקור..."`) + N12chat 22:49 (Atias, Sharabi at the ceremony). Correct (same quote).
6. Kan `עמרי שפרוני, שורד הטבח, בטקס המשפחות: "ללא חקירת האמת..."` 22:30 (+2) + N12 22:13 + ynet-flash 22:10. Correct (Telegram plus RSS, O-17).
7. Kan `טראמפ: לעולם לא נשכח את 7 באוקטובר` 00:45 (+1) + N12chat 00:29 (Trump post, inferred from the row that left the flat list when the thread collapsed). Correct.

Widget 15 (Unread only) showed no thread among its 10 unread rows after the update.

Missed duplicates (S3, not wrong groups): Kan 23:51 and ynet-flash 23:36 (Maduro indictment), ynet-flash 23:25 + N12chat 23:07 + N12chat 22:58 (tanker attacked near Qatar), ynet-news 23:03 and ynet-flash 22:48 (Syria aid to Saudi Arabia), ynet-flash 00:30 and N12chat 00:29 (Trump post).

Regression sample on widget 19 (Expand and Focus, build 908):

| Test | Result | Evidence |
|---|---|---|
| BUG-022 description-less tap collapses another expanded article | PASS | expanded `hykvsa7img`, then tap on a description-less Kan row: `expanded_article_id` cleared, `last_tapped_article_id` moved, one render |
| BUG-024 focus zoom persists | PASS | `focus_scale` 1.70 after three +; unchanged after focusing a different article and after force-stop + 2 CLOCK_TICK; a footer refresh with focus cleared also kept it; six `-` gave 0.80 |
| BUG-025 Telegram rows normal size | PASS | N12 / N12chat rows the same size as neighbours in Glamour (`o03_after.png`, `o15_focus_lead.png`) and Simple (`theme_simple.png`) |
| Read on move-away, non-thread articles | PASS | tapping row B marked the previous `last_tapped` article read (readAt set) in both modes |
| Dissolve without threads | PASS | `o05n_sheet.png`, gone about 6.3 s |
| Other theme | PASS | Simple: badge, member gutter and Load more render (`theme_simple.png`); Glamour is the default used elsewhere |
| RTL layout of badge and gutter | PASS with note | Hebrew rows: the badge sits on the physical left right after the time; member rows are indented 33 px with a beige bar on the physical left (spec: 14 dp left gutter; in Hebrew rows that is the end side, finding F-6) |
| Logs | PASS | persistent logcat from the second half of the session (114k lines) plus per-step checks: no FATAL, ANR, `TransactionTooLarge`, "Can't show content", bitmap-size or `cannot have more than` entries; crash buffer empty; `crash_log.json` md5 unchanged (729b6f10...) |

Tap-to-render latency (adb `input tap` to the launcher host's `perform updateAppWidget`, warm unless noted): row tap on an expandable article 0.62 to 0.82 s (before the feature about 0.6 s); badge tap 0.83 to 1.07 s; the first tap after an idle minute 1.1 to 1.5 s for both rows and badges; footer refresh 3.8 to 6.0 s (network). The badge path is about 0.2 to 0.3 s slower than a row tap (suspected cause: it decodes the stored article list and regroups before writing the toggle).

Findings (none blocks the P0 behaviour; no source changed):

* F-1 (Medium, O-22): badge tap target 18 dp high (27 x 18 dp) at default font, 40 dp at font 3.0; the case asks for at least 44 dp. Taps work only inside the exact bounds; 12 px outside hits the row. Repro: any lead row, uiautomator bounds of `+N ▾`.
* F-2 (Low): at font 3.0 (Glamour) the lead's meta line overflows and the time is clipped (`):45` instead of `00:45`) when the badge is present (`o20_exp.png`); rows without a badge are fine.
* F-3 (Low/Medium UX, O-09/O-10): when the expanded lead sits at the end of the row budget (10 rows, or the memory cap), the badge flips to `▴` but no member is visible and the header total drops (17 to 11 with `+6`), so the tap looks like it did nothing; Load more reveals the members. By spec (the budget counts rows) but surprising.
* F-4 (Low): a story that two feeds return with the same article id (Globes all + Globes Israel) becomes a thread whose lead and member share an id, so both rows expand together in Expand mode and are read together; whole-thread read still works. Pre-existing duplicate ids made visible by threads.
* F-5 (Low, doc): O-19 says a read thread's lead id is pruned once it has left (Unread only); `toggleThreadExpanded` prunes only ids that no longer lead a 2+ thread in the stored articles, so ids of read threads stay (they still show under All and Read only). Five stale ids were kept in the run. Either the text of O-19 or the code should change.
* F-6 (Low): RTL feed rows indent members on the physical left (end side) with a left bar; it reads as a bar, not as nesting under the right-aligned lead. Matches the spec text.
* F-7 (Low, UI): in widget settings the Focus hint text sits directly above the "Group similar stories" label with no gap (`set_on.png`).
* F-9 (Low, O-05): the Focus-mode thread read produced 6 renders (an extra one 0.27 s after the tap render) against the 5 of a non-thread read and of the Expand-mode thread read; cause not investigated, no visual effect.
* F-8 (S3, O-21): one over-grouped event thread (+6, ceremony) and the missed duplicates listed above.

Restore: `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984 identical (it returned to that value on its own when widget 19 was removed, and was re-written from backup anyway); `newsfeed_read_status` (3089e962...), `appWidget-15` (b7f3ffbf...), `appWidgetLayout-15` (eb51b9b9...), `GlanceAppWidgetManager` (ca6ea36d...) and `release_notes` (27249a21...) rewritten from `C:\t\bk13\` (force-stop, write, two CLOCK_TICK) and md5-identical. Build 908 left installed (`versionCode=908`); widget 15 renders on it. Temporary widget 19 removed through the launcher; `appWidget-19` and `appWidgetLayout-19` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty.

### 14.ad Thread rail / +− badge visual check (build 909), 2026-10-08

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD 8c603c1 (spec "Revised 2026-10-08", QA cases O-28, O-29, O-30), installed with `-PbuildVersionCode=909` over 908, no uninstall, source untouched. All interaction on temporary widget 17 (4 rows, added through the One UI picker, default 11 feeds, Sort changed to Newest first; later theme, font, Show, tap mode changed per test). Screenshots in `C:\t\q14\`. Measurements are screen pixels (450 dpi = 2.81 px/dp); the host renders the widget at 0.83 scale, so 3 dp = about 7.0 px.

| Check | Result | Evidence |
|---|---|---|
| (a) Collapsed thread: `+N` badge, no rail | PASS | `t0.png`, `t0_row3.png`: `+1` pill after the time, no rail pixels left of the row. Single rows have none |
| (b) Expanded: badge `−`, one continuous left rail | PASS with a 1 px seam (BUG-1) | `t1.png`, `t5.png` (lead + 3 members), `t8.png`, `f3e.png`. Badge text is U+2212 (uiautomator). Rail x = 70..75 full + 76 partial (6.7 px, about 2.85 dp), flush with the widget's left edge, in Hebrew/RTL rows; colour (168,120,64) on (253,250,246). `t5.png`: rail y 292..1067 unbroken through lead + 3 members and exactly ending with the last member. Also continuous beside a lead whose article body is expanded (`thr2b.png`) |
| (b) LTR row | NOT VERIFIED | no thread with an English/LTR lead in live data (the Russian Kan row is not in a thread). The rail is a Row child, independent of text direction, but not seen on an LTR row |
| (c) Thin separators | FAIL: separators are not painted (BUG-2) | Normal rows: 8 to 9 px line (about 3 px hairline (237,228,212) + 5 px shadow (202,200,197)), text-to-text gap 44 px. Inside an expanded thread (`t3_zoom.png`, `t5.png`, `simple_exp.png`, `glam_dark2.png`): NO line at all between lead and member or member and member, gap 21 to 24 px (48 to 55 % of normal, so the spacing part is right); item bounds reserve only 2 px below each row. After the last member (before the next unrelated row) also NO line (expected the normal one), gap 41 px, item reserves 4 px instead of 8. Pixel scan (`hl.py`) found uniform separator rows only above the lead |
| (d) Single rows and groupStories OFF unchanged | PASS | single-row separators in `fin.png` (pre-change) and now identical (8 px, same colours); OFF + Save: `"groupStories":false` in `config_json`, 8 scrolled screens `off_0..7.png`: 0 badges, 0 rail pixels, flat 20 rows; ON + Save: key absent again |
| (2) Rail on the physical left in RTL | PASS | every row in the run is Hebrew/RTL; rail on the left in all |
| (4) Font 3.0 / article font 3.0 | PASS with 1 px seam | `f3d.png`, `f3e.png`: rail y 809..1170 and 268..1170 gap-free (seam at 820 colour (210,185,155), 1 px). Known F-2 (clipped time `7:45`) still present, not new |
| (5) Themes | PASS | rail visible: Glamour light (168,120,64) / white-ish, Glamour dark (212,165,116) on (38,25,15) (`glam_dark2.png`), Simple black on white (`simple_exp.png`), Black & White light and dark (`bw_exp.png`, `bw_dark.png`), Lavender, Amethyst, Glassy, Aerospace, Data Science (`themes_all.png`, `th_*.png`). Weak case: Auto (system) with system dark (`auto_dark.png`): rail (208,188,255) on a white widget background, contrast about 1.7:1 (BUG-3, caused by the existing palette/background mismatch: its text is also pale grey on white). Not run: Custom. System dark set with `cmd uimode night yes`, restored to `no` (`mNightMode=1`) |
| (6) Thread last in list / cut by the row budget | PASS | `t8.png`, `t7.png`, `thread_last_cut.png` (`Showing 25 of 212`), `focus_thr_lead_cut.png` (Focus mode): rail ends at the last visible row (the lead when all members are cut, F-3 behaviour unchanged), Load more revealed the member and the rail extended (`t9.png`); no crash |
| (7) Badge tap toggles, not the row | PASS | 12+ taps: only `expanded_thread_ids` changed; `last_tapped_article_id` and `expanded_article_id` unchanged (also with both set). Touch target: clickable 77 x 50 px (27 x 18 dp) expanded `−`, 89 x 50 px collapsed `+3`, 141 x 112 px at font 3.0; a tap in the transparent margin 14 px left of the text toggled. Still below 44 dp (known F-1). Row tap on a thread lead still expands its article |
| (8) groupStories OFF then ON | PASS | see (d) |
| (9) Unread only dissolve with rail | PASS | `dis3_sheet.png`, `dis3_left.png`: an expanded 4-row thread read by moving to an unrelated row: rows normal to about 3.2 s, dots at about 3.9 s, shrinking 4.3 to 5.5 s, gone by 6.7 s (capture times include about 0.4 s screencap lag). The rail shrinks with the rows (rail px 735, 609, 548, 486), badge hidden from the dots stage, rail pixels 0 in the first gone frame: no stub. An earlier 3-article thread (read while off-screen) dropped the header count 29 to 26 |
| (10) Logs | PASS | logcat since install: no FATAL EXCEPTION, ANR, `Can't show content`, `TransactionTooLarge`, `cannot have more than`, bitmap or OOM entries; no E/W lines from the app or Glance; crash buffer empty; `crash_log.json` md5 729b6f10... unchanged |
| (11) Regressions | PASS | single rows unchanged (above); BUG-022: expanded Walla row then tap on a description-less Kan row cleared `expanded_article_id` and moved `last_tapped_article_id`; BUG-024: three `+` gave `focus_scale` 1.70, unchanged after focusing another row; BUG-025: Telegram rows (N12, N12chat) the same size as neighbours in `t5.png`, `glam_dark2.png`. Latency (tap to first `updateAppWidget`): badge warm 0.74 to 0.86 s, first after idle 0.98 to 1.33 s; row tap warm 0.75 to 0.92 s, after idle 1.1 to 1.4 s (baseline 0.6 to 0.8 and 1.1 to 1.5 s) |

Findings:

* BUG-1 (Low, cosmetic): a 1 px lighter line in the rail at some item boundaries, 7 to 35 % lighter (`t1.png` y 1057 (180,138,89); `t8.png` y 503, 673, 913; `f3e.png` y 820 (210,185,155), `simple_exp.png` y 434), depends on scroll offset (none at all in `t5.png`). Rounding between list items at the 0.83 host scale; visible only when zoomed (`t8_seam.png`, `f3e_seam.png`), not a gap (background never shows through).
* BUG-2 (Medium, requirement (c)): inside an expanded thread, and after its last member, the divider boxes are not painted (no hairline, no shadow strip), on every theme tried. The thread block is no longer delimited from the next row (`t3_zoom.png`), and the row after the thread has no line above it although every other row does. Layout reserves about the hairline height only, so the divider Boxes in the `Row > Column(defaultWeight)` rail wrapper seem to collapse or be clipped (cause not investigated, source not touched). Repro: Glamour, expand any thread, scroll to its last member.
* BUG-3 (Low, observation): Auto (system) theme with system dark mode shows a pale lavender rail (and `−` pill) on a white widget background; the text is equally low contrast, so it is the existing day/night mismatch of that theme rather than the rail. Not compared with build 908.
* Observations (not new): known F-1 (small badge target), F-2 (time clipped at font 3.0), F-3 (lead expanded at the budget end shows no members). Black & White shows the badge without a pill, so `−` is a bare dash (pre-existing style).
* Harness hazard: slow short swipes (200 px, 450 to 800 ms) inside the widget trigger the launcher's widget drag and moved the temporary widget; BACK restored the selection. No app issue.

Restore: temporary widget 17 removed through the launcher, `appWidget-17` and `appWidgetLayout-17` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty. From `C:\t\bk14\` (force-stop, write, two CLOCK_TICK): `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984, `newsfeed_read_status` (3089e962...), `appWidget-15` (0c1ba708...), `GlanceAppWidgetManager` (ca6ea36d...) and `release_notes` (27249a21...) md5-identical; `appWidgetLayout-15` is rewritten by every render (now ed89f86f..., the backup was dd478b30...), widget 15 renders its 10 unread rows as before. Build 909 left installed (`versionCode=909`).

### 14.ae Separator fix verification (build 910), 2026-10-08

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD ffdf61e ("Paint the thread separators: fix divider width inside the rail wrapper"), clean working tree, built with `-PbuildVersionCode=910`, `adb install -r` over 909 (no uninstall), source untouched. Focused re-check of BUG-2 from 14.ad. All interaction on temporary widget 18 (added through the One UI picker, default 11 feeds, Glamour, Sort changed to Newest first; later theme, font, Show, Sort changed per test). Screenshots in `C:\t\q15\`. Measurements are screen pixels (450 dpi; the host renders the widget at about 0.83 scale: 1 dp = about 2.3 px, so a 1 dp line is 2 px and a 2 dp shadow 5 px).

**Result: BUG-2 is NOT fixed in build 910.** No separator is painted between lead and member, between members, or after the last member, on every theme and font size tried. Single rows are unchanged.

| Check | Result | Evidence |
|---|---|---|
| (1) Thin line between thread rows, normal line after the last member | FAIL | Glamour light, lead 07:55 + 4 members (`t4a.png`, `t4b.png`, `t4b_zoom_member_boundary.png`): `hl.py` finds a uniform separator only above the lead (8 px: 2 px line (237,228,212) + 5 px shadow (202,200,197)); none at the 4 lead/member and member/member boundaries (pixel column x=900 is the plain background (253,250,246) across every boundary). After the last member before an unrelated row (thread 08:21 + 1 member then 08:18, `e2.png`, y 405 to 420 and 640 to 660 at x=900): background only, no line, no shadow. Gaps measured by uiautomator (previous body bottom to next time top): lead to member and member to member 20 to 21 px, after the last member 41 px; normal single-row gap 44 to 45 px (`coll2.png`: 44, 45, 44, 44). So the spacing is right (about 46 % inside the thread) but there is nothing painted in it |
| (1) Rail one continuous line | PASS with the seam (BUG-1) still there | Rail x 70 to 75 full (168,120,64) + x 76 partial, 293 to 1058 gap-free through lead + 4 members (`t4b.png`); background never shows. A 1 px lighter row sits at EVERY item boundary in the captures with a thread: `t4a.png` 4 of 4 ((180,138,89), (187,149,104), (187,149,104), (180,138,89)), `t4b.png` 4 of 4 (same values), Glamour dark `gd0.png` 2 of 2 ((174,134,94), (154,118,82) on (212,165,116)), font 2.93 `f3_thr.png` 1 of 1 ((154,118,82)). Same size as in 909 (7 to 35 % lighter), but present at all boundaries here (909's `t5.png` had none), so not worse in size, possibly more frequent |
| (2) Single rows and groupStories OFF unchanged | PASS | Single-row separators in `bot.png`, `t4a.png`, `off0.png`: 8 to 9 px, line (237,228,212) + shadow (202,200,197), identical to `fin.png` and `q14\final15.png`. OFF + Save: `"groupStories":false` in `config_json`, flat rows, 0 badges, 0 rail pixels (`off0.png`); ON + Save: key absent again |
| (3) Thread last in list / cut by the row budget | PASS (rail), separators as (1) | Last in list with Load more below (`t4b.png`): rail ends exactly with the last member (y 1058), no separator after it (last row, by design). Cut by the budget: `visible_article_count` of the temporary widget set to 21 by editing its DataStore file (`q15vis.py`, not source), so the lead 08:21 is the last row and its member is cut (`cut21.png`): `−` badge, rail y 850 to 1058 = the lead item, no stub into the Load more button, normal 9 px separator above the lead (F-3 behaviour unchanged) |
| (4) Themes | FAIL for separators on all three, rail PASS | Glamour light (`t4b.png`), Simple light (`simple_exp.png`: lead 08:21 + member 08:19, no line between them or after the member, normal 8 px lines on the single rows), Glamour dark (`gd0.png`: bg (38,25,15), rail (212,165,116), no line between the thread rows or after the last member, 8 px lines on the single rows). The Kan row shows a thin black vertical bar in the feed-icon slot in all themes, also on single rows (`simple0.png`); looks like the feed icon itself, not a thread issue, not compared with an earlier build |
| (5) Font 3.0 | FAIL for separators | Font size slider at its end (`fontSize` 2.9320872, label Huge), article font Medium (1.067), Sort By feed to get a thread inside the row cap (`f3_thr.png`): lead 08:21 + member 08:19, no line (profile at y 1515 to 1530 is background only), rail gap-free with one seam at y 1521 (154,118,82), time clipped `)8:21` (known F-2). With article font Huge too the list is capped at 9 rows (`Showing 9 of 219 · reduce font size to see more`) and no thread was reachable in them |
| (6) Badge `+N` and `−`, tap behaviour | PASS | Collapsed `+4` / `+1` pill after the time, no rail, normal separators (`coll2.png`); expanded `−` (U+2212), rail. 8 badge taps: with a non-thread article expanded (`expanded_article_id` = `last_tapped_article_id` = N12chat/228950) neither changed over 4 toggles, only `expanded_thread_ids`. Note: with a MEMBER of the thread expanded, collapsing the thread cleared `expanded_article_id` (kept `last_tapped_article_id`), the pointer cleanup of O-16; expanding the thread again did not restore it |
| (7) Unread only dissolve of an expanded thread | PASS | Glamour, expanded lead 08:21 + member 08:19 read by tapping an unrelated row (`dis_00.png` to `dis_15.png`, `dis_sheet.png`): rail pixels 459 (all rows normal to about 3.4 s), 320 (3.9 to 4.4 s), 258 (4.9 to 5.9 s, dots), 0 from 6.5 s; the rail shrinks with the rows, final frame has no left-edge pixels other than background and the neighbours' separators (no stub); badge gone; no dividers exist to dissolve (see BUG-2) |
| (8) Logs | PASS | Crash buffer empty, `crash_log.json` md5 729b6f10... unchanged, no FATAL, ANR, `Can't show content`, `cannot have more than`, `TransactionTooLarge` or OOM entries; only system noise (`ziparchive ... base.dm`, `InputManager-JNI ... disposed`, `Invalid packageName` after force-stop) |
| (9) Node structure of an expanded-thread item | for diagnosis | uiautomator tree of the state in `e2.png`. Thread member item (`AppWidgetHostView` [70,411][1008,651], h 240): `FrameLayout` > `FrameLayout` > `FrameLayout` > `LinearLayout` (the Column, [70,411][1008,651]) whose only child is the row `FrameLayout` [82,411][1008,651] (x starts at 82 = 70 + 5 dp), plus the rail `FrameLayout` [70,411][77,651] (7 px wide, full item height). There is NO divider node: not a collapsed 0 px one, absent from the dump. Same for all 5 items of the 07:55 thread (Column children: 1 each, `tree.py` on the state of `t4a.png`). In a single-row item (`[70,651][1008,787]`) the Column `LinearLayout` has 3 children: the row [70,651][1008,780], a 2 px `FrameLayout` [70,780][1008,782] and a 5 px one [70,782][1008,787]. Inside the thread item the inner row sits 4 px inside the outer row frame at top and bottom (415 to 647 inside 411 to 651), single rows have no such inset; the item height equals the row, no 2 + 5 px added |

Findings:

* BUG-2 (Medium, still open): thread dividers are not in the rendered view tree at all. The decision logic is right (`separatorStyleFor` / `isLastOfThread`, unit-tested), the single-row path that calls the same `RowAndDivider()` works, so the fault is in the `Box { Column(padding(start = 5.dp)) { RowAndDivider() }, rail Box(fillMaxHeight) }` wrapper as converted by Glance 1.1.0. Suggested next probes (not run, no source change here): render the expanded item without the rail child, or give the rail an explicit height, to see whether the match-parent rail in the wrapping FrameLayout makes the Column's extra children (dividers) get dropped or measured to 0; compare with a `Row` wrapper that has no weighted column.
* BUG-1 (Low, open): the 1 px lighter rail seam at item boundaries, unchanged, see (1).
* Not re-checked: BUG-3 (Auto theme dark rail contrast), LTR thread row.
* Side effect of the harness, not an app issue: one slow 600 ms swipe that started on the home screen below the widget moved the temporary widget and re-flowed the page's four icons (Clock, Samsung Free, Gaming Hub, OneDrive) up one row; after removing the widget they were dragged back to their original row (page 2 looks as at the start of the run).

Restore: temporary widget 18 removed through the launcher (Remove), `appWidget-18` and `appWidgetLayout-18` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty. From `C:\t\bk15\` (force-stop, write, two CLOCK_TICK): `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984, `newsfeed_read_status` (3089e962...), `appWidget-15` (0c1ba708...), `GlanceAppWidgetManager` (ca6ea36d...) and `release_notes` (27249a21...) md5-identical (checked right after the restore); `appWidgetLayout-15` is rewritten by every render (now 14a8bbd8..., backup ed89f86f...), widget 15 renders its 10 unread rows. Build 910 left installed (`versionCode=910`).

### 14.af Separator fix (build 911, final source 912), 2026-10-08

Device RFCR91J237W (Android 15, One UI Home). Source change limited to the expanded-thread item block of `NewsFeedWidget.kt` (no change to `data/ThreadRows.kt`). Built with `-PbuildVersionCode=911` (variant A, tested) and `912` (same code, comment/doc text only, installed last), `adb install -r`, no uninstall. All interaction on temporary widget 19 (One UI picker, default 11 feeds, Sort Newest first; later Oldest first, By feed, Show Unread only, Group similar stories off and on). Screenshots in `C:	\q16\`. Measurements are screen pixels (the host renders the widget at about 0.83 scale: 1 dp is about 2.3 px).

**Result: BUG-2 is FIXED by variant A (first variant tried; B, C not needed).** Variant A: ONE outer `Column(fillMaxWidth){ [expanded thread: Box(fillMaxWidth){ Column(fillMaxWidth, padding start 5dp){ ArticleRow() }; rail Box(3dp, fillMaxHeight, primary) }] | [other rows: ArticleRow()]; Dividers() }`; `Dividers()` = THIN: `Box(fillMaxWidth, height 1dp, surfaceVariant){ Box(width 3dp, height 1dp, primary) }` (rail segment overlaid at the start, no weights), NORMAL: the unchanged 1dp line + 2dp shadow `Box` pair, NONE: nothing. Single rows, collapsed leads and groupStories-off rows keep the same node tree (Column with row + 1dp + 2dp boxes).

| Check | Result | Evidence |
|---|---|---|
| (1) Thin line between thread rows, normal line + shadow after the last member | PASS | `a_exp1.png` (lead 09:31 + 1 member), `a_t4f.png` (lead 09:41 + 4 members): hairline (237,228,212) 2 to 3 px at every lead/member and member/member boundary (x=100: y 587 to 589, 757 to 759, 983 to 985, 1153 to 1155), none missing. After the last member: line (237,228,212) 2 px + shadow (202,200,197) 5 px = 8 px (`a_exp1.png` y 1011 to 1017; `a_t4d.png` y 673 to 679). The rail ends exactly at the last member's bottom (y 1010 / 672), then the full-width normal divider |
| (1) Rail one continuous line | PASS with the known 1 px seam (BUG-1) | rail (168,120,64) x 70..76 unbroken from the lead top to the last member bottom in `a_exp1.png` (637 to 1010) and `a_t4f.png` (393 to 1170): no background pixel; the thin divider carries the 3 dp rail-coloured segment so there is no gap at the separators; 1 px lighter rows ((180,138,89) to (206,180,146)) at the item boundaries, same size as 909/910 |
| (1) Single-row separators unchanged | PASS | 8 px (2 to 3 px line (237,228,212) + 5 px shadow (202,200,197)) on every single row in every capture, identical to 14.ad/14.ae and `fin.png` |
| (2) Node dump | PASS | uiautomator (`cols.py`): thin divider nodes now exist: `FrameLayout [70,587][1008,590]` (3 px) in `a_t4f`, each containing the rail segment `[70,587][77,590]`; thread items `[70,760][1008,986]`: row 223 px + `[70,983][1008,986]` 3 px. After the last member: `FrameLayout [70,1011][1008,1013]` (2 px line) + `[70,1013][1008,1018]` (5 px shadow). The rail `FrameLayout [70,637][77,831]` remains next to the row. Single rows: row + 2 to 3 px + 5 px (unchanged). Depth is unchanged or less than 910 (the item Column now holds the wrapper and the dividers instead of the wrapper holding them) |
| (3) Logs | PASS | crash buffer empty (0 lines) after every step, no FATAL EXCEPTION, ANR, `Can't show content`, `TransactionTooLarge`, `cannot have more than` entries; only the usual system W lines (`ziparchive ... base.dm`, `InputManager-JNI ... disposed`) |
| (4) Badge collapse/expand | PASS | `+1` / `+4` expand to `−` and collapse back (`a_coll.png`, `a_t4.png`): collapsed rows show the pill and normal separators, no rail |
| (4) Unread only dissolve | PASS | an expanded 5-row thread read by tapping an unrelated row (`dd_00` to `dd_21`): rail 422 px, rows shrink to dots (`dd_sheet.png`), 0 rail pixels from the frame at about 6.7 s, no stub, no divider left; a 2-row thread (`dis_*`) also left no stub |
| (4) groupStories OFF | PASS | `"groupStories":false` in `config_json`, flat rows, 0 badges, 0 rail pixels, every row has the normal 3/2 px + 5 px dividers (`off1.png`); ON again: `+N` and the expanded rail/thin dividers back with build 912 (`f912.png`) |
| Unit tests | PASS | `testDebugUnitTest`: 306 tests, 1 failure, the known `WidgetThemesTest colorProvidersFor custom wraps the same scheme rawColorSchemeFor produces` |

Root cause understanding: a divider `Box` placed in the same container as a `fillMaxHeight()` rail child is missing from the rendered view tree in Glance 1.1.0 (build 910's `Box{ Column{ row + dividers }, rail }` and build 909's `Row{ rail, Spacer, Column(defaultWeight){ row + dividers } }` both lost them; not collapsed, absent from the dump, while the paddings still applied). The weight in 909 was therefore not the (only) cause. Dividers that are siblings of the rail wrapper, in the same outer `Column(fillMaxWidth)` as single rows, are painted. The exact mechanism inside the RemoteViews conversion was not isolated (it was not needed).

Note on the harness: only 2-row native threads existed in the live data during this run (a +2 and +4 appeared after a refetch), so a 5-row thread was first built by editing the temporary widget's stored article titles (`q16/put19.py`, DataStore of widget 19 only, not source); the later `+4` threads in `a_t4f.png`, the dissolve run and `off*.png` are real refetched data. A slow press-and-hold swipe (350 ms) in the widget sometimes put it in the launcher's resize-selection frame (BACK or a tap below restores it).

Restore: temporary widget 19 removed through the launcher (Remove), `appWidget-19` and `appWidgetLayout-19` gone (datastore folder lists only the 15 files). From `C:	k16\` (force-stop, write, two CLOCK_TICK): `newsfeed_config` md5 720a2dd2c3a48ed644fe6b3ca30ab984, `newsfeed_read_status` 3089e962..., `appWidget-15` 0c1ba708..., `GlanceAppWidgetManager` ca6ea36d..., `release_notes` 27249a21... all identical to the backup; widget 15 renders its 10 rows. Build 912 left installed.

### 14.ag Timeline look and Focus budget fixes (build 913), 2026-10-08

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD ac82d3a (e7d5fca timeline + ac82d3a Focus budget), built `-PbuildVersionCode=913`, `adb install -r` over 912 (no uninstall), source untouched. All interaction on temporary widget 21 (One UI picker, default 11 feeds, Sort Newest first; later Focus/Expand, Show All/Unread, fonts, Glamour/Simple, Light/Dark, Group on/off per test). Widget 15 (the user's) was not opened, not edited, its settings never shown; before every settings Save the config activity was confirmed to belong to widget 21 (Sort = Newest first on open, which widget 15 does not have; widget 15 `config_json` compared after every Save: unchanged each time). Screenshots in `C:\t\q18\`. Measurements are screen pixels (450 dpi, the host renders the widget at 0.83: 1 dp = about 2.34 px; widget right edge x = 1008).

Harness note: the widget was only 4 to 5 rows tall (a first long-press to resize was blocked by the tool's permission check, so no taller widget); the list is scrolled with fast swipes. After rewriting widget 21's DataStore (see below) the old RemoteViews stayed on screen until the next tap, an artifact of the test, not the app.

Test data: besides live data, three synthetic scenarios were written into widget 21's DataStore only (files `appWidget-21`, backups `C:\t\bk18\w21_before_plant*.pb`): `plant://0..9` (a 10-article thread), `p2://*` (an English LTR thread of 4 over the AI News / AI Newsletter / The Hindu feeds, with those feeds set to LTR in widget 21's feed list, a Hebrew thread of 3, a pair, a same-id-from-two-feeds pair `dup://1`, singles) and `visible_article_count` 2.

| # | Check | Result | Evidence |
|---|---|---|---|
| 1a | Rail on the physical right, every row of an expanded thread; 2 dp line; hollow lead, filled members; line ends at the last member | PASS | Glamour light, Hebrew thread lead + 5 members (`t5_a.png`, `t5_b.png`, `t5_a_zoom.png`), lead + 1 member (`th_lm.png`, `th_lm_zoom.png`), lead + 9 members (`d1b.png`): line x 984..988 (5 px = 2 dp), centre x 986 = 22 px from the right edge; ring 28 px outer (x 973..998, 12 dp), interior background-coloured (hollow, 17 px), stroke 5 px; member dot 19 px (x 977..995, 8 dp); line starts at the ring bottom + 1 px and ends exactly at the last member's dot bottom (y 1013..1014, background from 1015 in `t5_b.png`; y 571 in `th_lm.png`); nothing below |
| 1b | Dot vertical alignment with the first (meta) line | PASS | member dot centre minus row top = 26 px at font 1.0 (formula 4 dp + 7 dp = 25.7 px); `th_lm.png`: lead ring centre y 268.5 vs `19:13` text centre 270.5, member dot 562 vs text 560.5; LTR lead with pill: 42 px (8 dp + 10 dp) and ring 428.5 vs time centre 430, members 608.5 / 778.5 / 948.5 vs 608 / 778 / 948 (`g2_exp.png`); font 3.0 (`e3_02.png`): member dot centre 739, offset 58 px (formula 4 + 21 dp = 58.5), dot stays 19 px; font 0.51 (`f05.png`): offset 17 px (formula 17.8), lead ring 35.5 (15.1 dp = 35.4); Focus, enlarged member at 1.75: dot 584.5 vs `20:44` text 584 (`d6.png`) |
| 1c | Continuity at every row boundary | PASS with a 1 px seam (BUG-1, below) | `t5_a.png`: 10 boundaries, the rail colour never drops to the background; each boundary has one or two 1 px rows of a lighter tint, e.g. (195,163,124), (187,150,105) instead of (168,120,64) |
| 1d | Thin separators painted, rail unbroken through them; normal separator after last member | PASS | `th_lm.png` x=900: hairline (237,228,212) y 533..535 between lead and member; after the last member line (237,228,212) 768..770 + shadow (202,200,197) 771..775; uiautomator: `FrameLayout [70,533][1008,536]` (3 px) between thread rows and `[70,768][1008,771]` + `[70,771][1008,776]` after the last member (same shape as for singles) |
| 1e | Collapsed thread: ring + `+N`, no line; single rows no rail | PASS | `sc_01.png`, `g0.png`: ring 28 px at the right edge, level with the pill, no line; single rows keep the right accent stripe, no rail, no extra right gap |
| 1f | Badge `−` when open, `+N` collapsed | PASS | uiautomator texts `−` (U+2212) and `+5`, `+3`, `+2`, `+1` |
| 1g | Hebrew/RTL: per-feed stripe hidden on rail rows; no overlap with the feed circle | PASS | pixels x 996..1007 on lead/member rows are background, on single rows the stripe (168,120,64) x 1001..1007 (`th_exp3b.png`); feed circle right edge about x 945, rail dot left edge x 977 (32 px gap), thumbnail right edge x 947 |
| 1h | LTR English thread | PASS (planted data) | `g2_exp.png`: timeline on the physical right, LTR text, left accent stripe kept on every row, badge `−` level with the ring, dots on the meta line, line ends at the last dot, normal separator after the thread. Real data has no LTR thread (the AI Newsletter articles are a week apart, the grouping window is 6 h), so the thread was planted |
| 1i | Themes | PASS | Glamour dark (`gd_0.png`): rail (212,165,116) on (38,25,15), ring + dots + line + `−` pill visible; Simple dark (`sd_0.png`): white rail on (17,17,17); Simple light (`sl_0.png`): black rail on white; Glamour light as above. Not run: other themes, Auto (BUG-3 of 14.ad not re-checked) |
| 1j | Last in list / cut by the row budget / `LEAD_CUT` | PASS | font 3.0 (`e3_02.png`): thread cut after the 4th member, last visible dot filled, line ends at it, "Showing 9 of 250" below; `visible_article_count` 2 (`lc_0.png`): expanded lead is the last row, ring only, line stops at the ring (y 442), no stub; Load more (`lc_1.png`): rail extends to the revealed members; collapsed `+1` ring at the end of the list (`th_exp3.png`) |
| 1k | Group similar stories OFF | PASS | `go_0.png`: flat rows, no badges, no rail pixels, normal separators; `groupStories` false in `config_json`; ON again removes the key |
| 1l | Expanded member with an article body, and the Focus look | PASS | `bt_1.png`: rail unbroken beside a 620 px expanded member (y 428..1047); Focus, focused member (`ds3.png`, `d5.png`): line runs unbroken beside an 826+ px enlarged row; the tan focus highlight stops before the 18 dp rail column (cosmetic, see observations) |
| 2a | Focus: tap the last row of the list, rows allowed X | PASS | Glamour, scale 1.45: font 1.0 All: 21 rows with nothing focused, 26 after the tap, 25 from scale 1.9 to 2.5 (`fb0.png`, `fb2.png`); font 1.0 Unread: 18 -> 26 -> 25 (`u2_nofocus.png`, `u3_f.png`); font 1.39 Unread (scale 1.3): 15 -> 19 -> 18 at 1.9..2.5 (`v2_nofocus.png`, `v3_bot.png`, `v4_bot.png`); font 1.39 All (1.45): 13 -> 19 -> 18 at 1.9..2.5 (`w2_nf.png`, `w3_f.png`). Header always showed N/M (21/26, 18/26, 15/19, 13/19) with − / +, the tapped row stayed enlarged, the cap never dropped (before the fix 27 -> 22) |
| 2b | Footer refresh with a focused near-bottom row, then Load more | PASS | refresh pushed two newer rows in: N 21/26 -> 23/26, still enlarged; 10 newer articles planted above the focused last row: N/M 18/18 (pinned into the last slot, row visible, `p3.png`), nothing marked read until focus moved; font 0.51 Focus (cap 46): Load more to `Showing 46 of 249`, focus 11/46 kept. The "left the list altogether" case (no N/M and no −/+) was not reproduced: the pin kept the row in |
| 2c | Duplicate ids across feeds | PASS | live Globes pair `1001558825`: one row (its thread shows `+5` for 5 other articles, not `+6`); planted `dup://1` in both Globes feeds: one row without a pill, Focus tap enlarges exactly one row (135 -> 297 px, N/M 18/22, `dup_f.png`), second tap clears `focused_article_id`; both stored copies get `isRead` when focus moves on |
| 2d | Focus + Unread: whole-thread read, moving within a thread, dissolve | PASS | thread of 10 (`plant://`): tap member `plant://8` (2/25), then member `plant://6` (4/25): nothing read (0 of 10, total read count unchanged apart from the previously focused on-screen row); tap an unrelated row: 10 of 10 read at once (total 19 -> 29); dissolve frames `dis2\f00..f22.png`: the last member row shrinks to dots (frames 4..9), gone by 6.6 s, no dot or line stub (`dis2\sheet.png`) |
| 2e | Memory / "Can't show content" at the new caps | PASS | font 0.51: 46 rows (Focus), Load more to the cap; font slider max 2.996 + article font 2.996 + Full article length + Focus scale 2.5 (8 rows, "Showing 8 of 250", `x2.png`) and Expand with an expanded thread (9 rows, `e3_01.png`, `e3_02.png`): renders, no `Can't show content`, `TransactionTooLarge`, OOM, RemoteViews/bitmap errors, FATAL or ANR; crash buffer empty after every step |
| 3 | Regressions | PASS | BUG-022: Expand, Telegram post 48149 expanded, tap a description-less row: `expanded_article_id` cleared, `last_tapped_article_id` moved (`b22.png`); BUG-024: `focus_scale` 1.45 unchanged across focus changes, footer refresh, force-stop; BUG-025 / Telegram URL: 66 Telegram posts, 0 titles contain `http`, 17 posts with the link label all carry the URL once in the description; row collapsed at normal size, expanded shows the URL under the image (`tg0.png`, `tg1.png`); whole-thread read in Expand: one tap on a member of the 6-article thread marked all 6 read (header 33(35) -> 27(29), 6 ids added); badge tap: `expanded_article_id` and `last_tapped_article_id` unchanged over 3 toggles with a Telegram article expanded (a thread's badge toggle while its own member is expanded clears the pointer, the O-16 cleanup); Unread-only dissolve: see 2d |
| 4 | Latency (tap to first `updateAppWidget`, `logcat -v epoch`) | note | warm 0.88 / 0.89 / 0.94 / 0.99 s, after 20 s idle 1.31 and 0.92 s (baseline 0.6..0.9 warm, 1.1..1.5 idle): warm at the top of or slightly above the baseline, idle within it |
| 5 | Logs | PASS | no FATAL, ANR, `Can't show content`, `TransactionTooLarge`, `cannot have more than`, OOM; crash buffer 0 lines; only system noise |

Findings:

* BUG-1 (Low, unchanged from 14.ad): a 1 px lighter row (e.g. (187,150,105) vs (168,120,64), up to 35 % lighter; darker on Simple dark) sits in the rail at every item boundary, one or two per boundary (`th_lm.png` y 535, `t5_a.png` 10 of 10, `d1b.png`). Not a gap. Visible only when zoomed.
* Observation (Medium, to verify): in Focus mode, tapping a row at the bottom of the list re-renders the list scrolled to the TOP (`fb0.png` -> `fb1.png`, `u3_f.png`): the enlarged row (N/M 21/26) is off screen and has to be scrolled to, which can look like "nothing happened". Not compared with a pre-fix build; no jump when the tapped row was near the top (`d4`, `d6`).
* Observation: with Focus on, the tan focus highlight stops at the 18 dp rail column, leaving a cream strip with the line beside the enlarged row (`ds3.png`). Cosmetic, possibly intended.
* Observation: focusing makes the cap jump (X 21 -> 26, 18 -> 26 at scale 1.45): rows below the tapped one appear. By design of `maxRowsAllowedFor`.
* Not verified: Auto theme and the other themes, Expand-mode Unread-only dissolve with a real (description-bearing) thread in this build, a taller widget (resize blocked).

Restore: temporary widget 21 removed through the launcher (long-press, Remove): `appWidget-21` and `appWidgetLayout-21` gone, `OrphanCleanupWorker` SUCCESS, crash buffer empty, `newsfeed_config` md5 back to the start value 6d485c1f65bad26470de74fbbfb4874b. Widget 15: `config_json` byte-identical to the start; `appWidget-15` md5 190bde8f... -> 3217ab35... (articles_json refreshed by the background refresh, `last_refresh_*` changed, six `isRead` flags flipped, see below); `newsfeed_read_status` md5 ccd6c37a... -> 72ab1bd7...: 100 -> 129 ids, none dropped; the 29 added ids are 19 synthetic (`plant://`, `p2://`, `dup://`) and 10 real (ynet h1lbxshome, S1ZTzBBiMl, rkrh11rbszx, sjveihrofg; Globes 1001558825; Kan 1585437b...; N12_News 48142, 48143, 48149; N12chat 229106, 229117, 00b2fd08...). Because read state is shared between widgets, widget 15's own list now shows h1lbxshome, S1ZTzBBiMl, rkrh11rbszx, 1001558825 and sjveihrofg as read (they were unread there at the start); two more flips (1001559000, 1001558846) are not in my list (the user's own reading). Nothing was reverted. Build 913 left installed (`versionCode=913`).

### 14.ah Similarity fix and 15 minute retention (build 914), 2026-10-09

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD 31514e6 (a32fafc similarity + 31514e6 15 minutes), built `-PbuildVersionCode=914`, `adb install -r` over 913 (no uninstall), source untouched. All interaction on temporary widget 22 (One UI picker, default 11 feeds, then Sort Newest first, Simple theme for readable text, later Glamour). Widget 15 (the user's) was not opened and its settings were never shown; before every settings Save the config activity was confirmed to be widget 22's (Show = All, Font = Medium, Sort = Newest first; widget 15 is Unread only with font 1.39). Widget 15 `config_json` was compared byte for byte after each of the 12 Saves: identical every time. Screenshots in `C:\t\q19\`. Measurements: device clock (`adb shell date`) and `logcat -v epoch` (`HoneyAppWidgetHostView updateAppWidget id=22`).

Method for the threads: a Python port of StoryThreads.kt (new matcher) and of the pre-a32fafc matcher (`C:\t\port19.py`) was run on the widget's own `articles_json`. The port was validated against the device first: the first 50 rendered rows (rows, order and every `+N` badge: 09:06 +1, 08:38 +2, 08:27 +1, 07:12 +2, 06:49 +2) and the expanded members of three threads matched the port exactly. The port is therefore used for rows beyond the first 50 and for widget 15's data (read-only).

| # | Check | Result | Evidence |
|---|---|---|---|
| 1a | Live threads on the temp widget (228 de-duplicated articles, Group ON), every thread judged by its member titles | PASS: 12 threads, 12 GOOD, 0 WEAK, 0 WRONG | list below; device-confirmed for the first 50 rows and 5 badges, threads older than row 50 by port only |
| 1b | No thread held together by a reporter name, a channel label (`צ'אט כתבים`, `מבזקן 12`, N12, ynet, `קישור לכתבה`), a show name or the words of the day | PASS | none in live data; planted cases on device (1c) |
| 1c | Planted BUG-028 cases on the temp widget (only `p19://` articles, DataStore edit): same reporter two stories, `מבזקן 12 נתניהו נפגש` vs `מבזקן 12 נתניהו הודיע`, `נתניהו צה"ל ימשיך יום` vs `נתניהו צה"ל קריטי יום`, show/channel names only | PASS | `pl_0.png`: the six articles stay six separate rows, no badge. The pre-a32fafc matcher (port) links the reporter, label and words-of-day pairs (3 false threads) on the same data |
| 1d | Same story from different reporters/feeds is still ONE thread (planted: 3 members with two different reporters + ynet; N12 flash label vs twin post, 2; Binyamin-style 4 members over N12chat/N12_News/Kan; quote pair + Kan `- כאן 11` suffix twin, 3) | PASS | badges `+2`, `+1`, `+3`, `+2`; members read after expanding (`R_exp.png`) |
| 1e | Group OFF (settings + Save, `groupStories:false`) | PASS | flat list, no badge, no rail, header 50(50) for 50 rows (Group ON 55(55): the 5 hidden members); ON again removes the key |
| 1f | Obvious same-event articles that are NOT grouped (not a failure per O-35) | 5 found | see list below |
| 1g | Old vs new matcher on 20 distinct real article sets (backups + widget 15 now, `C:\t\oldnew.py`) | info | the new matcher removes 3 old threads (national memorial ceremony Kan + ynet pair lost; a 12 member Binyamin-attack chain split into 4 + 9; the Emirates report loses its N12 member) and adds 4 (including `גאנם איברהים: תיעוד מזירת הפיגוע` + `מבזקן 12 זירת הפיגוע`, the pair the label used to dilute); no reporter-only or label-only group existed in the old results on this real data either |
| 2a | Keep articles for dropdown order and default | PASS | `15 minutes, 1 day, 3 days, 1 week, 2 weeks, 1 month, Forever`; a new widget shows Forever |
| 2b | 15 minutes + Save: config and pruning | PASS | `config_json` has `"retentionMinutes":15` and no `retentionDays`; the Save refresh left 3 articles (ages 3.4, 12.6, 13.7 min), header 3(3), 3 rows (`ret15_a.png`); worker refresh at 11:43:17: 4 stored, all under 12.3 min, header 4(4) |
| 2c | Render-time filter with a real article crossing 15 minutes | PASS | Globes article published 11:25:00: re-rendered at age 900.0 s (host-side `updateAppWidget`) it was still shown (1(1)); at 916.0 s gone (0(0), `No articles`) while `articles_json` still held 3 articles (no worker run). Two older rows (ages 15:04 and 16:12) stayed on screen until the first re-render, then vanished (3(3) to 1(1)) |
| 2d | A feed with nothing newer shows nothing | PASS | only N12 rows in the 15 minute list at 11:43 |
| 2e | What re-renders | note | opening Settings and pressing Back does NOT re-render (footer stays `in 14min`, expired rows stay on screen); resizing the widget (no worker run) and Save do |
| 2f | 1 day / 3 days / Forever | PASS | `retentionDays:1` and no `retentionMinutes` (stored 3 to 153 after the Save refresh), `retentionDays:3` (reopening shows `3 days`), Forever removes both keys |
| 2g | Widget 15 retention untouched | PASS | its `config_json` has neither retention key (Forever); articles back to 2026-08-16 are still stored (300, oldest 54 days) after the build 914 refreshes; `config_json` byte-identical to the start |
| 3a | Regression sample | PASS | badge toggle; right-side rail with hollow lead (`R_exp.png`); whole-thread read (Focus + Unread only: 3 members read with ONE shared `readAt`, a 4 member thread dissolving with dotted placeholder rows in `R5_focus.png`); duplicate id shown once (`p19://dup` in two feeds: one row, no badge); Focus tap on the last of 50 rows stays enlarged with `50/50` and `-/+` (`B_2.png`, BUG-026); Telegram data (21 posts, 0 titles with `http`, 9 URLs once at the end of the description, 7 of 7 link labels carry a URL; a planted post renders the URL line under its title) |
| 3b | Logs | FAIL (BUG-1) | no FATAL, no ANR; crash buffer holds only the old 2026-09-20 build 901 entry, no new entry; `Can't show content` appeared once, caused by BUG-1 |

Threads on the live temp widget at 11:20 (all GOOD): T1 Kan `יתושות נגועות בקדחת מערב הנילוס` + N12chat `אביבית מיסניקוב ... לכדנו יתושות שנגועות בנגיף קדחת מערב הנילוס`; T2 N12_News + N12chat `אחרי הפרסום ב-N12: כתב אישום נגד חייל שגנב נשקים מחיילים בשייטת` (reporter label stripped, same story); T3 Binyamin road crash (N12chat 08:38, N12_News 08:36, Kan 08:35); T4 Polanski loses the election (Kan 08:27 + N12chat 06:35); T5 weather forecast (Kan 07:12, ynet flashes 06:25, ynet news 05:33; GOOD but the least informative, only the forecast phrase is shared); T6 execution of the Palestinian-American (N12_News 06:49, ynet 03:58, ynet flashes 00:36); T7 electric scooter rider in Tel Aviv (ynet 03:20 + Kan 03:10); T8 US 3 day attack plan on Iran (ynet 01:55 + ynet flashes 00:50); T9 Pentagon plan (N12_News 01:18 + Kan 01:03; same story as T8, not merged); T10 Capt. Zfalmus cleared for publication (Globes 22:59 + Kan 21:11); T11 two soldiers lightly hurt by ammunition misuse (Kan, ynet, N12_News 22:23-22:27); T12 Or Akiva car blast (ynet 22:12 + N12_News 22:11). Widget 15's data (read-only, 16 threads in 300 articles) is all GOOD too.

Same-event articles not grouped (all confirmed as separate rows on the device in the first 50 rows): the tank-missile indictment (ynet flashes 09:55, ynet news 09:54, N12_News 09:39, N12chat 09:38 and 09:29: 5 articles, 4 feeds, no thread: pairs fall under 3 shared words or the 60% bar, e.g. the N12_News and N12chat posts share only `מטאדור` and `צה"לי`); the Binyamin crash follow-ups (ynet flashes 08:38 `נהג מונית נהרג בתאונה עם אוטובוס בבנימין`, N12chat 08:46 `תאונת הדרכים הקטלנית בבנימין`, 3 of 6 words = 50%); the Polanski story (ynet news 07:59, N12_News 07:30, N12chat 06:36 stay single, only 2 of 6 articles joined); the Iran plan split in two threads (T8 and T9); the execution story (Kan 07:43 not joined with T6).

Bugs:

* BUG-1 (High, reproducible 2 of 2): Simple theme (and every non-Glamour theme), `Load more` past about 55 rows breaks the widget. Steps: temp widget, Simple theme, Group ON, Show All, default feeds (about 230 articles), tap `Load more articles` repeatedly (visible_article_count 10, 20 ... 50: header 55(56)). At 60 the update is not delivered (system_server `TransactionTooLargeException: data parcel size 554832 bytes`, logged at 10:28:22 and 10:43:53), the screen stays at the 50 row render, the `Load more` button stays and every further tap only raises `visible_article_count` (60 to 130) with nothing changing on screen; from 120 the widget process itself fails (`GlanceAppWidget ... TransactionTooLargeException: data parcel size 1103716 ... 1913988 bytes`) and the next update (Save or tap) shows `Can't show content` (`bug_cant_show.png`); a DataStore reset of the count was needed to recover. Cause: `maxRowsAllowedFor` gives non-Glamour themes a 300 row ceiling budgeted on thumbnail bitmaps only, but the serialized RemoteViews hits the Binder limit at about 60 rows (about 9 KB per row). Glamour is safe (cap 26 rows, footer `Showing 26 of 173 · reduce font size to see more`). Evidence `lm19_run2.log`, `bug_cant_show.png`.
* BUG-2 (Medium, carried from 14.ag, reproduced): Focus tap on a row near the bottom re-renders the list scrolled to the TOP; the enlarged row (focus kept, `50/50`) is off screen until scrolled to (`B_1.png`, then `B_2.png`).
* BUG-3 (Low): a Telegram title keeps the literal HTML entity `&rlm;` (N12chat 08:41 post by ענבר טויזר, `"&rlm;אנחנו לא מתקפלים ...` visible in the row and in `articles_json`, `c5_25.png`).
* Observation (Medium, performance): tap to first host update 1.2 to 1.3 s (4 of 6 Focus taps) and 2.8 to 2.9 s (2 of 6, taps that also marked a thread read and dissolved it, with further dissolve renders at +1.3 to 1.6 s steps) on a 50 row Simple list (parcel about 0.5 MB); the 14.ag baseline was 0.9 s on about 25 rows.
* Observation (Low): one transient footer `refresh failed - tap to retry` at 11:11 (cleared by the next refresh, no network exception in logcat, cause not identified); the 15 minute empty state is the generic `No articles`.
* Not verified: threads older than row 50 and widget 15's threads on the device (port only); a real rotter or Google News feed on the temp widget (not in the default 11; their pair types were exercised with planted Kan `- כאן 11` and quoted-title pairs, and through the port on widget 15's data); Glamour on-screen rendering of a Telegram URL.

Restore: temporary widget 22 removed through the launcher (long-press, Remove): `appWidget-22` and `appWidgetLayout-22` gone, `OrphanCleanupWorker` SUCCESS, `newsfeed_config` md5 back to the start value 6d485c1f65bad26470de74fbbfb4874b. Widget 15 md5s start to end: `newsfeed_config` 6d485c1f... to 6d485c1f... (same, shared file), `appWidget-15` 5983b828... to 3d4a51d1... (background refresh and the user's own use; 300 articles), `newsfeed_read_status` 72ab1bd7... to 96cb59ad... (shared read set: +10 synthetic ids `p19://R1a-d, R2a-c, dup, F1, F45`, no real id); widget 15 `config_json` byte-identical. Widget 15 settings were not opened or saved. Build 914 left installed.

### 14.ai Serialized row cap and direction marks (build 915), 2026-10-09

Device RFCR91J237W (Android 15, One UI Home). Build under test: HEAD 78c7c50 (34539a1 serialized-size row cap, aab6322 `&rlm;`/`&lrm;` removal, 78c7c50 footer wording), built `-PbuildVersionCode=915`, `adb install -r` over 914 (no uninstall), source untouched. All interaction on temporary widget 23 (One UI picker, default 11 feeds, later +1 Telegram feed; Sort Newest first, Simple theme, Group ON, Show All unless stated). Before every settings Save the config activity was confirmed to be widget 23's (the activity intent carried `appWidgetId=23`); widget 15 was never opened. Screenshots in `C:\t\q20\`. Caps are the footer `Showing X of Y` count, cross-checked by scrolling the whole list and counting rows (32 of 32, 33 of 33, 31 of 31, 26 of 26 matched).

Not done: the control run of the pre-fix build (installing an older APK over the user's live app was refused by the permission system, so the old behaviour was NOT re-reproduced in this run; the reproduction is the 14.ah BUG-1 evidence on the same data set and steps). The update parcel size cannot be read on a non-root device (no parcel size log line exists unless a transaction fails), so it is an estimate from the 14.ah failure (554 832 B at 60 rows = 9.2 KB per row): 32 rows is about 300 KB, 33 flat rows about 305 KB, both under the 507 KB oneway limit.

| # | Check | Result | Evidence |
|---|---|---|---|
| 1a | Simple, Group ON, Show All, Newest first, about 225 articles: Load more from 10 rows | PASS | taps: `visible_article_count` 20 (header 22(22)), 30 (33(33), no footer), then 32 (40(40)); then no button, footer `Showing 32 of 205 · the widget can't show more at once`; 32 rows counted; DataStore stays 32 (no 60, 130); no `TransactionTooLargeException`, `data parcel size`, `Can't show content` in `logcat -b all`, crash buffer 0 lines; the widget kept rendering and answering taps (`lm_end.png`) |
| 1b | Second non-Glamour theme (Lavender) | PASS | Save with stored count 32: no Load more, footer `Showing 32 of 208 · the widget can't show more at once`, 32 rows (`lav_a.png`) |
| 1c | Glamour on the same data | PASS | 26 rows, footer `Showing 26 of 208 · reduce font size to see more` unchanged (`gla_end.png`); the stored count stays 32 (only Load more clamps it) |
| 1d | Group OFF (Simple) | PASS | cap 33 flat rows, `Showing 33 of 225 · the widget can't show more at once`, count 33, 33 rows counted (`goff_end.png`) |
| 1e | Show Unread only, Group ON | PASS | 32 rows, `Showing 32 of 202 · the widget can't show more at once`, header 36(36) |
| 1f | Expand an article at the cap | PASS | expanding a Telegram post at 32 rows: still 32 rows and footer (`exp_cap1.png`); planted 4000-char Telegram description (synthetic `p20://long1`, Full article length) expanded near the end: renders, footer `Showing 31 of 216` (cap drops by 1 row), 31 rows counted, no overflow in logcat |
| 1g | Expand a thread at the cap | PASS with note | thread in the middle: members appear, trailing rows are trimmed, still 32 rows (`Showing 32 of 207`). Thread whose lead is the LAST row: the badge turns to `-` but its members are not shown (past the cap, `thr_cap2.png`, count stays 32): see BUG-1 |
| 1h | Focus mode at the cap | PASS | 32 rows; tapping the last row (BUG-026): enlarged row kept with `32/32` and `-/+`, footer `Showing 32 of 208` intact, no overflow (`foc_tap1.png`, `foc_bottomA.png`) |
| 1i | Font slider smaller / larger | PASS | font 0.5: 32 rows, footer `the widget can't show more at once` (not the `this device` wording); font 2.986 (Huge): still 32 rows, same footer, large text renders (`fmax_end.png`): the serialized cap does not move with the font |
| 2 | Short lists unchanged | PASS | 10 rows header 10(10) with Load more; 20 rows 22(22); 30 rows 33(33): no footer, button present at each step |
| 3 | `&rlm;` / `&lrm;` | PASS | fresh fetches of the default 11 feeds: 0 literal `&rlm;`/`&lrm;`/`&#8207;` in 224 and later 267 articles (titles and descriptions); the default Telegram pages carry none today. Real sample: added the public channel `t.me/s/yediothnews` to the temp widget, whose page contains the post `בשם ה'&lrm;`: the stored title is `בשם ה'` with the mark dropped, no literal text (`arts_yed.json`). RTL rows (N12chat, N12_News) display right to left and threads are unaffected (`thr_exp_top.png`). The pre-fix literal was not re-shown on this build (see not-done note) |
| 4a | BUG-026 focus pin at the cap | PASS | see 1h |
| 4b | Thread timeline on the right, whole-thread read | PASS | planted 4 and 3 member synthetic threads: hollow lead, filled members and the line on the RIGHT (`thr_exp_top.png`); Expand mode: tapping a member only expands it; moving on to an article outside the thread marked all 4 members read with ONE shared `readAt` (later the 3 member thread the same) |
| 4c | Unread-only dissolve | PASS | both read threads vanished from the Unread only list on the move-away tap (`unr_top.png`) |
| 4d | 15 minutes retention option | PASS | `15 minutes, 1 day, 3 days, 1 week, 2 weeks, 1 month, Forever` |
| 4e | Logs | PASS | no FATAL, ANR, `TransactionTooLarge`, `data parcel size`, `Can't show content`; crash buffer 0 lines before and after |

Findings:

* BUG-1 (Low): expanding a thread whose lead row is the last row inside the cap toggles the badge to `-` but shows none of its members (they fall past the cap) and the footer count stays. Repro: Simple, Group ON, cap reached, scroll to the last row, tap its `+N` badge (`thr_cap1.png` before, `thr_cap2.png` after). Collapsing works. Expected: the members visible (trailing rows trimmed instead) or the badge disabled.
* Observation (Medium, known from 14.ah BUG-2): Focus tap on the last row re-renders the list scrolled to the TOP; the enlarged row is off screen until scrolled to (`foc_tap1.png`).
* Observation: header `N(M)` and footer `Showing X of Y` count different things (header = articles shown including hidden thread members, footer = rows): e.g. 40(40) with `Showing 32 of 205`; the header drops when a thread is expanded at the cap (41 to 36) because trailing rows are trimmed.
* Observation (Low, intermittent): the first tap after a list swipe is sometimes swallowed (fling stop): twice on a badge, once on a Load more; a second tap works.
* Performance: tap to the first host update, 31 rows Simple font 1.2 Unread only, Telegram post expanded: 2.3 s, 0.36 s, 1.1 s (three taps; later updates of about 2 s follow from the dissolve check); above the 14.ag/14.ah baseline of 0.9 to 1.3 s once.
* Harness note: `adb shell am force-stop` of the app freezes the widget on the launcher's loading view and cancels its PendingIntents (taps do nothing) until the next refresh; a plain `kill -9` through run-as does not.

Restore: temporary widget 23 removed through the launcher (long-press, Remove): `appWidget-23` and `appWidgetLayout-23` gone, appwidget ids 15, 3, 2 only, crash buffer empty, `newsfeed_config` md5 back to the start value 6d485c1f65bad26470de74fbbfb4874b. Widget 15 md5s start to end: `newsfeed_config` 6d485c1f... to 6d485c1f... (same), `appWidget-15` 70a0cb64... to 42fa4755... (background refreshes and the user's own use), `newsfeed_read_status` 96cb59ad... to 2e36e0bd...: 106 to 116 ids, none dropped; 9 added are synthetic (`p20://T1a-d, T2a-c, long0, long1`) and 1 real (`https://t.me/N12_News/48179`, expanded and moved away from on the temp widget; widget 15 shows it as read if it holds it). Widget 15 `config_json` byte-identical to the start; its settings were not opened or saved. Build 915 left installed (`versionCode=915`).

---

## Reporting

For every ❌, capture: exact steps to reproduce, a screenshot, the widget theme/settings active at the time, and whether it reproduces in both tap modes or just one. File findings as you go rather than batching them — several past bugs in this project were only caught because a specific repro was pinned down immediately rather than described vaguely after the fact.

### 14.aj Focus scroll position and tap speed (build 931), 2026-10-09

Device RFCR91J237W (Android 15, One UI Home). Code under test: HEAD 89cb7e3 plus commits `84f0bf7` (header fix), `d2fd73a` (tap speed), `11e345e` (1x no-focus row cap); builds 916 to 931 (`-PbuildVersionCode`, `adb install -r` over the previous one, never an uninstall or a downgrade). 916 to 927 and 929 carried temporary `NFTiming` logs (removed; the patch is kept in `C:\t\q21\instrumented_working_tree.patch` and `C:\t\instr21.py`), 928, 930 and 931 are clean builds. Everything was done on a TEMPORARY second widget (id 24, One UI picker, default 11 feeds, Newest first, later Simple / Glamour, Focus / Expand, All / Unread only); widget 15 was never opened, saved or restored. Every gear tap was guarded: exactly one NewsFeed host view on screen with a height of about 1100 px (widget 15's is about 2000 px and lives on another page) and the settings screen showing Medium font and Newest first. One gear tap was denied by the permission classifier early on because the first script version tapped the first gear on screen without that host-view check; it never executed, and the check was added before any further tap. For the timing and scroll series the widget's article list was replaced by 300 synthetic articles (`p21://nnn`, real headlines of `story_threads_fixture2.tsv`, dated 3 hours ahead so they stay above real articles), see the read-status note at the end. Screenshots, JSON series and scripts in `C:\t\q21\` (`sc21.py`, `perf21.py`, `c21.py`, `plant21.py`, `ds21.py`, `coldidle.py`, `cmp21.py`).

#### A. Focus tap scrolled the list to the top (BUG-032)

Method: scroll the list (fast 150 ms swipes), note the visible rows' time text and y (uiautomator), tap a row at its centre, wait 3.3 s, read again; "KEPT" = the tapped row is still on screen and the first visible row is not the list's first row. One variable per build.

| Build | Change | Result (Simple unless noted) | Evidence |
|---|---|---|---|
| 916 (before) | none | last row none to focused: JUMPED (first row 18:48 on screen, 32/32 off screen); clear: JUMPED; middle row after one swipe: JUMPED (22/32 off screen); focus A to B: KEPT; **+**: KEPT | `a_before.png`, `a_after.png`, `m1_before.png`, `m1_after.png`, `e1_before.png`, `e1_after.png` |
| 916 | Expand mode, tap expands a row while scrolled | KEPT (so a row that grows is not the cause) | `exp2_before.png`, `exp2_after.png` |
| 918, 916 | rows 32 before and after (cap 32 both), then 20 of 32 rows (Load more in both states) | still JUMPED: hypothesis 1 (row-count / cap discontinuity) is not the cause in Simple | `x3_20rows_*.png` |
| 917 | header Row with the same number of children, blank non-clickable placeholders | JUMPED | `x1_before_clear.png`, `x1_after_clear.png` |
| 918 | header exactly as tall in both states (uiautomator list bounds `[70,289][1008,1171]` with and without focus) | JUMPED: not the header height (hypothesis 3) | `x2_nofocus_to_focus_*.png` |
| 920 | same styles and padding, placeholders without click | JUMPED | `x2p_focus_*.png` |
| 919 | header identical in both states, real clickable **-** / **+** always there | KEPT, focus and clear | `x4a_*.png`, `x4b_*.png` |
| 921 / 922 | same, **-** / **+** transparent and (922) zero-step when nothing is focused | KEPT, focus and clear, Simple and Glamour | `x5_*`, `s_last_focus_after.png`, `g_last_focus_after.png` |

Reading: hypothesis 1 (cap continuity), 2 (item ids: stable, `article.id.hashCode()`) and 3 (height) are not the cause; hypothesis 4 holds in the sense that the host keeps the position whenever the update leaves the header's views alone and re-creates the list when the clickable **-** / **+** views are added or removed. Decompiled Glance 1.1.0 (`translateEmittableLazyList`): `setHasStableIds(true)`, `setViewTypeCount(getTopLevelLayoutsCount())`, items added with their `itemId`, so the adapter is reused on update; why the header change still re-creates the list was not isolated further (launcher side). No `setScrollPosition` workaround was needed.

Second finding on build 930 (header fix only), Glamour, scale 1.4: scroll to the 26th row (it exists only because a row is focused: no-focus cap 19, focus cap 26), clear focus: the row vanished and the view landed at the end of the 19-row list (`v_gla_last_clear_after.png`). Variant V1 (build 931): a Focus widget with nothing focused is budgeted at the 1x cap (27 rows); the cap changes by one row at a tap and the pin keeps the tapped row.

Final matrix (build 931 unless noted; first visible row kept and the tapped row on screen):

| Check | Simple | Glamour |
|---|---|---|
| tap the last row, then clear | KEPT, KEPT (32/32) `f_simple_last_*` | KEPT, KEPT on 931 (`v1_gla_last_*`); on 922 KEPT, KEPT with the list growing 19 to 26 (`g_last_*`) |
| tap a middle row, then clear | KEPT, KEPT (`f_simple_mid_*`) | KEPT, KEPT (`v1_gla_mid_*`, `g_mid_*`) |
| move focus to another row | KEPT (`e1_*` on 916 already) | KEPT |
| **+** then **-** | zoom 1.40, 1.55, 1.40, KEPT | not repeated |
| tap the transparent placeholder, nothing focused | no state change, no update (`updateAppWidget` count 0) | not repeated |
| row count none / focused | 32 / 32 | 27 / 26 (was 19 / 26) |

#### B. Slow taps (BUG-033)

Method: `adb shell 'date +%s.%N; input tap X Y; date +%s.%N'` (device clock, the second stamp is the injection), then `logcat -v epoch`: temporary `NFTiming` lines (callback start, state done, mark-read done, update returned, `WidgetContent` phases, composition end) plus the app's `AppWidgetManager: updateAppWidget()` and the launcher's `[updateAppWidget id=24] perform updateAppWidget`. "tap to host" = injection to the first perform line. Taps alternate rows 2 to 4 at the top of the list, one tap every 6 to 7 s so the dissolve of the previous tap is over; every tap after the first moves focus / the expanded article and so marks a thread read. Median / p95 in ms.

Phases, Simple, Group ON, Unread only, Focus, 26 taps, 33 rows, 300 stored articles (before = build 922: the header fix but none of the speed changes, the grouping code is the one of 916; after = build 927):

| Phase | Before p50 / p95 | After p50 / p95 |
|---|---|---|
| tap to callback start (trampoline activity) | 32 / 60 | 35 / 52 |
| callback state (`cb_state_done`) | 1289 / 1449 | 168 / 251 |
| of it `renderedRowIds` (grouping 1) | 571 / 641 | 16 / 29 |
| of it `idsToMarkReadOnMoveAway` (grouping 2) | 646 / 695 | 83 / 109 |
| render: decode articles JSON | 39 / 63 | 0 / 0 |
| render: `displayThreadsFor` (grouping 3) | 561 / 618 | 26 / 40 |
| grouping parts: storyStats / tokens / pairs | 284 / 177 / 92 | 12 / 1 / 9 |
| `serializedRowCap` | 6 / 22 | 5 / 12 |
| composition end (from `WidgetContent` start) | 736 / 1102 | 184 / 319 |
| composition end to `updateAppWidget` (Glance builds the RemoteViews) | 186 / 492 | 194 / 269 |
| **tap to the app's first `updateAppWidget`** | 2308 / 2982 | 630 / 1149 |
| **tap to the launcher's first update** | **2327 / 3029** | **669 / 1189** |

Per scenario, tap to launcher update (instrumented builds; before = 922, after = 927 for Simple Focus and 929 for the others, same speed code):

| Scenario | n before / after | Before p50 / p95 | After p50 / p95 |
|---|---|---|---|
| Simple, Unread only, Focus | 26 / 26 | 2327 / 3029 | 669 / 1189 |
| Simple, Unread only, Expand | 25 / 25 | 1981 / 2385 | 660 / 1184 |
| Glamour, Show All, Focus (moves only: 1921 / 3059 before, 773 / 1277 after) | 16 / 26 | 1398 / 2963 | 787 / 1335 |
| Glamour, Show All, Expand | 24 / 24 | 1174 / 1819 | 716 / 1223 |
| Simple, Unread only, Focus, idle (45 s between taps, process alive) | 4 / 4 | 1725, 1747, 3182, 3510 | 683, 827, 1151, 1633 |
| Simple, Unread only, Focus, first tap after `kill -9` | 3 / 5 | 4627, 5280, 6298 | 1955, 4669, 5300, 5307, 5308 |
| Simple, Unread only, Focus, clean build 928, warm | | | 663 / 1703 (two outliers, 1884 and 4381 ms, in 26 taps) |

Each optimisation, Simple Focus Unread, taps that move focus (tap to launcher update, p50 / p95): 922 baseline 2325 / 3041 (n 25); token memo (923) 1010 / 1578 (22); windowed pair scan (924) 896 / 1580 (24); int-id compare (925) 814 / 1430 (17); article list cache (926, controlled move-only taps) 635 / 1158 (25); storyStats one map (927) 669 / 1189 (26, run-to-run noise is about 50 ms). Whole-run medians on the same scenario: 2327, 1006, 957, 810, 690, 669.

JVM microbenchmark (`StoryThreadsPerfTest`, 478 real headlines, desktop JVM, warm, ms per call): 55 before (first measurement on the unchanged code) to 13 to 24 after; the phone is 15 to 25 times slower per call because ART interprets and compiles cold code, which is why the phone numbers above are the ones that count. The unit test also proves equality with the original all-pairs algorithm on both fixtures and, in a mixed read-state and shuffled-order run, on every pair.

Cold start (build 929 with logs, median of 4): process start and trampoline 220 ms, callback state 1787 ms (500 ms of it the first grouping, the rest DataStore and serialization class loading), `cb_to_wc` 2538 ms, composition 823 ms, RemoteViews build 1040 ms, tap to launcher update 4754 ms. That is class loading and JIT of the Glance / Compose / DataStore stack in a debug build; it did not move with the grouping fix (4.6 to 6.3 s before, 2.0 to 5.3 s after, five samples). A baseline profile or a release build is the lever. Not done.

Not changed (numbers too small for the risk): `serializedRowCap` binary search (4 to 7 ms), the grace `SideEffect` scan (1 ms), restricting the thread lookup's grouping to unread articles (about 40 ms), memoising `storyStats` across the three groupings (about 35 ms).

#### Results

| # | Check | Result |
|---|---|---|
| 1 | Focus tap keeps the scroll position (Simple, Glamour, last row, middle row, clear, move, zoom) | PASS on 931, table A |
| 2 | Tap speed, warm | PASS: medians 2327 / 1981 / 1398 / 1174 ms to 669 / 660 / 787 / 716 ms; p95 3029 to 1189 ms on the reported scenario |
| 3 | Tap speed, first tap on a killed process | NOT FIXED (about 5 s), see above |
| 4 | Results identical to the old grouping | PASS (oracle unit test on both fixtures, mixed read states, three orders) |
| 5 | Unit tests | 446 run, 445 pass; the known `WidgetThemesTest colorProvidersFor custom wraps the same scheme rawColorSchemeFor produces` failure is the only one and was already failing |
| 6 | Logs | no FATAL, ANR, `TransactionTooLarge`, `data parcel size`, `Can't show content` in logcat; crash buffer 0 lines at the end |

Restore: temporary widget 24 removed through the launcher (long-press, Remove): `appWidget-24` and `appWidgetLayout-24` are gone, only widget 15's files remain, crash buffer empty, `newsfeed_config` md5 back to the start value `6d485c1f65bad26470de74fbbfb4874b`. Widget 15 md5s start to end: `newsfeed_config` 6d485c1f... to 6d485c1f... (same), `appWidget-15` 30853153... to 253d4c2a... (background refreshes and the user's own use), `newsfeed_read_status` 2e36e0bd... to f10c04d0.... The installed build is 931 (HEAD code).

Side effect to know about: the read-status store is global (last 500 ids, shared by all widgets), so every article a tap of mine marked read there reaches widget 15 at its next refresh. 211 ids from this session are synthetic (`p21://`) and harmless, but they use up space in the 500-id list (440 now, so nothing was evicted yet). In the first hour of this session (before the list was replaced by synthetic, future-dated articles) the Glamour baselines ran on a list whose top rows were real, new articles, so some real ids were marked read by my taps, probably a few dozen N12chat / N12_News / ynet items between roughly 19:10 and 19:45 on the device clock (the real part of the store grew from 82 ids in the end snapshot of the previous session, `C:	\q20
s_end.pb`, to 209, mixed with the user's own reading, which cannot be separated afterwards). They may show as read in widget 15. The store was not edited to undo it.

### 14.ak Final verification (b610825), 2026-10-09

Device RFCR91J237W (Android 15, One UI Home). Code under test: HEAD b610825 (contains `ee42cb8`, `89cb7e3`, `84f0bf7`, `d2fd73a`, `11e345e`); the installed build is `versionCode=931` (same code, no rebuild, no uninstall, source untouched, nothing pushed). Everything was done on a TEMPORARY second widget (id 25, One UI picker, default 11 feeds, Sort changed to Newest first; later Simple / Glamour, Focus / Expand, All / Unread only, Group on / off, Keep 15 minutes / Forever per test). Widget 15 (the user's) was never opened, its gear never tapped, its settings never saved, nothing restored from a backup. Every gear tap was guarded: exactly one NewsFeed host view on screen with a height of about 1100 px (here `[70,158][1008,1264]`; widget 15's is about 2000 px), the settings screen showing Medium font and Newest first, and the activity intent carrying `appWidgetId=25` (`dumpsys activity`); no guard failed. Because the first article list was real, it was replaced by synthetic articles (`p23://`, `p23c://`, `p23d://`, `p23g://`, `p23r://` ids, distinct 8-word titles, 5 minutes apart, dated up to 3 hours ahead; threads built from a lead plus members with one extra word; DataStore of widget 25 only, written with the app killed, `C:\t\plant23.py`) so that the row cap, the last row and the threads were under control. Screenshots in `C:\t\q23\`, scripts `C:\t\q23lib.py`, `plant23.py`. Latency = device clock at the tap injection to the launcher's `perform updateAppWidget id=25` (`logcat -b all -v epoch`).

#### Results

| # | Check | Result |
|---|---|---|
| 1a | Simple, Group ON, Show All, Load more to the cap (ee42cb8) | PASS: first data set (thread only at the top): `visible_article_count` 10, 20, 30, 33 (stops at 33, button gone), footer `Showing 35 of 274 · the widget can't show more at once` under `35(35)` (`s1_cap_footer.png`); after planting the 12-member thread as row 33 the same stored count gives `46(46)` / `Showing 46 of 274` (`s1_b.png`) |
| 1b | Last row = lead of a 12-member thread (`+11`, STORY_MAX_THREAD_SIZE), tap the badge | PASS: badge turns to `−` and 6 of the 11 members appear (partial list, rail ring plus 6 dots, line ends at the last visible member; `s1_expanded_bottom.png`), header `41(41)`, footer `Showing 41 of 274`, no `TransactionTooLargeException` / `Can't show content` / `data parcel size` in `logcat -b all`; collapse (`−`) returns to the cap: `46(46)`, `Showing 46 of 274`, `expanded_thread_ids` cleared. First tap after the plant's process kill took 2.05 s (cold), collapse 1.0 s |
| 1c | Last row = lead of a 3-member thread (`+3`), Simple | PASS: all 3 members appear (`s1b_expanded.png`), header `38(38)` before and after, footer `Showing 38 of 265`, collapse back to `38(38)` |
| 1d | Thread in the middle (`+2` at row 3) expanded while at the cap | PASS (unchanged behaviour): the trailing rows are pushed out, header `38(38)` to `33(33)`, footer `Showing 33 of 265`; collapse `38(38)` |
| 1e | Glamour, last row = `+3` lead at its bitmap cap | PASS: collapsed `31(31)` / `Showing 31 of 265 · reduce font size to see more`; badge tap: badge `−`, NO member added, header `28(28)`, footer `Showing 28 of 265 · reduce font size to see more` (wording unchanged), hollow ring alone with no line, no log errors (`s2_glamour_bottom_b.png`, `s2_glamour_after_badge.png`); collapse back to `31(31)` |
| 1f | Update parcel size | NOT VERIFIED (no tool shows it; only the absence of any `TransactionTooLargeException`, the estimate is at most the 420 000 byte hard budget) |
| 1g | Focus pin (BUG-026) with a focused row beyond the cut | NOT VERIFIED (not exercised) |
| 2a | Footer X equals the header total T (second number) | PASS: Simple 46/46 collapsed, 41/41 with the thread open, 38/38, 33/33 (mid thread open); Glamour 31/31 collapsed, 28/28 with the thread open, 28/28 with only the top thread; Simple Focus focused row `Showing 37 of 265` under `37(37)`; Glamour Focus `Showing 28 of 299` under `27(28)` (U=27 unread of T=28) |
| 2b | Group similar stories OFF | PASS (plain article counts, as before): Glamour `26(26)` / `Showing 26 of 299 · reduce font size to see more`, Simple `33(33)` / `Showing 33 of 299 · the widget can't show more at once` |
| 2c | Short lists show no footer and no Load more | PASS: 20 articles `20(20)`, no footer (`s2_short_list.png`) |
| 3a | Focus header with nothing focused | PASS: N/M text blank (a space), `−` / `+` present as invisible placeholders (uiautomator bounds `[706,181][778,263]` and `[778,181][851,263]`, nothing drawn), list starts at y 307 instead of 269 (+38 px, about 16 dp), looks clean, no clipping (`f0_top.png`, `g0_top.png`) |
| 3b | Tap on the `−`, `+` and N/M area with nothing focused | PASS in Simple and Glamour: 0 `updateAppWidget` calls, 0 host updates, `appWidget-25` md5 identical before and after, `focus_scale` key unchanged (absent in Simple, 1.15 in Glamour), `focused_article_id` empty |
| 3c | Scroll position kept and focused row on screen (Simple): last row, middle row, clear, move | PASS: first visible row and its y identical before and after in all 7 taps (last 23:01 y 340 to 340; N/M 32/32, header 38 to 37; clear KEPT; middle 22/32 KEPT, clear KEPT; move 21/32 to 24/32 KEPT, clear KEPT) |
| 3d | Same in Glamour | PASS: last row 26/26 KEPT, clear KEPT; middle 19/26 KEPT, clear KEPT; move 17/26 to 18/26 KEPT, clear KEPT. Note: with the last row focused the row's Open / Share buttons sit partly below the visible bottom edge (`g3_last_focus_after.png`), the headline and description are visible and one scroll reveals the rest and the footer (`g3b_bottom_after_scroll.png`) |
| 3e | Real `−` / `+`: step and clamp | PASS: Simple default 1.25, `+` 1.40, 1.55, `−` 1.40, 1.25, 1.10, 0.95, 0.80, 0.75, then 0.75, 0.75 (clamp), `+` from 0.75: 0.90 ... 2.25, 2.40, 2.50, then 2.50 x5 (clamp). Glamour (scale was 1.15 left from Simple) `+` 1.30, 1.45, `−` 1.30, 1.15, 1.00, 0.85, 0.75, 0.75, 0.75; 16 x `+` ended at 2.50 (N/M 25/25), back to 1.15 with 9 x `−` |
| 3f | Focus + Unread only: dissolve and whole-thread read | PASS: Simple, focus the `+2` lead (37(37)), move focus to row 1: header 34(37) (3 articles at once), the thread row is dotted at 2.7 s (`u2_dissolve_2p7s.png`), gone after 6 s `35(35)`; DataStore: all 3 article ids `isRead` true. Single row: move focus off row 1, `34(35)` then the row is removed. Glamour: `31(31)` to `28(31)` to `29(29)`, 3 of 3 `isRead` true |
| 3g | Tap latency, Simple, Unread only, Focus, warm (13 focus-move taps, 5.5 s apart, tap to launcher update) | PASS: p50 657 ms, min 545, mean 723, p90 847, max 1122 ms; values 847 723 690 690 1122 657 618 607 597 623 545 638 1048. Two of 13 (1122, 1048 ms) are over 1 s (both after a fast scroll to the top; logcat was cleared per tap and not kept, so the cause was not isolated; matches the 14.aj p95 of about 1.2 s). Other focus taps seen in the series above: Glamour 911, 436, 465, 506, 377, 417, 853, 529, 330 ms; Simple 978, 636, 517, 955, 676, 586 ms. First tap after the DataStore rewrite and process kill (cold): 1.88 and 2.05 s (known, not fixed) |
| 4a | Right-side rail: hollow lead, filled members, badge `+N` / `−` | PASS (`s1b_expanded.png`, `s1_expanded_bottom.png`, `g3_*`) |
| 4b | Thin separators painted | PASS: a line between every pair of rows, also between thread members, in Simple and Glamour in all screenshots |
| 4c | Telegram links under the headline | PASS: copies of two real stored N12_News posts (synthetic ids): headline ends with the link label, the URL is on its own line under it at normal size, Open article / Share below (`t1_tg_expand_after.png`). Stored real data after a refetch: 20 Telegram posts, 0 titles contain `http`, 8 descriptions end with the URL |
| 4d | 15 minute retention present and working | PASS with one caveat: dropdown `15 minutes, 1 day, 3 days, 1 week, 2 weeks, 1 month, Forever`; Save writes `retentionMinutes` 15. Articles aged 2, 6, 10, 13, 14.5, 16, 20, 45, 120 min planted: only 2, 6, 10, 13, 14.5 shown (`5(5)`); the 14.5 min one (published 22:30:11) gone at the render of 22:46:08; the 13 min one (22:31:41) still shown at 22:46:12 (age 14:31) and gone at 22:46:54 (age 15:13). Caveat: one tap-triggered dump at about 22:45:40 still showed the 14.5 min article at age 15:20; no `updateAppWidget` was counted for that tap, so it probably did not render, not re-verified |
| 4e | Expand mode: whole-thread read | PASS: tap the `+2` lead (it expands, `expanded_article_id` = lead), tap another row: header `33(34)` to `30(34)`, all 3 ids `isRead` true |
| 4f | BUG-022 collapse on a description-less tap | PASS: row with description expanded, tap a description-less row: `expanded_article_id` empty, `last_tapped_article_id` moved to the tapped one, the previous one read, exactly 1 `updateAppWidget` (754 ms) |
| 4g | Duplicate ids shown once | PASS: two stored records with the id `p23d://0009` (different titles), one row on screen |
| 4h | `&rlm;` absent | PASS: 0 literal `&rlm;`, `&lrm;`, `&#8206;`, `&#8207;` in the 90 real articles (20 Telegram) fetched on this build; the pre-fix literal itself was not shown again |
| 4i | Logs and crash buffer | PASS: no FATAL, ANR, `Can't show content`, `TransactionTooLarge`, `data parcel size`, bitmap-memory errors in `logcat -b all` at the end and after every action (logcat was cleared before each timed action, so the evidence is per action window, not continuous); `dumpsys dropbox` has no `data_app_crash` / `data_app_anr` entries; crash buffer 0 lines |

No new bug found. Observations (not bugs): (a) Simple Focus at the cap, focusing the last row lowers the cap by one row (33 to 32 rows, header 38 to 37) because the enlarged row costs more, the known "cap changes by one row at a tap"; (b) Glamour at its cap, the opened thread shows `−` with no member and the header total drops by the thread's hidden members (31 to 28), as specified in M-49 / O-09; (c) first-tap-after-kill latency stays at about 2 s.

Restore: temporary widget 25 removed through the launcher (long-press the widget header, Remove): `appWidget-25` and `appWidgetLayout-25` are gone (datastore folder lists only widget 15's files, `newsfeed_config`, `newsfeed_read_status`, `GlanceAppWidgetManager`, `release_notes`), appwidget ids 15, 3, 2 only, crash buffer 0 lines. Widget 15 md5s start to end: `newsfeed_config` 6d485c1f65bad26470de74fbbfb4874b to 6d485c1f65bad26470de74fbbfb4874b (same, shared file, restored by the removal), `appWidget-15` 253d4c2a... to 1cd38121... (background refreshes and the user's own use), `newsfeed_read_status` f10c04d0... to 5bb7431e... (shared read set, +31 synthetic `p23*://` ids from the taps, no real article was tapped). Widget 15's settings were not opened or saved. The installed build is 931 (HEAD code).

### 14.al Background refresh reliability (BUG-034, BUG-035, BUG-036), code done, DEVICE VERIFICATION PENDING

User report: "sometimes the widget does not refresh automatically". Read-only device study: 13 of 27 periodic runs had every DNS lookup blocked (`isBlocked=true`, `APP_BACKGROUND` firewall state), success only within about 51 s of a `CLOCK_TICK`; the worker still returned success and overwrote `last_refresh_time`; the tick alarm chain is armed in only a few places. This corrects the BUG-014 "environmental flap" verdict. Fixes, one commit per group (nothing pushed): network constraint + `UPDATE` migration + saved interval (BUG-034), honest outcomes (BUG-035), truthful footer (BUG-035), self-healing tick + watchdog (BUG-036), boot / package-replace re-arm (BUG-036). QA cases `I-15` to `I-22`, `M-52` to `M-54`.

#### Pending device checks (nothing below was run; no adb was used for this change)

| # | Check | Result |
|---|---|---|
| 1 | After `adb install -r` over the old build (widget NOT removed): `dumpsys jobscheduler` shows `NewsFeedRefresh` with `CONNECTIVITY`, same interval as before; `newsfeed_refresh_status.xml` `work_schema_version` 1 (I-15) | PENDING |
| 2 | Airplane mode for more than one period, screen off, then network on: no run while off, no false "refresh failed", run within about 1 min of the network (I-16, I-21) | PENDING |
| 3 | Interval 6 h survives reboot and update; two widgets use the smaller interval (I-17) | PENDING |
| 4 | Feed answering 404 beside working feeds: `failed_feed_count` 1, `last_refresh_time` advances; every feed failing with the network on: `last_refresh_time` unchanged, at most 3 attempts then success (I-18) | PENDING |
| 5 | Footer texts: fresh `↻ in Nmin`, overdue `Updated Nh ago` (muted), all failed amber, partial `⚠ N failed · ...`; no overflow of Share / gear at the narrowest width and font 3.0 (I-19) | PENDING |
| 6 | Force-stop the app, restart the process: `CLOCK_TICK` pending again within ~1 min, ticks resume; last widget removed: no alarm (I-20) | PENDING |
| 7 | Watchdog: `last_ok_refresh_ms` set old with run-as: exactly one `NewsFeedRefreshNow`, no repeat within one interval, no kick while healthy (I-21) | PENDING |
