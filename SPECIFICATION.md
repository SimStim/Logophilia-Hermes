# Logophilia Hermes — Specification

**Version 1.1 — 2026-11-27.** Supersedes `design outline.md`, which is kept for
reference only and will be abandoned. v1.1 folds in the decisions made after
v1.0: greeting-only key renewal (§3.5), no `Content-Length` on `/download`
(§3), incremental per-file commit with a per-commit network-gate re-check
(§4, §5), acceptance of temporarily-not-metered networks (§4), indicator
consolidation and a hierarchical Library (§8), image MIME types with a
generic no-handler dialog (§7), bundled fonts and externalized user-facing
strings (§8). Cross-repository follow-ups live in `TODO.md`. The API contract
in §3 was verified against `SimStim/Logophilia-API` at commit `7994618`;
re-verify if the API changes.

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

- Response: `application/octet-stream`. The server sends **no**
  `Content-Length` (chunked transfer — found in the M2 live check), so the
  client must not rely on it; the SHA-256 verification is the integrity
  check. No HTTP Range support: interrupted downloads cannot resume and are
  retried in full.
- A missing or invalid `fileName` currently returns HTTP 200 with a JSON
  error body (API fix scheduled). The client must therefore treat a response
  whose `Content-Type` is not `application/octet-stream` as a failed
  download.

`GET /` — greeting. HTTP 200, `{"message":"Welcome to Logophilia API.","status":"success"}`.
Used as the server-reachability probe for the Wi-Fi indicator (§8) and as the
key-renewal channel (§3.5). Like all endpoints it requires the API key. It is
not a synchronization attempt and does not affect the cooldown (§4).

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

### API key renewal (greeting-only channel; blocked on API feature)

The API delivers key updates exclusively through the greeting endpoint. When
a request presents a key found on the server's retired-keys list, `GET /`
responds with the `key_expired` error carrying `new_api_key`:

```json
{ "success": false, "error": "key_expired",
  "message": "Your API key has expired.", "new_api_key": "NEW_KEY" }
```

Every other endpoint keeps the strict single-key check and answers an expired
key with a plain `403`; no other endpoint ever delivers a replacement key.
Rationale: renewal logic lives in one controller instead of all of them; the
website never calls the greeting, so a replacement key can never be disclosed
to web clients by mistake; and the app probes the greeting on every trigger
event anyway, so a fresh key always arrives before any sync traffic.

App behavior: the greeting probe is the first network step of every trigger
(§4). If it reports `key_expired`: validate that the replacement key is
present and non-empty, persist it, and continue the regular flow with the
new key. No retry-once rule exists: a key rotated mid-sync simply fails that
attempt, and the next probe delivers the new key. Never loop renewals; if a
renewal fails, show a useful error and wait for the next permitted attempt.
Until the API implements this, the client behaves as in §3 Authentication.
See `TODO.md`.

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

Synchronize only over networks that never bill the user: the active network
must report `NET_CAPABILITY_VALIDATED` and either `NET_CAPABILITY_NOT_METERED`
or `NET_CAPABILITY_TEMPORARILY_NOT_METERED` (API 30+). "Temporarily not
metered" never charges the user, so it qualifies; because it may flip back
to metered mid-transfer, the gate is re-checked before every file commit
(§5). Never download anything over a metered connection. Known accepted
limitation: a hotspot fed by someone else's mobile data still appears
unmetered; no finer client-side signal exists.

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
attempt, whatever the reason. With incremental committing (§5) it only ever
holds the file or extraction currently being processed. Nothing survives
across attempts; a failed attempt keeps only what it already committed.

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
  (self-heal; in normal operation this never occurs, because sidecars are
  written from the manifest value after a successful download verification).
- ZIP file: current ⟺ the extracted directory (§6) contains a marker file
  named `<sha256>.hash` with the manifest's checksum for that ZIP. The
  marker replaces the original archive, which is never retained after
  successful extraction. A missing marker means the next sync re-downloads
  and re-extracts (self-heal after a crash mid-swap).

### Phase C — Download, verify and commit, file by file

Process the required new or modified files one at a time, in manifest order:

1. Re-check the network gate (§4). If it no longer qualifies, stop the
   attempt: keep everything already committed, delete nothing, mark the
   attempt failed.
2. Download the file completely into Temp.
3. Compute SHA-256 and compare with the manifest; on mismatch, reject the
   file and fail the attempt (no cleanup; committed work retained).
4. Commit the verified file immediately:
   - Plain file: move it from Temp into its final location (atomic rename)
     and write the `.hash` sidecar from the manifest value.
   - ZIP: perform the full ZIP handling of Phase D (extract, swap, marker,
     delete the archive).
5. Continue with the next file.

This verification is the only hashing a synchronization performs; unchanged
content is never re-hashed (§ Phase B). Never overwrite a valid existing file
with an unverified download. Because commits are immediate, a failed attempt
never re-downloads the files it already committed: they match the manifest
and are recognized as unchanged by the next attempt (§ Phase B).

### Phase D — ZIP handling (per file, as part of its commit)

For each new or modified ZIP, within its Phase C commit step:

1. Verify the original ZIP checksum (Phase C step 3).
2. Extract into a Temp directory, preserving the archive's internal
   directory structure.
3. Apply ZIP Slip protection: reject entries that attempt to write outside
   the extraction destination.
4. Apply reasonable limits to extracted size and entry count to prevent
   resource exhaustion.
5. Ensure extraction completes before touching any live directory.
6. Swap: remove the previous version of the extracted directory completely
   (never merge) and move the new directory into its place; write the
   `<sha256>.hash` marker into the new directory root; delete the ZIP. The
   ZIP itself is never retained.

Extraction always completes before any swap, so an interrupted run can only
lose content in the instant of the swap itself; that case self-heals on the
next sync (missing directory or missing marker ⇒ re-download).

### Phase E — Cleanup and completion

Once every required file has been committed (Phase C completed without
failure):

1. Compare the final local content against the authoritative manifest and
   delete obsolete files and extracted directories (including their
   markers) that no longer exist in the manifest.
2. Remove Temp and any leftovers.
3. Mark the synchronization successful.

Deletions happen only after a fully successful attempt. If any download,
verification, extraction, or commit fails — including a network-gate flip
to metered mid-attempt — no obsolete-content cleanup is performed, and the
files already committed in this attempt remain in place. An interrupted
operation must never leave the user with neither the old working version
nor the verified replacement, except for the self-healing crash windows
noted above.

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
| `.jpg`, `.jpeg`, `.png`, `.webp`, `.gif` | image viewer |
| other | let Android choose |

Use suitable MIME types and content URIs. Do not assume an application is
installed based on its name; use actual intent resolution and handle missing
handlers gracefully: show a concise installation suggestion, provide a direct
Google Play link where an appropriate application exists, and offer to retry
after installation. Suggested suggestions: EPUB → Moon+ Reader (free),
https://play.google.com/store/apps/details?id=com.flyersoft.moonreader ;
audio → foobar2000 for Android (package verified, M5). If no handler exists
and no specific suggestion applies, still show a dialog — a generic "no
application found" message with a store search and a retry. Opening a file
must never fail silently.

## 8. User interface

Compact, clear, visually restrained. The primary purpose is to show
available content and make it easy to open.

### Home

Logophilia logo (bundled assets, § Visual identity), the Wi-Fi indicator as a
large symbol — the same size as the synchronization symbol on the Sync page —
counts of available books/albums/documents, recently added content, quick
access to content categories. Home shows no separate sync symbol and no
colored status circle: sync status lives in the bottom navigation's animated
Sync icon and on the Sync page; each indicator has exactly one visual home.

"Recently added" is derived from file modification times, newest first, and
lists top-level items only: a new OST album appears once, never as its inner
files (for archive items, the extracted directory's own timestamp is used).
This is sound because synchronization never rewrites files whose checksum
matches the manifest — an mtime only changes when content actually changed
or was first downloaded. Consequences, accepted: on a fresh install
everything is recent; content is excluded from backups (§2) so restores
cannot fake recency. No added dates are stored anywhere.

### Library

Browse locally synchronized content by category: Books, Music, Documents,
Other. The listing is hierarchical: plain files appear as items; each
extracted archive appears as a single item named after the archive basename
without ".zip"; tapping it lists what it contains (`.hash` markers and
sidecars are never listed). Subdirectories inside an archive are browsed the
same way — "Back" pops one level — and every display name is the bare
file-system name, never a path: if content is nested five levels deep, the
user taps five times. Opening an item or a contained file launches the
appropriate external application (§7).

### Sync

Current synchronization state, last attempt time, error details, and a
Manual Sync button that is disabled (greyed out) while the cooldown is
running. The large synchronization symbol on this page is the only
color-coded sync indicator in the app; tapping it opens a small detail
popup: status, last error if any, and the remaining cooldown time. The
bottom navigation's Sync icon plays the rotation animation while a
synchronization runs and stays neutral otherwise, so sync activity is
visible from every tab.

### About (replaces Settings)

Application information and version. There are no user preferences.

### Visual identity

Use the actual Logophilia logo, bundled in the APK from `art/`:
`logo-light.png` (black on transparent) for light mode, `logo-dark.png`
(white on transparent) for dark mode. The app always follows the system
theme; the matching logo variant is used automatically. No logo is fetched
from the website at runtime.

Typography: the app bundles **EB Garamond** (body) and **Comfortaa**
(headings) as latin-subset variable fonts, with their OFL license files
shipped in the APK. No user-side font installation is involved.

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

### User-facing copy

All user-facing strings live in `res/values/strings.xml` — including short
words like "New", "Updated", "Removed" and the category labels. Counts use
ICU `<plurals>` ("1 document" vs "N documents"). The string inventory and
the editing workflow are documented in `docs/STRINGS.md`.

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
5. Interrupted download: previously committed files remain; nothing is
   deleted; the interrupted file is discarded with Temp.
6. Invalid checksum: downloaded file rejected; attempt fails; already-
   committed files remain.
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
16. Expired API key: the greeting probe delivers the replacement, it is
    stored, and the subsequent requests succeed. *(Deferred until the API
    implements key renewal.)*
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
25. Files committed before a mid-attempt failure (including a network-gate
    flip to metered) remain installed and are not re-downloaded by the next
    attempt.
26. An image file opens in a viewer or shows the generic "no application
    found" dialog; opening a file never fails silently.

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
