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

**Status:** Seen twice this session, self-resolves on retry, root cause not confirmed —
logcat evidence was lost both times before it could be captured.

Reported live twice in one session: the widget footer briefly shows "⚠ refresh failed — tap
to retry". `lastRefreshFailed` is only set `true` when `allFailed` is true in
`NewsFeedRepository.getArticles()` — i.e. **every single enabled feed's fetch threw** on
that attempt, not just one flaky feed — which rules out ordinary single-feed network
flakiness as the cause; it points at something shared across all outbound requests at that
moment (a brief DNS/connectivity blip, or Android's Doze/background-network execution
restrictions activating between long stretches of heavy on-device testing today). Both
times, a manual retry tap immediately succeeded cleanly (all DNS lookups OK, `WidgetWorker`
returned `SUCCESS`) — but both times the *causing* attempt's own logcat output wasn't
captured before it rotated out of the buffer (once because investigation started after the
fact, once because logcat was mistakenly cleared right before the retry instead of before
checking history). **Not yet root-caused.** Next occurrence: check `adb logcat -d` (without
clearing first) immediately, and check `adb shell dumpsys deviceidle` / battery
optimization state for the app, before retrying.

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
