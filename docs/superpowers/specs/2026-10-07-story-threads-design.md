# Story threads: group similar stories into collapsible threads - design spec
**Date:** 2026-10-07
**Project:** NewsFeed widget (`com.newsfeed.widget`)
**Status:** Approved by the user (decisions below are agreed and closed); implementation not started
**Plan:** `docs/superpowers/plans/2026-10-07-story-threads.md`

---

## Goal and non-goals

**Goal.** The same news story is usually published by several feeds within minutes (ynet, N12, Kan, walla, rotter, Telegram channels). Today it fills the list with near-identical rows. With story threads, those articles collapse into **one row**: the newest article, plus a small **+N** badge. Tapping the badge lists the other reports right under it.

**Non-goals.** No change to fetching, accumulation, the 300-article store, themes, Focus rendering or the tap modes. No ML, no network, no per-article stored state: grouping is a pure function of fields the articles already carry. Paraphrased headlines that share no words are **not** grouped (see Limitations). One setting turns everything off.

## Decisions (closed)

1. Threads join articles from **different feeds**. Same-feed articles never link directly (they can still land in one thread through a third feed).
2. Similarity = shared **key words** in the title (details below). A thread is a connected component of the link relation (union-find), capped at **12** members. **Lead = newest member** (tie: smaller id).
3. **Computed at render time** in `WidgetContent` over the post-filter/sort list. Nothing is stored per article. Only the user's expand/collapse choice is stored (`WidgetStateKey.expandedThreadIds`, per widget, lead ids).
4. Per-widget switch **Group similar stories**, **on by default, including widgets that already exist** (`WidgetConfig.groupStories = true`). Off restores today's rendering exactly.
5. Reading a thread member and then **moving on** marks the **whole thread** read at once, through the existing mark-read paths.

## Matcher (`data/StoryThreads.kt`, pure, deterministic, ignores "now")

**Key words** (`storyTokens`): NFKC; strip niqqud; drop a trailing `" - <source>"` tail of at most 3 words / 24 chars (Google News feeds add `- כאן 11`, `- N12`); remove quote marks (so rotter's `''` and ynet's `"` agree); lower-case; split into words and numbers (numbers keep inner `. / , :`, so `7/10` and `6.7` survive); drop stop words (the agreed Hebrew list `של את על עם כי אל זה הוא ...`, a small English list, and Telegram boilerplate `קישור לכתבה העדכונים`); strip **up to two** leading prefix letters `ה ו ב ל מ ש כ` from Hebrew words longer than 3 letters; drop tokens shorter than 2 characters.
*Refinement of the agreed single-letter trim:* real headlines need two (`ו`+`ה`, `ש`+`ה`: `והמחבל`, `שבבנימין`), and the up-to-two rule is a strict superset on the reviewed data (52 groups vs 50, no new wrong group).

**Link rule** (all must hold), constants documented in the file:

| Rule | Value |
|---|---|
| Different feed | `feedId` differs |
| Time window | `abs(publishedAt difference) <= 6 h` (`STORY_WINDOW_MS`) |
| Same read class | both unread, or both read with the **same** `readAt` (see below) |
| Shared key words | `>= 3` (`STORY_MIN_SHARED`) **and** `shared * 100 >= shorter * 60` (`STORY_MIN_COVERAGE_PERCENT`), where `shorter` = key words of the shorter title |

**Decision on very short titles: no lower bar.** A title with fewer than 3 key words never groups. Tried on the real data: a "2 shared words when the shorter title has <= 4" bar added one correct pair and one wrong one (`ועוד ... עולם` vs `לעולם לא עוד`); a missed group costs one extra row, a wrong group hides a different story.

**Cap.** A component with more than 12 articles keeps its 12 newest as the thread; the older overflow stays as ungrouped rows.

**Read class (the key invariant).** Threads never mix read states: unread articles link only to unread ones, read articles only to read ones with the same `readAt` (a thread is always marked read with one shared stamp). Consequences: (a) a later article about the same story is unread, so it starts a **new unread thread** and the already-read ones stay read; (b) `All / Unread only / Read only` keep or drop whole classes, so threads computed from the filtered render list are **identical** to threads recomputed by a tap callback from all stored articles of the configured feeds. This is what lets callbacks "recompute the same groups" with no stored thread state. Unit test: grouping a set equals grouping its filtered subsets.

## Rendering

`WidgetContent` pipeline: decode articles -> keep configured feeds -> `applyFilterAndSort` (unchanged) -> `groupStories` (or `ungroupedStories` when the switch is off) -> `layoutThreads(threads, expandedThreadIds, min(visibleCount, maxRowsAllowed))` -> dissolve (Unread only) on **every** visible row -> `LazyColumn` items (one item per row, never a thread-sized Column: RemoteViews caps a Column at 10 children and a thread can have 12).

* **Lead / single row.** A thread sits where its **lead** sits in the sorted list (any sort), its other members vanish from the flat list. The lead renders as a normal `FeedItemRow` with a **`+N`** pill at the end of the meta line (a **`−`** when expanded; revised 2026-10-08, see the end of this document). Tapping the row itself does what the tap mode says (Expand: expand text; Focus: enlarge).
* **Badge mechanics.** The pill is its own `.clickable(actionRunCallback<ToggleThreadCallback>(leadId))` inside a row that already has row-level clickables. This is the same nesting that the existing `Open article ->`, `Share` and `Load full article` pills use inside `FeedItemRow` (child click wins in the RemoteViews click-fill-in mechanism; verified by those buttons on device). Hidden while the row dissolves. Meta Row child count stays 9 of the 10 allowed.
* **Members (expanded).** Each other member is its own list item under the lead: a `FeedItemRow` inside the thread rail (revised 2026-10-08; originally a 14 dp left gutter; since 2026-10-09 the rail is a timeline on the right, see below). Normal clicks per tap mode (Expand: expand; Focus: can be focused). Newest first.
* **Toggle.** `ToggleThreadCallback` flips the lead id in `expandedThreadIds`, prunes ids that no longer lead a 2+ thread, caps the list at 30, and on **collapse** clears `expandedArticleId` / `focusedArticleId` if they point at a now-hidden member. It never marks anything read (opening a thread is not moving on).
* **Lead changes.** If a newer article joins, the lead changes and the thread is **collapsed** again (its id is no longer in the set), with an updated `+N`.

## Read state

* `markPreviousTappedReadThread` (Expand) and `applyFocusTapThread` (Focus) call one helper, `idsToMarkReadOnMoveAway`: for the unread article being left it returns the article **plus all unread members of its thread**, stamped with the **same** `readAt`. The old single-id functions remain as thin wrappers (tests unchanged).
* **Moving between members of the same thread is not moving on**: if the newly tapped article is in the same thread, nothing is marked (tracking still follows the tap).
* `ReadStatusStore.markReadAll(ids)` writes every member in one DataStore edit, so `WidgetWorker` (which sets `isRead` from that store and carries `readAt` over via `mergeFreshArticles`) keeps every member read with its stamp. A new article is not in the store, hence unread.
* **Unread only.** The 5 s grace and dot-dissolve are per `readAt`, which is shared, so all rows of the thread dissolve in lockstep and leave together. Filter-vs-thread: members are never read at different times, so a thread can never be "half in" the list. Grace schedule key: the callback and the render-time resume both use `GraceRefreshRegistry.representativeId(members)` (smallest id) and the resume schedules **once per `readAt`**, so a thread costs the same 4 delayed renders as one article.

## Budget, counts, other settings

* `maxRowsAllowed`, `visibleCount`, **Load more** and `Showing X of Y` all count **rows**: collapsed thread = 1, expanded = lead + members; the cut is `take(limit)` on the flat row list (an expanded thread may be cut mid-way; Load more finishes it). `worstCaseRowScale` and the bitmap budget are per row and unchanged.
* **Header** `unread(total)` still counts **articles** (a collapsed thread adds its hidden members), so it means what it always did. Focus `N/M` counts the rows on screen.
* Telegram posts (title = first two lines, <= 200 chars) group with RSS items like any other title. Reporter-name prefixes just add unshared tokens.

## State and the interaction matrix

| Area | Behaviour |
|---|---|
| Tap mode Expand / Focus | Lead and members behave like any row; whole-thread read via the two paths above; mode switch (`resetTapState`) **keeps** `expandedThreadIds` (thread layout is not tap state) |
| One expanded article | `collapseExpandedOnOtherTap` unchanged: one expanded **article** at a time; expanded **threads** are independent of it and of each other |
| Show All / Unread / Read | Class invariant (above); Read only shows read threads whole |
| Sort Newest / Oldest / By feed / Unread first | Thread at its lead's slot in the sorted list; members never repeated |
| Dissolve and resume `SideEffect` | All visible rows dissolve; one schedule per `readAt`; same registry key as the callbacks |
| Orphans | `expanded_thread_ids` lives in the widget's own Glance DataStore, removed with the widget; `OrphanCleanup` needs no change |
| Worker merge | No change |
| Switch off | `ungroupedStories`: every article its own row, no badge, stored `expandedThreadIds` ignored; marking read reverts to single article |
| JSON | `groupStories: Boolean = true`; old JSON (missing key) decodes `true`; with `encodeDefaults` off a `true` is not written, a `false` is (`"groupStories":false`); round-trip tested with the default `Json` (no `ignoreUnknownKeys`, as `WidgetContent` uses) and the lenient one |

## Tuning evidence

The matcher was built against real data: 750 distinct headlines from the widget DataStore backups on the dev machine (feeds: rotter, ynet flash/news, Kan newsflash + Google News, walla, Globes, N12 Telegram/chat/Google News). A 294-headline slice (two busy windows, 2026-09-20 and 2026-10-07) is committed as a test fixture; it yields **27 threads** (22 pairs, 2 triples, one 4, one 5, one 12). Reviewed groups include the Binyamin shooting reported by 8 feeds, Eisenkot's quote (rotter `''` vs ynet `"`), the stock-market close (prefix `ה`), Kan Google News `- כאן 11` twins. Near misses that correctly stay apart: same number, different meaning (`בן 30` motorcyclist vs `בן 30` shooting victim), two different security events the same day, same topic different story (Iran sanctions vs Iran claim), same words more than 6 h apart.

## Limitations (documented, hence the switch)

* Paraphrases with no shared words do not group; a fast-evolving story can split into two threads (e.g. "first reports" vs "victim named").
* Different stories sharing names/places may group wrongly. Example seen in the data: an N12 weather forecast and a Yom Kippur prayer-times item (5 shared generic words) link; the user can switch grouping off per widget.
* Titles with fewer than 3 key words never group. Hebrew prefix stripping is heuristic (stems are only compared with each other, never displayed).
* Grouping is O(n^2) over at most 300 articles (a few tens of thousands of set checks) on each render and tap callback.

## Testing and release gate

Unit tests: token rules, link thresholds, grouping (cross-feed, same-feed, window edge, read class, cap, lead tie-break, order independence, filter invariance), real Hebrew must-group pairs and near misses, the 294-headline fixture (golden threads + group-count band + review listing), `layoutThreads` budget/counts, expand-state toggle/prune/cap/collapse cleanup, whole-thread read in both modes, config JSON. A **manual review gate** shows the fixture's group listing to the user before any UI work. Release gate (standing rule): on-device QA of the new section O in `docs/QA_PLAN.md` with DataStore/uiautomator/logcat evidence in both tap modes, a `DEBUG_PLAN.md` entry, release note, security scan, and **no push without the user's go-ahead**.

## Revised 2026-10-08 (user feedback after the device run: rail, thinner separators, +/− badge)

* **Thread rail (left bar: SUPERSEDED 2026-10-09 by the right-side timeline, see "Revised 2026-10-09" below; the left 3 dp bar, the 5 dp start padding and the "physical left" statements no longer apply, the rule that dividers are siblings of the rail wrapper still does).** While a thread is expanded, ONE continuous 3 dp bar (the theme's `primary` colour, visible on every light/dark theme; the old gutter used the near-invisible `surfaceVariant`) runs down the **physical left** of the lead row and every member row, including across the separators, so the block reads as one thread. The 14 dp member gutter is gone. Collapsed threads and single rows have no rail and keep their old node tree. Each list item is ONE outer `Column(fillMaxWidth)` exactly like a single row: `Column(fillMaxWidth){ [expanded thread: Box{ Column(fillMaxWidth, padding start 5dp){ row }, Box(3dp rail, fillMaxHeight) }] | [other rows: row]; divider(s) }`, so the dividers are siblings of the rail wrapper, never inside it (the rail overlays the padded row only). Two earlier builds put the dividers INSIDE the rail wrapper (909: `Row{ rail, Spacer, Column(defaultWeight){ row + dividers } }`; 910: `Box{ Column(padding start 5dp){ row + dividers }, rail Box(fillMaxHeight) }`) and on device the divider Boxes were absent from the rendered view tree (not collapsed: not in the uiautomator dump), thin and normal alike; with the dividers outside the wrapper (build 911) they paint. The cause is therefore a divider sharing a container with a `fillMaxHeight()` rail child in Glance 1.1.0's RemoteViews conversion (the weight of build 909 was not the (only) problem). The widget composition is locked to `LayoutDirection.Ltr` (`provideGlance`), so the Box's start alignment and the column's start padding are the physical left for RTL and LTR feeds alike (same as the accent stripe). The 5 dp padding (3 dp bar + 2 dp gap) keeps the rail from merging with an LTR row's own 3 dp accent stripe.
* **Thin separators (rail overlay SUPERSEDED 2026-10-09: the rail-coloured piece on the hairline is now a 2 dp x 1 dp piece near the RIGHT end, not a 3 dp piece at the start; spacing, padding and the normal divider after the last member are unchanged).** Between the rows of an expanded thread (lead to member, member to member) the divider is the 1 dp hairline only (no 2 dp shadow strip), drawn as a full-width 1 dp `Box` with a 3 dp x 1 dp rail-coloured `Box` overlaid at its start, so the rail also reads as unbroken through the separator (no weights, no padding on a filled box). The facing vertical paddings drop from 8 dp to 4 dp (about half the normal gap). After the last member (and after a collapsed lead or a single row) the divider is the NORMAL 1 dp line + 2 dp shadow strip across the full width, and the rail ends at the last member's bottom; the padding above the lead and below the last member stay normal, so the thread block is clearly delimited. Pure decisions: `separatorStyleFor`, `verticalPaddingDpFor`, `isLastOfThread`, `inExpandedThread` in `data/ThreadRows.kt` (unit tested).
* **Badge.** `+N` while collapsed, a single `−` (U+2212) while expanded (`threadBadgeLabel`, unit tested); the `▾` / `▴` glyphs are gone. The clickable is the pill plus a transparent 6 dp margin on each side so the narrower pill keeps its hit width; behaviour (own clickable, hidden while dissolving) is unchanged.
* No new release note: Note 12 never mentioned the glyphs.

## Revised 2026-10-09 (timeline on the right)

The user saw four mockups of the open-thread look and chose **look C, "Timeline", with the line and dots moved to the right side**. This replaces the left 3 dp bar of the 2026-10-08 revision.

* **Look.** A story trail on the **physical right** edge of every row of a thread: one dot per article joined by a thin vertical line. The lead (newest article) has a **hollow ring**, the other members **filled dots**; the line runs from just below the lead's ring through the members and **ends at the last member's dot** (nothing below it). A **collapsed** thread shows only the hollow ring next to the `+N` badge, no line. Single rows (and every row with grouping off) have no rail. The `+N` / `−` badge is unchanged.
* **Rail column.** 18 dp wide (`RAIL_WIDTH_DP`), flush with the right edge of the item, holding a 2 dp line (`RAIL_LINE_DP`) centred in it and the dot: ring 12 dp (`RAIL_RING_DP`), filled dot 8 dp (`RAIL_DOT_DP`), both in the theme's `primary` colour (the line is thin instead of alpha-muted: the theme colour is a `ColorProvider`, which cannot be given an alpha for the "auto" theme, and `primary` is the colour already verified visible on every theme). The row's content is padded 18 dp at the end, so text never runs under the rail.
* **Pure decisions** (`data/ThreadRows.kt`, unit tested): `railPartFor(row, isLastOfThread)` gives `NONE` (single), `COLLAPSED_LEAD`, `LEAD_OPEN`, `MIDDLE`, `LAST`, or `LEAD_CUT` (an expanded lead whose members were all cut by the row budget: ring, no line); `railSpecFor(part)` says which dot (`HOLLOW` / `FILLED` / `NONE`) and whether the line runs above and/or below it; `railDotCenterDpFor(paddingTopDp, metaScale, hasBadge)` gives the dot's centre.
* **Dot position.** A fixed offset from the item's top, not a function of the number of headline lines: the row's top padding (8 dp, 4 dp on a member) plus half the height of the **meta line** (feed circle / time / feed name / `+N` pill), the row's first text line: `max(14 dp x fontSize, 12 dp x fontSize + 8 dp when the +N pill is shown) / 2`. It therefore follows the Font size setting. In Focus mode the meta line is capped at the base font size, so the zoomed row's dot stays level with it. Estimated, not measured: the pill's text height assumes 1.2 line height and `sp` equal to `dp` (system font scale 1.0).
* **Drawing.** `ThreadRail.kt`: one narrow `Column(width 18 dp)` with [line above (fixed height up to the dot) or a spacer], the dot as an `Image`, [line below, the only `fillMaxHeight()` child]. The dots are white-on-transparent bitmaps tinted with `ColorFilter.tint(primary)`, NOT `Box.background().cornerRadius()`: Glance 1.1.0's `cornerRadius` is a no-op before API 31 (minSdk is 26; the feed icon circle has the same limitation) and a ring made of two Boxes would paint the translucent widget background over the accent instead of being hollow.
* **Structure and the BUG-2 rule.** Item = `Column(fillMaxWidth){ [thread rows: Box(fillMaxWidth, contentAlignment = TopEnd){ Column(fillMaxWidth, padding end 18 dp){ FeedItemRow }, rail Column }] | [other rows: FeedItemRow]; divider(s) }`: the dividers stay **siblings of the Box that holds the `fillMaxHeight` child**, no weights, the same nesting depth as the left-bar build. The THIN divider keeps the rail unbroken with a 2 dp x 1 dp piece of `primary` on the hairline, 8 dp in from the right edge (the centre of the rail column), placed by a 10 dp wrapper `Box` that the hairline `Box` aligns to its end (one extra nesting level inside the divider only). The NORMAL divider after the last member is plain and full width. The hairline continues 8 dp past the rail line (a cosmetic stub).
* **Physical right.** The composition is locked to `LayoutDirection.Ltr` (`provideGlance`), so `TopEnd` alignment and `padding(end = ...)` are the physical right for RTL and LTR feeds alike (the same assumption the left bar made with start / `TopStart`).
* **The RTL accent stripe.** An RTL `FeedItemRow` draws its 3 dp per-feed accent stripe at the physical right, exactly where the rail sits (the rail is outside, to the right of the row, so it cannot cover the feed circle icon, which is inside the row on the stripe's left). The stripe would sit 8 dp from the rail line as a second, differently coloured vertical line, so rows that carry a rail (collapsed lead, open lead, members) pass `showRtlAccentStripe = false` and omit the stripe and its 6 dp gap; the rail marks the right edge, and the feed is still identified by the circle icon and the name. RTL single rows keep the stripe; LTR rows keep the stripe on the left, far from the rail.
* **Tree identity.** Single rows, rows with grouping off: unchanged node tree. Collapsed lead: gains the wrapper `Box` with the 18 dp end padding and the ring rail (and, in RTL, loses the stripe). Expanded rows: wrapper `Box` as above.
* Removed: the left 3 dp bar, the 5 dp start padding, `inExpandedThread`. No new release note: Note 12 does not describe the visuals.

## Revised 2026-10-09 (similarity)

User report (BUG-028): "similar stories from different feeds group but the similarity is only one word or the reporter or channel name, not the main idea of the article".

* **Investigation.** The matcher was run over the 294-headline golden set, a new 478-headline set (`app/src/test/resources/story_threads_fixture2.tsv`, headlines of 2026-10-07 19:19 to 2026-10-08 19:34) and the union of every widget backup on the dev machine (1266 public headlines, 99 groups). The hypothesis "two reporter names plus one word form a thread" never happened in that data (no thread was held together by names alone), but it is arithmetically possible: a Telegram post is `<reporter> @ צ'אט כתבים <text>` or `<reporter> <text>`, the name and the label are 2 to 4 tokens, and a short second post by the same reporter needs only one more word for 3 shared / 60%. What the data did show: (a) the label and name tokens inflate the *shorter* title, so a real pair fell below 60% (the "מבזקן 12" flash vs its channel twin about the Neve Tzuf scene); (b) generic words of the day ("נתניהו", "צה"ל", "יום", "ארץ", "רחבי") are enough for 3 shared; (c) one chain: two grenade incidents of the same night (Ashkelon, Tel Aviv) link through "רימון רסס / אין נפגעים / הרקע פלילי", and a weather bulletin links to a Yom Kippur fast-times item.
* **Boilerplate removal** (all deterministic, from the article list only): (1) static labels in `storyTokens`: the `<reporter> @ צ'אט כתבים` lead, the `מבזקן 12` lead, and the channel/show names `n12 ynet mako walla וואלה כאן חדשות` are not key words; (2) **per-feed bylines**: in a Telegram feed (`feedId` contains `t.me/s/`), an opening of 2 to 3 key words that starts at least 2 of that feed's titles is a reporter signature and is dropped from those titles' key words (the longest prefix with the same support as its 2-word parent, so `איתי גל און` is cut whole but a topic word after `אלי הירשמן` is not). Restricted to Telegram feeds because RSS titles carry no byline and a repeated opening there is content (`מלי וליאל ...` is a story, not a signature): in the test data an unrestricted rule cut story names and lost real threads, as did a per-feed word-frequency rule (it removed the words of the day's biggest story from the small feeds that covered it most). A repeated single word is never cut. A reporter with a single post in the list is not detected (limit).
* **Content bar** (rule 5): of the shared tokens at least 2 must be *content* words, i.e. not in the list's **generic** set. A word is generic when it is in at least 5 titles and 3% of the list and its first and last occurrence are more than 6 h apart (it recurs across stories; a burst of one story inside one 6 h window never makes its own words generic, so the biggest story of the day keeps grouping). IDF weights were not needed: the simple count separates the cases, and a weighted score did not (the pairs it should reject scored the same as pairs it should keep).
* **Same list for render and taps.** The statistics are computed inside `groupStories` from `statsFrom`, which the render sets to the unfiltered, de-duplicated list of the configured feeds (`displayThreadsFor`); `storedThreads` groups that same list, so a read-state filter on the rendered list cannot change a thread. Constants: `STORY_MIN_CONTENT=2`, `STORY_BYLINE_MIN_TITLES=2`, `STORY_BYLINE_MIN/MAX_TOKENS=2/3`, `STORY_GENERIC_MIN_TITLES=5`, `STORY_GENERIC_MIN_PERCENT=3`. Cost: one more O(n x tokens) pass, pair checks unchanged.
* **Result.** Golden set 1: 27 to 28 groups (+1: the Neve Tzuf pair); golden set 2: 35 to 35; no real group lost on either. Known residuals (kept, documented): the two grenade incidents still chain (all shared words are content words; only the place names differ), and the weather bulletin / fast-times item can still link. The switch remains the escape hatch.

