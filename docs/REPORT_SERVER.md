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
- Tapping it first shows a confirmation: what is sent (the report text, with **Preview first** to read it), that nothing is sent automatically, a
  warning that the user's own feed names and addresses are included (only when "Include my own feeds (names and addresses)" is ticked), and **Send** / **Cancel**. The
  server address is not shown. Nothing
  leaves the phone before the tap on **Send**.
- Result texts: `Sent. Report #42 — quote this number if you contact the developer.`, a "too many reports" text
  with the wait, a "couldn't reach the server" text, and for any other failure a short reason followed by
  `Use Share report instead.`
- Optionally the user attaches **one picture** (a screenshot or photo) to the report. The confirmation then adds an amber
  warning that the picture is sent too and may show private information, with a small preview of it. The picture is
  uploaded only after the report itself was stored, see [Send the picture of a report](#send-the-picture-of-a-report-optional--requires-server-version--11).
  Result texts with a picture: `Sent. Report #42 with your picture — quote this number if you contact the developer.`,
  or, when the report arrived but the picture did not, `Report #42 was sent, but the picture could not be uploaded
  (<reason>). You can use Share report to send the picture yourself.` A picture that fails never makes the report look
  failed or unsent.
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

### Send the picture of a report (optional) — requires server version ≥ 1.1

**Server version ≥ 1.1 only.** A server without this endpoint answers `404` or `405`; the app then tells the user
`Report #N was sent, but the picture could not be uploaded (the server does not accept pictures yet (404/405)). You can
use Share report to send the picture yourself.` and the report itself stays stored.

The app calls it **only** when the user attached a picture, **only after** `POST /api/v1/reports` answered `201` with a
report number, and with the **same** `clientId` the report was sent with. At most one picture per report.

```
POST {BASE}/api/v1/reports/{id}/image
Content-Type: image/jpeg
X-App-Key: <APP_KEY>
X-Client-Id: <the clientId the report was sent with>

<raw JPEG bytes, at most 1,500,000>
```

`{id}` is the number from the `201` answer of the report. The body is the JPEG itself, not JSON and not base64. The
app always sends a JPEG that it re-encoded itself (see Privacy), normally **at most 900 KB**, never more than
**1,400,000 bytes** (a picture that would be larger is refused on the phone and not sent).

| Answer | Meaning | What the app shows after `Report #N was sent, but the picture could not be uploaded (...)` |
|---|---|---|
| `201` `{"id":N,"imageBytes":M}` | Stored | (the success text `Sent. Report #N with your picture — ...`) |
| `400` | Invalid: empty or not a JPEG | the server did not accept it |
| `401` | Missing or wrong `X-App-Key` | the server did not accept this version of the app |
| `404` `not_found` | Unknown report id **or** the `X-Client-Id` does not match the report's (deliberately the same answer); also what a server without the endpoint says | the server does not accept pictures yet (404/405) |
| `405` | No such method here (an old server) | the server does not accept pictures yet (404/405) |
| `409` `image_exists` | That report already has its one picture | the server already has a picture for this report |
| `413` `too_large` | Over 1,500,000 bytes | too large |
| `415` `unsupported_media_type` | `Content-Type` is not `image/jpeg` | the server did not accept it |
| `429` `rate_limited` + `Retry-After` | 5 pictures per client per 24 hours reached | too many pictures today, try later |
| `503` `storage_full` / other `5xx` | Server problem | the server had a problem |
| no answer (offline, DNS, timeout, TLS) | Not reachable | offline |

Rules for the server: the picture may only be added within **24 hours** of the report; the check on `X-Client-Id`
must give the same `404` as an unknown id so ids cannot be probed. Redirects are never followed. Timeouts and the
response body cap are the same as for the report (10 s connect, 15 s read and write, at most 4 KB of an answer is read).
The app does not retry. The picture bytes, the key and the report text are never logged: the only log lines are
`image stored bytes=M`, `image rate_limited retry_after=S`, `image network_error <ExceptionClass>`,
`image rejected code=C`, `image server_error code=C`, `image too_large`, `image not_found`, `image already_exists`,
`image unauthorized`, `image unreadable` and `image misconfigured`.

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
| Picture (server ≥ 1.1) | 1,500,000 bytes (the app sends at most 1,400,000, normally at most 900 KB), JPEG only, one per report |
| Pictures per `clientId` (server ≥ 1.1) | 5 per 24 hours |
| Time to add the picture (server ≥ 1.1) | 24 hours after the report |

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
widget, the names of the app's own default feeds, the refresh status, the last 20 refreshes with the reason each failed feed failed, and
recorded crashes with their stack traces.

- The user's **own feeds** (names and addresses) are left out unless the user ticks **Include my own feeds (names
  and addresses)** (off by default). While it is off, only the app's bundled default feeds are named (a feed is a
  default when its address equals one in the bundled list, `http` and `https` alike); every other feed is `feed #1`,
  `feed #2` ... (numbered in order of first appearance, the same number everywhere in the report, including the
  failure lines of the refresh log); no custom name, address or host name is printed, also not inside error
  messages. A widget line shows the counts, for example `feeds: 13 (13 enabled; 10 default, 3 yours)`. When the box
  is ticked, names and addresses are included and passwords and token-style parameters are replaced by `REDACTED`.
  The same rule applies to the user's note (any address typed there is replaced by `<address>`, or redacted when the
  box is ticked). The report header says which: `Your own feeds: not included (named feed #1, #2 ...)` or
  `Your own feeds: included (passwords and tokens removed)`.
- Nothing is sent automatically, in the background, on a schedule or on a crash. Only the user's tap on **Send** in
  the confirmation sends one report.
- **The attached picture (optional, one per report).** The user picks it with Android's photo picker (no storage
  permission is requested) and it is **re-encoded by the app as a JPEG** (long edge at most 1600 px, quality 80 first,
  lowered step by step, under 900 KB): whatever the original was (JPEG, PNG, WEBP, HEIC), the new file carries **no
  EXIF data at all** (no GPS location, no camera or phone model, no time stamps); the camera's rotation is applied to
  the pixels first so it is not turned. The picture can still show private things (a screenshot of a mail, a face), so
  the app says so under the thumbnail and again in the send confirmation. It is kept only in the app's cache folder and
  in memory, forgotten (and its file deleted) when the user leaves Settings or taps **Remove**, and it leaves the phone
  **only** when the user taps **Send** (this endpoint) or **Share report** (the share sheet, together with the report
  file). Choosing a picture, previewing the report or opening Settings sends nothing. The report text only says
  `Attached picture: yes (812 KB)` so the developer can see a picture exists.

What the server keeps: the report text, the `clientId`, the app build (`appVersionCode`, `appVersionName`), the
creation time, and a **hashed** IP address (used only for the per-IP limit). When the user attached a picture, the server
also keeps that JPEG with its report. Reports **and their pictures** are deleted after **180 days**.
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
