# NewsFeed Widget — Full Release QA Plan

Reusable, versioned release QA plan for the NewsFeed Android Glance widget (`com.newsfeed.widget`).
Built from the actual code (`WidgetConfigActivity.kt`, `FeedConfig.kt`, `glance/`, `update/`,
`data/`), `README.md`, `docs/PRD.md`, `docs/BUGS.md`, `docs/DEBUG_PLAN.md`,
`docs/RELEASE_NOTES.md` and the approved spec
`docs/superpowers/specs/2026-09-20-focus-as-setting-design.md`. Where the docs and the code
disagree, the code wins and the discrepancy is listed in [Appendix D](#appendix-d--docscode-discrepancies-found-while-building-this-plan).

---

## Standing rule (set by the user, 2026-09-20)

> **Before every release the full QA plan is run: all features, all options, and their
> combinations. If a bug is found that the plan does not already test, a case for it is added
> to the plan.**

Consequences, in order of importance:

1. A "release" is any push to `main` that ships a code change (CI publishes every such push as the
   rolling `latest` release that installed apps self-update from — see `DEBUG_PLAN.md` §14).
   Docs-only / CI-only pushes keep the exemption stated there, but still need a green CI run.
2. The **Full run** (below) is what satisfies the gate. The **Smoke subset** does not; it is for
   mid-development sanity checks and hotfix triage only.
3. A bug found by any route (this plan, ad-hoc use, a user report) that no existing case would
   have caught gets a new case **before** the fix is declared done — see
   [Bug-to-test rule](#bug-to-test-rule--how-to-add-a-case).
4. Anything not actually observed is reported as **Unverified**, never as Pass.

---

## Contents

1. [How to run](#how-to-run)
2. [Case format, priorities, evidence codes](#case-format-priorities-evidence-codes)
3. [Bug-to-test rule / how to add a case](#bug-to-test-rule--how-to-add-a-case)
4. [Result recording](#result-recording)
5. [Severity and gating rules](#severity-and-gating-rules)
6. [Smoke subset and Full run](#smoke-subset-and-full-run)
7. [Combination strategy](#combination-strategy)
8. Cases: [A Install/first-run & default feeds](#a--installfirst-run--default-feeds) ·
   [B Feeds management](#b--feeds-management) · [C Fetching & sources](#c--fetching--sources-incl-telegram) ·
   [D Display & layout](#d--display--layout) · [E Reading](#e--reading-expand--focus--open--share) ·
   [F Read state & grace/dissolve](#f--read-state--unread-only-graceperiod--dissolve) ·
   [G Sort/Filter](#g--sort--filter) · [H Appearance](#h--appearancethemefonts) ·
   [I Refresh/worker/boot](#i--refresh--worker--timers--boot) · [J Settings persistence/backup/multi-widget](#j--settings-persistence--backup--multi-widget) ·
   [K Self-update & release notes](#k--self-update--release-notes) · [L Robustness](#l--robustness) ·
   [M Regressions](#m--regression-cases-for-past-bugs) ·
   [N Applies once Focus-as-setting ships](#n--applies-once-focus-as-setting-ships)
9. Appendices: [A Bug ledger](#appendix-a--cases-added-because-of-a-bug) ·
   [B Pairwise tables](#appendix-b--pairwise-configuration-tables) ·
   [C Test data](#appendix-c--reference-test-data) ·
   [D Doc/code discrepancies](#appendix-d--docscode-discrepancies-found-while-building-this-plan)

---

## How to run

**Device:** `RFCR91J237W` (real Galaxy Z Fold; RTL/locale/fold bugs in this project never showed on an
emulator). All `adb` commands below assume `adb -s RFCR91J237W`.

### 1. Build the build under test, and prove it is the one installed

Pushing a commit does **not** put it on the device (lesson from the `c230520` stale-build incident).

```bash
# Git Bash, from the repo root
export JAVA_HOME="C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
mkdir -p /c/t   # the TEMP dir must exist
export TEMP='C:\t' TMP='C:\t'
"/c/Users/PhotoStudio/.gradle/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle.bat" --no-daemon testDebugUnitTest assembleDebug
adb -s RFCR91J237W install -r app/build/outputs/apk/debug/app-debug.apk
adb -s RFCR91J237W shell dumpsys package com.newsfeed.widget | grep -E "versionCode|versionName"
```

* CI runs **only** `assembleDebug` (no unit tests), so `testDebugUnitTest` must be run locally as
  part of every full run (case `M-38`). The local `versionCode` defaults to `1` unless
  `-PbuildVersionCode=<n>` is passed; CI uses the run number. Record which one was installed.
* Signing: every build uses the committed debug keystore, so `install -r` and self-update both work over
  each other. A different key means `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; do not uninstall on the
  device carrying the reference data without backing it up first.

### 2. Safety: back up before, restore after

A run **changes real state**: read flags (`newsfeed_read_status` is app-global and permanent), widget
config, accumulated articles, release-notes "seen" id. Back it all up first and restore at the end.

```bash
mkdir -p qa-backup && cd qa-backup
for f in $(adb -s RFCR91J237W shell run-as com.newsfeed.widget ls files/datastore | tr -d '\r'); do
  adb -s RFCR91J237W exec-out run-as com.newsfeed.widget cat files/datastore/$f > "$f"
done
adb -s RFCR91J237W shell dumpsys appwidget | grep -B1 -A6 com.newsfeed.widget > appwidget-ids.txt   # ids -> which is which
```

Restore (app must be stopped or Glance/WorkManager will rewrite the files):

```bash
adb -s RFCR91J237W shell am force-stop com.newsfeed.widget
for f in *.preferences_pb; do
  adb -s RFCR91J237W push "$f" /data/local/tmp/$f
  adb -s RFCR91J237W shell run-as com.newsfeed.widget cp /data/local/tmp/$f files/datastore/$f
done
# then tap the widget / open Settings once so Glance re-renders (may show "No session available" once - see M-08)
```

Notes: reading articles during the run flips their read flags permanently; say so in the run record.
Widget ids are only valid for the widgets present now: map them with `dumpsys appwidget` (older
runs used `15` = Standard, `16` = Focus). Never delete the reference widgets to test placement; place
**extra** widgets instead and remove those.

### 3. Evidence method (the only accepted proof)

| Code | Method | Command / note |
|---|---|---|
| **DS** | DataStore pull | `adb exec-out run-as com.newsfeed.widget cat files/datastore/appWidget-<id>.preferences_pb` (per-widget Glance state: `articles_json` with `isRead`/`readAt`, `config_json`, `expanded_article_id`, `last_tapped_article_id`, `focused_article_id`, `focus_scale`, `full_article_*`, `visible_article_count`, `last_refresh_*`, `grace_check_tick`). Other files: `newsfeed_config` (per-widget `widget_<id>` config), `newsfeed_config_backup`, `newsfeed_read_status`, `release_notes` (`last_seen_release_note_id`). Decode with `python -c "print(open('x.pb','rb').read().decode('utf-8','replace'))"`. `readAt` is only serialized once set, so "readAt appears" means "marked read". |
| **UI** | uiautomator dump | `adb shell uiautomator dump /sdcard/u.xml && adb pull /sdcard/u.xml` — verify text presence/absence and `bounds` (tap targets, row sizes, clipped controls). Prefer it over screenshots for "is X on screen". |
| **SS** | Screenshot | `adb exec-out screencap -p > s.png`, inspected at **1.2× scale** (zoom the region; glyph-level and colour checks, pixel sampling for exact colours). |
| **TS** | Timed screenshot series | Loop of `screencap` every ~0.5 s from the moment of the action (dissolve stages, grace period); record the frame time of each stage. |
| **LC** | logcat FATAL check | `adb logcat -c` before the case, then `adb logcat -d \| grep -E "FATAL EXCEPTION\|AndroidRuntime\|exceeds maximum bitmap\|Can't show content\|No session available"` — must be empty for `com.newsfeed.widget`. Run at least once per section and at the end of the run. |
| **JS** | Scheduler / alarm state | `adb shell dumpsys jobscheduler \| grep -A5 NewsFeedRefresh`, `... NewsFeedUpdateCheck`; `adb shell dumpsys alarm \| grep -i newsfeed` (`CLOCK_TICK`, `CLOCK_TICK_FOCUS`). |
| **UT** | Unit test | `testDebugUnitTest` (JVM tests under `app/src/test/`). Unit tests never replace the device check for a P0 case; they are listed in the **Auto** column only to say what is already machine-checked. |
| **ADB** | Other adb output | `dumpsys package`, `dumpsys appwidget`, `ls`/`unzip -l`, `am`/`appops` results; quote the relevant lines. |
| **VIS** | Direct observation | Only for things the above cannot show (share chooser contents, ripple feedback). Record exactly what was seen. |
| **CI** | GitHub Actions / release | `gh run list --workflow "Build APK" -L 3`, `gh release view latest`. |

Every case records **which widget** (id / type), the build (`versionCode`), and the Settings values in force.
Use both widget types wherever a case says "both" (until the Focus setting ships, see section N).

### 4. Preconditions common to all cases

* Reference widgets placed (one standard, one Focus until merged) with the default feed set
  ([Appendix C](#appendix-c--reference-test-data)); device online; system language English unless a case
  says Hebrew; system dark mode off unless stated; notification permission state noted.
* Settings changes only take effect on **Save** — every case that changes a setting ends with Save.

---

## Case format, priorities, evidence codes

Each case row: `ID | P | Do ⇒ Expect | Evid | Auto`.

* **ID** = section letter + number (`F-07`); matrix rows `G-M03`. IDs are never reused or renumbered.
* **P0** — must run on every release, including the Smoke subset. A P0 failure blocks the release.
* **P1** — must run on every Full run. **P2** — Full run, lower risk / rarely changing areas.
* **Evid** — evidence codes from the table above.
* **Auto** — what is machine-checked today. `UT:<file>` = a unit test file that exercises the logic;
  `none` = manual only. "Auto" is a coverage note, not a substitute for the device run on P0 cases.
* Tags: **[until merged]** = applies to the separate NewsFeed Focus widget and goes away when Focus-as-setting
  ships; **[post-merge]** = only applies once it ships (all of section N). **↔ X-nn** = the case is the same
  execution as `X-nn` viewed as a regression (run once, record under both IDs).

---

## Bug-to-test rule / how to add a case

**Rule:** a bug that no existing case would have caught gets a new case, added to this file in the same
change that fixes it (or, if unfixed, in the same change that logs it in `docs/BUGS.md`). The new case must
be executed against the buggy build where that is still possible to prove it detects the bug, then against
the fixed build.

**Steps**

1. Search this plan for a case that already covers the symptom. If one does but missed it, **tighten that
   case** (add the missed step/expectation) instead of adding a duplicate, and note the change in the ledger.
2. Otherwise add a row at the **end** of the fitting section (next free number; never renumber). If it is a
   past-bug regression, add it to section **M** and cross-reference the feature case.
3. Fill the template below in full; the "Added because" field is mandatory.
4. Add one line to [Appendix A](#appendix-a--cases-added-because-of-a-bug) (bug ref, date, case IDs).
5. Give it a priority: P0 if a failure would corrupt data, crash, blank the widget, break update or mislead
   the user about read/unread state; otherwise P1/P2.

**ID scheme:** `<section letter>-<two digits>`; matrix rows `<letter>-M<two digits>`; bug numbers follow
`docs/BUGS.md` (`BUG-nnn`; next free number) — several older bug references in `DEBUG_PLAN.md` collide with that
numbering, see Appendix D.

**Template**

```
ID:            F-24                       (section letter + next number)
Area:          Read state / Unread-only dissolve
Priority:      P0 | P1 | P2
Preconditions: widget type/tap mode, Settings values, feed set, article state, device state
Steps:         1. ... 2. ...
Expected:      exact, observable result (what a wrong build would show instead)
Evidence:      DS / UI / SS / TS / LC / JS / UT (which keys / which frames)
Automation:    UT:<file> or none (and whether one should be added)
Added because: BUG-0nn (YYYY-MM-DD) — one-line symptom + root cause; commit of the fix
```

---

## Result recording

One block per release in `docs/DEBUG_PLAN.md` **§14 → "Release log"** (that section is the system of record;
this file defines the cases and the format). Link to this plan and the plan revision (`git rev-parse --short HEAD:docs/QA_PLAN.md`).

```markdown
#### Release <build/versionCode> — <date> — plan rev <sha> — run type: Full | Smoke

Device RFCR91J237W · build <versionCode> (installed, verified via dumpsys) · widgets <ids/types> · locale · Android/One UI version

| Section | Cases run | Pass | Fail | Unverified | N/A |
|---|---|---|---|---|---|
| A | 12 | 11 | 0 | 1 | 0 |
| ... | | | | | |

| Case | Result | Evidence (file / DS key / frame) | Notes, bug ref, severity |
|---|---|---|---|
| F-09 | ✅ | TS frames 1–14 in run folder | stages at 2.6/3.4/4.2 s, gone 5.7 s |
| K-14 | ⚠️ Unverified | — | notification could not be held for a tap (Android dedup) |
| E-08 | ❌ S2 | SS s31.png | Share ↗ missing when Open-in=Browser; BUG-031 |

Read flags / config changed by the run: <what>. Restored from backup: yes/no.
Gate decision: Release-ready | Blocked (why) | Not release-ready — Unverified P0: <ids>
```

Rules: list **every** non-pass case; passes may be summarised per section but every P0 must appear as an
individual row. Legend: ✅ Pass · ❌ Fail (with severity) · ⚠️ Unverified (why it could not be checked) ·
⏭ N/A (with reason, e.g. "Android < 13 has no notification prompt"). A case is ✅ only if the evidence code
listed was actually collected.

---

## Severity and gating rules

| Sev | Meaning | Examples |
|---|---|---|
| **S1 Blocker** | Crash/FATAL, "Can't show content"/blank widget on a supported config, data loss (feeds, config, read state), update cannot install or wipes data, wrong item marked read on a P0 path, security issue | FATAL in logcat; widget blank after Save; feeds empty after self-update |
| **S2 Major** | A feature does not work and there is no reasonable workaround; a P0 case fails without a crash | Filter ignored; share sends wrong URL; dissolve never removes the article |
| **S3 Minor** | Works with a workaround, or only on rare configs; wrong but recoverable display | Wrong colour in one theme; one control mis-sized |
| **S4 Cosmetic** | No functional effect | Spacing, 1 px misalignment |

**Gate (all must hold to call a build release-ready):**

1. Every **P0** case ran and passed on this build. A P0 that could not be run or verified means **the build is
   not release-ready**; say so and list it. Never write "should work".
2. No open **S1** or **S2** failure. Only the user can waive an S2 (record the waiver text and date in the release
   log); S1 is never waived.
3. Every **P1** case ran. A P1 failure blocks unless it is triaged S3/S4 **and** logged in `docs/BUGS.md`
   (or matches a listed known issue with unchanged behaviour). P2 failures are logged; they do not block.
4. Logcat FATAL/bitmap-memory check clean for the whole run (`L-12`).
5. Unit tests green locally (`M-38`) and CI green on the pushed commit, with both `NewsFeed-latest.apk` and
   `version.json` published (`M-12`). Security review done before the push (project rule, `DEBUG_PLAN.md` §14).
6. New/changed user-facing behaviour has its cases added or updated in this plan **before** the run, and a
   release note (`docs/RELEASE_NOTES.md`, next `## Note n`) exists (`K-17`).
7. **Known open issues** (currently BUG-002 system-locale mirroring, BUG-010 cleartext feeds, BUG-012 last-row
   clip, BUG-013 residual entity decoding, BUG-014 environmental refresh failure, see Appendix A) do not block if
   their behaviour is **unchanged** from the recorded baseline; the case is still run and the actual result
   recorded. A change (better or worse) is reported.

---

## Smoke subset and Full run

**Smoke subset** — every **P0** case (list at the end of this section), ~45 min on device. Use for
mid-development checks and hotfix triage. Not sufficient for a release.

**Full run** — required before every release:

1. All cases in sections A–M (P0 + P1 + P2), run on the standard widget **and** (until merged) the Focus widget
   where a case says "both" or is tagged **[until merged]**.
2. Matrix `G-M01…G-M12` (filter × sort × tap behaviour) — all 12 rows, each in both tap behaviours.
3. Pairwise `PW-A` (50 configurations, Appendix B) via case `H-24`; once Focus-as-setting ships also `PW-B` via `N-22`.
4. Section N in full once Focus-as-setting ships (it replaces every **[until merged]** case).
5. Unit tests, CI status, logcat check, restore of backed-up state.

Estimated effort: Smoke ≈ 45 min; Full ≈ 6–8 h including PW-A (≈ 2 h alone; the 50 rows are ordered so an
interrupted run resumes cleanly — record the last completed `Axx`).

### Smoke subset — P0 cases in sections A–M

38 rows = **35 distinct executions** (`M-01`, `M-02`, `M-10` are the same execution as `F-01/F-02`, `F-04`, `F-12`), ≈ 45–60 min. `E-20` and `F-08` are **[until merged]** Focus cases. Section N adds 10 P0 cases once Focus-as-setting ships (`N-01…N-07`, `N-09`, `N-14`, `N-18`) and replaces the two Focus ones.

`A-01`, `B-01`, `B-05`, `B-07`, `C-01`, `C-04`, `C-15`, `D-03`, `D-17`, `E-01`, `E-04`, `E-07`, `E-09`, `E-20`, `F-01`, `F-02`, `F-04`, `F-08`, `F-09`, `F-12`, `G-05`, `G-M05`, `H-01`, `I-02`, `I-07`, `J-05`, `J-06`, `K-01`, `K-03`, `K-12`, `L-01`, `L-12`, `M-01`, `M-02`, `M-09`, `M-10`, `M-11`, `M-38`

**Case counts** (rows in the tables above; `G` includes the 12 matrix rows):

| Section | Cases | P0 | P1 | P2 |
|---|---|---|---|---|
| A Install / first run & default feeds | 12 | 1 | 10 | 1 |
| B Feeds management | 24 | 3 | 19 | 2 |
| C Fetching & sources (incl. Telegram) | 20 | 3 | 15 | 2 |
| D Display & layout | 20 | 2 | 17 | 1 |
| E Reading (expand / focus / open / share) | 21 | 5 | 16 | 0 |
| F Read state & Unread-only grace / dissolve | 22 | 6 | 13 | 3 |
| G Sort / filter (incl. matrix M1) | 22 | 2 | 20 | 0 |
| H Appearance / theme / fonts | 26 | 1 | 24 | 1 |
| I Refresh / worker / timers / boot | 13 | 2 | 10 | 1 |
| J Settings persistence / backup / multi-widget | 14 | 2 | 10 | 2 |
| K Self-update & release notes | 19 | 3 | 15 | 1 |
| L Robustness | 20 | 2 | 14 | 4 |
| M Regression cases for past bugs | 39 | 6 | 32 | 1 |
| N Applies once Focus-as-setting ships | 25 | 10 | 15 | 0 |
| **Total** | **297** | **48** | **230** | **19** |

Plus the two pairwise tables (50 configurations each, run through `H-24` and `N-22`).


---

## Combination strategy

The option space is too large to test exhaustively (theme 10 × variant 2 × accent 2 × font 3 × style 5 ×
direction 2 × display 2 × length 3 × font size 3 × article font size 3 × opacity 3 × open-in 2 × tap 2 ≈ 780,000 even with sliders reduced to three levels).
Two techniques are used, chosen per axis size and per risk:

* **Full matrix** where the axes are small **and** the interaction is the risk: read-state behaviour depends on
  **filter {All, Unread only, Read only} × sort {Newest, Oldest, By feed, Unread first} × tap behaviour {Expand,
  Focus}** = 24 cells. All 24 are run (`G-M01…G-M12`, each row in both tap behaviours).
* **Pairwise (all-pairs)** for the large appearance/reading option space: every pair of values of any two factors
  appears together in at least one configuration. Generated with a deterministic greedy all-pairs generator
  (seeded, 6 restarts, best result kept) and **verified by exhaustive pair counting** (705/705 pairs for PW-A,
  785/785 for PW-B). Both tables are 50 rows — the theoretical minimum for the two largest axes
  (theme 10 × text style 5). Pairwise is not a full guarantee (3-way interactions are not covered), so:
* **Single-factor sweeps** cover each option's own behaviour (every theme, every dropdown value, slider extremes)
  in the section cases, and **risk-based extras** (below) cover the known-dangerous 3-way combos.

Risk-based extras run on top of pairwise (all in the Full run): Glamour × font 3.0 × article font 3.0 × full
article (bitmap memory, `M-09`); Focus × Glamour × font 3.0 × scale 2.5 (`E-24`); Glamour × RTL × thumbnails ×
Hebrew locale (`L-10`); Custom theme with equal / near-equal font & background colours (`H-15`); Unread only ×
Focus/Expand × Load full article (`F-11`); minimum widget size × failure banner × 3.0 font (`M-11`).

Excluded from pairwise on purpose (covered elsewhere): sort and filter (matrix), refresh interval and retention
(independent, `I-01`, `C-17`), article count/memory (`D-06`, `M-09`).

---

## A — Install/first-run & default feeds

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| A-01 | P0 | On a device with no data (or a spare install ID) install the APK, long-press home → Widgets → NewsFeed, drop it ⇒ the Settings screen opens by itself **before** the widget is placed; the feed list is pre-filled with the 11 bundled feeds; **Save** places the widget and it fills with articles within ~1 min | UI, DS (`newsfeed_config` has 11 feeds for the new id), LC | none |
| A-02 | P1 | Open Settings on that brand-new widget without touching anything ⇒ documented code defaults: Sort **By feed**, Show **All**, Refresh **15 minutes**, Keep articles **Forever**, Open in **Browser**, Font size 1.0 (Medium), Article font size 1.0, Expanded article **First paragraph**, theme **Glamour**, variant **Light**, Use theme accent colors **on**, opacity **100%** | UI, DS (`config_json`) | none |
| A-03 | P1 | Save the fresh widget and view it ⇒ Glamour Light look (cream/beige, Playpen Sans Hebrew handwriting headlines), header "NewsFeed" + `unread(total)` badge, footer `↻ …`/Share/⚙ | SS | none |
| A-04 | P1 | Open the "Add widget" picker (long-press → Widgets → NewsFeed) ⇒ **[until merged]** two entries "NewsFeed" and "NewsFeed Focus", correct labels, no duplicates; **[post-merge]** one entry (see N-01) | VIS/SS | none |
| A-05 | P1 | **Add default feeds** on a list that lacks some defaults ⇒ only missing ones appended in defaults order, none duplicated; status + Toast "Added N default feeds (M already present)"; tapping again ⇒ "Added 0 … (11 already present)"; nothing persisted until Save (Back without Save keeps the old list) | UI, DS | UT:DefaultFeedsMergeTest |
| A-06 | P1 | **Reset to defaults** ⇒ confirmation dialog "Remove all your feeds and load the defaults?"; **Cancel** changes nothing; **Reset** replaces the whole list with the 11 bundled feeds, status "Feeds reset to defaults (tap Save to apply)"; Back without Save keeps the old list; Save persists | UI, DS | none |
| A-07 | P1 | Add default feeds when the list already contains the same feed typed differently (`HTTPS://WWW.YNET.CO.IL/…/`, trailing slash, `@N12_News` vs `t.me/s/N12_News`) ⇒ treated as already present (skipped); `http://` vs `https://` of the same host is **not** equated (both kept) | UI | UT:DefaultFeedsMergeTest |
| A-08 | P1 | Drop a widget, then press **Back** on its first Settings screen ⇒ the widget is **not** placed (Android cancels the placement) and nothing crashes; drop again ⇒ Settings opens again | VIS, LC | none |
| A-09 | P1 | Place a second standard widget while the first exists ⇒ it opens with **defaults**, not a copy of the first widget's settings; both keep their own settings afterwards (`J-06`) | UI, DS | none |
| A-10 | P1 | First render before any fetch completes ⇒ footer shows `↻ now`; after the first refresh it shows `↻ in Nmin`/`↻ <1min` and the header badge counts | SS/UI | none |
| A-11 | P2 | Verify the shipped asset: `unzip -l app-debug.apk \| grep default_feeds.opml` and count `<outline` (11) with **one** walla feed | ADB | none |
| A-12 | P1 | `adb install -r` a new build over the running one ⇒ widgets stay placed and keep feeds, config, read state; a one-off "No session available" placeholder is tolerated only if a single tap recovers it (else S2) | UI, DS, LC | none |

---

## B — Feeds management

Section 6 of Settings ("FEED ORDER & STYLE") plus ADD FEED and FIND FEEDS.

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| B-01 | P0 | Add a valid RSS URL (Add button and IME "Done") ⇒ spinner, real feed title fetched, row appended, field cleared, accent = next palette colour of the current theme (`feeds.size % 12`); after Save its articles appear | UI, DS, SS | none |
| B-02 | P1 | Add a valid Atom URL ⇒ same as B-01 | UI | none |
| B-03 | P1 | Add an unreachable / invalid / HTML-page URL ⇒ inline error "Could not load feed — check the URL", nothing added, no crash | UI, LC | none |
| B-04 | P1 | Add `example.com/feed` without a scheme ⇒ `https://` is prefixed; blank input ⇒ Add disabled | UI | none |
| B-05 | P0 | Add the same feed twice; and the same Telegram channel as `@ch`, `t.me/ch`, `telegram.me/ch`, `https://t.me/s/ch` in turn ⇒ "This feed is already added" each time (no duplicate feedId → no LazyColumn key crash) | UI, LC | UT:TelegramFeedParserTest (canonicalize) |
| B-06 | P1 | Add an `http://` (cleartext) feed ⇒ **record actual**: either rejected at add-time or added but yields 0 articles (BUG-010 open — Android blocks cleartext). Check whether default feed "The Hindu" (`http://…`) yields articles | UI, DS, LC | none |
| B-07 | P0 | Tap × on a feed row ⇒ the row is removed (the **Edit** dialog must not open); after Save its articles are gone from the widget, remaining feeds unchanged | UI, SS | none |
| B-08 | P1 | Tap a feed name → Edit; change only the name ⇒ saved without re-fetch; new name shown in Settings and in the row's meta line after Save | UI | none |
| B-09 | P1 | Edit a feed's URL to another valid feed ⇒ spinner, feed re-validated, `feedId` and `feedOrder` entry become the new URL, name kept (or fetched name if blank) | UI, DS | none |
| B-10 | P1 | Edit a feed's URL to an invalid one ⇒ inline error "Could not load feed — check the URL"; dialog stays open; nothing saved; Cancel discards | UI | none |
| B-11 | P1 | Edit with a blank name ⇒ falls back to the fetched title (if URL changed) or the original name | UI | none |
| B-12 | P1 | Drag a **middle** feed to the top, then another down two places using the ⠿ handle ⇒ exactly the touched row moves each time (offset of 4 header items); after Save + reopen the order persisted; **By feed** sort follows the new order | UI, DS (`feedOrder`), SS | none |
| B-13 | P1 | Import a flat OPML file (existing + new feeds) ⇒ status "Added N feed(s)", existing skipped, new feeds get rotating palette colours (not all `#9B72E3`); a file with only known feeds ⇒ "No new feeds found" | UI, DS | none |
| B-14 | P1 | Import a grouped/nested OPML (folders) from a real reader (Feedly/Reeder) ⇒ all `xmlUrl` outlines imported, folder outlines ignored | UI | none |
| B-15 | P2 | Import a non-OPML/binary/empty file ⇒ no crash; "No new feeds found" (or "Could not read file") | UI, LC | none |
| B-16 | P1 | Export OPML ⇒ share sheet with `feeds.opml`; open it in another reader/import into a spare widget (round trip: same names/URLs; `&`, `<`, `"` escaped); button disabled when there are no feeds | VIS, UI | none |
| B-17 | P1 | Find Feeds: search a broad keyword ("technology") ⇒ results with title, description (≤80 chars), subscriber count; **+ Add** adds it and the button becomes **Added** (disabled), also for a feed already in the list; dead/blocked candidates are filtered out (validated with the app's own headers) | UI | none |
| B-18 | P1 | Search for gibberish, and again with the device offline ⇒ "No feeds found"; no hang/crash; spinner stops | UI, LC | none |
| B-19 | P1 | Search via the keyboard Search action and via the button; blank query ⇒ button disabled | UI | none |
| B-20 | P1 | With **Use theme accent colors** off, tap a feed's colour swatch ⇒ 12-colour grid matching the **current theme's palette** (themes without their own palette — Black & White, Custom — show the "auto" palette); switching theme then reopening shows that theme's palette; picking applies to circle, stripe, name, unread dot | UI, SS | none |
| B-21 | P1 | Toggle RTL/LTR, TXT/IMG, Font (Default/Serif/Mono) and B/I/U on one feed, Save, reopen ⇒ every value persisted on that feed only | UI, DS | none |
| B-22 | P1 | 30+ feeds: scroll the Settings list, drag a row from the bottom section, add/remove ⇒ smooth, no wrong-row moves, no crash | UI, LC | none |
| B-23 | P2 | Feed with a very long or RTL name ⇒ Settings row wraps sanely; in the widget the feed name truncates (max 1 line) without pushing the circle/timestamp off the row | SS | none |
| B-24 | P1 | Change the theme **after** feeds exist ⇒ stored accent colours are unchanged (assigned at add time); only the picker palette changes | DS | none |

---

## C — Fetching & sources (incl. Telegram)

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| C-01 | P0 | Refresh with the default feeds ⇒ articles for each working feed; each row's time equals the feed's real `pubDate` converted to device time (check one `+0300` and one `GMT` feed); today `HH:mm`, older `dd/MM HH:mm` (cross-midnight shows the date) | UI, DS (`publishedAt`) | none |
| C-02 | P1 | Atom feed (`<entry>`, `published`/`updated`, `content`/`summary`) ⇒ parsed with title, time, description | UI | none |
| C-03 | P1 | Hebrew feeds (ynet, walla, globes, kan) and rotter.net ⇒ fetched (browser-like headers), no garbled characters, including rotter.net whose charset is only in `<meta charset>` | UI, SS | none |
| C-04 | P0 | Add Telegram channels typed as `@ch`, `t.me/ch`, `https://t.me/ch`, `telegram.me/ch`, `t.me/s/ch`, `https://t.me/s/ch`, `T.ME/ch` ⇒ each is recognised as Telegram (not "Could not load feed"), title is the real channel title (not the handle), feedId = `https://t.me/s/<ch>`. **Record** the known gap: `http://t.me/s/ch` and `telegram.me/s/ch` still hit the old failure | UI, DS | UT:TelegramFeedParserTest |
| C-05 | P1 | Telegram posts ⇒ first two lines joined into one headline, description = the COMPLETE post text (title lines included, up to 4096 chars; BUG-020); photo-only post falls back to the channel name as title; permalink is the article URL and id; timestamps correct; images load in IMG mode | UI, SS, DS | UT:TelegramFeedParserTest |
| C-06 | P1 | Add a private/invite link (`t.me/joinchat/…`, `t.me/+…`) and a non-existent channel ⇒ not accepted as a channel; inline error, no crash | UI, LC | UT:TelegramFeedParserTest |
| C-07 | P1 | Refresh a Telegram feed twice ⇒ no duplicate articles (id = permalink); history grows only with new posts | DS | none |
| C-08 | P1 | Add a feed that has a large backlog ⇒ first fetch pulls up to 300 items; subsequent refreshes take the 50 newest; a known feed's fetch-time cap is 10 per feed in the fetch batch, while the accumulated store keeps up to 300 | DS (count per feedId) | none |
| C-09 | P1 | rotter.net titles containing double-escaped entities (`&amp;#128308;`, `&amp;#8207;`, geresh `&amp;#1523;`) ⇒ decoded to the real characters. **Known residual (BUG-013):** a few titles stay literal — record which, unchanged vs baseline is acceptable | SS, DS | UT:TelegramFeedParserTest (Telegram path only) |
| C-10 | P1 | Descriptions are plain text (no tags/entities), ≤ 400 chars stored; length modes show 100 / 400 / full (see E-04) | DS, SS | none |
| C-11 | P1 | Image sources: feeds using `media:thumbnail`, `media:content`, `enclosure`, first `<img>` ⇒ thumbnail downloaded for the newest 30 merged articles and shown beside the headline in IMG mode; none reserved in TXT mode | SS, UI | none |
| C-12 | P1 | Favicons ⇒ downloaded and shown as the feed circle; feeds without one show the accent-coloured circle with the initial | SS | none |
| C-13 | P2 | Feed items with no `guid`/`id` **and** no `link` ⇒ record whether the same item gets a new id on every refresh (latent risk noted in BUG-007: duplicates accumulate) | DS | none |
| C-14 | P1 | One blocked feed (HTTP 403) among working ones ⇒ that feed yields 0 articles, others unaffected, **no** "refresh failed" banner (that needs every feed to fail) | UI, LC | none |
| C-15 | P0 | Airplane mode, tap the footer refresh ⇒ footer shows amber `⚠ refresh failed — tap to retry`; cached articles stay; restore network + tap ⇒ recovers, banner gone | SS, DS (`last_refresh_failed`) | none |
| C-16 | P1 | Accumulation: with many feeds ⇒ store ≤ 300 articles, every feed keeps its 10 newest even when high-frequency feeds dominate (per-feed floor), ids deduplicated | DS | none |
| C-17 | P1 | **Keep articles for**: set 1 day, refresh ⇒ older articles pruned, cap still applies; set Forever ⇒ nothing pruned by age. Also 3 days / 1 week / 2 weeks / 1 month accepted and saved | DS (`retentionDays`, `publishedAt`) | none (gap: no unit test for retention cutoff or `retainWithPerFeedGuarantee`) |
| C-18 | P1 | Merge keeps `readAt` and every fresh field ⇒ mark read, refresh, article still read with the same `readAt` | DS | UT:ArticleMergeTest |
| C-19 | P1 | Previously read articles stay read after refresh, reboot and app restart (global read-id store) | DS (`newsfeed_read_status`) | none |
| C-20 | P2 | Odd feeds: gzip, redirect chain, malformed XML, empty feed, 10 MB feed, 25 s+ slow feed ⇒ failure contained to that feed within the 15 s connect / 25 s read timeouts | LC, UI | none |

---

## D — Display & layout

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| D-01 | P1 | Inspect a row of each direction ⇒ accent stripe (3 dp) on the **left** for LTR and **right** for RTL; feed circle (favicon or initial), feed name, timestamp (physically left), unread dot only while unread, headline (≤3 lines; ≤8 in Glamour), thumbnail beside the headline | SS, UI | none |
| D-02 | P1 | Header badge `U(T)` ⇒ U = unread among **displayed** rows, T = displayed rows, `99+` when U > 99; decrements when an article is marked read | UI | none |
| D-03 | P0 | Footer ⇒ `↻ now` (never refreshed), `↻ in Nmin`, `↻ <1min`, or amber `⚠ refresh failed — tap to retry`; tap the countdown **including its padding** ⇒ refresh runs; **Share** opens the share dialog; **⚙** opens Settings for this widget | UI (bounds), SS, LC | none |
| D-04 | P1 | Countdown ticks down without interaction (≈ every 60 s via the clock alarm) and never shows a negative/stale value after a refresh | UI over 3 min, JS | none |
| D-05 | P1 | With > 10 articles ⇒ scrolling list; **Load more articles ↓** reveals the next 10 per tap and disappears when nothing more can be revealed | UI | none |
| D-06 | P1 | Push against the memory ceiling (Glamour, font 3.0, 300 stored) ⇒ instead of Load more, a non-tappable line `Showing X of Y · reduce font size to see more` (at font 0.5: `… · this device can't show more at once`); X is accurate; no "Can't show content" | UI, LC | none |
| D-07 | P1 | Show = Read only with no read articles, or an empty feed list ⇒ centred "No articles"; header/footer still work | SS | none |
| D-08 | P1 | Non-Glamour theme with ≥ 200 stored ⇒ scroll reaches ≈ the total (up to 300); Glamour caps the list at 60 rows | UI | none |
| D-09 | P1 | **Font size** slider 0.5–3.0, labels Tiny (<0.75), Small (<1.0), Medium (<1.5), Large (<2.0), Huge ⇒ headline, meta line and thumbnail scale. **Verify and record** whether header/footer text scales: the code uses fixed sizes there while the README says it scales (Appendix D) | SS, UI | none |
| D-10 | P1 | **Article font size** slider (same range/labels) ⇒ scales **only** the expanded body text; change one slider and confirm the other's rendered size does not move | SS | none |
| D-11 | P1 | Settings live preview card ⇒ updates immediately (no Save) for theme, variant, custom colours, font size, article font size and expanded-article length; preview ignores per-feed font/style; thumbnail box capped at 64 dp so the headline never breaks mid-word at 3.0 | SS | none |
| D-12 | P1 | **Background opacity** 0–100 % in 5 % steps ⇒ card transparency scales smoothly (0 % = see-through to wallpaper); text stays legible; Glassy still renders | SS | none |
| D-13 | P1 | Resize each widget to its minimum (130×200 dp) and maximum (500×600 dp), and place at the default 4×4 ⇒ no broken clipping/overlap of header, rows or footer; widget re-renders at real width (SizeMode.Exact) | SS, UI | none |
| D-14 | P1 | Unread state ⇒ dot shown only while unread (LTR and RTL), headline colour dimmed to the muted "read" colour when read (non-Glamour) | SS | none |
| D-15 | P1 | Thumbnails ⇒ side thumbnail ≥ 2 headline-lines tall, width `52×fontSize` capped at 120 dp, hidden while the row is expanded (replaced by a 120 dp header image) | SS, UI | none |
| D-16 | P1 | Row dividers ⇒ hairline + shadow strip between rows, none after the last | SS | none |
| D-17 | P0 | Settings screen ⇒ sections in order SORT & FILTER, DISPLAY, APPEARANCE, ADD FEED, FIND FEEDS, FEED ORDER & STYLE, APP UPDATE, BUG REPORTS; **nothing applies until Save** (Back discards; widget unchanged) | UI, DS | none |
| D-18 | P1 | Settings honours system dark/light mode; readable in both; per-feed row controls legible | SS | none |
| D-19 | P1 | System font size / display size at maximum ⇒ widget text scales with `sp` (Glamour bitmaps too) without clipping the footer controls | SS, UI | none |
| D-20 | P2 | Long headlines (Hebrew, mixed Hebrew/English, digits at start, quotes) ⇒ no mid-glyph clipping at the row edge; Hebrew word spacing normal (no oversized justification gaps) | SS | none |

---

## E — Reading (expand / focus / open / share)

Standard-widget cases run in "Expand" behaviour (the only one until Focus ships). Cases marked **[until merged]** run on
the separate NewsFeed Focus widget.

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| E-01 | P0 | Tap an article that has a description ⇒ expands inline (description, plus **Open article →** and, when Open-in = Browser, **Share ↗**; in Full mode **Load full article ↓**); tap it again ⇒ collapses; expanding another collapses the first (one at a time) | UI, DS (`expanded_article_id`) | none |
| E-02 | P1 | IMG-mode feed expanded ⇒ 120 dp header image above the text; TXT-mode ⇒ none | SS | none |
| E-03 | P1 | Tap an article with **no description** (rotter.net, ynet flash) ⇒ nothing expands, no Open/Share links, native press ripple only | UI, VIS | none |
| E-04 | P0 | Expanded-article length **Subtitle only** ⇒ ≤ 100 chars + `…`; **First paragraph** ⇒ ≤ 400; **Full article** ⇒ shows the description first; tap **Load full article ↓** ⇒ replaced by the fetched page body (two-phase); each mode matches the Settings preview. **Telegram sub-case (BUG-020):** on a Telegram post (1-line and 5+-line) expand in each mode ⇒ Subtitle ≤ 100 + `…`, First paragraph ≤ 400, Full shows the whole post text; **no** Load full article button on Telegram posts; a 1-line post still expands to its text; never shows "Download Context Embed View In Channel" page chrome | SS, DS (`full_article_*`) | none |
| E-05 | P1 | Full mode on a clutter-heavy site and on a Hebrew site (ynet) and rotter.net ⇒ real body only (no nav/ads/related links), no charset mangling, correct RTL | SS | none |
| E-06 | P1 | Full mode **Load more ↓** ⇒ 1200-char chunks, each with **Open in browser ↗**, stops cleanly at the article end or the memory/8-chunk cap with no dangling button; in non-Glamour the whole fetched text renders at once | SS, UI, LC | none |
| E-07 | P0 | **Open article →** with Open-in = Browser ⇒ default browser opens the article URL; with Open-in = **Share sheet** ⇒ chooser titled "Share article" with the URL | VIS, LC | none |
| E-08 | P1 | **Share ↗** (only present when Open-in = Browser) ⇒ chooser titled "Share article" carrying that article's URL; absent when Open-in = Share sheet; works on both widget types | VIS, UI | none |
| E-09 | P0 | Footer **Share** ⇒ dialog "Share NewsFeed" with "Share the app" (repo URL) and "Share the download link" (releases/tag/latest URL); each shares the right URL under its own chooser title; dismissing without choosing leaves nothing on screen; no crash | VIS, LC | none |
| E-10 | P1 | Expanded row at font 3.0 and at min widget width ⇒ Open/Share/Load buttons do not overlap or overflow; hidden while the row is dissolving (`F-11`) | SS, UI | none |
| E-11 | P1 | Full mode with the device offline / dead article URL ⇒ description stays, no crash, no infinite spinner | SS, LC | none |
| E-12 | P1 | Expanded state survives a refresh and a Settings save; article without a URL ⇒ no Open/Share links | DS, UI | none |
| E-13 | P1 | Open-in = Share and a Browser-mode change made on a **second** widget ⇒ each widget keeps its own setting | UI | none |
| E-20 | P0 | **[until merged]** Focus widget: tap article A ⇒ row enlarges (default 1.25×) with the primary-container tint, its description/full-text auto-expands, every other row shrinks to *Background rows size*; header shows **N/M** and **− +** only (no ▲ ▼ ✕); none of these appear on the standard widget | SS, UI | none |
| E-21 | P1 | **[until merged]** **− / +** ⇒ focused row scale steps 0.15 within 0.75×–2.5×, clamps at both ends; focusing a different article resets to 1.25× | DS (`focus_scale`), SS | none |
| E-22 | P1 | **[until merged]** Tap the focused row again ⇒ focus clears, all rows normal size, **N/M and −/+ disappear**, nothing marked read | DS, UI | none |
| E-23 | P1 | **[until merged]** *Background rows size* slider (25–100 %) appears **only** in the Focus widget's Settings, absent on the standard widget; changing it changes non-focused rows' size, not the focused row's | UI, SS | none |
| E-24 | P1 | **[until merged]** Glamour + Font size 3.0 + focus scale 2.5 (worst case) ⇒ renders (≥ 1 row), no "Can't show content", logcat clean | SS, LC | none |
| E-25 | P1 | **[until merged]** Step focus A→B→C ⇒ only the current row is tinted/enlarged; previous rows' tint/size never stick (recycled-view regression) | SS, UI | none |
| E-26 | P1 | **[until merged]** Focused row at high scale ⇒ meta row (favicon, name, time) stays at normal size and never spills off the edge | SS | none |
| E-27 | P1 | **[until merged]** Focused row's Load full article / Load more / Open article / Share ↗ / Open in browser ↗ behave exactly as on the standard widget | UI, VIS | none |

---

## F — Read state & Unread-only grace period / dissolve

Rules under test. **Standard widget:** pressing an article marks nothing; when a **different** article is tapped,
the previously tapped one is marked read (`isRead=true`, `readAt=now`); re-tap/collapse marks nothing; an already-read
article is never re-stamped; description-less articles follow the same rule. **Focus [until merged]:** the article
**losing** focus is marked when focus moves to another article; clearing focus marks nothing. **Unread only:** a
just-read article stays 5 s: normal text to 2.5 s → all dots 2.5–3.33 s → 2/3 of the dots 3.33–4.17 s → 1/3 of
the dots 4.17–5 s → removed (observed ≈ 5.6–5.9 s wall time because renders fire ≈ 0.5 s late). Dissolve applies **only**
under "Unread only".

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| F-01 | P0 | Standard widget, unread article A: tap it once ⇒ A **not** marked (`isRead:false`, no `readAt`); `last_tapped_article_id` = A | DS | UT:ReadOnMoveAwayTest |
| F-02 | P0 | Then tap a different article B ⇒ A now `isRead:true` with a fresh `readAt`; B not marked; header unread count −1 | DS, UI | UT:ReadOnMoveAwayTest |
| F-03 | P1 | Tap A again immediately / collapse the expanded A ⇒ nothing marked, no `readAt` change | DS | UT:ReadOnMoveAwayTest |
| F-04 | P0 | Description-less article A (rotter/ynet flash) then a different article B ⇒ A marked read at B (via the no-op tap path); pressing A alone marked nothing; no expand, ripple only | DS | UT:ReadOnMoveAwayTest |
| F-05 | P1 | Mixed chain: expandable A → description-less B → expandable C ⇒ A read at B, B read at C, C unread | DS | UT:ReadOnMoveAwayTest |
| F-06 | P1 | Tap through already-read articles ⇒ their `readAt` is not re-stamped, no new grace period starts | DS | UT:ReadOnMoveAwayTest |
| F-07 | P1 | Press **Open article →** / **Share ↗** / **Open in browser ↗** on the current article ⇒ **record** whether anything is marked read (code: these buttons mark nothing; `DEBUG_PLAN.md` §1 claims the Open-in setting marks read — Appendix D) | DS | none |
| F-08 | P0 | **[until merged]** Focus widget: focus A ⇒ nothing marked; focus B ⇒ **A** marked read (losing focus), B unread; tap focused B to clear ⇒ nothing marked | DS | none |
| F-09 | P0 | Show = **Unread only**, mark A read (tap B) and capture TS frames ⇒ A visible dimmed; text normal until ≈ 2.5 s, all dots ≈ 2.5 s, 2/3 dots ≈ 3.3 s, 1/3 dots ≈ 4.2 s, gone ≈ 5.6–5.9 s; each stage seen in at least one frame, in order; unread badge already decremented | TS, DS | UT:ArticleDissolveTest, ArticleSortingTest |
| F-10 | P1 | Same read event under **All** and **Read only** ⇒ article is never dotted or removed, only dimmed (All) / listed (Read only) | TS/SS | UT:ArticleSortingTest |
| F-11 | P1 | While a row dissolves ⇒ its Load full article / Open / Share / Load more controls are hidden (they would act on dotted text); return to normal rows unaffected | UI | UT:GraceRefreshRegistryTest (`isDissolving`) |
| F-12 | P0 | Mark A read then tap **Save** in Settings (triggers `refreshNow`) within 5 s ⇒ `readAt` survives the refresh, A still dims for the full window (does not vanish in ~1–2 s) | DS, TS | UT:ArticleMergeTest |
| F-13 | P1 | Description-less article read under Unread only ⇒ gets `readAt` and the full grace + dissolve, disappears ≈ 5–8 s later | DS, TS | UT:ReadOnMoveAwayTest |
| F-14 | P1 | Heavy row (Kan/Glamour, big font) read under Unread only ⇒ no stage is skipped (each of the 3 dissolve frames visible); ≤ 4 updates per read article in logcat | TS, LC | UT:ArticleDissolveTest (`remainingRefreshDelays`) |
| F-15 | P1 | Read A under Unread only, `adb shell kill -9 <pid>` at +1.7 s / +3.4 s / +60 s, then trigger a re-render (`CLOCK_TICK` or tap widget) ⇒ A is not stuck dotted; gone; bounded updates, none after removal | TS, LC, DS | UT:GraceRefreshRegistryTest |
| F-16 | P1 | Mark two articles read within ~1 s of each other ⇒ each dissolves on its own timeline; both gone by ≈ 6 s after their own mark | TS | UT:ArticleDissolveTest |
| F-17 | P1 | Read flags persist across refresh, reboot, `force-stop`, and appear on **new** fetches of the same article id | DS | none |
| F-18 | P1 | Show = **Read only** after reading ⇒ the article is listed (dimmed); unread articles absent; badge `0(n)` | UI | UT:ArticleSortingTest |
| F-19 | P1 | Sort = **Unread first**, mark A read ⇒ A moves below all unread articles on the next render; unread order stays newest-first | UI | UT:ArticleSortingTest |
| F-20 | P2 | Read state across widgets ⇒ reading an article in widget 1 marks the same id read in widget 2 **on its next refresh** (global read-id store), not instantly | DS | none |
| F-21 | P2 | Rapid tap storm A,B,A,B,C on rows ⇒ final state consistent (each moved-away article read once), no crash | DS, LC | none |
| F-22 | P2 | Reinstall (`install -r`) or Save Settings mid-dissolve ⇒ record whether a row can stay dotted until the next tap (known, cosmetic); rows below jump up when the row disappears | TS | none |

---

## G — Sort & filter

Sort: Newest first, Oldest first, **By feed (default)**, Unread first. Filter: All, Unread only, Read only.
Sort/filter are applied at render time to the whole accumulated list.

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| G-01 | P1 | Sort = Newest first ⇒ strictly descending `publishedAt` across all feeds | UI | UT:ArticleSortingTest |
| G-02 | P1 | Sort = Oldest first ⇒ ascending | UI | UT:ArticleSortingTest |
| G-03 | P1 | Sort = By feed ⇒ true round-robin (one article per feed per round) in **Settings feed order**; reorder feeds + Save ⇒ round-robin order follows; a low-frequency feed's newest article is in the first round | UI | UT:ArticleSortingTest |
| G-04 | P1 | Sort = Unread first ⇒ unread articles above read ones regardless of date; within each block newest first | UI | UT:ArticleSortingTest |
| G-05 | P0 | Show = Unread only ⇒ read articles genuinely absent (except the 5 s grace); Read only ⇒ only read articles; All ⇒ everything | UI | UT:ArticleSortingTest |
| G-06 | P1 | A brand-new widget's Sort is **By feed** (code default; README says Newest first — Appendix D) | DS, UI | none |
| G-07 | P1 | Change Sort/Filter, Save ⇒ widget reflects it, and it **survives** the next refresh (sort/filter must not be re-applied only to a fresh batch) | UI | UT:ArticleSortingTest |
| G-08 | P1 | Filter change while an article is expanded (Expand) or focused (**[until merged]** Focus) ⇒ no crash; expanded/focused state is dropped or kept consistently, record which | UI, DS | none |
| G-09 | P1 | Sort × Load more ⇒ order is stable across chunks (no article repeats/skips between the first 10 and the next 10) | UI | none |
| G-10 | P1 | Low-frequency feed under By feed with high-frequency neighbours ⇒ its articles are reachable within Load more (under Newest they may not be — note the difference) | UI, DS | none |

### Matrix M1 — filter × sort × tap behaviour (24 cells)

**Scenario S** (run in **each** row, once per tap behaviour): ≥ 3 feeds, ≥ 6 articles each, mix of read/unread (for
Read only pre-read ≥ 3 articles). Tap top-most eligible article **A**, then another article **B** (A ≠ B, both
eligible for the filter). Expand behaviour = standard widget; Focus behaviour = Focus widget [until merged] /
`tapMode=Focus` [post-merge]. Record `isRead`/`readAt` (DS) and TS frames where dissolve is expected.

| ID | P | Filter | Sort | Expected — Expand (tap B after A) | Expected — Focus (focus B after A) |
|---|---|---|---|---|---|
| G-M01 | P1 | All | Newest | Order desc by time; A marked read at B, dimmed **in place**; never dotted/removed | Same, trigger = focus moves A→B; clearing focus marks nothing |
| G-M02 | P1 | All | Oldest | As M01 with ascending order | As M01 |
| G-M03 | P1 | All | By feed | Round-robin in feed order; A read at B, dimmed in place | As M03 Expand, trigger = focus move |
| G-M04 | P1 | All | Unread first | A read at B; on the next render A moves **below all unread** articles | Same |
| G-M05 | P0 | Unread only | Newest | A marked at B, stays dimmed to 2.5 s, dotted stages, gone ≈ 5.6 s; badge already −1; B and the rest unaffected | Same on the article losing focus (A); the focused B is unread and stays |
| G-M06 | P1 | Unread only | Oldest | As M05, ascending order | As M05 |
| G-M07 | P1 | Unread only | By feed | As M05; round-robin closes up when A leaves (rows below move up) | As M05 |
| G-M08 | P1 | Unread only | Unread first | As M05; during the grace window A (read) sorts **after** the unread rows, then disappears | As M05 |
| G-M09 | P1 | Read only | Newest | Only read articles listed (pre-read set); tap A then B ⇒ **nothing marked**, `readAt` unchanged, no dissolve/removal | Focus A then B ⇒ nothing marked, unchanged |
| G-M10 | P1 | Read only | Oldest | As M09, ascending | As M09 |
| G-M11 | P1 | Read only | By feed | As M09, round-robin among read articles | As M09 |
| G-M12 | P1 | Read only | Unread first | As M09 (all read ⇒ plain newest-first among them) | As M09 |

Expectation for every row: header badge, footer and other rows unaffected; logcat clean; nothing outside A is marked.

---

## H — Appearance (theme / fonts)

Fonts: theme decides the default typeface (Lavender/Amethyst serif, Aerospace/Data Science mono, Glamour handwriting
bitmap, others sans); the per-feed **Font** dropdown only *overrides* to Serif or Mono ("Default" = follow the theme).
Glamour ignores per-feed font and B/I/U (bitmap headlines).

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| H-01 | P0 | Glamour (default) ⇒ headlines and article text are the Playpen Sans Hebrew handwriting bitmaps (regular body, bold headline), tinted to the theme ink; **no other theme** ever shows the handwriting face | SS | none |
| H-02 | P1 | **Lavender** Light+Dark ⇒ lavender palette, serif typeface, soft editorial look | SS | UT:WidgetThemesTest (existing themes unaffected) |
| H-03 | P1 | **Amethyst** Light+Dark ⇒ deep purple palette, serif | SS | UT:WidgetThemesTest |
| H-04 | P1 | **Glassy** Light+Dark ⇒ frosted semi-transparent surface, readable at opacity 100/50 % | SS | UT:WidgetThemesTest |
| H-05 | P1 | **Simple** Light+Dark ⇒ grayscale, sans | SS | UT:WidgetThemesTest |
| H-06 | P1 | **Aerospace** Light+Dark ⇒ amber on charcoal, mono; dark headline colour `#FFE5B4` | SS (pixel sample) | UT:WidgetThemesTest |
| H-07 | P1 | **Data Science** Light+Dark ⇒ teal/navy, mono; light headline colour `#007870` | SS (pixel sample) | UT:WidgetThemesTest |
| H-08 | P1 | **Black & White** Light+Dark ⇒ pure black/white text (255,255,255 in Dark), no grays in text/containers. **Known gaps (unchanged = OK):** per-feed favicon circles keep brand colours; the failure banner is amber | SS (pixel sample) | UT:WidgetThemesTest |
| H-09 | P1 | **Auto** ⇒ follows the **system** light/dark (Material You); the header/rows use system-driven colours | SS | none |
| H-10 | P1 | Auto × Theme variant Light × **system dark mode on** ⇒ **record and judge legibility**: the widget background follows the *variant* while text colours follow the *system* (code path differs), so light-on-white is possible | SS | none |
| H-11 | P1 | Auto × variant Dark × system dark off ⇒ same check (dark background, dark text?) | SS | none |
| H-12 | P1 | Auto × variant Light × system light, and Auto × Dark × system dark ⇒ readable, consistent | SS | none |
| H-13 | P1 | Settings preview vs widget for Auto with variant flipped ⇒ preview honours the variant while the widget may not — record any mismatch (Appendix D) | SS | none |
| H-14 | P1 | Theme **variant** Light/Dark on every non-Auto theme ⇒ independent of the device dark-mode setting | SS | none |
| H-15 | P1 | **Custom**: three RGB sliders each for Font and Background colour ⇒ swatch and preview update live; saved as uppercase `#RRGGBB`; Light uses the colours as picked, Dark **swaps** them; gear icon, footer text, unread badge, dividers and row dots derive from the two colours (no stock purple); edge pairs: equal colours (unreadable but no crash), CC3 near-equal grays | SS, DS (`customFontColor`, `customBackgroundColor`) | UT:WidgetThemesTest |
| H-16 | P1 | **Use theme accent colors** on ⇒ every feed uses the theme accent and the Settings swatches are hidden; off ⇒ per-feed colours reappear and swatches show | SS, UI | none |
| H-17 | P1 | Per-feed **Font** Default/Serif/Mono on non-Glamour themes ⇒ Serif and Mono change that feed's headline (and body) typeface only; Default follows the theme (so Lavender/Amethyst "Default" is serif, Aerospace/Data Science "Default" is mono); on Glamour the dropdown has no visible effect | SS | none |
| H-18 | P1 | **B / I / U** on a non-Glamour feed ⇒ Italic and Underline change that feed's headlines; **record** that Bold shows no change (headlines are already bold; only a `normal` key would un-bold and no UI writes it — Appendix D); combination BIU applies all that work; Glamour ignores all three | SS | none |
| H-19 | P1 | Per-feed **RTL/LTR** with one RTL and one LTR feed in the same widget ⇒ stripe side, meta-row order, text alignment follow each feed's own setting; independent of the feed's language | SS | none |
| H-20 | P1 | Per-feed **TXT/IMG** ⇒ IMG shows thumbnails (downloaded), TXT reserves no space; thumbnail scales with font size (capped) | SS | none |
| H-21 | P1 | Settings preview vs real widget for the same config (theme, font, sizes, length, custom colours) ⇒ the same colours/typeface/wrapping (preview is a 303 dp card) | SS | none |
| H-22 | P1 | Palette per theme ⇒ the 12-colour picker changes with the theme (Lavender, Amethyst, Aerospace, Data Science, Glassy, Simple, Glamour, Auto); Black & White and Custom use the "auto" palette | UI | none |
| H-23 | P1 | Opacity 0 / 50 / 100 % on Glassy, Simple, Glamour, Custom ⇒ readable, no stale opaque background | SS | none |
| H-24 | P1 | **Pairwise PW-A** — run all 50 configurations of Appendix B (apply per-feed factors to all feeds); for each: widget renders, no crash, expectations of the single-factor cases hold; record the last completed `Axx` | SS, LC | none |
| H-25 | P1 | Legacy/alias theme keys (`light`, `dark`, `data_science`) in a config ⇒ resolve to Lavender / Amethyst / Data Science (only reachable from an old saved config) | DS/UT | UT:WidgetThemesTest |
| H-26 | P2 | Unknown theme key in a config ⇒ falls back to Auto without crashing | LC | none |

---

## I — Refresh / worker / timers / boot

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| I-01 | P1 | **Refresh every** — cycle all 7 options (15 m, 30 m, 1 h, 2 h, 4 h, 6 h, 12 h) and Save each ⇒ `NewsFeedRefresh` period changes each time (`dumpsys jobscheduler`); footer countdown uses the chosen interval | JS | none |
| I-02 | P0 | Tap the footer refresh control 5× within ~1 s ⇒ exactly **one** `WidgetWorker` run (unique work), both widgets updated; a later tap starts a new run | LC, DS (`last_refresh_time`) | none |
| I-03 | P1 | **Save** in Settings triggers an immediate refresh of all placed widgets (both types) and reschedules the periodic job to the saved interval | JS, DS | none |
| I-04 | P1 | After placing a widget ⇒ `NewsFeedRefresh` (periodic), `NewsFeedUpdateCheck` (24 h) and the `CLOCK_TICK` alarm are scheduled | JS | none |
| I-05 | P1 | Enabling a second widget (either type) after the interval was set to e.g. 6 h ⇒ the interval is **not** reset to 15 min | JS | none |
| I-06 | P1 | **[until merged]** Remove only the standard widget ⇒ shared jobs survive, Focus `CLOCK_TICK_FOCUS` pending, standard `CLOCK_TICK` gone; remove only the Focus widget ⇒ mirror image; remove both ⇒ `NewsFeedRefresh`/`NewsFeedUpdateCheck` cancelled | JS | none |
| I-07 | P0 | Reboot with a widget placed ⇒ refresh job, update-check job and the clock alarm(s) resume **without** opening the app; widget refreshes | JS, DS | none |
| I-08 | P1 | Leave the phone idle ≥ 1 h (screen off) ⇒ `last_refresh_time` advances at about the chosen interval; if a whole cycle fails with "refresh failed" and logcat shows `isBlocked=true` DNS for several apps, classify as the environmental network flap (BUG-014), not an app defect | DS, LC | none |
| I-09 | P1 | Worker with an empty feed list (simulate by clearing the live config) ⇒ `ConfigBackup.restoreIfEmpty` restores feeds and order on the next refresh; no crash | DS, LC | none |
| I-10 | P1 | Two widgets ⇒ a refresh updates **both** (each fetches with its own feed list) and keeps each widget's expanded/focused state | DS | none |
| I-11 | P1 | Read-triggered re-renders ⇒ no polling: ≤ 4 updates per article marked read under Unread only; no updates after removal | LC | UT:GraceRefreshRegistryTest |
| I-12 | P1 | Countdown alarm survives Doze (`setAndAllowWhileIdle`), may lag; card still updates after unlock | UI | none |
| I-13 | P2 | 11 feeds on Wi-Fi ⇒ full refresh completes in a reasonable time (< 30 s) and thumbnails for the newest 30 are cached | LC | none |

---

## J — Settings persistence / backup / multi-widget

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| J-01 | P1 | Change **every** SORT & FILTER control (sort, show, refresh, keep articles, open article in), Save, reopen ⇒ each value persisted and reflected on the widget | DS, UI | none |
| J-02 | P1 | Change **every** DISPLAY control (font size, article font size, expanded article; **[until merged]** background rows size on the Focus widget) ⇒ persisted | DS, UI | none |
| J-03 | P1 | Change **every** APPEARANCE control (theme, variant, custom font/background RGB, accent switch, opacity) ⇒ persisted | DS, UI | none |
| J-04 | P1 | Per-feed fields (name, URL, colour, direction, display mode, font, styles, order) ⇒ persisted per feed | DS | none |
| J-05 | P0 | Change several settings and press **Back** (no Save) ⇒ widget and stored config unchanged; reopening shows the old values | DS, UI | none |
| J-06 | P0 | Two widgets with different themes/sort/filter/feeds ⇒ each keeps its own config, articles, expanded/focused state and read flags; changing one does not affect the other; after opening B's Settings then A's, A still shows **A's** values (no bleed) | DS, UI | none |
| J-07 | P1 | Reconfigure an existing widget (long-press → Edit widget, and the footer ⚙) ⇒ opens with its current config; Save updates that widget only | UI, DS | none |
| J-08 | P1 | `ConfigBackup`: tap **Update Now** (or call the flow) ⇒ `newsfeed_config_backup` gains `widget_<id>` for each widget with feeds; then wipe the live feed list and refresh ⇒ feeds and order are restored from the backup (other settings keep their defaults) | DS | none (gap: no unit test for `ConfigBackup`) |
| J-09 | P1 | A widget with an **empty** feed list must not overwrite its good backup | DS | none |
| J-10 | P1 | Save while offline ⇒ config saved, widget re-renders, refresh fails gracefully | DS, SS | none |
| J-11 | P1 | Upgrade path: install the previous release, configure it, `install -r` the new build ⇒ config with older/missing fields decodes with defaults, nothing resets | DS, UI | none |
| J-12 | P1 | Export OPML → Reset to defaults → Import the OPML ⇒ same feed set restored (colours regenerated) | UI | none |
| J-13 | P2 | Remove a widget from the home screen ⇒ record whether its `widget_<id>` config / `appWidget-<id>` state remains (known leak: no `onDeleted`); no crash; no effect on other widgets | DS | none |
| J-14 | P2 | `allowBackup="true"`: config restored via device backup/restore is not required, but a restore must not crash the app (widget ids change → `restoreIfEmpty` keyed by id finds nothing) | LC | none |

---

## K — Self-update & release notes

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| K-01 | P0 | Settings → APP UPDATE ⇒ "Currently on build N" equals the installed `versionCode` | UI, ADB (`dumpsys package`) | none |
| K-02 | P1 | **Check now** on the latest build ⇒ Toast "You're up to date (build N)"; nothing downloaded (`run-as … ls -la cache/updates/` shows no new file) | VIS, ADB | none |
| K-03 | P0 | **Check now** with a newer build available (test build with a lowered versionCode) ⇒ dialog "Update available": "Build X is ready to install." and the unseen notes as bullets; **Later** dismisses without downloading and stores the highest shown note id (`release_notes` DataStore); **Update Now** ⇒ "Downloading update…" Toast then Android's install screen | UI, DS, LC | UT:ReleaseNotesFetcherTest (parser) |
| K-04 | P1 | Check again after Later ⇒ same dialog with **no** "What's new" section (all notes seen) and both buttons still work; also with the notes fetch failing (offline GitHub raw) | UI, LC | UT:ReleaseNotesFetcherTest |
| K-05 | P1 | Dismiss the dialog by tapping outside/Back ⇒ treated as Later: seen id stored, nothing downloaded | DS | none |
| K-06 | P1 | Several unseen notes ⇒ all listed in order (oldest first), keyed by note id independent of versionCode | UI | UT:ReleaseNotesFetcherTest |
| K-07 | P1 | Offline **Check now** ⇒ Toast "Couldn't check for updates — try again later"; spinner returns to the button | VIS | none |
| K-08 | P1 | Double-tap **Check now** ⇒ button becomes a spinner; one check only; no two concurrent downloads | LC | none |
| K-09 | P1 | Android 13+: first **Check now** with notification permission not granted ⇒ system prompt; deny ⇒ manual check still works | VIS | none |
| K-10 | P1 | `appops set com.newsfeed.widget REQUEST_INSTALL_PACKAGES deny`, **Update Now** ⇒ Toast + this package's "install unknown apps" screen; after granting, **Update Now** proceeds straight through | VIS, LC | none |
| K-11 | P1 | Download failure (kill network during download) ⇒ Toast "Update download failed — try again later"; no partial install | VIS | none |
| K-12 | P0 | Complete an update over the previous build ⇒ install succeeds (same keystore), widgets still placed, feeds/config/read state intact, build number in Settings incremented | UI, DS, LC | none |
| K-13 | P1 | `NewsFeedUpdateCheck` scheduled (24 h). With a newer build and notification permission granted, a forced run posts the system notification "NewsFeed update available" — **no** dialog; without permission no notification | JS, VIS | none |
| K-14 | P1 | Tap the update notification ⇒ `UpdateRelayActivity` screen: spinner → "Build X is ready to install." + unseen notes + **Update Now**/**Later**; "up to date"/"couldn't check" states also render. (Previously not witnessed: hold a live notification long enough to tap it) | VIS, DS | none |
| K-15 | P1 | `adb shell am start -n com.newsfeed.widget/.update.UpdateRelayActivity` (and `.glance.ShareRelayActivity`) ⇒ `SecurityException` (not exported) | ADB | none |
| K-16 | P1 | After **Update Now** ⇒ `newsfeed_config_backup` written for all widgets **before** the installer opens | DS | none |
| K-17 | P1 | Release-notes hygiene: `docs/RELEASE_NOTES.md` has a new `## Note n` for every user-visible change in this release; each bullet is **one physical line**; appended, never renumbered; the file on `main` matches (`ReleaseNotesFetcher` reads the raw `main` file) | UT, CI | UT:ReleaseNotesFetcherTest |
| K-18 | P1 | Rolling release ⇒ `latest` release has `NewsFeed-latest.apk` and `version.json` whose `versionCode` equals the CI run number, greater than the installed build under test | CI | none |
| K-19 | P2 | README install steps still work (download link, unknown-sources flow, Auto Blocker note) on a spare device or in review | VIS | none |

---

## L — Robustness

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| L-01 | P0 | Device offline, widget on screen ⇒ cached articles render; footer failure banner after a refresh attempt; nothing crashes; back online ⇒ next refresh recovers | SS, LC | none |
| L-02 | P1 | Add Feed / Find Feeds / Edit URL / Check now while offline ⇒ each shows its own error, no hang | UI | none |
| L-03 | P1 | Bad feeds (404, 403, HTML instead of XML, malformed XML, empty, 10 MB, 30 s slow) ⇒ contained: other feeds still update, timeouts honoured | LC, UI | none |
| L-04 | P1 | 300 stored articles + 11 feeds + IMG mode on ⇒ renders within the row cap, no "Can't show content" | LC | none |
| L-05 | P1 | Kill the process (`kill -9`) and `am force-stop` with articles expanded/read ⇒ on the next tap/refresh state (expanded id, read flags, config) is intact; a one-off host placeholder ("No session available") recovers on a single tap | DS, LC | none |
| L-06 | P1 | Rotate the phone with the Settings screen open and unsaved edits ⇒ **record** whether edits survive (the edit state is not saved across recreation) and that nothing crashes | UI, LC | none |
| L-07 | P1 | Z Fold fold/unfold and rotation with the widget on screen ⇒ widget re-renders at the new size (Exact), no clipped footer/gear, no bitmap-memory crash | SS, LC | none |
| L-08 | P1 | Toggle system dark mode with widgets and Settings visible ⇒ Auto theme flips, other themes unchanged, Settings readable | SS | none |
| L-09 | P1 | Change system font size and display size ⇒ see D-19; widgets keep working | SS | none |
| L-10 | P1 | **System language Hebrew**: Settings mirrors RTL and stays readable; feeds explicitly set LTR/RTL — **record** whether the stripe/meta row mirror with the locale (BUG-002 open, structural Glance limit); timestamps, badge and (until merged) N/M read correctly | SS, UI | none |
| L-11 | P1 | Locale switch English↔Hebrew while widgets are placed ⇒ no crash; widget re-renders | LC | none |
| L-12 | P0 | Logcat over the whole run ⇒ **zero** `FATAL EXCEPTION`/`AndroidRuntime` for `com.newsfeed.widget`, no `exceeds maximum bitmap memory`, no `Can't show content` | LC | none |
| L-13 | P1 | Bug reports: empty state text "No crashes detected on this device."; `adb shell am crash com.newsfeed.widget` ⇒ still crashes for real; relaunch ⇒ entry (type, message, count, build, time) marked **Unsolved**; **Share crash report** ⇒ chooser with a `crash_report.txt` file (no inline text); no network activity | UI, VIS, LC | UT:CrashLogStoreTest |
| L-14 | P1 | After updating to a newer build without re-crashing ⇒ that entry shows **Solved** | UI | UT:CrashLogStoreTest |
| L-15 | P2 | Corrupt/empty live config DataStore ⇒ no crash loop; backup restore path works | LC, DS | none |
| L-16 | P1 | Time zone / clock change ⇒ article times re-render in local time; countdown sane | UI | none |
| L-17 | P1 | Remove a widget while a dissolve is running ⇒ no crash (detached coroutine is guarded) | LC | none |
| L-18 | P2 | Tap storm on −/+, rows, footer ⇒ no crash, consistent state | LC | none |
| L-19 | P2 | Battery saver / data saver / restricted background ⇒ refresh may fail with the banner; recovers when unrestricted | LC | none |
| L-20 | P2 | Missing/corrupt thumbnail or favicon file ⇒ row still renders (initial-letter circle, no image) | SS, LC | none |

---

## M — Regression cases for past bugs

One row per past bug. `↔` = same execution as the referenced case, recorded under both IDs. Bug numbers refer to
`docs/BUGS.md`; unnumbered items come from `DEBUG_PLAN.md` release log, commit history and session history.
Ledger: [Appendix A](#appendix-a--cases-added-because-of-a-bug).

| ID | P | Bug / symptom that must not return ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| M-01 | P0 | **Read flag on press** (883e2ed): pressing an article marked it read immediately, so under Unread only it vanished while being read ⇒ press marks nothing; only tapping another article marks it ↔ F-01, F-02 | DS | UT:ReadOnMoveAwayTest |
| M-02 | P0 | **Description-less articles marked on press** (de130cb): rotter/ynet flash items were marked at press ⇒ marked only when a different article is tapped ↔ F-04 | DS | UT:ReadOnMoveAwayTest |
| M-03 | P1 | **Update dialog without notes**: dialog must still render and work when there are no unseen notes or the notes fetch fails ⇒ "Build X is ready to install." + buttons, no crash ↔ K-04 | UI, LC | UT:ReleaseNotesFetcherTest |
| M-04 | P1 | **Dissolve skipped a stage** on a slow row (6adfd3b): delays counted from the wrong instant ⇒ heavy row still shows all three dissolve stages; delays counted from `readAt` ↔ F-14 | TS | UT:ArticleDissolveTest |
| M-05 | P1 | **Dissolve stuck after process kill** (bdf1d8a) ⇒ killed at +1.7 s/+3.4 s/+60 s, next re-render clears the dotted row; no update storm ↔ F-15 | TS, LC | UT:GraceRefreshRegistryTest |
| M-06 | P1 | **Orphan cache after feed removal** (DEBUG_PLAN "BUG-003"): ~48 articles of a removed feed linger in `articles_json` after Save ⇒ record the actual count; they must not render or count in the badge; store cleans up by the next refresh or is documented as known | DS, UI | none |
| M-07 | P1 | **Accent colour reuse / default accent** (BUG-016, 8474b57): manual add, OPML import, search add and Add default feeds must each assign a rotating palette colour, and Add default feeds prefers colours no existing feed uses ⇒ none is left at `#9B72E3` by default | DS | none |
| M-08 | P1 | **Blank widget after reinstall "No session available"**: after `install -r` or `force-stop` the host may show a placeholder ⇒ one tap must recover the widget; if it stays blank after a tap, or after 60 s, S2 ↔ A-12 | UI, LC | none |
| M-09 | P0 | **Glance "Can't show content" / bitmap memory ceiling**: Glamour × font 3.0 × article font 3.0 × Full article with chunks × 300 stored (× **[until merged]** focus 2.5) ⇒ always renders ≥ 1 row, row cap message accurate, logcat has no `exceeds maximum bitmap memory` / `Column container cannot have more than 10 elements` ↔ D-06, E-24, E-06 | LC, SS | none |
| M-10 | P0 | **Unread grace vanished early** (3325f4c, f48ea17): refresh wiped `readAt`, and the expiry re-render did nothing until a later tick ⇒ `readAt` survives refresh and the article is gone ≈ 5–8 s after the mark, not 70+ s ↔ F-12, F-13 | DS, TS | UT:ArticleMergeTest |
| M-11 | P0 | **Share button footer overflow** (23a52e4): minimum width widget + `⚠ refresh failed — tap to retry` ⇒ the countdown truncates with an ellipsis while **Share** and **⚙** stay fully visible **and tappable** (open Settings) | UI (gear present), SS | none |
| M-12 | P1 | **CI publish failures** (f18617d setup-android `sdkmanager tools`, b0cff3a rename step, 1da7246 compile, caaf549 overlapping runs) ⇒ after the push: `Build APK` run green; `latest` release updated with `NewsFeed-latest.apk` + `version.json` (`versionCode` = run number); no overlapping runs | CI | none |
| M-13 | P1 | **Refresh tap target** (BUG-006): only the exact glyph area worked ⇒ bounds ≈ 343×53 px; tap in the padding refreshes, tap outside does not | UI, LC | none |
| M-14 | P1 | **Rapid refresh taps queue duplicates** (BUG-011) ⇒ 5 taps in < 1 s = 1 worker run ↔ I-02 | LC | none |
| M-15 | P1 | **Drag reorder off-by-one** (BUG-019) ⇒ touched row moves ↔ B-12 | UI | none |
| M-16 | P1 | **Pasting canonical `t.me/s/<ch>` failed** (BUG-018) ⇒ adds as Telegram; already-added canonical URL says "already added" ↔ C-04, B-05 | UI | UT:TelegramFeedParserTest |
| M-17 | P1 | **Duplicate feed crashes Settings list** (duplicate feedId vs LazyColumn key) ⇒ Add Feed, OPML import and search-add all refuse duplicates, no crash ↔ B-05 | LC | UT:DefaultFeedsMergeTest |
| M-18 | P1 | **Sort/Show had no effect** (BUG-001) ⇒ applied to already-stored articles at render time ↔ G-07 | UI | UT:ArticleSortingTest |
| M-19 | P1 | **Per-feed direction flipped by system locale** (BUG-002, open/structural) ⇒ run under Hebrew system locale; record whether stripe/meta mirror; unchanged from baseline is acceptable ↔ L-10 | SS, UI | none |
| M-20 | P1 | **[until merged]** Focus zoom scaled only the headline (BUG-017) ⇒ body text scales in lockstep with the headline (≈ 2.0× between focus 1.25 and 2.5) ↔ E-21 | SS (line-height measurement) | none |
| M-21 | P1 | **[until merged]** Focus highlight stuck on the previous row ⇒ see E-25 | SS, UI | none |
| M-22 | P1 | **Raw HTML entity text in titles** (BUG-013, partial) ⇒ typical rotter titles decode; record residual literal ones ↔ C-09 | SS | none |
| M-23 | P1 | **Low-frequency feeds starved / unreachable** (BUG-008, BUG-015) ⇒ per-feed floor of 10 in the store and By-feed default lets a quiet feed show in the first round ↔ C-16, G-10 | DS, UI | none |
| M-24 | P1 | **Custom theme left stock purple in five colour slots** ⇒ gear, footer text, badge, dividers, row dots use derived colours ↔ H-15 | SS | UT:WidgetThemesTest |
| M-25 | P1 | **Share-sheet chooser title regression** ("Share" instead of "Share article") ⇒ per-article share titled "Share article"; footer options titled "Share the app"/"Share the download link" ↔ E-08, E-09 | VIS | none |
| M-26 | P1 | **`Theme.NoDisplay` crashes** in relay activities (69ff68c, footer Share) ⇒ footer Share dialog stays open and never crashes; `UpdateRelayActivity` renders (K-14) ↔ E-09 | LC | none |
| M-27 | P1 | **Refresh interval silently reset when a second widget type was first enabled** (dee3a01) ⇒ see I-05 | JS | none |
| M-28 | P1 | **Debug overlay left in production** (`DBG cfg=…` label, c230520) and **stale-build lesson** ⇒ no `DBG` text on any row; installed `versionCode` equals the build under test (`K-01`) | SS, ADB | none |
| M-29 | P1 | **× opened Edit instead of removing** (BUG-009, status open in `BUGS.md`) ⇒ × removes; record the current status ↔ B-07 | UI | none |
| M-30 | P2 | **Focus last visible row clipped mid-glyph** (BUG-012, open) ⇒ record whether the last row's final line is sliced by the footer (Focus and standard) | SS | none |
| M-31 | P1 | **Full-article Load more stalled short of the end / only one "Open in browser" link** (chunk ellipsis, missing `key()`) ⇒ every revealed chunk shows all its text and its own link ↔ E-06 | SS | none |
| M-32 | P1 | **Thumbnail bitmap-budget crash** (d0de925) ⇒ IMG mode with many thumbnails and large fonts renders | LC | none |
| M-33 | P1 | **Refresh failed for every feed at once** (BUG-014, environmental) ⇒ see I-08; recovers on the next attempt, banner truthful | LC | none |
| M-34 | P1 | **Black & White not fully monochrome / "white text not pure white"** (2026-09-07) ⇒ text pixel-checks to (255,255,255) in Dark; known colour gaps unchanged ↔ H-08 | SS | none |
| M-35 | P1 | **Config required before placement** (0e8cea6) ⇒ widget is not placed without passing Settings ↔ A-08 | VIS | none |
| M-36 | P1 | **Cleartext (HTTP) feeds silently yield nothing** (BUG-010, open) ⇒ record status ↔ B-06 | UI | none |
| M-37 | P1 | **Time reported wrong / new articles "not on time"** (BUG-004/005/007, not reproduced) ⇒ one article's time matches the feed's `pubDate`; a manual refresh inserts new articles within seconds ↔ C-01 | DS | none |
| M-38 | P0 | **Unit tests not run by CI**: `testDebugUnitTest` passes locally for the build under test (all `app/src/test` classes) | UT | all test files |
| M-39 | P1 | **Telegram "Load full article" replaced the post with page chrome** (BUG-020): a `t.me/<ch>/<id>` page is a JS shell ⇒ Telegram posts never offer or run a page fetch; Full mode shows the whole post text (up to 4096 chars, one 1200-char chunk in Glamour); a fetch on any feed never replaces the shown text with blank/error/much-shorter text ↔ E-04, C-05 | SS, UI | UT:TelegramFeedParserTest, FullArticleTextTest |

---

## N — Applies once Focus-as-setting ships

**Status: not built.** Spec `docs/superpowers/specs/2026-09-20-focus-as-setting-design.md` (approved): one "NewsFeed"
widget; per-widget setting **"When I tap an article: Expand in place / Focus (enlarge)"** (default Expand) in the
SORT & FILTER section directly after "Open article in"; only the focused article enlarges, other rows keep their
normal size; no Background-rows-size slider; `NewsFeedFocusWidgetReceiver` deleted, so already placed Focus widgets
disappear on update. When it ships: run this section in full, **remove** the **[until merged]** cases (`E-20…E-27`,
`F-08`, the Focus-only halves of `G-M01…G-M12`, `I-06`, `J-02` slider clause, `M-20`, `M-21`) and re-baseline the
smoke list. Field: `WidgetConfig.tapMode` (`"expand"` | `"focus"`); deprecated `focusBackgroundScale` is kept and ignored.

| ID | P | Do ⇒ Expect | Evid | Auto |
|---|---|---|---|---|
| N-01 | P0 | Add-widget picker ⇒ **one** entry "NewsFeed" (no "NewsFeed Focus"; `appwidget_info_focus.xml` and the Focus receiver gone) | VIS, ADB (`dumpsys appwidget`) | none |
| N-02 | P0 | New widget's Settings ⇒ row **When I tap an article** after "Open article in" with "Expand in place ▾" (default) / "Focus (enlarge) ▾"; picking Focus shows the hint "Tap enlarges the article; use − / + in the widget header to resize it."; no Focus-only slider in DISPLAY in either mode | UI, DS (`tapMode`) | UT (planned): TapMode.fromKey |
| N-03 | P0 | Upgrade path: a config saved by an older build (no `tapMode`, possibly `focusBackgroundScale`) ⇒ loads as **expand**, widget keeps rendering (not blank), all other settings intact | DS, UI | UT (planned): WidgetConfig decode |
| N-04 | P0 | tapMode = Focus, tap article A ⇒ A enlarges (1.25×) with tint and auto-expands; **every other row is exactly the same size as in Expand mode** (compare `uiautomator` bounds / screenshots), also when the saved config carries `focusBackgroundScale` 0.25 | UI (bounds), SS | UT (planned): rowFontScale |
| N-05 | P0 | Tap routing — Expand + description ⇒ toggles expand; read rule: previously tapped article marked at the next different tap | DS | UT (planned): TapRoutingTest |
| N-06 | P0 | Tap routing — Expand + **no** description ⇒ ripple only; previous marked at the next different tap | DS | UT (planned): TapRoutingTest |
| N-07 | P0 | Tap routing — Focus + description ⇒ focus set, auto-expanded; article **losing focus** marked when focus moves; clearing focus marks nothing | DS | UT (planned): TapRoutingTest |
| N-08 | P1 | Tap routing — Focus + no description ⇒ still focuses (wins regardless of description) | DS, UI | UT (planned): TapRoutingTest |
| N-09 | P0 | Switch mode on a **live** widget Expand→Focus and Focus→Expand (reconfigure, Save) ⇒ `expanded_article_id`, `focused_article_id`, `last_tapped_article_id`, `focus_scale` all cleared; `isRead` flags **unchanged**; the pending article is dropped, not marked | DS | UT (planned): resetTapState |
| N-10 | P1 | Save Settings **without** changing the mode while an article is expanded/focused ⇒ tap state is kept (reset only on an actual mode change) | DS | none |
| N-11 | P1 | Focus header ⇒ **N/M** correct and updates on tap; **− / +** step 0.15 within 0.75×–2.5×, reset when focus moves; no ▲ ▼ ✕ | UI, DS | none |
| N-12 | P1 | Stale `focused_article_id` while tapMode = expand ⇒ no row enlarges (defense in depth) | DS, SS | none |
| N-13 | P1 | Old saved config JSON containing `focusBackgroundScale` (e.g. 0.25) in the Glance state ⇒ decodes without blanking the widget and renders the same as one without the key | DS, SS | UT (planned) |
| N-14 | P0 | Update over a build with a **placed Focus widget** ⇒ that widget disappears, no crash, logcat clean, standard widgets untouched; after one refresh the orphan `appWidget-<id>` files and any `CLOCK_TICK_FOCUS` alarm are gone | UI, DS, JS, LC | UT (planned): orphanedIds |
| N-15 | P1 | Update when **only** Focus widgets existed ⇒ after the first refresh `NewsFeedRefresh` and `NewsFeedUpdateCheck` are cancelled; adding a widget re-arms them | JS | none |
| N-16 | P1 | Remove a widget ⇒ its `widget_<id>` entries in `newsfeed_config` and `newsfeed_config_backup` and its Glance state file are deleted (`onDeleted`); closes the leak recorded in J-13 | DS | none |
| N-17 | P1 | Reboot ⇒ jobs and the single clock alarm resume (one receiver) | JS | none |
| N-18 | P0 | Memory: tapMode = Focus, Glamour, Font 3.0, focus scale 2.5 ⇒ renders, no "Can't show content"; row cap identical to Expand mode | SS, LC | none |
| N-19 | P1 | Hebrew system locale ⇒ the new Settings row mirrors like its siblings; hint text readable | SS | none |
| N-20 | P1 | Two widgets, one Expand and one Focus ⇒ independent tap state and read tracking | DS | none |
| N-21 | P1 | Matrix `G-M01…G-M12` executed with **tapMode = Focus** (replaces the Focus-widget halves) | DS, TS | none |
| N-22 | P1 | **Pairwise PW-B** (50 configurations, includes the Tap factor) | SS, LC | none |
| N-23 | P1 | `docs/RELEASE_NOTES.md` has the new note (Focus is now a setting; existing Focus widgets are removed — add NewsFeed again and choose Focus) and shows in the update dialog | UI | UT:ReleaseNotesFetcherTest |
| N-24 | P1 | Docs in sync: README (one widget, Focus section reworded, settings row), PRD, DEBUG_PLAN §7/§8, this plan's **[until merged]** cases removed | VIS | none |
| N-25 | P1 | New unit tests exist and pass: TapRoutingTest (6 cells), `TapMode.fromKey`, `WidgetConfig` decode (missing key, unknown key), `resetTapState`, `orphanedIds`, `rowFontScale` | UT | planned |

---

## Appendix A — Cases added because of a bug

Ledger of bug → case. New lines are added at the bottom under the [rule](#bug-to-test-rule--how-to-add-a-case). "Status" is the
status recorded in `docs/BUGS.md` / `DEBUG_PLAN.md` when this plan was written (2026-09-20).

| Bug / origin | Symptom (short) | Root cause / fix | Cases | Status |
|---|---|---|---|---|
| Read flag on press (883e2ed) | Article read the instant it was pressed | Mark-read moved from press to move-away | M-01, F-01–F-03 | Fixed |
| Description-less on press (de130cb) | Flash items marked on press | `NoOpTapFeedbackCallback` joined the move-away tracking | M-02, F-04, F-05 | Fixed |
| Update dialog without notes | Dialog must work with no unseen notes / fetch failure | `ReleaseNotesContent` hides the notes section when empty | M-03, K-04 | Fixed |
| Dissolve timing / skipped stage (6adfd3b) | Slow row skipped stages | Delays counted from `readAt` | M-04, F-14 | Fixed |
| Dissolve stuck after kill (bdf1d8a) | Row left dotted after process death | Render-time resume of the schedule | M-05, F-15 | Fixed (force-stop quirk noted) |
| Orphan cache after feed removal (DEBUG_PLAN "BUG-003") | Removed feed's articles linger in the store | Not cleaned | M-06, B-07 | Open, cosmetic |
| Accent colour reuse (BUG-016, 8474b57) | New feeds shared `#9B72E3` / reused colours | Palette rotation everywhere + free-colour preference | M-07, B-01, B-13, A-05 | Fixed |
| "No session available" after reinstall | Blank host placeholder after `install -r` | Glance/host quirk; one tap recovers | M-08, A-12 | Known |
| Glance "Can't show content" / bitmap ceiling | RemoteViews bitmap budget exceeded | Row budget, ALPHA_8 bitmaps, 350 dp cap, min 1 row | M-09, D-06, E-24 | Fixed, regression-watched |
| Unread grace vanished early (3325f4c, f48ea17) | `readAt` wiped by refresh; expiry not re-rendered | `mergeFreshArticles`, `graceCheckTick` | M-10, F-12, F-13 | Fixed |
| Share button footer overflow (23a52e4) | ⚙ pushed off at narrow width | Countdown text made the flexible element | M-11 | Fixed |
| CI publish failures | Builds/publishing failed (setup-android, rename step, compile, overlapping runs) | Workflow fixes | M-12, K-18 | Fixed |
| BUG-001 | Sort/Show no effect | `applyFilterAndSort` at render time | M-18, G-07 | Fixed |
| BUG-002 | Per-feed direction flipped by system locale | Glance mirrors whole row by ambient locale; no API | M-19, L-10 | Open (structural) |
| BUG-003 (BUGS.md) | Settings labels not Hebrew-localised | Product decision pending | L-10 (observe only) | Deferred |
| BUG-004/005/007 | Time mismatch / new articles late | Not reproduced; tied to BUG-006 | M-37, C-01 | Closed pending report |
| BUG-006 | Refresh tap target too small | Padding added | M-13, D-03 | Fixed |
| BUG-008/015 | Low-frequency feeds crowded out | Per-feed floor 10, By-feed default, validated search | M-23, C-16, G-10, B-17 | Fixed |
| BUG-009 | × opens Edit | Suspected hit-target overlap | M-29, B-07 | Open (per BUGS.md) |
| BUG-010 | HTTP feeds never fetch | Cleartext blocked | M-36, B-06 | Open |
| BUG-011 | Rapid refresh taps stacked work | Unique work KEEP | M-14, I-02 | Fixed |
| BUG-012 | Focus last row clipped | Glance list height fit | M-30 | Open |
| BUG-013 | Raw HTML entities in titles | `Html.fromHtml` on titles; residual cases | M-22, C-09 | Partial |
| BUG-014 | Refresh failed for every feed at once | Device-wide network flap | M-33, I-08, C-15 | Environmental |
| BUG-016 | accentColor inconsistency | see above | M-07 | Fixed |
| BUG-017 | Focus zoom only headline | Body text shadowed with focus scale | M-20, E-21 | Fixed |
| BUG-018 | Canonical `t.me/s/…` add failed | `isTelegramFeed` check | M-16, C-04 | Fixed (http/`telegram.me/s` gap) |
| BUG-019 | Drag reorder off by one | Offset 3→4 | M-15, B-12 | Fixed |
| Duplicate feedId crash | Duplicate feed crashed Settings | Duplicate guard | M-17, B-05 | Fixed |
| Custom theme stock purple | 5 slots not derived | `customColorScheme` full derivation | M-24, H-15 | Fixed |
| Share chooser title regression | Wrong title | `chooserTitle` param | M-25, E-08, E-09 | Fixed |
| NoDisplay relay crashes (69ff68c) | Crash before `onResume` finished | Translucent theme / real screen | M-26, K-14 | Fixed |
| Interval reset by second widget type (dee3a01) | Interval silently reset | `ensureScheduled` uses KEEP | M-27, I-05 | Fixed |
| Debug label / stale build (c230520) | `DBG` text visible; old build tested | Removed; verify installed build | M-28, K-01 | Fixed |
| Full-article chunking / >10 children | Load more stalled; Column limit crash; single link | Chunk budget, per-chunk `Column`, `key()` | M-31, M-09, E-06 | Fixed |
| Thumbnail budget crash (d0de925) | Bitmap budget on thumbnails | Smaller thumbnails | M-32 | Fixed |
| BW not monochrome / white text | Favicons + banner keep colour | Known gap | M-34, H-08 | Known |
| Config required before placement (0e8cea6) | Widget placed without config | `configure` activity | M-35, A-08 | Fixed |
| Unit tests not gated by CI | CI only runs `assembleDebug` | Manual `testDebugUnitTest` | M-38 | Process gap |
| BUG-020 | Telegram posts did not load fully; "Load full article" replaced the text with page chrome | Post page is a JS shell; description now holds the whole post, no fetch for Telegram, fetch result guarded by `chooseFullArticleText` | M-39, E-04, C-05 | Fixed |

---

## Appendix B — Pairwise configuration tables

**How to apply a row:** set the values on the widget under test (`Font`, `Style`, `Dir`, `Mode` on **all** feeds,
so a mixed RTL/LTR widget is covered by `H-19` instead); `FontSz` = Font size slider, `ArtSz` = Article font
size slider, `Opacity` = Background opacity, `OpenIn` = Open article in, `Length` = Expanded article, `Accent` =
Use theme accent colors (`theme` = on). `Style` `-` = none, `BIU` = Bold + Italic + Underline. `Tap` (PW-B) =
When I tap an article, **only after** Focus-as-setting ships. For each row: Save; expand an article with a
description, an article without one, and one with a thumbnail; check headline, meta row, expanded text, footer, no
overlap/clipping, the row's expected effect of each option (see H-01…H-23), logcat clean. **Custom RGB** sets:
`CC1` font `#1B1F27` on background `#FFFFFF` (default), `CC2` font `#FFE5B4` on `#101820`, `CC3` font `#808080`
on `#909090` (near-equal edge). Expected "no visible effect" pairs (Glamour × Font/Style, Accent = theme × swatches)
are still executed: the check is that nothing breaks and the setting has no effect.

Generator: deterministic greedy all-pairs (seed sweep, keep the smallest of 6 restarts), verified by counting every
value pair of every factor pair; sorted by theme so the order is stable. To regenerate after adding a factor or
value, recompute with any all-pairs tool (e.g. `allpairspy`) and replace the table — the coverage claim
(`pairs covered = pairs possible`) must be re-verified and the new row count recorded here.

### PW-A — current build (12 factors, 50 configurations, 705/705 value pairs covered)

| Cfg | Theme | Variant | Accent | Font | Style | Dir | Mode | Length | FontSz | ArtSz | Opacity | OpenIn | Custom RGB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | auto | light | theme | serif | I | RTL | IMG | short | 1.0 | 3.0 | 100% | browser | - |
| A02 | auto | dark | theme | default | B | LTR | TXT | full | 1.0 | 1.0 | 50% | share | - |
| A03 | auto | dark | theme | serif | BIU | LTR | TXT | medium | 0.5 | 1.0 | 0% | browser | - |
| A04 | auto | dark | per-feed | default | - | RTL | TXT | full | 0.5 | 0.5 | 50% | browser | - |
| A05 | auto | dark | per-feed | mono | U | LTR | TXT | short | 3.0 | 1.0 | 0% | share | - |
| A06 | lavender | light | theme | serif | U | LTR | IMG | medium | 3.0 | 0.5 | 50% | browser | - |
| A07 | lavender | light | theme | mono | B | RTL | TXT | full | 1.0 | 1.0 | 0% | browser | - |
| A08 | lavender | light | per-feed | serif | - | RTL | TXT | short | 3.0 | 3.0 | 50% | share | - |
| A09 | lavender | dark | theme | mono | I | RTL | TXT | medium | 1.0 | 0.5 | 100% | share | - |
| A10 | lavender | dark | per-feed | default | BIU | LTR | IMG | short | 0.5 | 3.0 | 0% | share | - |
| A11 | amethyst | light | per-feed | default | BIU | RTL | IMG | full | 3.0 | 3.0 | 100% | share | - |
| A12 | amethyst | light | per-feed | serif | B | RTL | TXT | short | 0.5 | 1.0 | 0% | browser | - |
| A13 | amethyst | light | per-feed | mono | I | RTL | TXT | full | 1.0 | 3.0 | 50% | browser | - |
| A14 | amethyst | dark | theme | serif | - | RTL | IMG | short | 0.5 | 3.0 | 50% | browser | - |
| A15 | amethyst | dark | theme | mono | U | LTR | TXT | medium | 1.0 | 0.5 | 50% | browser | - |
| A16 | glassy | light | theme | default | B | RTL | TXT | short | 0.5 | 1.0 | 100% | share | - |
| A17 | glassy | light | per-feed | serif | U | RTL | TXT | full | 3.0 | 0.5 | 100% | share | - |
| A18 | glassy | dark | theme | default | - | RTL | IMG | short | 1.0 | 3.0 | 50% | share | - |
| A19 | glassy | dark | theme | serif | BIU | LTR | TXT | short | 0.5 | 3.0 | 50% | browser | - |
| A20 | glassy | dark | theme | mono | I | LTR | IMG | medium | 0.5 | 1.0 | 0% | browser | - |
| A21 | simple | light | theme | serif | - | RTL | TXT | full | 0.5 | 3.0 | 0% | share | - |
| A22 | simple | light | theme | serif | BIU | RTL | TXT | medium | 1.0 | 1.0 | 50% | browser | - |
| A23 | simple | light | theme | mono | I | LTR | TXT | full | 3.0 | 3.0 | 0% | share | - |
| A24 | simple | dark | per-feed | default | B | LTR | IMG | short | 0.5 | 0.5 | 100% | share | - |
| A25 | simple | dark | per-feed | mono | U | RTL | TXT | medium | 1.0 | 3.0 | 0% | browser | - |
| A26 | aerospace | light | theme | default | U | RTL | TXT | medium | 0.5 | 3.0 | 100% | browser | - |
| A27 | aerospace | light | theme | mono | I | LTR | TXT | medium | 0.5 | 0.5 | 50% | share | - |
| A28 | aerospace | dark | per-feed | default | - | LTR | IMG | short | 3.0 | 1.0 | 100% | browser | - |
| A29 | aerospace | dark | per-feed | serif | B | LTR | IMG | short | 1.0 | 0.5 | 0% | share | - |
| A30 | aerospace | dark | per-feed | mono | BIU | LTR | IMG | full | 3.0 | 1.0 | 50% | share | - |
| A31 | silicon | light | theme | serif | BIU | LTR | IMG | short | 3.0 | 0.5 | 50% | browser | - |
| A32 | silicon | light | theme | mono | I | LTR | TXT | short | 1.0 | 0.5 | 100% | share | - |
| A33 | silicon | light | theme | mono | U | RTL | IMG | medium | 3.0 | 0.5 | 50% | browser | - |
| A34 | silicon | light | per-feed | mono | B | RTL | TXT | medium | 1.0 | 1.0 | 100% | share | - |
| A35 | silicon | dark | per-feed | default | - | RTL | TXT | full | 0.5 | 3.0 | 0% | share | - |
| A36 | glamer | light | theme | default | I | RTL | IMG | full | 0.5 | 1.0 | 100% | browser | - |
| A37 | glamer | light | theme | serif | - | RTL | TXT | short | 1.0 | 0.5 | 0% | share | - |
| A38 | glamer | dark | theme | serif | BIU | LTR | TXT | short | 3.0 | 3.0 | 100% | browser | - |
| A39 | glamer | dark | per-feed | mono | B | LTR | IMG | medium | 3.0 | 3.0 | 50% | browser | - |
| A40 | glamer | dark | per-feed | mono | U | LTR | TXT | full | 1.0 | 3.0 | 50% | browser | - |
| A41 | blackwhite | light | theme | mono | - | LTR | IMG | full | 3.0 | 1.0 | 100% | browser | - |
| A42 | blackwhite | light | theme | mono | B | RTL | IMG | short | 3.0 | 3.0 | 50% | browser | - |
| A43 | blackwhite | light | per-feed | default | U | LTR | IMG | medium | 1.0 | 3.0 | 0% | share | - |
| A44 | blackwhite | dark | theme | default | BIU | LTR | TXT | short | 0.5 | 1.0 | 50% | share | - |
| A45 | blackwhite | dark | per-feed | serif | I | RTL | TXT | full | 0.5 | 0.5 | 50% | share | - |
| A46 | custom | light | theme | default | I | RTL | TXT | short | 3.0 | 1.0 | 50% | browser | CC1 |
| A47 | custom | light | theme | mono | B | LTR | TXT | short | 1.0 | 0.5 | 50% | browser | CC2 |
| A48 | custom | dark | theme | serif | U | RTL | IMG | full | 0.5 | 0.5 | 0% | browser | CC3 |
| A49 | custom | dark | per-feed | default | - | LTR | IMG | medium | 1.0 | 3.0 | 100% | share | CC1 |
| A50 | custom | dark | per-feed | mono | BIU | RTL | TXT | full | 1.0 | 3.0 | 100% | browser | CC2 |

### PW-B — once Focus-as-setting ships (13 factors, adds **Tap**, 50 configurations, 785/785 pairs covered)

| Cfg | Theme | Variant | Accent | Font | Style | Dir | Mode | Length | FontSz | ArtSz | Opacity | OpenIn | Tap | Custom RGB |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B01 | auto | light | theme | default | I | LTR | TXT | full | 3.0 | 1.0 | 0% | browser | expand | - |
| B02 | auto | light | per-feed | mono | U | RTL | IMG | short | 1.0 | 0.5 | 50% | share | focus | - |
| B03 | auto | dark | theme | serif | - | LTR | TXT | medium | 0.5 | 3.0 | 100% | browser | expand | - |
| B04 | auto | dark | theme | mono | BIU | LTR | IMG | full | 3.0 | 1.0 | 0% | browser | expand | - |
| B05 | auto | dark | per-feed | default | B | RTL | IMG | medium | 0.5 | 1.0 | 100% | share | focus | - |
| B06 | lavender | light | theme | serif | BIU | RTL | IMG | full | 3.0 | 0.5 | 0% | share | focus | - |
| B07 | lavender | light | per-feed | default | - | LTR | TXT | short | 0.5 | 0.5 | 50% | share | expand | - |
| B08 | lavender | dark | theme | default | U | LTR | TXT | medium | 0.5 | 3.0 | 100% | browser | focus | - |
| B09 | lavender | dark | per-feed | serif | B | LTR | TXT | medium | 3.0 | 0.5 | 50% | browser | expand | - |
| B10 | lavender | dark | per-feed | mono | I | LTR | TXT | short | 1.0 | 1.0 | 50% | browser | expand | - |
| B11 | amethyst | light | theme | serif | I | LTR | IMG | medium | 0.5 | 0.5 | 0% | share | expand | - |
| B12 | amethyst | light | theme | mono | BIU | RTL | IMG | full | 0.5 | 3.0 | 100% | browser | expand | - |
| B13 | amethyst | light | per-feed | serif | B | RTL | IMG | short | 1.0 | 3.0 | 50% | browser | expand | - |
| B14 | amethyst | dark | theme | default | U | LTR | TXT | full | 3.0 | 1.0 | 0% | share | focus | - |
| B15 | amethyst | dark | per-feed | mono | - | RTL | IMG | medium | 0.5 | 0.5 | 100% | browser | expand | - |
| B16 | glassy | light | per-feed | serif | U | RTL | TXT | medium | 0.5 | 1.0 | 50% | share | focus | - |
| B17 | glassy | dark | theme | default | I | RTL | IMG | medium | 1.0 | 0.5 | 0% | browser | expand | - |
| B18 | glassy | dark | per-feed | serif | B | RTL | TXT | medium | 1.0 | 0.5 | 100% | browser | focus | - |
| B19 | glassy | dark | per-feed | mono | - | RTL | TXT | full | 0.5 | 3.0 | 0% | browser | expand | - |
| B20 | glassy | dark | per-feed | mono | BIU | LTR | TXT | short | 3.0 | 3.0 | 100% | share | focus | - |
| B21 | simple | light | theme | serif | - | LTR | IMG | full | 1.0 | 1.0 | 50% | browser | expand | - |
| B22 | simple | light | theme | mono | B | RTL | TXT | short | 0.5 | 1.0 | 100% | share | focus | - |
| B23 | simple | light | per-feed | default | BIU | RTL | IMG | medium | 3.0 | 3.0 | 100% | browser | focus | - |
| B24 | simple | light | per-feed | mono | U | LTR | IMG | full | 0.5 | 3.0 | 50% | share | focus | - |
| B25 | simple | dark | theme | mono | I | LTR | TXT | short | 0.5 | 0.5 | 0% | share | expand | - |
| B26 | aerospace | light | per-feed | default | - | RTL | IMG | full | 3.0 | 1.0 | 50% | browser | focus | - |
| B27 | aerospace | light | per-feed | serif | B | LTR | IMG | medium | 3.0 | 1.0 | 0% | browser | expand | - |
| B28 | aerospace | light | per-feed | mono | U | RTL | TXT | medium | 1.0 | 3.0 | 50% | browser | expand | - |
| B29 | aerospace | dark | theme | default | BIU | LTR | TXT | short | 0.5 | 0.5 | 100% | share | expand | - |
| B30 | aerospace | dark | per-feed | serif | I | RTL | TXT | medium | 1.0 | 3.0 | 0% | share | expand | - |
| B31 | silicon | light | per-feed | mono | B | RTL | TXT | full | 3.0 | 1.0 | 0% | browser | expand | - |
| B32 | silicon | light | per-feed | mono | I | LTR | IMG | full | 1.0 | 3.0 | 100% | share | focus | - |
| B33 | silicon | dark | theme | default | BIU | RTL | TXT | medium | 0.5 | 0.5 | 50% | share | focus | - |
| B34 | silicon | dark | theme | serif | U | RTL | IMG | short | 3.0 | 3.0 | 0% | browser | expand | - |
| B35 | silicon | dark | theme | mono | - | RTL | TXT | short | 0.5 | 1.0 | 0% | browser | expand | - |
| B36 | glamer | light | theme | default | U | RTL | TXT | short | 0.5 | 3.0 | 100% | share | expand | - |
| B37 | glamer | light | theme | serif | BIU | RTL | IMG | full | 1.0 | 1.0 | 50% | share | expand | - |
| B38 | glamer | light | per-feed | mono | I | RTL | IMG | full | 3.0 | 0.5 | 100% | share | expand | - |
| B39 | glamer | dark | theme | mono | - | RTL | TXT | short | 3.0 | 3.0 | 50% | browser | focus | - |
| B40 | glamer | dark | per-feed | mono | B | LTR | TXT | medium | 3.0 | 0.5 | 0% | browser | focus | - |
| B41 | blackwhite | light | theme | serif | B | RTL | IMG | full | 0.5 | 0.5 | 0% | share | expand | - |
| B42 | blackwhite | light | theme | mono | - | LTR | IMG | medium | 1.0 | 1.0 | 100% | share | expand | - |
| B43 | blackwhite | dark | theme | default | U | LTR | IMG | full | 0.5 | 0.5 | 100% | share | expand | - |
| B44 | blackwhite | dark | per-feed | default | I | LTR | TXT | short | 3.0 | 3.0 | 50% | browser | focus | - |
| B45 | blackwhite | dark | per-feed | default | BIU | RTL | IMG | short | 1.0 | 0.5 | 50% | browser | expand | - |
| B46 | custom | light | theme | mono | - | RTL | IMG | short | 1.0 | 0.5 | 50% | share | focus | CC1 |
| B47 | custom | dark | theme | mono | I | RTL | TXT | medium | 3.0 | 1.0 | 50% | browser | expand | CC2 |
| B48 | custom | dark | per-feed | default | B | LTR | TXT | full | 0.5 | 1.0 | 100% | browser | expand | CC3 |
| B49 | custom | dark | per-feed | serif | BIU | RTL | TXT | medium | 3.0 | 3.0 | 0% | browser | focus | CC1 |
| B50 | custom | dark | per-feed | mono | U | RTL | TXT | full | 3.0 | 0.5 | 50% | browser | focus | CC2 |

---

## Appendix C — Reference test data

* **Default feed set (11)** — from `app/src/main/assets/default_feeds.opml`: ynet מבזקים, ynet חדשות, וואלה מבזקים,
  גלובס כל הכתבות, גלובס בארץ, N12 צ'אט הכתבים (`t.me/s/N12chat`), חדשות N12 (`t.me/s/N12_News`), כאן חדשות
  (Newsflash API), AI News (artificialintelligence-news.com — may return 403), AI Newsletter (substack),
  The Hindu AI News (**`http://`** — cleartext, see B-06).
* **Article shapes to have on the widget:** with description and image; with description and no image; **no
  description** (rotter.net, ynet flash); Hebrew RTL headline; mixed Hebrew/English; headline starting with a
  digit or quote; very long headline; Telegram post (two-line headline); Telegram photo-only.
* **Widgets:** at least one standard widget and (until merged) one Focus widget with distinct settings; a spare
  widget for placement/removal cases.
* **Extra feed URLs to keep handy:** a valid RSS, a valid Atom, an HTML page, a 404, a 403, a redirecting URL, an
  `http://` feed, `@telegram` / `t.me/cnnbrk` Telegram channels, an OPML file (flat) and one (grouped).

---

## Appendix D — Doc/code discrepancies found while building this plan

These are conflicts between documents, or between docs and code. The plan follows the code; each has a case that
records the actual behaviour. None has been "fixed" here (this file is docs-only).

1. **Default sort:** `README.md` (Sort options / Settings manual) says Newest first is the default; the code default is
   **By feed** (`WidgetConfig.sortOrder = "by_feed"`, BUG-015). → `G-06`, `A-02`.
2. **Bold toggle (B):** `FeedConfigRow` writes the key `"bold"`, but `FeedItemRow` only reads `"normal"` (to un-bold);
   headlines are bold by default, so the **B toggle appears to have no effect** while I and U do. README lists B/I/U as
   working. → `H-18`.
3. **Font size and header/footer:** README says Font size scales "headlines, meta text, and the header/footer"; header
   (13 sp) and footer (11 sp) use fixed sizes in code. → `D-09`.
4. **Auto theme × variant:** in the widget, `Auto` colours follow the **system** night mode, but the surface colour
   follows the **variant** setting, and the Settings preview follows the variant — a light variant on a dark-mode
   device can produce light text on a white card. → `H-10…H-13`.
5. **"Open article in" marks read:** `DEBUG_PLAN.md` §1 says both Open-in modes "mark the article read"; the code says the
   Open/Share buttons use `actionStartActivity` and mark nothing. → `F-07`.
6. **BUG numbering collision:** `DEBUG_PLAN.md` §14 calls the "removed feeds' articles linger" issue **BUG-003**, but
   `BUGS.md` BUG-003 is "Settings labels not Hebrew-localized"; that orphan-cache issue has no `BUGS.md` number. → `M-06`;
   assign a real number next time it is touched.
7. **PRD status:** `PRD.md` lists the Share button as "🚧 In progress" and describes Focus as a separate widget with
   "shrinking" rows; the Share button shipped (batch builds #127–#139 per `DEBUG_PLAN.md`, release Note 2), and the Focus wording changes with the spec.
8. **BUG-002:** `NewsFeedWidget.provideGlance` locks `LocalLayoutDirection` to LTR "to fix" the locale flip, while
   `BUGS.md` concludes the mirror is structural and unfixed; the README claims per-feed direction is locale-independent.
   The current real behaviour is unrecorded. → `L-10`, `M-19`.
9. **`FeedConfig.enabled`:** the flag exists and is honoured by the fetcher, but there is no UI to toggle it. Not
   testable; no case.
10. **CI does not run unit tests** (`assembleDebug` only) although the repo has 10 unit-test classes. → `M-38`.
11. **Untested logic (no unit test):** `retainWithPerFeedGuarantee` / retention cutoff (`C-16`, `C-17`), `ConfigBackup`
    (`J-08`), `OpmlManager` (needs the Android XML parser), footer countdown text, `formatDateTime`. Candidates for
    new tests.
12. **Settings screen rotation:** edit state lives in `remember`, not `rememberSaveable`, so unsaved edits are probably
    lost on rotation/recreation. → `L-06`.
13. **Default feed over cleartext:** the bundled The Hindu feed is `http://`; combined with BUG-010 it likely never
    yields articles. → `B-06`.
