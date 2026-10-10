# Report server: the contract and how the build gets its address and key

The in-app **Send to developer** button (Settings → REPORT A PROBLEM) posts the same plain-text report that
**Share report** produces to a small collector that the developer runs. This file is the contract between the app
and that collector, the privacy statement for it, and the steps that give a build its server address and key.

The collector is a separate project and is not part of this repository. **The address and the key are not in this
repository** (it is public): they are injected when the app is built, see [How the build gets the address and the
key](#how-the-build-gets-the-address-and-the-key).

## What the user sees

- The button exists only in a build that was given **both** a server address and a key, and the address must be
  `https://`. In any other build (local builds, forks, CI without the secrets) the button is **hidden**, not
  greyed out, and everything else works as before.
- Tapping it first shows a confirmation: what is sent (the report text, with **Preview first** to read it), which
  server it goes to (the host name only), that nothing is sent automatically, and **Send** / **Cancel**. Nothing
  leaves the phone before the tap on **Send**.
- Result texts: `Sent. Report #42 — quote this number if you contact the developer.`, a "too many reports" text
  with the wait, a "couldn't reach the server" text, and for any other failure a short reason followed by
  `Use Share report instead.`
- The phone remembers the time and number of the last successful send (shown as `Last sent: <time> (#42)`) and
  blocks a second send for 60 seconds. The app never retries by itself.

## The contract

### Send a report

```
POST {BASE}/api/v1/reports
Content-Type: application/json
X-App-Key: <APP_KEY>
```

```json
{
  "schema": 1,
  "clientId": "<uuid v4, random, created once per install>",
  "appVersionCode": 933,
  "appVersionName": "<at most 40 characters>",
  "createdAt": 1700001000000,
  "report": "<plain text, UTF-8, at most 60000 characters, no control characters except \n \r \t>"
}
```

| Answer | Meaning | What the app shows |
|---|---|---|
| `201` `{"id":N}` | Stored | `Sent. Report #N ...` (without the number if the answer has no readable id) |
| `400` `invalid_request` | The body was refused | "The server did not accept the report. Use Share report instead." |
| `401` `unauthorized` | Missing or wrong `X-App-Key` | "The server did not accept this version of the app. Use Share report instead." |
| `404` / `405` | Wrong address or method | same as 400 |
| `413` `too_large` | Body over 80 KB | "The report is too large for the server. Use Share report instead." |
| `429` `rate_limited` + `Retry-After` | Limit reached | "Too many reports right now. Try again in N minutes, or use Share report." (without a time when the header is absent) |
| `5xx` | Server problem | "The server had a problem. Try again later, or use Share report instead." |
| no answer (offline, DNS, timeout, TLS) | Not reachable | "Couldn't reach the server. Check your connection, or use Share report." |

Any other answer (a redirect, an unexpected 2xx) is treated like 400. Redirects are never followed, so a reply can
never send the key to another host.

### Health check (not used by the app)

```
GET {BASE}/api/v1/health   ->   200 {"ok":true,"version":"..","reports":N}
```

Handy to check that the server is up and that a test send raised `reports` by one.

### Limits

| Limit | Value |
|---|---|
| Body size | 80,000 bytes (UTF-8) |
| Report text | 60,000 characters |
| Reports per `clientId` | 5 per 24 hours |
| Reports per IP address | 20 per hour |
| Reports in total | 500 per 24 hours |
| App version name | 40 characters |

The app stays inside the size limits on its own: the report builder already caps the text at 60,000 characters, and
the upload step shortens it further (dropping the middle, with a marker line) if Hebrew or emoji make the encoded
body exceed 80,000 bytes. Disallowed control characters are removed before sending.

Timeouts: 10 s to connect, 15 s to read and to write.

### The client id

`clientId` is a random UUID v4 generated on first use and kept in the app's private settings (`report_settings`,
key `client_id`). It is **not** derived from the device, the Android ID, the account or anything else on the phone.
Clearing the app's data creates a new one. Its only purpose is the per-install daily limit.

## Privacy

What a report contains (the same text the user can read with **Preview report** before anything is sent): the app
build, the phone model and Android version, language and time zone, the user's own note, the settings of each
widget, the feed **names**, the refresh status, the last 20 refreshes with the reason each failed feed failed, and
recorded crashes with their stack traces.

- Feed **addresses** (URLs) are left out unless the user ticks **Include my feed addresses**; then passwords and
  token-style parameters are replaced by `REDACTED`. The same rule applies to the user's note (any address typed
  there is replaced by `<address>`, or redacted when the box is ticked).
- Nothing is sent automatically, in the background, on a schedule or on a crash. Only the user's tap on **Send** in
  the confirmation sends one report.

What the server keeps: the report text, the `clientId`, the app build (`appVersionCode`, `appVersionName`), the
creation time, and a **hashed** IP address (used only for the per-IP limit). Reports are deleted after **180 days**.
The developer reads them on the server with the `newsfeed-reports` tool (`newsfeed-reports show <id>`); a user who
quotes the report number from the confirmation can be matched to their report.

## How the build gets the address and the key

Two build-time values, both read the same way, in this order:

1. a Gradle property `-PREPORT_BASE_URL=...` / `-PREPORT_APP_KEY=...`
2. an environment variable of the same name
3. otherwise empty, which means "no Send button"

They become `BuildConfig.REPORT_BASE_URL` and `BuildConfig.REPORT_APP_KEY` (`app/build.gradle.kts`). Leading and
trailing spaces are removed; a value containing control characters is ignored. `REPORT_BASE_URL` is the server's
origin, for example `https://reports.example.org` (a trailing slash is fine, the app adds `/api/v1/reports`).
It must be `https`.

### Local builds

```
./gradlew assembleDebug -PREPORT_BASE_URL=https://reports.example.org -PREPORT_APP_KEY=<the key>
```

or set the two environment variables in the shell that runs Gradle. Without them the build simply has no Send
button. Do not paste the key into a file in the repository, and keep it out of shared terminal logs.

### GitHub Actions (the published builds)

Add the two values once as repository secrets: repository → **Settings** → **Secrets and variables** →
**Actions** → **New repository secret**; create `REPORT_BASE_URL` and `REPORT_APP_KEY`. The workflow
(`.github/workflows/build.yml`) hands them to the Gradle build step as environment variables of the same name; they
are not printed, not written to a file by the workflow, and GitHub masks them in logs. Builds from forks and any
run without the secrets are made without the Send button.

### Rotating the key

Change the key on the server and the `REPORT_APP_KEY` secret together, then let CI publish a new build. Apps built
with the old key keep working for everything else, but their Send answers `401` and they show "The server did not
accept this version of the app. Use Share report instead." until they update (the app self-updates from the latest
release). Rotating the address works the same way through `REPORT_BASE_URL`.

### What the key is, and is not

The key is **only a spam deterrent**. It ships inside every APK that was built with it, so anyone who downloads the
published app can extract it. It keeps casual or accidental traffic out; the real protection is the server's rate
limits and its size and content checks. Never reuse this key for anything else, and never put any other secret in a
build value.

## Checks before a push

The repository must never contain the address or the key. Before pushing:

```
git grep -nE "duckdns|X-App-Key|REPORT_APP_KEY|REPORT_BASE_URL"
```

should list only code that *reads* the values (`app/build.gradle.kts`, `ReportUploader.kt`, the workflow's
`secrets.` references) and documentation placeholders, never a real value. Also check that a debug APK built
locally without the values has no Send button, and that logcat never shows the key (QA plan section P).
