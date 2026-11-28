# STRINGS — every user-facing string of Hermes

All user-facing copy lives in `app/src/main/res/values/strings.xml`
(SPECIFICATION.md §8, "User-facing copy"). This document lists every string
with where it appears, so wording can be tuned by hand without a session.

## Editing workflow

1. Open `app/src/main/res/values/strings.xml` and change the wording.
   - `%1$s`, `%2$s`, … are placeholders filled by the code; keep them.
   - `<plurals>` entries use `%d`; the count is passed both as the quantity
     selector and as the pattern argument (see `quantityString` in
     `HermesApp.kt`).
   - `…` is a real ellipsis character; keep it.
2. Build and install onto a connected device/emulator:

   ```sh
   ./gradlew installDebug
   ```

3. Launch the app (launcher icon "Logophilia Hermes") and check the changed
   wording on screen. `adb logcat -s hermes-sync` shows sync activity if you
   need to trigger states (a real sync needs a validated unmetered network).

Note: `installDebug` replaces an installed debug build; a release build must
be uninstalled first (`adb uninstall eu.logophilia.hermes`) because of the
different signing identity.

## Inventory

### Identity

| Name | Value | Where it appears |
|---|---|---|
| `app_name` | Logophilia Hermes | Launcher/system label (AndroidManifest), logo `contentDescription` (Home and About) |
| `about_name` | Hermes | About page title, shown beneath the logophilia logo (§8 About — the logo already carries the wordmark) |

### Bottom navigation tabs

| Name | Value | Where it appears |
|---|---|---|
| `tab_home` | Home | Bottom nav label, Home icon description |
| `tab_library` | Library | Bottom nav label, Library icon description |
| `tab_sync` | Sync | Bottom nav label, Sync icon description |
| `tab_about` | About | Bottom nav label, About icon description |

### Content category labels (§8)

| Name | Value | Where it appears |
|---|---|---|
| `category_books` | Books | Home category chips, Library filter chips |
| `category_music` | Music | Home category chips, Library filter chips |
| `category_documents` | Documents | Home category chips, Library filter chips |
| `category_other` | Other | Home category chips, Library filter chips |

### Home (§8)

| Name | Value | Where it appears |
|---|---|---|
| `home_count_books` (plurals) | 1 book / %d books | Home counts list, one line per category |
| `home_count_albums` (plurals) | 1 album / %d albums | Home counts list |
| `home_count_documents` (plurals) | 1 document / %d documents | Home counts list |
| `home_count_other` (plurals) | 1 other item / %d other items | Home counts list |
| `home_recently_added` | Recently added | Home "Recently added" heading |
| `home_nothing_yet` | Nothing synchronized yet. | Home counts line before the first successful sync |

### Library (§8)

| Name | Value | Where it appears |
|---|---|---|
| `library_empty` | Nothing here yet — synchronize to fill this category. | Library list when a category is empty |
| `library_back` | Back | The back arrow's `contentDescription` when browsing inside an archive (the arrow icon replaces the word on screen) |

### Opening files with external applications (§7)

| Name | Value | Where it appears |
|---|---|---|
| `open_missing_title` | No app can open this | Missing-handler dialog title |
| `open_missing_with_suggestion` | No application for this file type is installed. %1$s can open it. | Dialog body when a concrete suggestion exists (`%1$s` = app name) |
| `open_missing_generic` | No installed application can open this file. | Generic dialog body — shown whenever no suggestion applies; opening never fails silently (§11: 26) |
| `open_install` | Install %1$s | Dialog button with a direct Play link (`%1$s` = app name) |
| `open_search` | Search on Google Play | Dialog button when no dedicated application is suggested |
| `open_retry` | Retry | Dialog button re-resolving the intent after installation |
| `open_cancel` | Cancel | Dialog dismiss button |
| `open_app_moonreader` | Moon+ Reader | The suggested EPUB reader's name (§7) |
| `open_app_foobar2000` | foobar2000 | The suggested audio player's name (§7, package verified M5) |

### Sync page (§8)

| Name | Value | Where it appears |
|---|---|---|
| `sync_state_idle` | Ready to synchronize | Sync symbol text before the first attempt |
| `sync_state_contacting` | Contacting server… | While the manifest request waits (blue) |
| `sync_state_receiving` | Receiving content… | While files are downloading (green animated) |
| `sync_state_committing` | Committing… | While verified files commit / cleanup runs |
| `sync_state_success` | Synchronized | After success; its own line, with the three plurals below listed one per line (like the Home page's lists) |
| `sync_count_new` (plurals) | 1 new / %d new | "new" count line under "Synchronized" |
| `sync_count_updated` (plurals) | 1 updated / %d updated | "updated" count line under "Synchronized" |
| `sync_count_removed` (plurals) | 1 removed / %d removed | "removed" count line under "Synchronized" |
| `sync_state_failure` | Synchronization failed | After a failed attempt (details in the error card) |
| `sync_last_attempt` | Last attempt: %1$s | Sync page, timestamp of the last attempt |
| `sync_never_attempted` | Not synchronized yet | Sync page before any attempt |
| `sync_cooldown` | Next attempt possible in %1$s | Sync page and popup during the cooldown (`%1$s` = m:ss) |
| `sync_manual` | Sync now | Manual Sync button |
| `sync_desc_idle` / `sync_desc_running` / `sync_desc_done` / `sync_desc_failed` | Synchronization: … | Sync symbol `contentDescription` (accessibility) |

### Popups

| Name | Value | Where it appears |
|---|---|---|
| `popup_ok` | OK | Popup confirm button |
| `popup_sync_title` | Synchronization | Sync detail popup title |
| `popup_wifi_title` | Server reachability | Wi-Fi detail popup title |
| `sync_detail_error` | Last error: %1$s | Sync popup error line (`%1$s` = technical detail, see below) |

### Wi-Fi indicator (§8)

| Name | Value | Where it appears |
|---|---|---|
| `wifi_desc_neutral` | Server reachability unknown | Neutral state (before the first probe) |
| `wifi_desc_red` | No unmetered network | Red state description |
| `wifi_desc_amber` | Server unreachable | Amber state description |
| `wifi_desc_green` | Server reachable | Green state description |
| `wifi_detail_red` | No validated unmetered network. Hermes waits for one and never downloads over mobile data. | Red popup text |
| `wifi_detail_amber` | An unmetered network is present, but api.logophilia.eu did not answer successfully (%1$s). | Amber popup text (`%1$s` = one of the reasons below) |
| `wifi_detail_green` | api.logophilia.eu answered successfully. | Green popup text |
| `wifi_reason_unreachable` | unreachable | Amber reason: the greeting request never completed |
| `wifi_reason_auth` | authentication rejected | Amber reason: HTTP 401/403 |
| `wifi_reason_server` | server error | Amber reason: any other unsuccessful greeting |

### About (§8)

| Name | Value | Where it appears |
|---|---|---|
| `about_description` | A companion for the Logophilia free loot: … | About page description |
| `about_version` | Version %1$s | About page version line |

## What is deliberately *not* in strings.xml

- **Engine and API diagnostics** (`SyncEngine`, `LogophiliaApi`, `ZipExtractor`
  messages): technical failure details shown as "Last error: …" on the Sync
  page. They are diagnostic output for support, not UI copy; localizing them
  would put resource lookups into the offline engine.
- **Play Store URLs and search terms** (`OpenWith`): URL construction, not
  display copy.
- **File and directory names**: content, sync metadata (`.hash`), categories
  on disk — not shown as copy anywhere.
