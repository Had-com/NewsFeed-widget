# Known bugs

Tracking list for issues found during QA/debugging. Numbered in the order found, not by
severity. See `docs/DEBUG_PLAN.md` for the QA checklist these were mostly found against.

## BUG-001 — Sort by / Show settings had no effect on displayed articles

**Status:** Fixed (commit `3041041`), verified on-device.

`config.sortOrder`/`config.filter` were applied to a freshly-fetched batch inside
`NewsFeedRepository.getArticles()`, but `WidgetWorker`'s merge step re-sorted the whole
accumulated store by `publishedAt` unconditionally on every refresh, discarding any
non-default sort order, and never re-applied the filter to previously-stored articles.
Fixed via `ArticleSorting.applyFilterAndSort()`, applied at render time in
`NewsFeedWidget.kt`.

## BUG-002 — Per-feed RTL/LTR direction gets flipped by device system locale

**Status:** Open — first fix attempt failed verification, root-cause investigation ongoing.

A feed explicitly configured RTL or LTR (`FeedConfig.layoutDirection`) should render that
way regardless of the device's system locale. Confirmed on-device: under Hebrew system
locale, both an RTL-configured feed (rotter.net) and an LTR-configured feed (ynet) render
identically mirrored — the per-feed setting is being overridden by the device locale.

First fix attempt (`FeedItemRow.kt`, commit `87ce124`) XOR'd the feed's configured
direction against a computed `deviceIsRtl` boolean, reasoned out via a truth table. Verified
on-device to have **zero measurable effect** — both feeds still flip identically, exactly as
before the fix.

Decompiling the actual `glance-appwidget:1.1.0` runtime shows Glance converts
`Alignment.Start`/`End` into Android's `Gravity.START`/`END` — direction-*relative*
constants — which the OS resolves to physical left/right at inflation time based on the
ambient (real device/launcher) layout direction; Glance never calls
`RemoteViews.setViewLayoutDirection` anywhere to override this. A temporary on-screen
diagnostic (commit `338a6b4`) was added to print the literal computed `isRtl`/`deviceIsRtl`
values per row.

**On-device diagnostic results (confirmed):** the device-locale read itself is correct —
`deviceIsRtl` reliably flips false→true when switching to Hebrew. The bug is downstream of
that: with the XOR formula in place, the computed `eff` (final per-row `isRtl`) also flips
between locales for a given feed (e.g. rotter.net: `eff=true` under English, `eff=false`
under Hebrew) — and the header row's visual layout was confirmed to mirror between the two
locale states right along with it. So the formula is not neutralizing the device locale at
all; it's just relabeling which literal Kotlin branch runs, while the real rendered output
still tracks device locale rather than staying pinned to the feed's own setting. This
matches the theory above (Android's own ambient-locale Gravity/LinearLayout mirroring,
independent of anything computed in `FeedItemRow.kt`) but the XOR's cancellation math
doesn't hold up in practice — likely because the meta-row's RTL/LTR branches
(`FeedItemRow.kt`) swap actual child *content/spacing* (not just alignment), which may not
mirror symmetrically the same way a pure alignment flip would. Fix attempt #1 (the XOR) is
now confirmed failed both by visual bounds (prior verification) and by the diagnostic
values (this pass). Next: investigate whether Glance exposes any modifier that can pin an
absolute (non-mirroring) layout direction per-subtree, before considering a rewrite of the
per-row RTL logic to avoid relying on Alignment.Start/End and relative child ordering at
all.

## BUG-003 — Settings screen labels not Hebrew-localized

**Status:** Deferred — needs product decision, not yet root-caused as a bug.

Only the app name and widget picker labels are actually localized into Hebrew; the
Settings screen's own controls are English-only. Unclear whether this is an intentional
scope limit or a gap — flagged to the user, no action taken pending their input.

## BUG-004 — Article time not in sync with the feed's actual article time

**Status:** Did not reproduce on the Standard widget — closing pending a fresh, specific
report. **Process note: every future check of this must cover BOTH the Standard and Focus
widgets** — the original verification pass only checked one instance, and each widget
instance (`appWidgetId` 15=Standard, 16=Focus, confirmed via `adb shell dumpsys appwidget`)
keeps its own fully independent config and accumulated article store, fetched separately by
`WidgetWorker.doWork()`'s per-`glanceId` loop. Re-checked both widgets directly on-device
after this was raised again: at that moment both showed identical timestamps for identical
articles (both had refreshed within 1 second of each other), so no live discrepancy was
caught — but this doesn't rule out genuine drift having occurred earlier during the same
day's heavy testing (rapid feed add/remove, refresh spam, locale switching), since the two
stores are independent and nothing keeps them in lockstep between refreshes. If seen again,
capture which widget (Standard/Focus) and the specific article/time.

Reported: the timestamp shown on an article row doesn't match the real publish time from
the source feed. On-device: cross-checked 6 articles across 4 feeds (rotter.net, ynet,
Google News, Walla — covering both `+0300`-offset and `GMT`-suffix `pubDate` formats)
against the widget's actual stored/displayed time. Every one matched the live feed's real
`pubDate` exactly to the second, with correct timezone conversion to device-local time.
Caveat: only tests articles fetched fresh during this session — doesn't rule out an
edge-case date format not present in today's live feeds, or a bug specific to articles
carried over from an older build. If this recurs, capture the specific article (title/feed)
and the mismatch amount so it can be pinned down precisely.

## BUG-005 — New articles don't appear on time

**Status:** Did not reproduce as a backend bug — likely explained by BUG-006's tap-target
issue (see below).

Reported: new articles from a feed are not showing up promptly/on the expected refresh
schedule. On-device: a mistap on the refresh control's true (narrow) hit target produced no
refresh at all, with the countdown simply ticking down naturally — easy to misread as
"nothing happens on refresh." Once tapped precisely on the correct bounds, a manual refresh
fetched and inserted genuinely new articles in under 1 second. Likely explanation: the same
narrow hit-target problem as BUG-006, causing real-world taps to miss and appear as "did
nothing" / "not on time." Keep an eye out after BUG-006's fix ships — if reports continue
despite a comfortably-sized tap target, re-open with a specific feed/timing repro.

## BUG-006 — Refresh button's tap target doesn't cover its own icon + text

**Status:** Fixed and verified on-device (commit `89db72e`).

The countdown/refresh `Text` in `WidgetFooter()` (`NewsFeedWidget.kt`) had `.clickable()`
applied directly with no padding, so its tap target was exactly the tight wrap-content
bounds of the rendered glyph string. The adjacent gear (`⚙`) button in the same `Row`
already used `.padding(4.dp)` before `.clickable()` for a comfortable hit area — the
refresh text just never got the same treatment. Fixed by adding the matching padding.

**Verified:** measured clickable bounds via `uiautomator` went from an estimated ~321×31px
to a confirmed 343×53px (roughly matching the gear button's own proportional padding
increase). A tap inside the new padded-but-previously-outside-bounds region reliably
triggered a real refresh (confirmed via logcat: broadcast fired, `WM-WorkerWrapper` reported
`SUCCESS` ~4.6s later); a tap just outside the new bounds correctly did nothing, confirming
the measurement wasn't just a generous accessibility box.

## BUG-007 — Refresh doesn't insert new articles

**Status:** Did not reproduce — likely explained by BUG-006's tap-target issue (see BUG-005).

Reported: triggering a refresh does not add newly-published articles to the widget's list.
On-device: once the refresh control was tapped on its actual (narrow) hit target, genuinely
new articles were fetched and merged into the stored article list (confirmed via a
before/after diff of the on-disk state) within under a second, with no errors in logcat.
`WidgetWorker.doWork()`'s merge logic checked out correctly both from source and live
behavior. One real risk still worth tracking separately, spotted in passing rather than
confirmed as reachable: `ArticleItem.id` falls back to
`"${feed.feedId}_${System.nanoTime()}"` (`NewsFeedRepository.kt:308`) when a feed item has
no `<guid>`/`<id>` AND no usable `<link>` — such an item would get a brand-new random ID on
every fetch, defeating the `it.id !in freshIds` dedup. None of this project's currently
configured feeds hit that fallback path, so it's not a confirmed bug, just a latent risk if
a feed without guid/link is ever added.

## BUG-008 — Newly added feeds don't appear on screen

**Status:** Confirmed with a real, quantified repro — two distinct contributing causes.

First generic test (a single new Hebrew news feed added, see below) did not reproduce — it
appeared within a minute, no problem. Re-opened after the user added 3 real feeds
(`https://www.artificialintelligence-news.com/feed/`, TechCrunch's AI category feed, AI
Weekly) that genuinely never appeared. Two separate confirmed causes:

1. **`artificialintelligence-news.com` returns HTTP 403** to the app's exact request
   (verified live, same headers as `NewsFeedRepository`'s `browserHeaders()`).
   `fetchFeedArticles()` (`NewsFeedRepository.kt:172-183`) returns an empty list on any
   non-2xx response, so this feed silently contributes zero articles whenever blocked —
   looks intermittent (one article from an earlier successful fetch is still in storage),
   possibly a bot-detection WAF reacting to the UA/headers. All 3 feeds were added via
   Find Feeds search, not typed manually — correctly pointed out that a search result
   should never be offered if it isn't actually addable in the first place.
   **Fixed:** `searchFeeds()` (`NewsFeedRepository.kt`) now validates every Feedly search
   candidate (in parallel, same `async`/`awaitAll` pattern as `getArticles()`) by actually
   fetching and parsing it with this app's own request headers before including it in
   results — a candidate that's dead, blocked, or unparseable is silently dropped from the
   list instead of being offered and then failing after the user adds it.

2. **Real root cause, quantified on-device:** the accumulated article store is capped at
   exactly 300 (`WidgetWorker.kt:74`), sorted newest-first globally across ALL feeds. Mixed
   into a widget already full of very high-frequency Hebrew news feeds (rotter.net, ynet,
   walla, etc. — many posts/hour each), a low-frequency feed's few articles get buried and
   evicted almost immediately. Confirmed by rank: one TechCrunch article placed at #19 of
   300 (technically visible, needs scrolling), a second at **#297 of 300** — on the verge of
   eviction by the very next refresh. AI Weekly and artificialintelligence-news.com each had
   exactly 1 stored article, similarly buried. This isn't a fetch failure — it's the global
   newest-first sort + shared 300-cap starving any feed that posts much less often than its
   neighbors.
   **Fixed:** chose the "per-feed minimum guarantee" approach. New
   `data/ArticleRetention.kt`'s `retainWithPerFeedGuarantee()` guarantees every feed keeps
   at least its 10 newest articles (matching the existing per-feed cap already used at
   render time for known feeds) regardless of the global newest-first ranking, only filling
   remaining slots up to the 300 cap with the next-newest articles overall. Wired into
   `WidgetWorker.kt`'s merge step in place of the old flat `.sortedByDescending {
   }.take(300)`.

**Status:** Both fixes implemented and pushed (commit `89db72e`), verified on-device.

**Verified — cause 1 (Find Feeds filtering):** searching "artificial intelligence news"
returned 7 results and `artificialintelligence-news.com` was no longer among them.
`isFeedReachable()` confirmed genuinely wired into the real fetch/parse path, not just
present in source. Note: this only prevents the broken feed from being *offered again* — it
does not retroactively remove the copy already added earlier in the same testing session
(that instance still yields 0 articles, as expected, since the feed is still actually
blocked; out of scope of this fix).

**Verified — cause 2 (per-feed minimum guarantee):** before/after a manual refresh,
TechCrunch AI reliably held exactly 10 stored articles with its oldest at rank **#267 of
300** — comfortably clear of eviction, versus the pre-fix #297/300. Clean, direct
confirmation the guarantee works.

**New finding while verifying cause 2 — see BUG-010:** AI Weekly (`http://aiweekly.co/issues.rss`)
still shows 0 stored articles even after the fix, for an unrelated reason: cleartext (plain
HTTP) traffic is almost certainly being blocked by Android's platform default.

## BUG-009 — Feed row's "×" remove button opens Edit instead of removing

**Status:** Open, found incidentally during BUG-008 verification, not yet investigated.

In the feed list (Settings), tapping a feed row's "×" remove control repeatedly opened the
"Edit feed" dialog instead of removing the row — possibly the parent row's edit-tap region
overlaps/intercepts the small "×" hit target (same general class of issue as BUG-006).

## BUG-010 — HTTP (non-HTTPS) feed URLs silently never fetch

**Status:** Found incidentally while verifying BUG-008, not yet fixed.

`http://aiweekly.co/issues.rss` is a live, valid feed (confirmed via direct `curl` with the
app's own headers — HTTP 200, valid RSS) but produces zero stored articles in the app.
`app/build.gradle.kts` targets SDK 35 with no `networkSecurityConfig` override, so Android's
default cleartext-traffic block almost certainly rejects the plain-HTTP request before it
ever reaches the feed — silently, since `fetchFeedArticles()`'s `runCatching` swallows the
exception with no logging. Not yet fixed: either add a `networkSecurityConfig` permitting
cleartext for feeds that need it (weakens transport security for those requests), reject/flag
`http://` feed URLs at add-time with a clear message instead of silently accepting them, or
just leave it as a known limitation and document it. Needs a product decision.

## BUG-011 — Rapid repeat taps on the on-widget refresh button enqueue redundant work

**Status:** Fixed and verified on-device (commit `87e46cb`).

`WidgetWorker.refreshNow()` (called by `RefreshNowCallback` on every refresh-control tap,
and by `WidgetConfigActivity`'s Save handler) used a plain `WorkManager.enqueue(...)` with no
uniqueness constraint — unlike every other periodic-work call site in the same file
(`schedule()`/`ensureScheduled()`), which explicitly use `enqueueUniquePeriodicWork` for
exactly this reason. Rapid repeat taps enqueued that many fully independent WorkManager
jobs, each running a complete fetch-all-configured-feeds cycle concurrently — wasteful of
network/battery, with no user-facing "already refreshing" indicator to discourage the repeat
taps in the first place (the analogous "Check for updates" self-update button already got a
double-tap guard for this same class of problem; the on-widget refresh never did). Fixed by
switching to `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)` under its own work name,
separate from the periodic job's — a tap while a manual refresh is already
pending/running is now silently absorbed instead of stacking another one.

**Verified:** 5 taps fired within 723ms collapsed to exactly 1 `WidgetWorker` execution
(confirmed by unique WorkManager Work id in logcat), which updated both widget instances
correctly with no errors. A follow-up single tap issued after that run completed triggered a
new, separate execution as expected — confirming the dedup only blocks *concurrent* repeat
taps, not all future refreshes.

## BUG-012 — Focus widget's last visible article row can be clipped mid-glyph

**Status:** Found during an 8-theme × 2-widget visual QA sweep, not yet investigated.

On the Focus widget specifically (not seen on Standard), when the accumulated content
doesn't divide evenly into the widget's fixed allocated height, the last visible row's final
line of text can be sliced mid-character by the footer bar instead of either fully fitting
or being hidden entirely. Confirmed theme-independent (same widget/content, clean clipping
in most themes, glyph-cutting seen in the Aerospace-theme capture) — a layout/content-fit
issue, not a per-theme rendering defect. Needs investigation into how Glance's `LazyColumn`
interacts with a host-allocated fixed-height widget; possibly a known Glance/RemoteViews
clipping limitation similar in spirit to the ones already found in BUG-002's investigation
(see the `docs/superpowers/plans/2026-09-04-remoteviews-rewrite.md` plan for prior-session
context on Glance's rendering limitations more broadly), rather than something fixable with
a small padding/sizing tweak — not yet confirmed either way.

## BUG-013 — Some article titles show raw HTML entity text instead of the real character

**Status:** Partially fixed (commit `88ea007`) — majority of cases confirmed working, but a
genuine, unexplained residual inconsistency remains. Root cause of the residual not yet
found; needs a programmatic diagnostic, not another guess.

Found during the same visual QA sweep: some titles displayed literal text like
`&amp;#128308;` or `&amp;#8207;` instead of the intended emoji/RTL-mark character. Confirmed
against the live rotter.net feed — its `<title>` tags genuinely contain double-escaped HTML
entities in the raw XML (e.g. `&amp;#128308;&amp;#128308;&amp;#128308;...`). The XML parser
only unescapes one level (`&amp;` → `&`), leaving the resulting `&#128308;` as literal text
content rather than decoding it further. `description` already runs `Html.fromHtml()` for
exactly this reason (see its own comment in `parseItem()`); `title` never got the same
treatment. Fixed by applying the identical `Html.fromHtml(..., FROM_HTML_MODE_COMPACT)` step
to the parsed title in `NewsFeedRepository.kt`.

**On-device verification found the fix works for most cases but not all.** Cross-referencing
stored titles against the live feed by article guid/link, confirmed across 3 separate
refreshes over several minutes (ruling out simple staleness):
- `962753.shtml`: `&amp;#128308;&amp;#128308;&amp;#128308;...` → correctly decoded to 🔴🔴🔴.
- `962752`, `962749`, `962745`: `&amp;#8207;...` (RTL mark, at string start or after "word: ")
  → correctly decoded in all three.
- `962739.shtml`: `ג&amp;#1523;...ארד קושנר: &amp;#1524;...` (geresh/gershayim, one mid-word,
  two after "word: ") → **all three occurrences stayed literal, undecoded**, unchanged
  across repeated refreshes.
- `962703.shtml`: `...: &amp;#8207;...` — **same entity, same "word: &#N;" structural shape
  as the successful 962745 above, yet failed** to decode.

That last comparison rules out the two most obvious hypotheses: this isn't about *which*
entity value it is (`&#8207;` succeeds elsewhere), and it isn't about *where* in the string
it sits (identical "colon-space-entity" shape succeeds in one title, fails in another).
Also checked and ruled out: stray ASCII punctuation elsewhere in a title confusing
`Html.fromHtml`'s tag-soup parser (962752 has a literal `''` mid-string and still decoded
correctly); simple re-fetch staleness (the broken examples are well within the per-feed
50-item fetch cap, so they're being freshly re-parsed every refresh and still coming out
wrong every time). `description` fields show zero leftover raw entities anywhere — the
regression is specific to the title path.

This looks like a genuine, content-dependent quirk in `Html.fromHtml()`'s own parsing
against these specific raw strings, not a caching/environment artifact. Since text-level
comparison has been exhausted without finding the differentiator, the next step is a direct
programmatic test — e.g. an instrumented on-device test (or a temporary diagnostic build)
that calls `Html.fromHtml()` against these exact raw strings in isolation and inspects the
result — rather than a second guess-based patch.

## BUG-014 — Occasional "refresh failed" affecting every configured feed at once

**Status:** Root cause confirmed. Not a code bug — an OS/device battery-management policy
interaction. Needs a product decision on whether to add an in-app mitigation.

Reported live three times in one session: the widget footer briefly shows "⚠ refresh failed
— tap to retry". `lastRefreshFailed` is only set `true` when `allFailed` is true in
`NewsFeedRepository.getArticles()` — every single enabled feed's fetch threw on that
attempt, ruling out ordinary single-feed flakiness. First two occurrences resolved on retry
before the cause could be captured (logcat rotated out / was accidentally cleared). On the
third occurrence, caught directly in the raw system log at the exact failure timestamp:

```
DNS Requested by 106, 10323(com.newsfeed.widget), 4(FAIL), isBlocked=true, 0ms
```

— repeated once per feed host, every one instantly rejected (`0ms`, not a timeout) with
`isBlocked=true`. That's Android's network-policy layer actively *blocking* the app's
outbound DNS at that moment, not a real connectivity problem — confirmed separately via
`adb shell dumpsys connectivity` showing WiFi fully connected and validated at the same
time. A `FreecessController` "importance" transition (Samsung One UI's own aggressive
background-app management layer, distinct from stock Android Doze) was logged for the app
within the same second. Root cause: when the periodic/background refresh fires while the
app is sitting in a low-priority background state, Samsung's battery management blocks its
network access for that cycle — every feed fails at once, while
`WidgetWorker.doWork()` still unconditionally returns `Result.success()` to WorkManager
(it never crashes, it just silently gets zero data back), so this is invisible in
WorkManager's own success/failure reporting and only shows up via the app's own
`lastRefreshFailed` flag.

**Not fixable as a simple code change** — this is standard, documented Android/OEM
battery-management behavior for background work in low-priority apps, not a defect in this
app's fetch/merge logic. The standard mitigation apps use is prompting the user to exempt
the app from battery optimization (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` /
"Unrestricted" data usage) so the OS stops deprioritizing its background network access —
worth doing, but it's a visible, somewhat heavyweight ask to put in front of every user, so
flagging for a product decision rather than adding it unilaterally.

## BUG-004 addendum — Walla "01:13" report investigated, not a bug

A live report ("walla article time 01:13 while the time now is 22:57") was investigated in
full: the live Walla feed's raw `<pubDate>` for that article is `22:13:00 GMT` — correctly
22:13 UTC = 01:13 IDT (Israel is UTC+3 in September), rolling into the *next* calendar day.
Confirmed on-screen the widget actually displays **"07/09 01:13"** (with the date prefix,
via `formatDateTime()`'s cross-midnight branch) — parsing, timezone math, and display
formatting are all correct. The report omitted the date prefix when describing what was
seen; no fix needed.

---

**Cleanup note:** the temporary `DBG cfg=... dev=... eff=...` diagnostic line added for the
BUG-002 investigation was found still visibly rendering on every article row in production
during a routine on-device check — removed in commit `c230520`. **That commit was pushed but
never actually reinstalled on the test device** — a follow-up theme QA pass still saw the
debug line because it was testing the stale, still-installed `89db72e` build, not `c230520`.
Fixed by actually installing the CI build for the next commit after it (`87e46cb`), which
includes both this removal and the BUG-011 fix. BUG-002 itself remains open; only the
leftover visible debug output was removed. Lesson: pushing a commit doesn't put it on the
test device — always explicitly rebuild+reinstall before the next verification pass, don't
assume a prior "installed" state carried forward.

---

**Cleanup note:** a test feed added during BUG-008 verification — "מבזקי ערוץ 7"
(`https://www.inn.co.il/Rss.aspx`) — was left configured on the device because BUG-009
prevented removing it during that same session. Remove it manually from Settings, or once
BUG-009 is fixed.

## BUG-015 — Low-frequency feeds' articles unreachable even after BUG-008's retention fix

**Status:** Fixed by changing the default sort order (commit `1f84ddd`).

Follow-up to BUG-008: even with `retainWithPerFeedGuarantee()` correctly protecting a
low-frequency feed's articles from eviction in the 300-article *store*, they still never
appeared on screen. Root cause: `NewsFeedWidget.kt`'s render layer has its own, completely
separate row-count ceiling (`maxRowsAllowed`, derived from a per-row memory budget) —
`availableArticles.take(visibleCount.coerceAtMost(maxRowsAllowed))`. Under "newest" sort,
`availableArticles` is sorted newest-first across every feed combined, so only the top
~17-20 articles overall (observed on-device) are ever reachable via "Load more," no matter
how many exist in storage. A widget mixing several high-frequency Hebrew feeds with a
handful of low-frequency English ones would nearly always fill that entire window with the
high-frequency feeds' content. Fixed by making "By feed" (round-robin, one article per feed
per round) the new default sort order — under that mode a quiet feed's newest article always
lands within the first round, well inside any reasonable row ceiling, regardless of how
often its neighbors post. Verified conceptually (round-robin's guarantee is order-
independent of posting frequency by construction); not yet re-verified on-device with the
specific AI feeds that originally surfaced this.

## BUG-016 — accentColor inconsistency between feed-add paths

**Status:** Fixed (commit `1f84ddd`).

Of the four places a `FeedConfig` gets created — the initial OPML-seeded defaults, a
mid-session OPML import, manually typing a URL, and adding a Find-Feeds search result — only
two (initial defaults, search-add) assigned a rotating `accentColor` from the theme's
palette; manual URL add and mid-session OPML import left every new feed at the class default
`#9B72E3`. Invisible whenever "use theme colors" is on (the default — all feeds render with
the theme's own accent regardless of their stored per-feed color), but a real, visible
inconsistency once a user turns that off: every manually-added feed would look identical
while feeds added via search or initial setup varied. Fixed by applying the same
palette-rotation logic to all four creation sites.

## Feature additions (2026-09-07)

- **Black & White theme** added — pure black/white only, no intermediate grays, distinct
  from the existing grayscale "Simple" theme.
- **Feed config backup/restore around app updates** — `ConfigBackup.kt` backs up every
  widget's feed list right before the self-update flow hands off to the installer, and
  transparently restores it if a widget's feeds are ever found empty on a subsequent
  refresh. A safety net alongside (not a replacement for) Android's own automatic data
  preservation across a normal same-signature app update.
- **Theme spec compliance check**: verified all 7 pre-existing themes' colors (including
  Glassy's alpha-channel values) and font-family assignments against the "NewsFeed Widget
  Themes" artifact — full compliance, no discrepancies found.

## On-device verification of the 2026-09-07 batch (commit `1f84ddd`)

**BUG-015 (By Feed default) — confirmed.** Placed a brand-new third widget instance and
opened its Settings without touching anything: "Sort by" already showed "By feed," and the
persisted config has no `sortOrder` override (consistent with it being the true code
default, not a UI-only default). Also confirmed the AI feeds win predicted by this fix:
under By Feed sort, TechCrunch AI's article appeared at row 10 of 10 loaded — reachable
within the normal "Load more" flow, where under "newest" sort it was buried past the render
ceiling entirely.

**BUG-016 (accentColor consistency) — confirmed fixed.** With "use theme colors" off, added
one feed by typing a URL and one via Find Feeds search: they landed on `#D4A574` and
`#C8956A` respectively — different from each other and neither the old default `#9B72E3`.

**Black & White theme — mostly confirmed, one real gap found.** Persisted correctly to
disk, and headline/body text render genuine pure black-and-white, starker than "Simple."
**But it isn't fully monochrome**: per-feed favicon circles keep their original brand colors
(red, gold, multicolor logos), and the "⚠ refresh failed" banner renders in orange rather
than black/gray. Not a regression specific to this theme — "Simple" has the identical
colored-icon behavior — but it does mean "Black & White" doesn't yet deliver on a literal
reading of "no color anywhere." Not yet fixed; needs a decision on whether favicons/status
banners should be forced to grayscale specifically for this theme (see also the still-open
question of whether the original "white text isn't pure white" report is about this, or
something more specific not yet isolated — pending a pixel-level check).

**New, minor finding:** a widget's config entry in `newsfeed_config.preferences_pb` is not
purged when the widget itself is deleted from the home screen — confirmed still present in
the datastore several minutes after removal. Not investigated further; likely a missing
`WidgetConfigStore.delete(widgetId)` call in whatever handles widget removal (`onDeleted`).
Minor (stale data, not a functional or security issue), not yet triaged as a numbered bug.

**Inconclusive, not reproduced a second time:** one observation of a widget's Settings
screen briefly showing "Black & White" as selected despite that widget's on-disk config
having no theme override — resolved itself after a force-stop + reopen showed the correct
persisted theme. Possibly a stale in-memory value bleeding between widget instances within
the same app process/Settings ViewModel, but not reliably reproducible. Flagged for
awareness, not filed as a confirmed bug.

## BUG-017 — Focus Mode zoom fix, verified

**Status:** Fixed and verified on-device (commit `e3afc5f`).

See the fix description above (`articleFontSize` wasn't covered by the Focus-scale shadow
that already applied to `fontSize`). Verified precisely by measuring rendered line-height
pixels at two focus-scale extremes (1.25x default, 2.5x max) via direct pixel-row-band
detection on real screenshots: headline lines grew 38.3px → 78.7px (2.05x) and description
lines grew 33px → 67px (2.03x) — both within measurement noise of the expected exact 2.0x
ratio (2.5 / 1.25), and tracking each other in lockstep. Confirms the description/body text
now scales proportionally with focus zoom, matching the headline.

## Black & White "white text isn't pure white" — pixel-checked, does not reproduce

Direct pixel sampling (PIL, on a real un-scaled screenshot) of the Black & White Dark
variant found both the headline text (`onSurface` role) and meta-row text
(`onSurfaceVariant` role, timestamp/feed name) rendering as **exactly (255,255,255)** at
every sample point checked — headline: 7,322 pixels at pure white out of a sampled region,
next-closest shade only 366px; meta-row: same pattern; 5 separate interior glyph-stroke
samples all landed at (255,255,255) with zero deviation. This does **not** reproduce the
original "white text isn't pure white" report at the pixel level for the core text.

The theme's genuine non-monochrome gaps remain (see above): per-feed favicon circles and
the "refresh failed" banner keep their original colors. It's possible the original report
was actually about one of those elements, or about a perceptual effect (small/thin
anti-aliased glyph strokes can visually read as "not stark white" even when their solid
core color is exactly 255,255,255, due to partial-coverage edge pixels blending toward the
background). Not closing this out definitively — if it's seen again, a specific screenshot
or the exact element being looked at would let this be pinned down precisely rather than
inferred.

## BUG-002 — isolation experiment: per-app locale override does NOT reproduce the mirror

Continuing the root-cause investigation (Glance confirmed to have no supported API for
this — checked the current AndroidX source, full changelog across all versions, and found
no documented community workaround either). Ran a clean, single-variable experiment:
removed the XOR entirely so `isRtl` is a pure, locale-independent function of the feed's own
`layoutDirection` setting (commit `1bb3afa`), then checked via direct pixel sampling
(`Bitmap.GetPixel` on real 1080×2400 screenshots, not visual impression) whether the
rendered accent stripe's physical position changes when only the locale changes.

**Result: no change.** Stripe bounds and color were byte-for-byte identical (x=1001-1007,
RGB 168,120,64) between English and a Hebrew **per-app locale override**
(`cmd locale set-app-locales com.newsfeed.widget --locales he-IL`), confirmed via the DBG2
label that the override was genuinely detected (`dev=true`) while `isRtl(fixed)` correctly
stayed unchanged.

**This does not settle the question** — it likely tests the wrong mechanism. The ambient-
mirroring theory (from decompiling `glance-appwidget:1.1.0`) specifically depends on the
*host launcher process's own* `Configuration.layoutDirection` at RemoteViews inflation
time. A **per-app** locale override (Android 13+ `LocaleManager`) only changes what
`com.newsfeed.widget`'s own process sees — it does not touch the launcher process that
actually inflates and renders the widget's RemoteViews. All of this session's earlier
confirmations of the actual bug (stripe/timestamp mirroring, feed indistinguishable from
its opposite direction) used a real **system-wide** locale change (Settings > Language),
which reconfigures every process including the launcher — a meaningfully different test.
Next step: repeat this exact isolated experiment with a genuine system-locale switch
instead of a per-app override, before concluding anything about whether ambient mirroring
is real.

## BUG-002 — ROOT CAUSE CONFIRMED (retest with real system-locale change)

Repeated the identical isolated experiment, this time with a genuine system-wide locale
change (Settings > System > Languages, not a per-app override) — the mechanism every
earlier confirmation of this bug actually used.

**Result: the physical rendered position changed.** With `isRtl` held provably constant at
`true` throughout (no XOR, a pure function of the feed's own setting), the accent stripe
moved from x=1001-1006 (physical right edge) under English to x=71-76 (physical left edge)
under real system-wide Hebrew — a full mirror of the entire row, confirmed via exact pixel
sampling (not visual impression) on both sides. The diagnostic label confirmed `dev` — the
device's own detected locale — correctly flipped to `true`, while `isRtl(fixed)` never
changed. Reverting the system locale restored the stripe to the exact original pixel bounds.

**This conclusively confirms the root cause**: Glance's `translateComposition()` mirrors
the entire finished row as a single opaque unit, keyed off the real ambient
`Configuration.layoutDirection` of whatever process actually renders the RemoteViews (the
home-screen launcher, not this app's own process) — completely independent of any value
computed in `FeedItemRow.kt`. This also retroactively explains why the original XOR fix
attempt showed "zero measurable effect": no compose-time compensation (reordering
children, flipping which `Alignment`/`TextAlign` branch runs) can counteract a mirror
applied to the *whole finished layout* after the fact — it doesn't matter which branch
produced the row, the ambient mirror flips whatever comes out the same way regardless.

Combined with the earlier research confirming Glance exposes no public API in any version
to pin an absolute, non-mirroring layout direction (no `AbsoluteAlignment`-style
`Alignment.Horizontal` variant, no modifier, no CompositionLocal — checked the current
AndroidX source and full changelog), **this is a confirmed, structural limitation of the
Glance library itself, not fixable from this app's Composable code at all.** The only real
fix requires raw `RemoteViews.setViewLayoutDirection()`, which is only reachable by
replacing Glance's rendering with hand-written RemoteViews (see
`docs/superpowers/plans/2026-09-04-remoteviews-rewrite.md`, previously scoped for a
different reason — the ~20-40 row render cap — and would fix both issues as one
architectural change).

**Code state:** `FeedItemRow.kt`'s `isRtl` is now the simple, XOR-free
`feedConfig.layoutDirection == "rtl"` (commit after `1bb3afa`) — the XOR added complexity
for zero measurable benefit, confirmed twice now. Diagnostic label removed. This is
functionally identical to the original reported bug (per-feed direction still gets
overridden by system locale) — no incremental fix was found to exist, so the code is left
in its simplest correct-when-locale-matches-config form rather than carrying dead
compensation logic. Needs a product decision: commit to the rewrite, or accept as a known
limitation for now.

## BUG-014 addendum — revised root cause: device-wide network flap, not per-app throttling

Seen live again. This time, caught the causing window directly in logcat (not cleared
first) rather than just the retry: `isBlocked=true` DNS failures hit **`com.newsfeed.widget`,
`com.microsoft.skydrive` (OneDrive), and `com.android.chrome` simultaneously**, over a
sustained ~5-minute window (22:45-22:50). OneDrive alone was blocked continuously, roughly
every 6-8 seconds, for the entire window.

This revises the earlier theory. A per-app Samsung Freecess/background-priority
restriction (the original BUG-014 hypothesis) would not explain three unrelated apps —
one of them the actively-used foreground browser — all failing at once. The pattern (many
apps, sustained multi-minute window, then a full recovery with `dumpsys connectivity`
showing WiFi fully `VALIDATED` and a clean `ping 8.8.8.8` immediately after) instead points
to a genuine device-wide WiFi instability/reconnect event — likely a brief drop-and-reconnect
cycle (sleep/wake, AP handoff, DHCP renewal) during which `netd` has no valid default route
for any app's sockets for a few minutes, rejecting DNS instantly (`0ms`, not a timeout) for
whoever happens to ask during that window.

**Still not something the app can prevent** — this is environmental, not a code defect —
but the earlier "battery management" framing was likely wrong. The app's own behavior
(reporting the failure honestly via `lastRefreshFailed`, recovering cleanly on the next
attempt) is correct either way. Confirmed again: retrying immediately after network
recovers succeeds cleanly (new WorkManager job, `SUCCESS`).

## Feature additions (2026-09-08) — Telegram channel as a feed source

Public Telegram channels can now be added through the existing Add Feed field, alongside
RSS/Atom URLs — no new UI element. Typing `@channel`, `t.me/channel`, `telegram.me/channel`,
or a full `https://t.me/s/channel` preview URL is recognized and converted into an ordinary
`FeedConfig` whose `feedUrl` is the channel's public, no-login `https://t.me/s/<channel>`
HTML preview page. Nothing downstream (storage, retention, sort/filter, per-feed accent
color, per-feed RTL/LTR, rendering) needs to know a feed originated from Telegram.

- New `TelegramFeedParser.kt` (`data/`) — pure-Kotlin, Android-framework-free HTML scraping:
  channel-reference detection/canonicalization, per-post extraction (permalink, timestamp,
  raw text, photo URL), a hand-written tag-stripper + entity-decoder (swapped in for
  `Html.fromHtml()` specifically so this file could be unit-tested with plain JUnit — see
  `docs/superpowers/specs/2026-09-08-telegram-rss-converter-design.md`), and article/title
  construction. **This is this project's first unit-tested code** (36 JUnit 4 tests) —
  everything else in the app remains verified on-device only, per its established pattern.
- `NewsFeedRepository.fetchFeedArticles()` gained one new branch recognizing
  `https://t.me/s/` feed URLs and routing to the parser instead of the XML pull-parser path;
  a new `fetchTelegramChannelTitle()` mirrors `fetchFeedTitle()`'s role at add-time.
- Add Feed field placeholder updated to "RSS, Telegram or Atom feed URL".
- A duplicate-feed guard was added to `doAddFeed()` during code review: the same channel
  can now canonicalize identically from three different typed forms (`@channel`,
  `t.me/channel`, `telegram.me/channel`), which made an existing, pre-Telegram gap (no
  duplicate check on manual Add Feed, unlike the Find Feeds and OPML import paths) newly
  reachable — a duplicate `feedId` crashes the settings screen's `LazyColumn` on its key
  uniqueness contract. Now shows "This feed is already added" instead.

**On-device verification (commit `85ba850`, device `RFCR91J237W`):** added `@telegram`,
confirmed the real fetched channel title ("Telegram News", not the raw handle) via both the
UI and on-disk DataStore, confirmed real article content (headlines, Telegram CDN images,
timestamps) in the widget's rendered cache, confirmed the duplicate guard blocks
`t.me/telegram` after `@telegram` is already added, confirmed a manual refresh doesn't
duplicate already-fetched articles, confirmed the invalid-channel error path shows the
existing "Could not load feed — check the URL" message with no crash, and confirmed the 8
pre-existing RSS/Hebrew feeds kept rendering normally throughout (no regression).

Two follow-up hardening items were flagged during review as non-blocking, not yet acted on:
`TelegramFeedParser`'s `extractBalancedDivContent()` scan isn't bounded to the enclosing
`<div>` the way it should be for full robustness against pathological markup, and
Telegram-sourced image thumbnails are fetched host-unrestricted (matching the pre-existing,
unchanged behavior for RSS `media:thumbnail`/`enclosure` images — not a new risk class, but
worth a shared fix across both).

## BUG-018 — Telegram Add Feed: a directly-pasted canonical `t.me/s/<channel>` URL failed

Reported live right after the Telegram feature above shipped: pasting the channel's own
canonical preview URL (`t.me/s/<channel>` or `https://t.me/s/<channel>`) directly into Add
Feed — rather than `@channel` or `t.me/channel`, the two forms exercised during that
feature's own on-device verification — showed "Could not load feed — check the URL"
instead of adding the feed.

**Root cause:** `TelegramFeedParser.canonicalize()` deliberately returns `null` for a
`t.me/s/...` input by design (it's already a preview URL, not a channel reference to
re-canonicalize — its regex only matches `t.me/<channel>` or `telegram.me/<channel>`).
`WidgetConfigActivity.doAddFeed()` used that `null` as its *only* signal for whether to
route the title-fetch through the Telegram parser vs. the XML/RSS parser, so a directly
pasted `t.me/s/...` URL fell through to the XML parser, which fails on HTML input.

**Fix (commit `ed5b330`):** added `isTelegramFeed = telegramUrl != null ||
url.startsWith("https://t.me/s/")`, so an already-canonical preview URL is now also
recognized. Verified on-device (build 109): `t.me/s/cnnbrk` now adds correctly as "CNN";
`https://t.me/s/telegram` (the already-added Telegram feed's canonical form) correctly
shows "This feed is already added" rather than "Could not load feed."

Known non-blocking gap (not the reported bug, not yet fixed): the added check is a literal,
case-sensitive `https://t.me/s/` prefix match, so `http://` (non-`https`), an uppercase host,
or the `telegram.me/s/...` alternate domain would still hit the original failure. Worth a
follow-up using the same case-insensitive matching `TelegramFeedParser`'s own regexes
already use.

## BUG-019 — Feed drag-reorder moved a different row than the one dragged

Reported live: dragging a feed row up in the settings screen's feed list sometimes moved a
*different* feed down instead of moving the touched feed up.

**Root cause:** the feed-order `LazyColumn` has 4 non-reorderable header `item {}` blocks
before the feed rows (Sort & Filter, Add Feed, Find Feeds, Feed Order & Style), but the
`reorderState`'s `onMove` callback subtracted a hardcoded `offset = 3` to convert the
library-reported list-wide `from`/`to` indices into `feedOrder`-relative indices — one too
few. Every reorder therefore operated on the row one position below the one actually
touched (traced concretely: with 5 feeds, dragging the 2nd feed to the top moved the *3rd*
feed instead).

**Fix (commit `ed5b330`):** `offset` corrected to `4`. Verified on-device (build 109):
dragged a middle feed to the top — the exact feed touched moved, no neighboring row shifted
incorrectly; repeated dragging a different feed down two positions with the same correct
result.

## BUG-002 addendum — non-Glamour themes have no forced RTL text direction at all

Investigated 2026-09-10 after a live report questioning whether Focus Mode's feeds are
"really RTL, or just aligned to the right." Confirmed by decompiling
`androidx.glance:glance-appwidget:1.1.0` and `glance:1.1.0` directly (javap on the
extracted AAR classes, not assumed from memory):

**Glamour theme is fine.** `TextBitmapHelper.kt` renders headline/body as bitmaps via
`android.text.StaticLayout`, explicitly calling `.setTextDirection(TextDirectionHeuristics.RTL)`
when `isRtl` is true — genuine forced bidi paragraph direction, already correctly
implemented (with its own documented past-bug fix, see the comment above that call site).

**Every other theme (Simple, Black & White, Lavender, Amethyst, Glassy, Aerospace, Silicon,
Auto) has no equivalent mechanism, and this isn't a gap in this app's code — Glance's public
API has no way to provide one.** `androidx.glance.text.TextStyle` (decompiled: exactly 7
constructor fields — `color`, `fontSize`, `fontWeight`, `fontStyle`, `textAlign`,
`textDecoration`, `fontFamily`) exposes only `textAlign` (Start/End), never a text-direction
or bidi override. `Text()`'s own composable signature has no direction parameter either.
Grepping every extracted class in both AARs for "Direction"/"RTL"/"Bidi"/"TextDirection"
found exactly one relevant internal symbol,
`RemoteViewsTranslatorKt`'s non-public `forceRtl`/`isRtl(Context)` — which reads the
**ambient system-locale layout direction** (not any per-widget or per-feed value) and uses
it only to resolve `TextAlign.Start/End` into `Gravity` constants. No `setTextDirection`
call, no `TextDirectionHeuristic` usage, anywhere in either module.

Net effect for non-Glamour themes (including Focus Mode combined with any of them, since
Focus Mode isn't restricted to Glamour):
1. "RTL" is really just right-alignment (`textAlign = End`) — there is no forced paragraph
   bidi direction at all. Actual character-order correctness for mixed content (a headline
   starting with a digit, Latin word, or quote mark) depends entirely on the platform
   TextView's own implicit Unicode-bidi auto-detection, not anything this app controls.
2. Even that alignment doesn't reliably follow the feed's own per-feed RTL toggle — it's
   resolved against the device's ambient system locale, the same root cause already
   confirmed and documented for BUG-002's accent-stripe mirroring.

Same ceiling as BUG-002 itself: not fixable from Glance's Composable API. The only real fix
is raw `RemoteViews.setViewLayoutDirection()`/`TextView.setTextDirection()`, reachable only
via the already-scoped RemoteViews rewrite
(`docs/superpowers/plans/2026-09-04-remoteviews-rewrite.md`). No code change made — this is
a confirmed-scope documentation update, filed alongside BUG-002 rather than as a new number
since the root cause and resolution path are identical.

## Queued, not yet brainstormed or scoped

- **Share button on the widget** — send the widget's/article's link via WhatsApp, Telegram,
  SMS, or the system share sheet. Requested 2026-09-10; not yet designed.
- **Release notes on self-update** — show the user what changed and why when the app
  self-updates. Requested during the Telegram feature's implementation; not yet designed.
- **Bug logger** — detect specific bugs on users' devices, report to a central GitHub-based
  collector (on detection or weekly), and on detection show the user a list of all bugs
  found so far with solved/unsolved status. Requested alongside the bug logger's own design
  work being deferred until after the Telegram feature and this bug logger are scoped
  together. Not yet designed.
