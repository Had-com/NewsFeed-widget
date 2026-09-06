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

**Status:** Did not reproduce — closing pending a fresh, specific report.

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

**Status:** Fixed (not yet pushed), needs on-device verification.

The countdown/refresh `Text` in `WidgetFooter()` (`NewsFeedWidget.kt`) had `.clickable()`
applied directly with no padding, so its tap target was exactly the tight wrap-content
bounds of the rendered glyph string. The adjacent gear (`⚙`) button in the same `Row`
already used `.padding(4.dp)` before `.clickable()` for a comfortable hit area — the
refresh text just never got the same treatment. Fixed by adding the matching padding.

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

**Status:** Both fixes implemented, not yet pushed/verified on-device.

## BUG-009 — Feed row's "×" remove button opens Edit instead of removing

**Status:** Open, found incidentally during BUG-008 verification, not yet investigated.

In the feed list (Settings), tapping a feed row's "×" remove control repeatedly opened the
"Edit feed" dialog instead of removing the row — possibly the parent row's edit-tap region
overlaps/intercepts the small "×" hit target (same general class of issue as BUG-006).

---

**Cleanup note:** a test feed added during BUG-008 verification — "מבזקי ערוץ 7"
(`https://www.inn.co.il/Rss.aspx`) — was left configured on the device because BUG-009
prevented removing it during that same session. Remove it manually from Settings, or once
BUG-009 is fixed.
