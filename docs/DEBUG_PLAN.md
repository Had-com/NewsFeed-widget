# NewsFeed Widget — Full Debug Plan

Derived directly from `README.md` — every checklist item below maps to a feature or setting the README documents as working. This plan exists to verify that claim on a real device, not to re-derive requirements from the code.

**Test device:** a real phone (this project's own history shows RTL/locale bugs that never appeared on an emulator or in English-locale testing — a real device with a Hebrew-locale toggle is required, not optional).

**Before starting:** install the current build fresh (uninstall any prior version first if its signature might not match — see §0), and place **one "NewsFeed" widget and one "NewsFeed Focus" widget side by side** before beginning, since several checks (§8) require both to exist at once.

**Reporting convention:** for each item, record ✅ Pass / ❌ Fail (with repro steps + screenshot) / ⚠️ Couldn't test (with why). Don't mark anything ✅ without actually observing it — several items below exist specifically because a past session's own optimistic "should work" assumption turned out wrong on-device.

---

## 0. Installation & first run

- [ ] Download `NewsFeed-latest.apk` from the [latest release](https://github.com/Had-com/NewsFeed-widget/releases/tag/latest); confirm the "install unknown apps" and (if shown) Play Protect prompts appear and can be gotten past — this is expected, not a bug.
- [ ] Fresh install (no prior data) → place a widget → confirm `default_feeds.opml`'s Hebrew news feeds load automatically as the starting feed list.
- [ ] Confirm the widget's **default** appearance matches the documented default: **Glamour theme, Light variant, accent colors on**.
- [ ] Open the system "Add widget" picker → confirm **both** "NewsFeed" and "NewsFeed Focus" appear as separate entries under one app, with correct labels and no duplicate/missing entries.
- [ ] Place a widget of each type simultaneously → confirm both render independently without interfering with each other.

---

## 1. Sort & Filter section

For each control, change it, tap **Save**, and confirm the *widget itself* reflects the change — not just the Settings screen's own state.

- [ ] **Sort by** — Newest first, Oldest first, By feed (verify true round-robin interleave, not just grouped-by-feed), Unread first (verify unread articles float above read ones regardless of date).
- [ ] **Show** — All, Unread only (read articles genuinely absent, not just dimmed), Read only.
- [ ] **Refresh every** — cycle all 7 options (15m/30m/1h/2h/4h/6h/12h); confirm via `adb shell dumpsys jobscheduler | grep -A5 NewsFeedRefresh` that the scheduled interval actually changed each time (waiting out the real interval isn't practical for the longer options — the job-scheduler period is the verifiable proxy).
- [ ] **Keep articles for** — set to "1 day", let the widget accumulate articles older than that, refresh, and confirm they're pruned while the 300-article cap still applies independently (test "Forever" too — confirm no pruning happens regardless of article age).
- [ ] **Open article in** — Browser (opens default browser) vs Share sheet (opens Android's share chooser); confirm both mark the article read and trigger a refresh.
- [ ] **Font size slider** — drag to each labeled tier (Tiny/Small/Medium/Large/Huge); confirm headline/meta/header/footer text visibly scales.
- [ ] **Article font size slider** — same tiers, confirm it scales *only* expanded body text and does **not** move the headline size (the two sliders must be provably independent — change one, confirm the other's rendered size doesn't shift).
- [ ] **Background rows size slider** — confirm this row is **present** on the Focus widget's Settings and **absent** on the standard widget's Settings for the exact same underlying setting.
- [ ] **Live preview card** — confirm it visibly updates in real time as you touch the theme, font size, article font size, and article-length controls above it, without needing to Save first.
- [ ] **Expanded article** (length mode) — Subtitle only (~100 chars), First paragraph (~400 chars), Full article (fetches the real page, see §2).
- [ ] **Widget theme** — cycle all 8 (Auto, Lavender, Amethyst, Glassy, Simple, Aerospace, Data Science, Glamour); confirm each renders its own documented palette/character, and confirm only Glamour shows the handwriting-font bitmap headlines (every other theme should show plain, crisp system-font text — if any non-Glamour theme shows the handwriting font, that's a real regression).
- [ ] **Theme variant** (Light/Dark) — confirm independent of the device's system dark-mode setting.
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
- [ ] Repeat the "finds an update" checks above for **both** widget packages/types if you have a way to test an older Focus-widget-only or standard-widget-only install — confirm each only ever offers its own correct build (there's only one shared APK now, so this mostly reduces to "confirm the same build number shows correctly in both widgets' Settings screens").

---

## 7. Focus Mode (NewsFeed Focus widget only)

- [ ] Tap any article — confirm it enlarges ("focuses"), every other row shrinks, and its description/full text **auto-expands inline** with no separate tap needed.
- [ ] Confirm the header now shows **▲ ▼**, an **N/M** position indicator, **✕**, and **− +** — and confirm none of these appear on the standard "NewsFeed" widget at any time.
- [ ] **▲ / ▼** — step through several articles; confirm focus moves correctly at both ends of the list (first/last article — should clamp, not wrap or crash) and the **N/M** indicator stays accurate at every step.
- [ ] **✕** — clears focus; confirm every row returns to normal size.
- [ ] **− / +** — adjust the focused row's scale; confirm it's clamped to the documented 0.75×–2.5× range; step focus to a different article and confirm the scale **resets to default** rather than carrying over.
- [ ] Tap the already-focused row again (not the ✕ button) — confirm this also clears focus, acknowledging this only reliably works if the tap lands on the row's *current* (possibly already-shrunk-back) bounds.
- [ ] Change **Background rows size** in Settings (e.g. to 25% and separately to 100%) — confirm the *unfocused* rows' relative size visibly changes accordingly while the focused row's own size (governed by −/+) is unaffected.
- [ ] Confirm tapping a focused article's **Load more ↓** / **Open article →** / **Open in browser ↗** controls (from the auto-expanded content) work exactly as they do on the standard widget.

---

## 8. Shared background work across both widget types

Requires both a standard and a Focus widget placed simultaneously (per the setup note above).

- [ ] With both placed, confirm both `NewsFeedRefresh` and `NewsFeedUpdateCheck` jobs are scheduled (`adb shell dumpsys jobscheduler`) and both `com.newsfeed.widget.CLOCK_TICK` / `CLOCK_TICK_FOCUS` alarms are pending (`adb shell dumpsys alarm`).
- [ ] Remove **only** the standard widget → confirm the shared jobs are **still scheduled** (the Focus widget still needs them) and the Focus widget's own `CLOCK_TICK_FOCUS` alarm is still pending, while the standard widget's now-orphaned `CLOCK_TICK` alarm is gone.
- [ ] Re-place a standard widget, then remove **only** the Focus widget instead → confirm the mirror-image result (shared jobs survive, standard's `CLOCK_TICK` still pending, `CLOCK_TICK_FOCUS` gone).
- [ ] Remove **both** widgets entirely → confirm the shared jobs are now genuinely cancelled (`NewsFeedRefresh`/`NewsFeedUpdateCheck` no longer listed) — this closes the loop on "does cleanup actually happen, not just survival."
- [ ] Place at least one widget again, then reboot the device → confirm refresh, update-check, and the correct clock-tick alarm(s) all resume automatically without needing to reopen the widget or Settings.

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
- [ ] (Focus widget only) Glamour + Font size 3.0× + focus scale at its own maximum (2.5×) simultaneously on the focused row — the historically worst-case combination — confirm the same: no crash, a real (even if small) row count still renders.
- [ ] A non-Glamour theme (e.g. Simple or Data Science) with 200+ accumulated articles — confirm the widget can scroll to reach close to the real total (up to 300), not capped at a flat 60 the way Glamour legitimately is.
- [ ] With articles near the 300-item accumulation cap, confirm "Load more articles ↓" and the "Showing X of Y" messaging both make sense and match what's actually reachable.

## 11. Widget resizing

- [ ] Resize a placed widget down to its minimum (130×200dp) — confirm layout doesn't clip/overlap in a broken way.
- [ ] Resize up to its maximum (500×600dp) — confirm content scales/reflows sensibly rather than leaving large dead space or breaking the header/footer.
- [ ] Repeat for both widget types.

## 12. Known-bug regression spot-checks

Quick confirmations that specific, previously-reported-and-fixed bugs haven't resurfaced:

- [ ] Hebrew headline text shows normal word spacing (no oversized gaps from a stray justification mode).
- [ ] After stepping Focus to a different article, the *previously*-focused row's highlight/size does not stick — only the newly-focused row is enlarged.
- [ ] The header's unread/total badge reflects only what's currently visible/scrollable, not the full up-to-300 accumulated store.
- [ ] Meta-row (feed name/timestamp) and article preview text are comfortably legible at default settings — not the thin/small rendering from before the readability fix.
- [ ] Non-Glamour headline text never renders in the Playpen Sans Hebrew handwriting font under any theme selection.

---

## Reporting

For every ❌, capture: exact steps to reproduce, a screenshot, the widget theme/settings active at the time, and whether it reproduces on both widget types or just one. File findings as you go rather than batching them — several past bugs in this project were only caught because a specific repro was pinned down immediately rather than described vaguely after the fact.
