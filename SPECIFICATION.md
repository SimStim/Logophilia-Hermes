# Logophilia Hermes — Specification

**Version 1.0 — 2026-11-26.** Supersedes `design outline.md`, which is kept for
reference only and will be abandoned. Cross-repository follow-ups live in
`TODO.md`. The API contract in §3 was verified against
`SimStim/Logophilia-API` at commit `7994618`; re-verify if the API changes.

## 1. Purpose and scope

Build a small, native Android application that connects to the existing
Logophilia API (`https://api.logophilia.eu`), downloads the currently available
free content, and keeps the local content synchronized with the server.

It is a convenience tool for discovering and trying newly available content.

It is NOT:

- A permanent media library.
- A general-purpose file manager.
- An EPUB reader, music player, or document editor.
- A replacement for keeping content permanently through the Logophilia website.

Keep the application deliberately simple, compact, reliable, and easy to
maintain. Build the smallest reliable application that accomplishes the
stated purpose; do not add features merely because Android makes them possible.

## 2. Technology and architecture

- Language: Kotlin.
- UI: Jetpack Compose.
- Minimum Android version: Android 10 (API 29). Target SDK: current stable.
- Networking: native Android HTTP networking or OkHttp.
- JSON: Kotlin serialization or equivalent.
- ZIP extraction: built-in Java ZIP utilities.
- External file access: Android Intents and FileProvider.
- Background/lifecycle integration: Android lifecycle APIs and
  ConnectivityManager.

Deliberately absent:

- No bundled networking binaries (curl etc.); Android provides everything.
- No Room/SQLite database. The local filesystem and the API manifest are
  sufficient for synchronization.
- No persisted user preferences. There is no theme selector (the app always
  follows the system theme) and no configurable endpoints or keys. The only
  persisted state in the entire app is the `lastSyncAttempt` timestamp (§4).
- No Auto Backup: `android:allowBackup="false"`, with matching
  `dataExtractionRules`. A restored months-old vault is worthless when the
  manifest is authoritative; excluding content from backups also keeps
  mtime-based recency (§8) honest.

## 3. API contract

Base URL: `https://api.logophilia.eu`

### Authentication

Every request must include the header `X-Api-key` with the current API key.

The initial key is compiled into the application (hardcoded). It is a client
credential, not a security measure — it protects against the most casual
misuse only; all content is public. Never log API keys or expose them in
diagnostic messages.

Auth failures: `401` (key missing) and `403` (key invalid), answered as
`{"status":"error","message":"..."}`. Until key renewal exists (§3.5), a `403`
is a plain authentication failure: report it and stop. No retry loops.

### CORS / Referer gate

The API currently rejects any request without an `Origin` header unless it
carries a `Referer` from `logophilia.eu`. The app sends neither by default.

- During development and testing, the app sends the spoofed header
  `Referer: https://logophilia.eu/`.
- Before public launch, this spoof is replaced by an explicit client marker
  exempted server-side (see `TODO.md`). Do not ship the spoof.

### Endpoints

`GET /freeloot` — content manifest. HTTP 200, JSON:

```json
{
  "message": "Here is your free loot!",
  "freeloot": [
    { "name": "The Pitch Science Fiction 2025 OST E55.zip",
      "size": 12345678,
      "sha256": "<64 lowercase hex characters>" }
  ],
  "status": "success"
}
```

- Flat list of files; no subdirectories, categories, or dates.
- `sha256` is computed over the complete file content (server-side `.hash`
  sidecar cache). `size` is in bytes.
- Empty content list: the current server answers `status: "error"` with an
  empty `freeloot` array (API fix scheduled, see `TODO.md`).

`GET /download?fileName=<name>` — one file.

- Response: `application/octet-stream` with `Content-Length`. No HTTP Range
  support: interrupted downloads cannot resume and are retried in full.
- A missing or invalid `fileName` currently returns HTTP 200 with a JSON
  error body (API fix scheduled). The client must therefore treat a response
  whose `Content-Type` is not `application/octet-stream` as a failed
  download.

`GET /` — greeting. HTTP 200, `{"message":"Welcome to Logophilia API.","status":"success"}`.
Used as the server-reachability probe for the Wi-Fi indicator (§8). Like all
endpoints it requires the API key. It is not a synchronization attempt and
does not affect the cooldown (§4).

Rate limiting applies to POST endpoints only; the app uses none.

### Manifest validation (Phase A)

Accept a manifest only if: HTTP 200, body parses as JSON, `status` is
`"success"`, `freeloot` is an array, and every entry has a non-empty `name`
(no path separators, not dot-prefixed), a non-negative `size`, and a 64-character
lowercase hexadecimal `sha256`. Reject malformed, incomplete, or corrupted
responses.

An API error or invalid response must NEVER be interpreted as an empty
content list, and must never delete or modify existing content. An empty
manifest (`freeloot: []`) is valid only when returned with
`status: "success"`; with the current server behavior (`status: "error"`) it
must be treated as a failed attempt that changes nothing locally.

### API key renewal (specified, blocked on API feature)

The API will eventually return, for a key found in its retired-keys list:

```json
{ "success": false, "error": "key_expired",
  "message": "Your API key has expired.", "new_api_key": "NEW_KEY" }
```

When this exact error is received: validate the new key is present and
non-empty, persist it, and retry the original request exactly once. No
endless renewal or retry loops; if renewal fails, show a useful error and
wait for the next permitted attempt. Until the API implements this, the
client behaves as in §3 Authentication. See `TODO.md`.

## 4. Synchronization policy

### Triggers

Synchronization is attempted:

- When the application is opened.
- When the application returns to the foreground.
- When a qualifying network (§ Network gate) becomes available while the
  application is running.

No periodic background synchronization. No polling; connectivity changes are
detected through Android's native callbacks.

### Network gate

Synchronize only over networks that are guaranteed unmetered: the active
network must report `NET_CAPABILITY_NOT_METERED` and
`NET_CAPABILITY_VALIDATED`. Never download anything over a metered
connection. Known accepted limitation: a hotspot fed by someone else's
mobile data still appears unmetered; no finer client-side signal exists.

If no qualifying network is available, show the existing local library and
skip synchronization.

### Five-minute cooldown

One timestamp, `lastSyncAttempt`, is persisted (DataStore or
SharedPreferences) and survives application restarts. It is written when an
actual synchronization attempt begins.

A new attempt is permitted only if at least five minutes have elapsed since
the previous attempt — regardless of the previous attempt's outcome
(success, empty manifest, invalid manifest, download failure, checksum
mismatch, extraction failure, server error, network interruption). The
reason does not matter: the API gets at least five minutes of breathing
room. The cooldown exists to protect the server (e.g. after a restart) from
request stampedes, not to police users.

Manual synchronization, if triggered by the user, obeys the same cooldown.
Simultaneous lifecycle and connectivity events must never start concurrent
synchronization operations (single-flight).

### Temp discipline

The Temp directory is wiped at the beginning of every synchronization
attempt, whatever the reason. Nothing survives across attempts: no resume,
no reuse of partially verified downloads, no memory of pending files. If an
attempt fails, its successful downloads are discarded together with Temp;
the next attempt starts over from a fresh manifest.

## 5. Synchronization algorithm

The remote manifest is authoritative. The checksum applies to the complete
original file content; the filename may remain unchanged when content is
updated, so filename comparison alone is insufficient.

### Phase A — Retrieve and validate manifest

Request the content list, handle authentication (§3), parse and validate
(§3 Manifest validation). Do not touch local content before a complete,
valid manifest has been received.

### Phase B — Identify required work

For each manifest entry, compare against local content:

- Plain file (not `.zip`): current ⟺ the file exists and its sidecar
  `<name>.hash` (stored in the same directory, containing the known
  checksum) matches the manifest `sha256`. If the sidecar is missing while
  the file exists, hash the file once, write the sidecar, then compare
  (self-heal; normally never happens because sidecars are written from the
  manifest value during commit, so no hashing is performed in practice).
- ZIP file: current ⟺ the extracted directory (§6) contains a marker file
  named `<sha256>.hash` with the manifest's checksum for that ZIP. The
  marker replaces the original archive, which is never retained after
  successful extraction. A missing marker means the next sync re-downloads
  and re-extracts (self-heal after a crash mid-swap).

### Phase C — Download and verify

Download all required new or modified files into Temp. For each file:
download completely, compute SHA-256, compare with the manifest, reject on
mismatch. Never overwrite a valid existing file with an unverified download.
If any required download fails, the attempt fails: retain existing local
content, discard Temp, perform no destructive cleanup.

### Phase D — ZIP processing

For every required ZIP:

1. Verify the original ZIP checksum (Phase C).
2. Extract into a Temp directory, preserving the archive's internal
   directory structure.
3. Apply ZIP Slip protection: reject entries that attempt to write outside
   the extraction destination.
4. Apply reasonable limits to extracted size and entry count to prevent
   resource exhaustion.
5. Ensure extraction completes before touching any live directory.

Replacement is a full swap, never a merge: the previous version of the
extracted directory is removed completely and the new directory takes its
place. Then write the `<sha256>.hash` marker into the new directory root.
The ZIP itself is deleted; nothing is archived.

Extraction always completes before any swap, so an interrupted run can only
lose content in the instant of the swap itself; that case self-heals on the
next sync (missing directory or missing marker ⇒ re-download).

### Phase E — Commit and cleanup

Only once all required downloads have succeeded, all checksums are verified,
and all required extractions have completed:

1. Move verified new plain files into their final locations (atomic rename)
   and write their `.hash` sidecars from the manifest values.
2. Perform the directory swaps for ZIPs (Phase D) and write markers.
3. Compare the final local content against the authoritative manifest and
   delete obsolete files and extracted directories (including their
   markers) that no longer exist in the manifest.
4. Remove Temp and any leftovers.
5. Mark the synchronization successful.

The operation is as transactional as practical. If any download,
verification, extraction, or commit fails, no obsolete-content cleanup is
performed. An interrupted operation must never leave the user with neither
the old working version nor the verified replacement, except for the
self-healing crash windows noted above.

An empty manifest (`status: "success"`, `freeloot: []`) is valid: after
validation, remove all previously synchronized content.

## 6. Local storage and directory structure

Everything lives in private application storage. No external storage
permissions, no user interaction required. The private directory is not
exposed to file managers; files are opened by external applications through
FileProvider (§7). All application-managed content is removed when the app
is uninstalled — intentional.

```text
app-private/
├── Books/       .epub and other book formats
├── Music/       audio files; OST archives extract to Music/<archive basename>/
├── Documents/   .txt, .md, .html, .htm, .pdf
├── Other/       everything else, including ZIPs without "OST"
└── Temp/        ephemeral; wiped at the start of every attempt
```

Classification rules (deterministic, filename/extension based):

| Content | Category |
|---|---|
| `*.zip` with `OST` in the name (pattern: `<publication title> OST<optional>.zip`) | `Music/<archive basename>/` (extracted) |
| `*.zip` without `OST` | `Other/` (until further notice) |
| `.epub` and other book formats | `Books/` |
| audio extensions (`.opus`, `.mp3`, `.flac`, …) | `Music/` |
| `.txt`, `.md`, `.html`, `.htm`, `.pdf` | `Documents/` |
| anything else | `Other/` |

`.hash` sidecars and ZIP markers are internal metadata: never listed in the
UI, never offered to external applications.

## 7. Opening files with external applications

No readers or media players are implemented. Files are handed to installed
applications via Intents, using FileProvider with temporary URI permissions;
private filesystem paths are never exposed.

| File type | Action |
|---|---|
| `.epub` | EPUB reader |
| `.opus`, `.mp3`, `.flac`, … | audio player |
| `.txt` | text viewer/editor |
| `.md` | Markdown viewer/editor |
| `.html`, `.htm` | browser |
| `.pdf` | PDF reader |
| other | let Android choose |

Use suitable MIME types and content URIs. Do not assume an application is
installed based on its name; use actual intent resolution and handle missing
handlers gracefully: show a concise installation suggestion, provide a direct
Google Play link where an appropriate application exists, and offer to retry
after installation. Suggested suggestions: EPUB → Moon+ Reader (free),
https://play.google.com/store/apps/details?id=com.flyersoft.moonreader ;
audio → foobar2000 for Android (verify the current official package and link
before implementation).

## 8. User interface

Compact, clear, visually restrained. The primary purpose is to show
available content and make it easy to open.

### Home

Logophilia logo (bundled assets, § Visual identity), Wi-Fi status indicator,
synchronization status indicator, counts of available books/albums/documents,
recently added content, quick access to content categories.

"Recently added" is derived from file modification times, newest first. This
is sound because synchronization never rewrites files whose checksum matches
the manifest — an mtime only changes when content actually changed or was
first downloaded. Consequences, accepted: on a fresh install everything is
recent; content is excluded from backups (§2) so restores cannot fake
recency. No added dates are stored anywhere.

### Library

Browse locally synchronized content by category: Books, Music, Documents,
Other. Opening an item launches the appropriate external application (§7).

### Sync

Current synchronization state, last attempt time, error details, and a
Manual Sync button that is disabled (greyed out) while the cooldown is
running. Tapping the synchronization status symbol opens a small detail
popup: status, last error if any, and the remaining cooldown time.

### About (replaces Settings)

Application information and version. There are no user preferences.

### Visual identity

Use the actual Logophilia logo, bundled in the APK from `art/`:
`logo-light.png` (black on transparent) for light mode, `logo-dark.png`
(white on transparent) for dark mode. The app always follows the system
theme; the matching logo variant is used automatically. No logo is fetched
from the website at runtime.

### Status indicators

Compact graphical indicators instead of lengthy status text.

Wi-Fi indicator (deterministic, via greeting probe `GET /`):

- **Red** — no validated unmetered network. No request is sent at all.
- **Amber** — unmetered network present, but the greeting did not return
  `status: "success"` (unreachable, server error, or auth rejection); tap
  for detail.
- **Green** — greeting answered successfully.
- Neutral placeholder state only before the very first probe.

The probe runs on the trigger events of §4 and is independent of the sync
cooldown. Server reachability is otherwise never synthesized from
synchronization requests alone.

Synchronization indicator (two-arrow circular sync symbol):

- Idle (before the first-ever attempt).
- Blue, static: manifest requested, waiting for the server.
- Green, animated: receiving the manifest or downloading files.
- Green, static: synchronization completed successfully.
- Red: synchronization failed; tap shows the error detail popup.

## 9. Error handling and reliability

The application must handle: no unmetered network; network without internet
access; API server unavailable; API authentication errors; API key renewal
(once implemented); malformed JSON; invalid or incomplete manifests;
interrupted downloads; SHA-256 mismatches; ZIP extraction failures;
insufficient storage; missing external applications; interrupted application
execution.

Invariants:

- Existing valid local content must remain usable offline.
- Never delete local content merely because an API request failed.
- Never overwrite valid content with an unverified file.
- Never run simultaneous synchronization operations.
- Respect the five-minute cooldown in every failure scenario.

## 10. Distribution and release

Ultimate distribution: Google Play Store (no account exists yet). Until
then: a signed release APK, downloadable from the website for sideloading,
plus a reproducible debug APK.

Play-ready hygiene from day one: stable `applicationId`, current target
SDK, and a release keystore created early and backed up securely — Play
requires a stable signing identity, and retrofitting one later is painful.
No in-app update mechanism (out of scope; revisit if sideloading persists
long-term).

## 11. Acceptance criteria

1. Fresh install, valid API key, unmetered network: retrieves and displays
   the content list.
2. Missing files: downloaded and verified.
3. Existing file with matching sidecar checksum: not downloaded again.
4. Existing filename with different checksum: downloaded, verified,
   replaced — plain file and extracted ZIP directory alike, marker updated.
5. Interrupted download: previous valid file retained; attempt discarded.
6. Invalid checksum: downloaded file rejected; attempt fails.
7. ZIP download: verified, safely extracted into the correct category
   directory, marker written, original ZIP deleted.
8. Interrupted ZIP extraction: previous working directory preserved.
9. File removed from the server: removed locally after a successful sync.
10. Corrupted manifest: all existing content preserved.
11. Server unavailable: content preserved; Wi-Fi indicator amber.
12. App reopened within five minutes: no synchronization attempt (the
    greeting status probe is permitted; no manifest or download requests).
13. App reopened after five minutes: synchronization is attempted.
14. No unmetered network: no synchronization; indicator red.
15. Unmetered network returns while the app is open: synchronization starts
    if the cooldown permits.
16. Expired API key: replacement received, stored, request retried
    successfully. *(Deferred until the API implements key renewal.)*
17. Unrecognized/revoked API key: authentication failure reported, no
    endless retries.
18. EPUB without an installed reader: Moon+ Reader installation offered.
19. Audio without an installed player: suitable installation suggestion.
20. Appearance follows the system theme in both light and dark, including
    logo selection.
21. All synchronized content remains available offline.
22. No downloads over metered connections under any circumstances.
23. ZIP marker matches the manifest checksum: no re-download and no
    re-extraction of that archive.
24. `.hash` sidecars and markers are never visible in the UI; PDFs open
    from Documents; non-OST ZIPs land in Other.

## 12. Development instructions

1. Inspect the API implementation and documentation (done; §3 records the
   verified contract).
2. Verify the foobar2000 Play Store package and link before implementing
   installation suggestions.
3. Check the target Android SDK and current recommended Android APIs.
4. Create a clean, independent Android project; copy `art/` logos into the
   resource directories.
5. Implement incrementally, but deliver a complete working application:
   complete source code, Gradle build configuration, manifest and resources,
   FileProvider configuration, build instructions, reproducible debug APK,
   release-build configuration, and a concise README (exists).

Do not use mock API responses in the finished application. Do not leave TODO
placeholders for essential functionality. Do not silently omit error
handling or security safeguards. Prefer simple, readable, maintainable code
over clever abstractions. Test against the real API wherever possible; if
credentials or device access are unavailable, clearly identify what remains
untested rather than claiming success.

**Guiding principle: build the smallest reliable application that
accomplishes the stated purpose.**
