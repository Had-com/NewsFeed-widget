# Fold NewsFeed Focus into the standard widget as a per-widget setting — design spec
**Date:** 2026-09-20
**Project:** NewsFeed widget (`com.newsfeed.widget`)
**Status:** Approved by the user (decisions a/b/c below); implementation not started
**Supersedes in part:** `2026-09-05-merge-focus-mode-design.md` (the "two widgets in one app" decision)

---

## Goal and non-goals

**Goal.** One widget in the "Add widget" picker ("NewsFeed"). Focus (tap-to-enlarge) becomes a per-placed-widget setting, "When I tap an article: Expand in place / Focus (enlarge)", default Expand. The In Focus mode **only the focused article enlarges; every other row renders at its normal size** (no shrinking), so the "Background rows size" setting is cancelled and there is no Focus-only control in settings.

**Non-goals.** No change to the rest of Focus rendering (focused-row scale, tint, auto-expand, header N/M and −/+ buttons); the only render change is that non-focused rows no longer shrink. No alias or migration for already-placed Focus widgets: `NewsFeedFocusWidgetReceiver` is deleted, so those widgets disappear from the home screen on update. The user accepted this and will re-add "NewsFeed" and choose Focus. No new Gradle flavors, no CI change.

## Config field

`WidgetConfig.tapMode: String = "expand"` (`"expand" | "focus"`), following the existing String-key convention (`sortOrder`, `filter`, `articleLength`). Add `enum class TapMode(val key: String) { EXPAND("expand"), FOCUS("focus") }` with `fromKey(key)` that maps anything unknown to EXPAND.

- **Old saved configs.** `WidgetConfigStore` and `ConfigBackup` decode with `ignoreUnknownKeys`, and kotlinx uses the constructor default for a missing key. Every existing config, including the copy in Glance state `WidgetStateKey.configJson` and the backup DataStore, therefore reads as `expand`. Every existing standard widget stays Expand with no migration code. Verify `WidgetContent`'s decode path uses the same defaults-on-missing behavior (it must, since `focusBackgroundScale` was added the same way).
- `focusBackgroundScale` is **kept in `WidgetConfig` as a deprecated, ignored field** (default 0.5, marked `@Deprecated`, comment "unused since 2026-09-20"). New code never reads or writes it. Reason it is not deleted: `WidgetContent` decodes `configJson` with the default `Json` (no `ignoreUnknownKeys`), and a user who moved the old slider has the key serialized in their saved JSON; removing the field would make that decode throw, `runCatching` would swallow it, and the widget would render as an empty default config. (`WidgetConfigStore` and `ConfigBackup` do ignore unknown keys, but the Glance-state path does not.) It can be deleted later, together with switching that decode to `ignoreUnknownKeys`.

## Render-time decision (config replaces widget class)

`isFocusWidget` becomes `config.tapMode == "focus"`, computed once in `WidgetContent` right after `config` is decoded (`isFocusWidget` stays as a local name so `FeedItemRow` and `WidgetHeader` need no signature change). Call sites:

| File | Change |
|---|---|
| `NewsFeedWidget.kt` | Remove `class NewsFeedFocusWidget` and `NewsFeedFocusWidgetReceiver`. `WidgetContent(isFocusWidget: Boolean)` loses its parameter and derives the flag from config. Drop `updateNewsFeedWidget()`'s provider lookup (it becomes `NewsFeedWidget().update`, or is deleted and callers use `NewsFeedWidget().update`). The grace-period lambda becomes `NewsFeedWidget().update(c, g)`. `worstCaseRowScale` (now `if (isFocusWidget) maxOf(focusScale, 1f) else 1f`, no `config.focusBackgroundScale`), the `focusBackgroundScale = config.focusBackgroundScale` argument at the `FeedItemRow` call (deleted), `focusedIndex`, `WidgetHeader(..., isFocusWidget)` and the `FeedItemRow(isFocusWidget = ...)` argument keep working off the derived flag. `NewsFeedWidgetReceiver.onDisabled` cancels shared jobs unconditionally (no other receiver to check). |
| `FeedItemRow.kt` | `toggleAction` calls the routing helper below. Delete the `focusBackgroundScale` parameter. The `fontSize` and `articleFontSize` shadows become `if (isFocusWidget && article.id == focusedArticleId) x * focusScale else x`, so non-focused rows keep the configured size. `baseFontSize` and `metaScaleFontSize` stay (the meta row is still capped at normal size on the enlarged row). Fix the comments that describe shrinking. |
| `SetFocusArticleCallback.kt`, `AdjustFocusScaleCallback.kt` | `NewsFeedFocusWidget().update` becomes `NewsFeedWidget().update`; scheduleRefresh lambda likewise. |
| `WidgetConfigActivity.kt` | Delete the receiver-class check (lines ~134-136) and the `NewsFeedFocusWidget().updateAll` call. Delete the Focus-only "Background rows size" slider block (lines ~684-701) and its comment. The Composable needs a Focus flag only for the one-line hint below (`config.tapMode == TapMode.FOCUS.key`). |
| `WidgetWorker.kt` | Remove `getGlanceIds(NewsFeedFocusWidget)` and the second `updateAll`. |
| `BootReceiver.kt` | Remove `focusIds` and the Focus clock tick. |
| `UpdateManager.kt` | Remove the Focus id concatenation before `ConfigBackup.backupAll`. |

## Tap-routing matrix

New pure helper `glance/TapRouting.kt`: `tapActionFor(mode: TapMode, hasDescription: Boolean): TapAction` where `TapAction` is `SET_FOCUS | TOGGLE_EXPAND | NO_OP`. `FeedItemRow` maps it to the three existing callbacks.

| Mode | Article has description | Callback | Read marking |
|---|---|---|---|
| Expand | yes | `ToggleExpandCallback` | `markPreviousTappedRead` (previous tapped article, on moving to a different one) |
| Expand | no | `NoOpTapFeedbackCallback` | `markPreviousTappedRead`, same rule |
| Focus | yes or no | `SetFocusArticleCallback` (wins regardless of description, as today; the focused row auto-expands) | article losing focus, when focus moves to another article |

The two read rules never run in the same mode, so there is no double-marking. Only the `focusedArticleId` rule applies in Focus and only `lastTappedArticleId` applies in Expand.

**Switching mode on a live widget.** Both directions go through one helper `resetTapState(prefs: MutablePreferences)` that removes `expandedArticleId`, `focusedArticleId`, `lastTappedArticleId` and `focusScale`. It is applied in the existing settings-save `updateAppWidgetState` block, only when the saved `tapMode` differs from the value that was loaded when the screen opened. Chosen semantics: **the pending article is dropped, not marked read on switch.** Read means "the user moved on to another article", and a mode switch is not that. Nothing is lost: the unread dot stays and the article is marked normally on a later move-away. As defense in depth, `focusedArticleId` is ignored at render when tapMode is expand (`FeedItemRow` already gates every use on `isFocusWidget`), so a stale key can never enlarge a row.

## Settings UI

In the **SORT & FILTER** section, directly after "Open article in", add a row using the same pattern as its neighbors (`Row(SpaceBetween)` with `Text("When I tap an article")` and a `TextButton` + `DropdownMenu` labelled "Expand in place ▾" / "Focus (enlarge) ▾"). This matches sibling rows, uses no hard-coded left/right, and mirrors correctly under an RTL locale like the rest of the screen. When Focus is selected, show a one-line `bodySmall` hint under the row: "Tap enlarges the article; use − / + in the widget header to resize it." There is no Focus-only slider: the DISPLAY section is identical in both modes. Reconfiguring (`widgetFeatures="reconfigurable"`) is how a user changes mode on a placed widget.

## Removal list

- `AndroidManifest.xml`: the `NewsFeedFocusWidgetReceiver` `<receiver>` block (action `CLOCK_TICK_FOCUS` goes with it).
- `res/xml/appwidget_info_focus.xml` (delete file); `strings.xml` `widget_label_focus`.
- Classes: `NewsFeedFocusWidget`, `NewsFeedFocusWidgetReceiver` (plus its `ACTION_CLOCK_TICK` / `RC_CLOCK = 1002` companion). Comments that reference "second widget" are updated.
- Picker: only "NewsFeed" remains.
- Release assets: see next section; nothing to remove in CI.

## Self-update and APK naming

Verified: `.github/workflows/build.yml` builds a single `assembleDebug` (no flavors; `build.gradle.kts` has one `applicationId`, `com.newsfeed.widget`) and publishes only `NewsFeed-latest.apk` and `version.json` to the `latest` release. `UpdateManager` hard-codes `RELEASE_BASE/version.json` and `RELEASE_BASE/NewsFeed-latest.apk`; there is no per-flavor asset selection. So the merged app needs **no CI or UpdateManager change**, and every installed `com.newsfeed.widget` build updates the same way. `NewsFeed-focusMode-latest.apk` and `NewsFeed-standard-latest.apk` on the release page are stale leftovers from the flavor era that nothing in CI or the app references. They are left as they are (deleting release assets is a manual GitHub action for the user, not part of this change). A user still on the old separate `com.newsfeed.widget.focus` package is a different app and cannot be upgraded into this one. That was already true after the 2026-09-05 merge and is unchanged here.

## Orphaned Focus widget state

Facts checked in code: no `onDeleted` override exists anywhere, and `WidgetConfigStore.delete()` is never called, so per-widget config already leaks for normally deleted widgets too. After the update the system drops Focus widget ids without calling anything, because their receiver no longer exists. Per-id leftovers: `widget_<id>` keys in `newsfeed_config` and `newsfeed_config_backup`, Glance state file `files/datastore/appWidget-<id>.preferences_pb`, and possibly a pending `CLOCK_TICK_FOCUS` alarm. Nothing crashes on them (BootReceiver/WidgetWorker/UpdateManager enumerate ids from `getGlanceIds(NewsFeedWidget)` only), but they leak, and if the user had only Focus widgets the periodic jobs would keep running with nothing to update.

Design:
1. `NewsFeedWidgetReceiver.onDeleted(context, ids)`: for each id, `WidgetConfigStore.delete(id)`, new `ConfigBackup.delete(id)`. Glance's own receiver already removes its `appWidget-<id>` state; this closes the pre-existing leak.
2. One-time-per-refresh sweep `OrphanCleanup.run(context)` at the start of `WidgetWorker.doWork` (it survives the update and runs even when only Focus widgets had been placed). It computes `live = AppWidgetManager.getAppWidgetIds(NewsFeedWidgetReceiver)`, then deletes config/backup keys and `appWidget-<id>.preferences_pb` files whose id is not in `live`, and cancels the stale `CLOCK_TICK_FOCUS` alarm (PendingIntent by component-name string, request code 1002). The id selection is a pure function `orphanedIds(known: Set<Int>, live: Set<Int>)`. The whole sweep is wrapped in `runCatching` so it can never fail the refresh. It only touches ids not registered to the live receiver, so it cannot remove a standard widget's data.
3. After the sweep, if `live` is empty, `WidgetWorker` calls `WidgetWorker.cancel` and `UpdateCheckWorker.cancel` and returns success, so nothing keeps polling dead ids. Adding a widget re-arms everything through `onEnabled`.

## Risks and defaults

- **Focus users lose their widget on update** (accepted). Their feeds are not lost from the app; a re-added widget starts with defaults, because config is per widget id. Called out in the release note.
- **Memory / row-count budget (reviewed).** The only code depending on background rows was `worstCaseRowScale = maxOf(focusScale, focusBackgroundScale, 1f)`. The slider range was 0.25–1.0, so `focusBackgroundScale` never exceeded 1 and the term was always dominated by `1f`: dropping it is behavior-neutral (same `maxRowsAllowed` for every input). Decision: **do not tighten further.** The budget uses a uniform worst case for every row; since only one row can now be at `focusScale`, it could be modelled as (n−1) rows at 1× plus one at `focusScale`, but that changes on-screen row counts the on-device "Can't show content" fix was tuned against, so it is left as a possible later simplification. Non-focused rows at normal size cost exactly what they cost in Expand mode. `HEADLINE_MAX_LINES` and the thumbnail term are unchanged.
- **Shared Glance state keys.** `focusedArticleId`, `focusScale`, `expandedArticleId`, `lastTappedArticleId` all live in one per-widget DataStore; the reset helper clears all four on a switch. Chosen default: reset also drops the custom focus scale.
- **Cleanup safety.** The sweep deletes only ids absent from the live receiver's id list, and is skipped if `getAppWidgetIds` throws.
- **Stale alarm.** A leftover `CLOCK_TICK_FOCUS` alarm would fire into a missing receiver, which is a silent no-op, and it is never re-armed. The sweep cancels it anyway.
- **Undecided items, defaults chosen:** the hint text under the dropdown is English-only like the rest of the screen; the mode setting is not exposed on-widget (only via reconfigure).

## Testing

Unit tests (JUnit, same style as `ReadOnMoveAwayTest`): `TapRoutingTest` (all 6 mode × description cells); `TapMode.fromKey` (known keys, blank, junk → EXPAND); `WidgetConfig` decode of a JSON string without `tapMode` and with an unknown extra key → EXPAND; `resetTapState` clears exactly the four keys and leaves articles/config untouched; `orphanedIds`; a `WidgetConfig` JSON that still contains `focusBackgroundScale` (e.g. 0.25) decodes with the default `Json` without error and renders the same as one without it. The row size math is extracted into a pure function `rowFontScale(isFocus, isFocused, anyFocused, focusScale)`, tested so non-focused rows return exactly 1.0 in every state.

On-device QA (extend `docs/DEBUG_PLAN.md`; the release gate applies, this is a user-facing feature, so no push until verified): fresh standard widget defaults to Expand; switch to Focus via reconfigure and back on a **live** widget with articles (both directions), checking DataStore via `run-as ... cat files/datastore/appWidget-<id>.preferences_pb` that the four keys are cleared and that `isRead` flags are unchanged by the switch; tap article A then B in each mode and confirm read is set only on A after moving to B (a description-less article included); with one article focused, every non-focused row is **unchanged in size** versus the same widget in Expand mode (compare screenshots or `uiautomator dump` bounds), including with an old saved config carrying `focusBackgroundScale` 0.25; Settings has no Background rows slider in either mode; the header controls that exist today still work in Focus mode: **N/M** (correct position, updates on tap) and **− / +** (0.75×–2.5×, reset when focus moves); the ▲/▼ and ✕ buttons were removed earlier and are not reinstated; config survives reconfigure and app restart; update over a build with a placed Focus widget: widget disappears, no crash, logcat clean, orphan `appWidget-<id>` files and `CLOCK_TICK_FOCUS` gone after one refresh, standard widgets untouched; with only Focus widgets before the update, `NewsFeedRefresh` is cancelled afterwards; reboot resumes jobs; Hebrew-locale check of the new settings row; memory: Focus mode at max focus scale with the largest font still renders (no "Can't show content").

## Docs

- `docs/RELEASE_NOTES.md`: append `## Note 8` with one single-line bullet: NewsFeed Focus is now a setting of the standard widget ("When I tap an article" in Widget settings); existing Focus widgets are removed by this update, so add the NewsFeed widget again and choose Focus.
- `README.md` (Focus): reword line 168 to "that row enlarges, every other row stays at its normal size" (drop the Background rows size clause); delete the settings-table row at line 250 ("Background rows size (slider)"); mention the "When I tap an article" row instead.
- `README.md`: intro "One app, two widgets" line 5 becomes one widget with two tap behaviors; line 58, 74, 164-174 (Focus Mode section reworded as a setting), 222, 250 (slider now "Focus mode only"), 350 (single picker entry), the file tree lines (NewsFeedWidget.kt no longer has "both" classes; drop `appwidget_info_focus.xml`; add `TapRouting.kt`). No screenshot in the README shows Focus, so no screenshot change.
- `docs/PRD.md`: lines 11 and 42 (section becomes "Focus Mode (tap-behavior setting)"); line 46 "while others shrink" becomes "while others stay the same size".
- `docs/DEBUG_PLAN.md`: line 37 (slider present/absent check) deleted; section 7 retitled to the setting with the switch-live checks above, line 108 "every other row shrinks" reworded to "stays normal size", and line 114 (Background rows size check) replaced by the non-focused-rows-unchanged check; section 8 rewritten (one receiver, one clock alarm, orphan sweep checks); new subsection in the release log; the "both widget packages/types" item in section 6 reduced to one widget.
- Add a one-line "superseded in part" pointer to the top of the 2026-09-05 spec.
