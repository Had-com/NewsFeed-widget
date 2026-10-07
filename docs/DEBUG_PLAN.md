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

---

## Reporting

For every ❌, capture: exact steps to reproduce, a screenshot, the widget theme/settings active at the time, and whether it reproduces in both tap modes or just one. File findings as you go rather than batching them — several past bugs in this project were only caught because a specific repro was pinned down immediately rather than described vaguely after the fact.
