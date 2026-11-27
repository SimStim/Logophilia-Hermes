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
- **State:** a single persisted timestamp (`lastSyncAttempt`); no user
  preferences, no settings screens
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
  downloaded, verified, and only then committed.
- **Safe ZIP handling** — checksums are verified before extraction, ZIP Slip
  is prevented, and extraction limits guard against resource exhaustion.
- **Transactional** — a failed or interrupted sync never leaves the user
  without either the previous working version or a verified replacement;
  existing content always remains usable offline.
- **Automatic API key renewal** — when the server reports an expired mobile
  API key, the replacement key is stored and the request is retried once.
  *(Pending the matching API feature; until then an unknown key is a plain
  authentication failure.)*

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
- Next: synchronization engine (§4, §5), library UI and file opening (§6–§7).
