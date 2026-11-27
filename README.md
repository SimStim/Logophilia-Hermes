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
- **Settings:** DataStore / SharedPreferences, with secure key storage backed
  by Android Keystore
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
  API key, the replacement key is stored securely and the request is retried
  once.

## Status

📝 **Design phase.** The full specification is in
[design outline.md](design%20outline.md); implementation has not started yet.
