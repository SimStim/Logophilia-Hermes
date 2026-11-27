# Logophilia Hermes — Android Companion

A small, native Android application that connects to the existing
[Logophilia API](https://api.logophilia.eu), downloads the currently available
free content to the device, and keeps the local content synchronized with the
server.

It is primarily a convenience tool for discovering and trying newly available
content.

## What it is — and what it is not

Hermes is deliberately simple, compact, reliable, and easy to maintain.

It is **not**:

- A permanent media library
- A general-purpose file manager
- An EPUB reader, music player, or document editor (files are handed to
  installed apps via Intents and `FileProvider`)
- A replacement for downloading and keeping content permanently through the
  [Logophilia website](https://logophilia.eu)

## Technology

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Minimum Android version:** Android 10 (API 29)
- **Networking:** native Android HTTP networking / OkHttp
- **JSON:** Kotlin serialization
- **State:** a persisted timestamp (`lastSyncAttempt`) and the current API
  key; no user preferences, no settings screens
- **File sharing:** Android Intents and FileProvider

No bundled networking binaries, no Room/SQLite database, no unnecessary
libraries or services: the local filesystem and the API manifest are
sufficient for synchronization.

## Synchronization at a glance

- **Wi-Fi only** — content is never downloaded over mobile data.
- **Five-minute cooldown** between synchronization attempts, regardless of
  the previous attempt's outcome; the timestamp survives app restarts.
- **Manifest-driven** — the remote manifest with SHA-256 checksums is
  authoritative; unchanged files are left untouched, new and changed files are
  downloaded, verified, and committed one by one (incremental per-file commit:
  a failed attempt keeps what it already committed, and the network gate is
  re-checked before every commit).
- **Safe ZIP handling** — checksums are verified before extraction, ZIP Slip
  is prevented, and extraction limits guard against resource exhaustion.
- **Transactional** — a failed or interrupted sync never leaves the user
  without either the previous working version or a verified replacement;
  existing content always remains usable offline.
- **Automatic API key renewal** — the greeting hands the current key to a
  holder of a retired key (the `usha` property); the app adopts it and
  persists it immediately, so server-side rotations need no app release.
  The existing greet-first cycle is all the self-healing there is: a refused
  request simply fails, and the next permitted attempt greets first.

## Building

Requirements: JDK 17 or later **with `javac`** (JRE-only Java installs are
not enough) and an Android SDK with the API 37 platform. Set `sdk.dir` in
`local.properties` (git-ignored), e.g.:

```
sdk.dir=/home/yourname/Android/Sdk
```

Debug APK:

```
./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk
```

Release APK (signed with the keystore named in `keystore.properties`,
git-ignored; see SPECIFICATION.md §10):

```
./gradlew assembleRelease   # → app/build/outputs/apk/release/
```

The API key is read from `secrets.properties` (git-ignored) and compiled into
`BuildConfig`:

```
API_KEY=…
```

Without that file the project still builds, with an empty key.

## Status

🚧 **Implementation started** (see [SPECIFICATION.md](SPECIFICATION.md),
which supersedes [design outline.md](design%20outline.md)).

- **Milestone 1 — done.** Project scaffold: Gradle build (Kotlin DSL, AGP
  9.5.3, Gradle 9.9.2), `applicationId eu.logophilia.hermes`, minSdk 29,
  targetSdk 37, Jetpack Compose shell with the four destinations (Home,
  Library, Sync, About) as placeholders, bundled logos from `art/`,
  FileProvider stub, backup/extraction rules, secret and signing wiring.
- **Milestone 2 — done.** API client (§3): OkHttp layer with the `X-Api-key`
  header and the `X-Client` client marker (the early development `Referer`
  spoof was later replaced by the honest marker — see the Milestone 6 note);
  `GET /freeloot` with strict manifest validation; `GET /download`
  with `Content-Type` sniffing; `GET /` greeting probe; INTERNET permission.
  Verified against the live API (curl and end-to-end unit tests, including a
  checksum round-trip); the manifest validator is unit-tested offline.
- **Milestone 3 — done.** Synchronization engine (§4–§6): content layout and
  classification (Books/Music/Documents/Other, sidecars, ZIP markers), guarded
  ZIP extraction (ZIP Slip containment, entry/size limits), the engine's
  phases A–E as a transactional operation, network gate (validated unmetered
  only), five-minute cooldown persisted in SharedPreferences, single-flight,
  Temp discipline, and the app wiring (coordinator, foreground and network
  triggers). 50+ unit tests; live end-to-end sync against the real API
  including a real OST extraction (opt-in via `HERMES_LIVE_SYNC=full`).
- **Milestone 4 — done.** UI (§8): Home (logo, Wi-Fi and sync indicators,
  counts, recently added, quick access), Library (category browse; internal
  metadata never listed), Sync (state, last attempt, error detail, cooldown
  countdown, Manual Sync honoring the cooldown), About. The Wi-Fi indicator
  probes `GET /` on the §4 trigger events (red without any request when no
  qualifying network exists); the sync indicator follows the engine phases
  (blue while waiting, green animated while transferring, green/red after).
  Verified on the emulator in light and dark; see NOTES for what needs a
  real Wi-Fi device to observe.
- Next: Milestone 5 — file opening with external applications (§7):
  FileProvider Intents, MIME mapping, missing-handler suggestions.
- **Milestone 5 — done.** File opening (§7): every Library item is an openable
  file (archive directories are browsed through their contents); extension→MIME
  mapping; `ACTION_VIEW` via FileProvider content URIs with temporary read
  permission and Android 11+ `<queries>` visibility; handlers are resolved
  at tap time, never assumed. Missing handlers get a concise dialog with a
  direct Play link where one is known — Moon+ Reader for EPUB (§7),
  foobar2000 for audio (package/link verified against the developer's own
  Play redirect, §12.2) — or a Play search, plus a retry after installation.
- **Milestone 6 — done.** Release engineering (§10): the stable signing
  identity lives in a keystore outside the repository (referenced by the
  git-ignored `keystore.properties`); `./gradlew assembleRelease` produces a
  minified, V2-signed release APK (verified with `apksigner`; stable
  `applicationId eu.logophilia.hermes`, minSdk 29, targetSdk 37, ~2.8 MB).
  The release build was installed and exercised on the emulator and behaves
  like the debug build. The development `Referer` spoof that guarded early
  testing was removed on 2026-11-27, after the API implemented the client
  marker exemption (TODO.md, both repositories): every request now declares
  the app honestly with `X-Client: eu.logophilia.hermes` (§3), release
  builds carry no spoofed headers, and the distribution ban is lifted.
- All milestones complete. Remaining cross-repository work is in `TODO.md`.
- **Milestone 7 (v1.1 amendments) — done.** Spec v1.1 folded into the app:
  the network gate also accepts `TEMPORARILY_NOT_METERED` and is re-checked
  before every file commit; incremental per-file commit (a failed attempt —
  including a mid-attempt gate flip — keeps everything already committed, and
  retries never re-download committed files); indicator consolidation (the
  Wi-Fi symbol on Home at the Sync page symbol's size, color-coded sync status
  only on the Sync page, a rotating bottom-nav Sync icon while a sync runs);
  the hierarchical Library (one item per archive, browsed one level down;
  recently added lists top-level items only); image MIME types and a
  guaranteed "no application found" dialog so opening never fails silently;
  every user-facing string externalized into `res/values/strings.xml` with ICU
  plurals and documented in `docs/STRINGS.md`; and the bundled variable fonts
  (EB Garamond body, Comfortaa headings) wired into the theme, OFL licenses
  shipped in the APK.
