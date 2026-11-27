# Project: Logophilia Android Companion

## Objective

Build a small, native Android application that connects to the existing Logophilia API (`https://api.logophilia.eu`), retrieves the currently available free content, downloads it to the device, and keeps the local content synchronized with the server.

The application is primarily a convenience tool for discovering and trying newly available content.

It is NOT intended to be:
- A permanent media library.
- A general-purpose file manager.
- An EPUB reader, music player, or document editor.
- A replacement for downloading and keeping content permanently through the Logophilia website.

Keep the application deliberately simple, compact, reliable, and easy to maintain.

## 1. Technology and architecture

Use native Android development:

- Language: Kotlin.
- UI: Jetpack Compose.
- Minimum Android version: Android 10 (API 29), unless a compelling compatibility reason suggests otherwise.
- Networking: native Android HTTP networking or OkHttp.
- JSON: Kotlin serialization or equivalent.
- Background/lifecycle integration: Android lifecycle APIs and ConnectivityManager.
- ZIP extraction: built-in Java ZIP utilities.
- External file access: Android Intents and FileProvider.
- Persistent settings: Android DataStore or SharedPreferences.
- Secure key storage: encrypted preferences backed by Android Keystore where practical.

Do not bundle curl or other external networking binaries. Android already provides everything required for REST communication.

No backend rewrite is required. Use the existing Logophilia API.

Do not introduce unnecessary libraries, databases, frameworks, or services.

In particular, no Room/SQLite database is needed. The local filesystem and API manifest are sufficient for synchronization.

## 2. API communication

API base URL:

`https://api.logophilia.eu`

First inspect the existing API implementation and documentation. Determine the actual endpoint paths, request formats, response formats, HTTP methods, authentication headers, and error handling.

Do not invent or assume endpoint names or response structures.

The application must:

1. Authenticate using the existing mobile API key.
2. Retrieve the current list of available content.
3. Download files using the existing download endpoint.
4. Handle HTTP errors and malformed responses gracefully.

The API key is a client credential, not a cryptographically secret application credential. Use a separate key for the mobile application, with appropriately restricted permissions and independently trackable download statistics.

Never log API keys or expose them in diagnostic messages.

Provide a way to configure or replace the initial API key on the device. Persist the key securely.

### Automatic API key renewal

The API may return a special authentication error indicating that the submitted key has expired and providing a replacement key.

Example response (illustrative; adapt to the actual API contract):

```json
{
  "success": false,
  "error": "key_expired",
  "message": "Your API key has expired.",
  "new_api_key": "NEW_MOBILE_KEY"
}
```

When this specific error is received:

1. Extract the replacement key.
2. Validate that it is present and non-empty.
3. Store it securely, replacing the previous key.
4. Retry the original API request once using the new key.

Do not attempt endless key renewal or retry loops.

If renewal fails, display a useful error message and wait until the next permitted synchronization attempt.

The server is responsible for verifying that the old key belongs to an eligible previous mobile key before returning a replacement. Do not implement a separate key-recovery endpoint.

## 3. Synchronization policy

### Triggers

Synchronization should occur automatically:

- When the application is opened.
- When the application returns to the foreground.
- When Wi-Fi connectivity becomes available while the application is already running.

No periodic background synchronization is required.

Use Android's native connectivity callbacks to detect network changes. Do not poll continuously.

### Wi-Fi restriction

The application must never download or synchronize content over mobile data.

Require an actual Wi-Fi network transport. Do not rely solely on Android's UNMETERED network classification.

If Wi-Fi is unavailable, display the existing local library and skip synchronization.

If Wi-Fi becomes available while the app is open, initiate synchronization if the cooldown permits it.

### Five-minute cooldown

Persist one timestamp: `lastSyncAttempt`.

This timestamp is updated immediately when an actual synchronization attempt begins.

A new API synchronization attempt is permitted only if at least five minutes have elapsed since the previous attempt.

This rule applies regardless of the previous attempt's outcome:

- Successful synchronization.
- Empty manifest.
- Invalid or corrupted manifest.
- Download failure.
- Checksum mismatch.
- ZIP extraction failure.
- Server error.
- Network interruption.

The reason for failure does not matter. The application must give the API at least five minutes of breathing room.

If an attempt occurred less than five minutes ago, do not contact the API.

The timestamp must survive application restarts.

If Wi-Fi is unavailable, no API attempt occurs and the timestamp is not updated.

Manual synchronization, if provided, must obey the same cooldown.

Ensure that simultaneous lifecycle and connectivity events cannot start concurrent synchronization operations.

## 4. Synchronization algorithm

The API provides a content manifest containing the available files and their SHA-256 content checksums.

The checksum is calculated over the complete original file content.

The filename may remain unchanged when the file is updated. Therefore, filename comparison alone is insufficient.

The remote manifest is authoritative.

### Phase A: Retrieve and validate manifest

1. Request the current content list.
2. Handle possible API key renewal.
3. Parse the response.
4. Validate the complete manifest structure.
5. Validate required filenames and SHA-256 values.
6. Reject malformed, incomplete, or corrupted responses.

An API error or invalid response must NEVER be interpreted as an empty content list.

Do not delete or modify existing content before a complete, valid manifest has been received.

### Phase B: Identify required downloads

Compare the manifest with the local files.

For each entry:

- If the filename exists locally and its SHA-256 matches, leave it untouched.
- If the file is missing, download it.
- If the filename exists but the SHA-256 differs, download the replacement.

Calculate local checksums where necessary. No database of previously recorded checksums is required.

### Phase C: Download and verify

Download all required new or modified files into a temporary directory.

For each file:

1. Download the complete file.
2. Calculate its SHA-256 checksum.
3. Compare the result with the checksum in the manifest.
4. Reject the download if the checksums differ.

Never overwrite a valid existing file with an unverified download.

If any required download fails, retain the existing local content and do not perform destructive cleanup.

### Phase D: ZIP processing

ZIP files must be handled automatically.

For every new or modified ZIP:

1. Verify the original ZIP checksum.
2. Extract into a temporary directory.
3. Preserve the archive's internal directory structure.
4. Apply ZIP Slip protection: reject entries that attempt to write outside the extraction destination.
5. Apply reasonable limits to extracted size and entry count to prevent resource exhaustion.
6. Ensure extraction completes successfully.

Only after successful extraction may the new extracted directory replace the previous version.

Use a backup/rollback procedure when replacing non-empty directories. Preserve the previous working version if replacement fails or is interrupted.

Retain the verified original ZIP in a private application-managed archive cache. This is necessary because the API checksum describes the original ZIP, not its extracted contents.

The archive cache is internal implementation storage and must not clutter the user-facing library.

### Phase E: Commit and cleanup

Once all required downloads have succeeded, all checksums have been verified, and all required ZIP extractions have completed successfully:

1. Move verified new files into their final locations.
2. Replace old versions with the new versions.
3. Replace extracted ZIP directories as appropriate.
4. Compare the final local content against the authoritative manifest.
5. Delete obsolete files that no longer exist in the manifest.
6. Delete obsolete extracted directories.
7. Remove unnecessary temporary files and backups.
8. Mark the synchronization as successful.

The operation should be as transactional as practical. Preserve recoverable backups until the replacement operation has completed.

If a download, verification, extraction, or commit operation fails, do not perform global obsolete-content cleanup.

An interrupted operation must never leave the user with neither the old working version nor the verified replacement.

### Empty manifest

An empty manifest is valid only when explicitly returned as a successfully validated API response.

In that case, remove all previously synchronized content after validation.

## 5. Local storage and directory structure

Use private application storage.

No external storage permissions or user interaction should be necessary.

Suggested structure:

```text
app-private/
├── Books/
├── Music/
├── Documents/
├── Other/
├── Archives/
└── Temp/
```

Content classification:

- EPUB and other book formats: Books.
- Music albums and extracted audio archives: Music.
- Text documents and HTML: Documents.
- Everything else: Other.

For ZIP files, use the archive basename as the extraction directory name.

Example:

```text
Music/
└── Album Name/
    ├── 01 - Track.opus
    ├── 02 - Track.opus
    └── Cover.jpg
```

Keep the verified original ZIP in the internal Archives directory, rather than displaying it as a separate user-facing item.

No need to expose the private directory to the user's file manager.

Files can still be opened by external applications through FileProvider.

All application-managed content is removed when the app is uninstalled. This is intentional.

## 6. Opening files with external applications

Do not implement readers or media players.

Use Android Intents to hand files to installed applications.

Use FileProvider with temporary URI permissions. Do not expose private filesystem paths directly.

Suggested handling:

| File type | Action |
|---|---|
| .epub | Open with EPUB reader |
| .opus | Open with audio player |
| .mp3, .flac, etc. | Open with audio player |
| .txt | Open with text viewer/editor |
| .md | Open with Markdown viewer/editor |
| .html, .htm | Open with browser |
| .pdf | Open with PDF reader |
| Other supported types | Let Android select an appropriate application |

Use suitable MIME types and content URIs.

If Android cannot find an application to handle a file:

- Display a concise installation suggestion.
- Provide a direct Google Play link where an appropriate application is available.
- Offer to retry opening the file after installation.

Suggested applications:

- EPUB: Moon+ Reader (free version).
  https://play.google.com/store/apps/details?id=com.flyersoft.moonreader

- Audio: foobar2000 for Android.
  Verify the current official Play Store package and link before implementation.

Do not assume that an application is installed based only on its name. Use Android's actual intent resolution and handle missing handlers gracefully.

## 7. User interface

Keep the interface compact, clear, and visually restrained.

The primary purpose is to show available content and make it easy to open.

Suggested main sections:

### Home

- Logophilia logo.
- Wi-Fi status indicator.
- Synchronization status indicator.
- Counts of available books, albums, documents, etc.
- Recently added content.
- Quick access to the main content categories.

### Library

Browse locally synchronized content by category:

- Books.
- Music.
- Documents.
- Other.

Opening an item launches the appropriate external application.

### Sync

- Current synchronization state.
- Last synchronization attempt.
- Optional progress information.
- Error details when synchronization fails.
- Manual Sync button, respecting the five-minute cooldown.

### Settings

- API key configuration/replacement.
- Automatic synchronization behaviour (the default is automatic).
- Theme selection: System default / Light / Dark.
- Optional application information and version.

Avoid unnecessary configuration options.

## 8. Visual identity and appearance

Use the actual Logophilia logo from the existing website.

The website provides lightweight logo assets with alpha transparency and separate light/dark variants.

Fetch and cache the appropriate logo. Also include a suitable fallback asset in the APK so the header works offline.

Support three appearance settings:

- System default.
- Light.
- Dark.

The selected theme must apply consistently throughout the application, including logo selection.

### Status indicators

Use compact graphical indicators rather than lengthy status text.

#### Wi-Fi indicator

- Green: Wi-Fi connected and Logophilia server reachable.
- Amber: Wi-Fi connected, but Logophilia server is unreachable.
- Red: no Wi-Fi connectivity.

Do not generate unnecessary API requests solely to test server availability. Determine server reachability through actual synchronization requests.

#### Synchronization indicator

- Blue, static: requesting the content list and waiting for the server response.
- Green, animated: receiving the manifest or downloading files.
- Green, static: synchronization completed successfully.
- Red: synchronization failed.

The sync icon should resemble the familiar two-arrow circular synchronization symbol.

Tapping a red error indicator may display the actual error message in a small dialog or expandable detail area.

Keep the main interface uncluttered.

## 9. Error handling and reliability

The application must handle:

- No Wi-Fi.
- Wi-Fi without internet access.
- API server unavailable.
- API authentication errors.
- API key renewal.
- Malformed JSON.
- Invalid or incomplete manifests.
- Interrupted downloads.
- SHA-256 mismatches.
- ZIP extraction failures.
- Insufficient storage.
- Missing external applications.
- Interrupted application execution.

Existing valid local content must remain usable offline.

Never delete local content merely because an API request failed.

Never overwrite valid content with an unverified file.

Never run simultaneous synchronization operations.

Respect the five-minute cooldown in every failure scenario.

## 10. Testing and acceptance criteria

The application is considered ready when the following scenarios work:

1. Fresh installation, valid API key, Wi-Fi available: retrieves and displays the content list.
2. Missing files: downloads and verifies them.
3. Existing file with matching SHA-256: does not download it again.
4. Existing filename with different SHA-256: downloads, verifies and replaces it.
5. Interrupted download: retains the previous valid file.
6. Invalid checksum: rejects the downloaded file.
7. ZIP download: verifies and safely extracts into the correct directory.
8. Interrupted ZIP extraction: preserves the previous working directory.
9. File removed from server: removes local copy after a successful synchronization.
10. Corrupted manifest: preserves all existing content.
11. Server unavailable: preserves content and displays appropriate status.
12. App reopened within five minutes: makes no API request.
13. App reopened after five minutes: attempts synchronization.
14. Wi-Fi disconnected: does not synchronize.
15. Wi-Fi restored while app is open: initiates synchronization if cooldown permits.
16. Expired API key: receives replacement, stores it, retries successfully.
17. Unrecognized/revoked API key: reports authentication failure without endless retries.
18. EPUB without installed reader: offers Moon+ Reader installation.
19. Audio without installed player: offers a suitable installation suggestion.
20. Light, dark and system theme settings work.
21. All synchronized content remains available offline.
22. No mobile-data downloads occur under any circumstances.

## 11. Development instructions

Before implementation:

1. Inspect the existing API implementation and documentation.
2. Inspect the existing Logophilia website for logo assets and actual download-section content.
3. Document the real API contract and any assumptions.
4. Check the target Android SDK and current recommended Android APIs.
5. Create a clean, independent Android project.

Implement incrementally, but deliver a complete working application.

Provide:

- Complete source code.
- Gradle build configuration.
- Android manifest and required resources.
- FileProvider configuration.
- Build instructions.
- A reproducible debug APK.
- A release-build configuration.
- A concise README.

Do not use mock API responses in the finished application.

Do not leave TODO placeholders for essential functionality.

Do not silently omit error handling or security safeguards.

Prefer simple, readable, maintainable code over clever abstractions.

Test the actual application against the real API wherever possible. If credentials or device access are unavailable, clearly identify what remains untested rather than claiming success.

**The guiding principle: build the smallest reliable application that accomplishes the stated purpose. Do not add features merely because Android makes them possible.**
