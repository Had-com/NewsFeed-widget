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
- [ ] Header controls in Focus mode: **N/M** shows the focused article's position and is accurate after each tap; **− / +** change the focused row's scale, clamped to 0.75×–2.5×, and the scale **resets to default** when focus moves to a different article. None of these controls ever appear on an Expand-mode widget. (The ▲/▼/✕ buttons were removed earlier and are not expected.)
- [ ] Tap the already-focused row again: focus clears and the widget looks like Expand mode.
- [ ] **Switch live, Expand → Focus:** on a widget with one article expanded, open Settings (⚙), switch to Focus, Save. Pull `files/datastore/appWidget-<id>.preferences_pb` (`adb exec-out run-as com.newsfeed.widget cat ...`) and confirm `expanded_article_id`, `last_tapped_article_id`, `focused_article_id`, `focus_scale` are all absent, the article count of `"isRead":true` in `articles_json` is unchanged, and the widget shows no expanded row.
- [ ] **Switch live, Focus → Expand:** with one article focused and a custom scale, switch to Expand, Save. Same DataStore check; no row enlarged; the previously focused article is **not** marked read by the switch itself.
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
| Orphan cleanup | Removed Focus id's appWidget file and config keys gone; no pending CLOCK_TICK_FOCUS after one refresh | After Save on widget 15: `appWidget-18.preferences_pb` deleted, `newsfeed_config` keys widget_15 only (5092 -> 2591 bytes), only one pending `CLOCK_TICK` alarm (CLOCK_TICK_FOCUS appears only in alarm history). `appWidgetLayout-16/-18/-19` files are NOT cleaned (leak, low) | ✅ (⚠️ layout files stay) |
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

---

## Reporting

For every ❌, capture: exact steps to reproduce, a screenshot, the widget theme/settings active at the time, and whether it reproduces in both tap modes or just one. File findings as you go rather than batching them — several past bugs in this project were only caught because a specific repro was pinned down immediately rather than described vaguely after the fact.
