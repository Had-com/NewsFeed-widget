# Share button — design

## Context

Queued since 2026-09-10 ("Share button on the widget — send the widget's/article's link via
WhatsApp, Telegram, SMS, or the system share sheet"), never brainstormed until now. The app
already has a partial version of this: Settings' "Open article in" toggle (`browser` | `share`)
makes tapping an article's "Open article" link open the system share sheet instead of a
browser, via `glance/ShareRelayActivity.kt` — a tiny invisible relay Activity, needed because
Glance can't launch an ambiguous `ACTION_SEND` chooser directly from a widget's own
`PendingIntent` context (confirmed on-device: a plain `ACTION_VIEW` works from Glance, a
chooser-backed `ACTION_SEND` does not). `ShareRelayActivity` exists specifically to be a single,
unambiguous target Glance *can* launch; once running as a real foreground Activity, it builds
and launches the chooser normally.

What's missing, per the original request being framed as its own **button** rather than a
replacement for how tapping opens an article: a dedicated share action available *alongside*
browser-opening (not gated behind switching "Open article in" away from Browser), plus a way
to share the app itself, not just an individual article.

## Decisions made during brainstorming

- **Two independent additions, not one**: per-article share, and app-wide share. Confirmed via
  direct question — the user wants both.
- **Per-article share button placement: only in the expanded row, next to "Open article →",
  not on every collapsed row.** This app has hit real RemoteViews bitmap-memory limits before;
  adding a new element to every one of up to 300 rendered rows (vs. only the one currently
  expanded) was explicitly rejected as unnecessary footprint for a single article action.
- **Per-article share button only shown when `externalApp == "browser"`.** When "Open article
  in" is already set to "Share", "Open article →" already shares the article — a second,
  identical "Share ↗" button next to it would be pure redundant clutter, not a new capability.
- **App-wide share lives on the widget itself (footer), not tucked into Settings.** Explicit
  user correction after an initial Settings-based proposal — "on the widget screen." The
  footer (refresh countdown + ⚙ gear) has room; the header (title, unread badge, and for Focus
  widgets, the N/M indicator + scale buttons) does not.
- **App-wide share offers a choice, resolved via a small on-Activity dialog, not two separate
  buttons.** Explicit request: tapping the one footer button should "offer both options" (share
  the app itself vs. share a direct download link) rather than needing two visible buttons.
  Glance can't show a popup/menu directly on the widget surface — this is a real platform
  limit, not a design choice — but `ShareRelayActivity` already solves exactly this class of
  problem (something Glance can't do directly, done by a real Activity once launched), so the
  natural fix is to extend it: show a two-option dialog once it's running as a real Activity,
  then launch the chooser with whichever the user picked.
- **The three-connected-circles Material "share" icon was considered and explicitly declined**
  in favor of a plain "Share" text label. That icon isn't reproducible with a single Unicode
  character — it would need a new static vector drawable resource, which would be a first for
  this app's header/footer chrome (every existing chrome element — ⚙, ↻, and the removed
  ▲▼✕−+ — is a plain Unicode glyph via Glance `Text`, not an `Image`/`ImageProvider` icon).
  Text avoids introducing that new pattern for a two-button dialog trigger.
- **Colors and font must comply with the active theme — clarified to mean: match the styling
  convention already used by immediate sibling chrome elements, not introduce new
  theme-driven behavior.** Color: reuse the same theme-derived color each neighboring element
  already uses (`accentProvider` for the per-article button, since it sits beside "Open
  article →" which already uses it; `GlanceTheme.colors.primary` for the footer button, since
  it sits beside the ⚙ gear which already uses that). Font: plain `FontFamily.SansSerif`,
  matching every other piece of chrome text in the footer and expanded-row action area — none
  of which currently varies by theme (`WidgetThemes.fontFamilyFor(theme)` is applied only to
  article headline/body text in `FeedItemRow.kt`, never to buttons or chrome). Extending
  per-theme font selection to chrome text would be new, inconsistent-with-neighbors behavior,
  and was explicitly ruled out.

## Architecture

Both additions route through `ShareRelayActivity`, extended with a second mode alongside its
existing one. The existing per-article-URL mode is completely unchanged. The new app-share mode
is triggered by a different Intent extra and shows a small, classic View-based `AlertDialog`
(not Compose — `ShareRelayActivity` stays a plain `Activity`, matching its current minimal
footprint) offering two choices before building the same `Intent.createChooser` share flow the
existing mode already uses.

## Components

### `glance/FeedItemRow.kt` — new per-article "Share ↗" button

Added immediately after the existing "Open article →" button (inside the same
`if (openIntent != null) { ... }` block, which is itself gated on the article actually having a
navigable URL), conditioned additionally on `externalApp == "browser"`:

```kotlin
if (openIntent != null) {
    Spacer(GlanceModifier.height(6.dp))
    Text(
        text = "Open article →",
        style = TextStyle(
            fontSize   = (9f * fontSize).sp,
            fontFamily = FontFamily.SansSerif,
            color      = accentProvider,
        ),
        modifier = GlanceModifier
            .background(GlanceTheme.colors.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clickable(actionStartActivity(openIntent)),
    )
    // New: only when "Open article in" is Browser - when it's already Share, "Open
    // article ->" above does the same thing this button would, so showing both would be
    // pure redundancy, not a new capability.
    if (externalApp == "browser") {
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = "Share ↗",
            style = TextStyle(
                fontSize   = (9f * fontSize).sp,
                fontFamily = FontFamily.SansSerif,
                color      = accentProvider,
            ),
            modifier = GlanceModifier
                .background(GlanceTheme.colors.primaryContainer)
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .clickable(actionStartActivity(
                    Intent(context, ShareRelayActivity::class.java)
                        .putExtra(ShareRelayActivity.EXTRA_ARTICLE_URL, article.articleUrl)
                )),
        )
    }
}
```

(The two buttons need to sit side by side in a `Row`, not stacked — the exact layout container
is an implementation detail for whoever writes the plan, informed by reading the surrounding
composable structure at that time; this spec fixes the button *content*, condition, and styling,
not the precise `Row`/`Column` wrapper.)

This reuses `ShareRelayActivity`'s existing, unmodified `EXTRA_ARTICLE_URL` mode — no changes
needed to `ShareRelayActivity.kt` for this half of the feature.

### `glance/NewsFeedWidget.kt` — new footer "Share" button

In `WidgetFooter` (shared by both `NewsFeedWidget` and `NewsFeedFocusWidget`), between the
refresh countdown and the ⚙ gear:

```kotlin
Text(
    text = "Share",
    style = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.SansSerif, color = GlanceTheme.colors.primary),
    modifier = GlanceModifier
        .padding(4.dp)
        .clickable(actionStartActivity(
            Intent(context, ShareRelayActivity::class.java)
                .putExtra(ShareRelayActivity.EXTRA_SHOW_APP_SHARE_CHOICE, true)
        )),
)
```

Placed after the existing `Spacer(GlanceModifier.defaultWeight())` and before the `"⚙"` `Text`,
so it sits between the countdown (left-aligned) and the gear (right-aligned).

### `glance/ShareRelayActivity.kt` — new app-share mode

```kotlin
class ShareRelayActivity : Activity() {
    companion object {
        const val EXTRA_ARTICLE_URL = "articleUrl"
        const val EXTRA_SHOW_APP_SHARE_CHOICE = "showAppShareChoice"
        private const val APP_URL = "https://github.com/Had-com/NewsFeed-widget"
        private const val DOWNLOAD_URL = "https://github.com/Had-com/NewsFeed-widget/releases/tag/latest"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val articleUrl = intent.getStringExtra(EXTRA_ARTICLE_URL)
        if (!articleUrl.isNullOrBlank()) {
            shareUrl(articleUrl)
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_APP_SHARE_CHOICE, false)) {
            AlertDialog.Builder(this)
                .setTitle("Share NewsFeed")
                .setItems(arrayOf("Share the app", "Share the download link")) { _, which ->
                    shareUrl(if (which == 0) APP_URL else DOWNLOAD_URL)
                    finish()
                }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        finish()
    }

    private fun shareUrl(url: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                "Share",
            )
        )
    }
}
```

The existing per-article path's behavior is unchanged (same `shareUrl()` body, extracted from
the inline code that was there before, called the same way). `setOnCancelListener { finish() }`
ensures backing out of the dialog (tap outside it, or the system back gesture) closes the relay
Activity rather than leaving it stranded on screen with nothing to show.

## Error handling

No new failure modes. Both paths terminate in the same, already-existing
`Intent.createChooser(...)` call — if the device somehow has no app capable of handling
`ACTION_SEND` (extremely unlikely; the system share sheet itself is always present on any real
Android device), that's pre-existing platform behavior, not something this feature changes or
needs to newly handle. Backing out of the app-share dialog is handled explicitly (see above).

## Out of scope

- Any change to the existing "Open article in: Browser | Share" setting's own behavior —
  untouched.
- Deep-linking directly into WhatsApp/Telegram/SMS specifically — the system share chooser
  (already used today) already surfaces whichever of those apps are installed as share targets;
  this spec doesn't special-case any one of them.
- A three-connected-circles vector icon — explicitly declined in favor of a text label (see
  Decisions).
- Any change to `UpdateManager.kt`/`UpdateRelayActivity.kt`'s own, unrelated GitHub Release
  URLs — this feature adds its own two constants local to `ShareRelayActivity.kt`, matching
  this codebase's established pattern of not centralizing every URL into one shared location
  (`TelegramFeedParser.kt`'s own doc comment on why network concerns aren't centralized).

## Testing / verification

Both changes are Android Activity/Compose UI with no pure logic to extract and unit test —
consistent with this codebase's existing convention (neither `FeedItemRow.kt` nor
`ShareRelayActivity.kt` has unit tests today, and nothing about this feature introduces a
wall-clock-time-independent, framework-free computation worth isolating). Verified entirely
on-device: confirm the per-article "Share ↗" button appears only when "Open article in" is
Browser and disappears when it's Share; confirm both buttons on an expanded row are usable
side by side without overlap; confirm the footer "Share" button appears on both widget types;
confirm tapping it shows the two-option dialog with both this app's real GitHub URLs; confirm
each option launches the real system share chooser with the correct URL; confirm cancelling the
dialog (tap outside, or back gesture) doesn't leave anything stranded on screen.
