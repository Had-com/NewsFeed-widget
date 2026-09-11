# Server-side backend: bug collector + true push — future roadmap

> **Status: future planning document, not scheduled for implementation.** This captures
> architecture decisions and scope for two features that share one backend — the already-
> deferred Bug Logger Phase 2, and a new "instant updates" push feature — so that whoever
> picks either one up later isn't starting from scratch. This is NOT an approved spec ready
> for `writing-plans`; when either half of this is actually picked up, it should go through
> its own focused brainstorm against whatever's true at that time (library versions, this
> app's own architecture, Cloudflare's/Firebase's then-current free tiers all may have
> changed), using this document as a starting point, not a locked contract.

## Why these two features share one backend

Both need the exact same shape of infrastructure: a small, always-available server holding
a secret this app's shipped APK must never contain (a GitHub token for bugs; nothing bug-
related for push, but the same Worker can host both cheaply), reachable over a public HTTPS
endpoint the app can call. Building one shared backend for both, rather than two separate
ones, is simpler to operate and — given the hosting decision below — costs nothing extra to
add the second feature once the first exists.

## Decisions made during brainstorming

- **Hosting: free-tier serverless (Cloudflare Workers), not a self-managed VM.** This
  project has zero existing server infrastructure and zero ongoing hosting cost anywhere
  today (GitHub Actions/Releases are free for a public repo) — a real always-on server would
  be the first recurring cost and maintenance burden this project has ever taken on.
  Cloudflare Workers' free tier (as of this writing: 100,000 requests/day, Workers KV for
  key-value storage, Cron Triggers for scheduled jobs) fits both features' actual traffic
  shape for a hobby-scale user base. **This needs re-checking against Cloudflare's actual
  current free-tier limits whenever this is picked up** — free-tier terms change.
- **Push is opt-in, off by default, with an explicit privacy disclosure shown before
  enabling it.** Today this app is genuinely standalone — no server ever sees a user's feed
  list. Push requires the server to know each opted-in user's feeds and hold an FCM device
  token to notify them. Every other user's experience must stay byte-for-byte identical to
  today (same local WorkManager polling, same no-server-involvement) unless they explicitly
  turn this on.
- **Crash reporting to the server is on by default, with a visible way to turn it off.**
  Lower sensitivity than a feed list (diagnostic data — exception type/message/stack trace —
  not what the user reads or subscribes to), so it follows the common commercial-app default
  of on-with-opt-out rather than push's off-with-opt-in. This is a deliberate escalation from
  Bug Logger Phase 1, which is local-only with no toggle at all today (nothing to opt out of
  yet, since nothing leaves the device) — Phase 2 needs a real Settings toggle where Phase 1
  had none.
- **Bug reports are deduplicated server-side into one shared, incrementing GitHub issue per
  crash signature** — not one issue per report. The server keys by (exception type, message),
  the same signature `CrashLogStore` already uses on-device (see
  `docs/superpowers/specs/2026-09-10-bug-logger-design.md`). First occurrence from any user,
  on any device, creates the GitHub issue; every subsequent occurrence of the same signature
  — from that user again, or a different user entirely — increments a counter (e.g. an edited
  issue-body line, or a new comment) on the existing issue instead of creating a new one.
  Keeps the tracker usable regardless of how many users hit the same bug.

## Architecture

One Cloudflare Worker, with Workers KV (or D1 if the eventual schema turns out more
relational than key-value — decide when actually building this) for state, and the real
GitHub token stored as a Worker secret (`wrangler secret put`, never in source, never in the
app). Two logically separate endpoint groups on the same Worker:

### Bug collector endpoints

- `POST /crash` — body: `{exceptionType, message, stackTrace, appVersionCode}` (the same
  shape `CrashLogStore.CrashRecord` already has locally — reuse it, don't invent a new
  shape). Worker computes a signature key from `(exceptionType, message)`, looks it up in KV:
  - Not found → create a GitHub issue via the GitHub REST API (`POST /repos/{owner}/{repo}/issues`)
    with the stack trace and a starting "Seen 1×" note; store the returned issue number in KV
    keyed by the signature.
  - Found → `PATCH` the existing issue (or add a comment) incrementing the occurrence count;
    no new issue.
  - No response body needed beyond a bare success/failure status — the app doesn't need to
    know the GitHub issue number.
- This endpoint's URL is safe to embed in the app (unlike a GitHub token) — the Worker does
  its own minimal validation (e.g. reject clearly-malformed payloads, rate-limit per rough
  client identifier) rather than requiring the caller to authenticate, matching how a public
  crash-ingestion endpoint is normally built. Exact abuse-prevention approach (rate limiting
  strategy, payload size caps) needs its own decision when this is actually built — not
  resolved here.

### Push endpoints

- `POST /register` — body: `{feedUrls: [...], fcmToken: "..."}`, called once when the user
  turns "instant updates" on, and again whenever their feed list changes while it's on.
  Overwrites (not appends) that device's registration in KV — a device's current feed list is
  always the source of truth, not an accumulating log.
- `POST /unregister` — called when the user turns "instant updates" off, or when the app is
  uninstalled if a way to detect that reliably exists (unresolved — Android has no reliable
  uninstall webhook; this may end up being "registrations expire after N days of inactivity"
  instead, decided when built).
- A **Cron Trigger** (not an HTTP endpoint a client calls — Cloudflare's own scheduler) firing
  every few minutes: reads all registered feed URLs (deduplicated — if 50 opted-in users all
  subscribe to the same popular feed, the Worker fetches that feed's XML exactly once per
  cycle, not 50 times), compares each feed's items against the last-seen article ID stored in
  KV for that feed, and for any genuinely new article, looks up which registered devices
  subscribed to that feed and sends each one an FCM push message (a lightweight data message —
  "feed X has new articles," not the article content itself, since the device already knows
  how to fetch and render its own feeds) via Firebase Cloud Messaging's HTTP v1 API.

### App-side changes

- **New Firebase dependency** — this app has never used Firebase/Google Play Services for
  anything; adding FCM means a new `google-services.json`, a new Firebase project, and a new
  `com.google.firebase:firebase-messaging` dependency. This is a bigger dependency-surface
  change than anything else in this app's history and deserves its own explicit sign-off when
  the push half of this is actually built — flagging it here so it isn't a surprise later.
- A new Settings toggle, "Instant updates" (off by default), showing the privacy disclosure
  (exact wording TBD when built, but must clearly state: "your feed list will be sent to
  [wherever this is hosted] to check for new articles and notify this device") before it can
  be turned on. Turning it on registers with the Worker; turning it off unregisters.
- A new Settings toggle, "Send crash reports" (on by default), gating whether
  `NewsFeedApplication`'s crash handler — beyond its existing local `CrashLogStore.record()`
  call, unchanged — also POSTs to `/crash`. The existing local-only Phase 1 behavior stays
  exactly as-is regardless of this toggle; the toggle only controls the additional network
  call.
- An FCM message-received handler that triggers the same immediate-refresh path the existing
  on-widget "refresh now" button already uses (`RefreshNowCallback`/`WidgetWorker`), so a push
  notification results in the widget actually updating, not just a system notification with
  stale content underneath it.

## Open questions to resolve when either half is actually picked up

- Exact Cloudflare Workers/KV/D1 free-tier limits at that time, and whether they still fit.
- Exact abuse-prevention strategy for the public `/crash` endpoint (this doc deliberately
  doesn't lock this down, since it depends on actual observed traffic patterns this project
  doesn't have yet).
- How to detect app uninstalls for push registration cleanup (or whether to just accept
  stale registrations expiring on a timer instead).
- Exact FCM setup steps and Firebase project ownership (whose Google account owns it,
  billing implications if usage ever exceeds Firebase's own free tier).
- Whether the two features should really ship together, or whether the bug collector (lower
  privacy sensitivity, no new client dependency) should ship well before push (which adds a
  whole new SDK and a much bigger privacy surface) — this document takes no position on
  sequencing; that's a call for whoever picks this up, informed by what else is going on in
  the project at that time.

## Out of scope (explicitly, for this document)

- Any code. This is a planning document only.
- A finished UI design for the two new Settings toggles/disclosure text — sketched above at
  the "what it needs to do" level, not designed.
- Migrating existing Bug Logger Phase 1 users' behavior — Phase 1's local-only logging is
  unaffected either way; this is a pure addition.
