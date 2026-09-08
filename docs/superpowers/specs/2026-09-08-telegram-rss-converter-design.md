# Telegram RSS converter — design

## Context

NewsFeed users want to add public Telegram channels as a feed source, alongside their
existing RSS/Atom feeds. Telegram channels don't publish RSS themselves, but every public
channel has a plain HTML "web preview" page at `https://t.me/s/<channel>` that lists its
recent posts, requires no login, and has a simple, regular structure — this is the standard
technique other "unofficial Telegram RSS" tools already use.

## Decisions made during brainstorming

- **Add flow**: reuse the existing "type a URL" Add Feed field. No new UI entry point — the
  app auto-detects a Telegram reference in the same field already used for RSS URLs,
  matching the existing "paste anything, we normalize it" behavior.
- **Fetch method**: scrape the public `t.me/s/<channel>` page. No Telegram Bot API, no bot
  token, no login — deliberately out of scope. A bot-token approach would require the user
  to create a bot via @BotFather, add it as a channel admin, and hand a secret token to the
  app; the public-scrape approach needs none of that, at the cost of depending on an
  unofficial page structure that could change.
- **Headline synthesis**: Telegram posts have no separate title field like RSS does. The
  message's first line becomes the headline; the rest becomes the description — matches how
  Telegram channels are typically written (many already lead with a short bold line).
- **Pagination**: explicitly out of scope (YAGNI). First-time add gets whatever's on the
  front page of `t.me/s/<channel>` (~20 most recent posts); the accumulated store grows over
  time through normal refreshes, exactly like every other feed already works. The `?before=`
  pagination parameter the page supports is not used.

## Architecture

A Telegram channel becomes an ordinary `FeedConfig` — its `feedUrl` is set to the canonical
`https://t.me/s/<channel>` URL. Nothing downstream (accumulated storage, retention, sort/
filter, per-feed accent color, per-feed RTL/LTR direction, rendering) needs to know or care
that a feed originated from Telegram; it's just another source of `ArticleItem`s. This keeps
the blast radius of the feature small: one new parser module, one new detection function
called from the existing Add Feed flow, and one new branch inside the existing per-feed
fetch dispatch.

## Components

### `data/TelegramFeedParser.kt` (new file)

- `fun canonicalize(raw: String): String?` — given raw user input from the Add Feed field,
  recognizes Telegram references and returns the canonical `https://t.me/s/<channel>` fetch
  URL, or `null` if the input doesn't look like one. Patterns recognized: `t.me/channelname`,
  `https://t.me/channelname`, `telegram.me/channelname`, and a bare `@channelname`.
- `suspend fun fetchArticles(channelFeedUrl: String, maxItems: Int): List<ArticleItem>` — the
  actual fetch + parse, described below.
- `suspend fun fetchChannelTitle(channelFeedUrl: String): String?` — fetches the channel's
  display name from the preview page, mirroring `NewsFeedRepository.fetchFeedTitle()`'s
  existing role for RSS feeds, used when the feed is first added.

### Fetch dispatch (every regular refresh, not just at add-time)

`NewsFeedRepository.fetchFeedArticles(feed, maxItems)` — the function `WidgetWorker`'s
periodic refresh already calls once per configured feed — gains one new check at its top:
if `feed.feedUrl.startsWith("https://t.me/s/")`, delegate to
`TelegramFeedParser.fetchArticles(feed.feedUrl, maxItems)` and return its result directly,
skipping the existing `XmlPullParser`-based path entirely for that feed. This is the only
change to the shared fetch loop — everything else in `getArticles()` (per-feed concurrency,
`allFailed` tracking, the retention/sort/filter pipeline) operates on the returned
`List<ArticleItem>` identically regardless of which path produced it.

### Fetching & parsing

Uses the existing OkHttp client and browser headers (already shared by all feed fetching).
Fetches `https://t.me/s/<channel>` and parses the HTML with a small, purpose-built parser —
not a general HTML/DOM library, since the preview page's structure is simple and regular and
a new dependency isn't warranted for it. Each message is a
`<div class="tgme_widget_message" data-post="channel/123" ...>` block. Per message:

- **id / permalink**: from `data-post="channel/123"` → `articleUrl = "https://t.me/channel/123"`,
  used directly as the article's stable `id` — no random-fallback needed, unlike a malformed
  RSS item with neither guid nor link.
- **timestamp**: from the `<time datetime="...">` element (ISO-8601) — parsed with the same
  approach already proven correct elsewhere in the app (`NewsFeedRepository.parseDate()`'s
  ISO-8601 branch).
- **text**: from `.tgme_widget_message_text`'s inner content, run through `Html.fromHtml()`
  the same way RSS titles/descriptions already are, to flatten Telegram's own inline
  formatting (`<br>`, `<b>`, `<i>`, `<a>`) down to plain text. First line → `title`; remaining
  text → `description`.
- **image**: from `.tgme_widget_message_photo_wrap`'s inline
  `style="background-image:url('...')"` attribute, extracted via regex — the same general
  technique already used to pull a fallback image out of RSS `<description>` HTML.
- Messages with neither text nor image (forwarded-message wrappers, service messages) are
  skipped — mirrors the existing RSS behavior of skipping an item with a blank title.
- Capped at `maxItems`, reusing the exact same `MAX_ITEMS_PER_FETCH` /
  `FIRST_LOAD_MAX_ITEMS` / `knownFeedIds` logic `NewsFeedRepository.getArticles()` already
  applies to every feed — no separate cap system for Telegram.

### Add-Feed integration

`WidgetConfigActivity.doAddFeed()` calls `TelegramFeedParser.canonicalize(raw)` **before**
its existing `if (raw.startsWith("http")) raw else "https://$raw"` RSS normalization — a bare
`@channelname` would otherwise be mangled into `https://@channelname`, and a bare
`t.me/channelname` would be misread as a literal (wrong) RSS-fetch target rather than
recognized as Telegram.

- If `canonicalize()` returns a URL: fetch the channel's title via `fetchChannelTitle()`
  (mirrors the existing `repo.fetchFeedTitle(url)` call for RSS), then build the `FeedConfig`
  exactly as the RSS path does today (including the existing accent-color-rotation logic —
  see the accentColor consistency fix already shipped, `commit 1f84ddd`).
- If `canonicalize()` returns `null`: fall through to the existing RSS-URL handling,
  unchanged.
- The mid-session OPML import and Find-Feeds search paths are **not** changed — OPML files
  and Feedly's search results are not expected to contain Telegram references, so adding
  Telegram-detection there would be dead code. If a user's OPML export happens to contain a
  `t.me/s/...` URL from a previous Telegram add, it will already round-trip correctly through
  the plain RSS path's `feedUrl`, since Telegram feeds are just `FeedConfig`s.

### Error handling

No new error paths. A channel that doesn't exist or is private (the preview page returns an
empty/placeholder body, no `.tgme_widget_message` blocks) makes `fetchChannelTitle()` return
`null`, which falls into the exact same existing "Could not load feed — check the URL"
message the RSS path already shows for an unreachable feed. A parse failure from a future
page-structure change is caught the same way malformed RSS already is — silently skipped for
that feed on that refresh, not a crash (`runCatching` around the per-feed fetch, already
present in `NewsFeedRepository.getArticles()`).

### UI copy

The Add Feed field's placeholder text changes from "RSS or Atom feed URL" to
"RSS, Telegram or Atom feed URL" so the new capability is discoverable without needing a
separate UI element.

## Out of scope

- Private Telegram channels (would require real Telegram API access — bot token or phone
  auth — a materially different, credential-handling feature).
- `t.me/s/` pagination for first-load history (see YAGNI decision above).
- Telegram channel discovery via Find Feeds search (Feedly's search API has no Telegram
  awareness; out of scope for this change).
- Any special per-Telegram-feed settings UI — a Telegram-sourced feed uses the exact same
  per-feed settings (accent color, RTL/LTR, display mode, font family) as any RSS feed.

## Testing / verification

Manual on-device: add a real, known-public Telegram channel via the existing Add Feed field,
confirm articles appear with sensible headlines/timestamps/images, confirm RTL/LTR renders
correctly if the channel is Hebrew, confirm a subsequent refresh only pulls genuinely new
posts (dedup working via the stable permalink-derived `id`), and confirm the
"Could not load feed" error path for a nonexistent/private channel handle.
