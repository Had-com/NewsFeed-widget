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

* **Lead / single row.** A thread sits where its **lead** sits in the sorted list (any sort), its other members vanish from the flat list. The lead renders as a normal `FeedItemRow` with a **`+N ▾`** pill at the end of the meta line (`+N ▴` when expanded). Tapping the row itself does what the tap mode says (Expand: expand text; Focus: enlarge).
* **Badge mechanics.** The pill is its own `.clickable(actionRunCallback<ToggleThreadCallback>(leadId))` inside a row that already has row-level clickables. This is the same nesting that the existing `Open article ->`, `Share` and `Load full article` pills use inside `FeedItemRow` (child click wins in the RemoteViews click-fill-in mechanism; verified by those buttons on device). Hidden while the row dissolves. Meta Row child count stays 9 of the 10 allowed.
* **Members (expanded).** Each other member is its own list item under the lead: a `FeedItemRow` with a 14 dp left gutter. Normal clicks per tap mode (Expand: expand; Focus: can be focused). Newest first.
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
