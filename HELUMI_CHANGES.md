# Helumi-style cloud drive — what changed

Goal: make AirDrive work like Helumi (your cloud shows up as a drive in the Files app and
streams on demand) while leaving every existing feature as it was.

## New files (nothing existing is overwritten)
| File | Purpose |
|---|---|
| `drive/CloudDriveProvider.kt` | Android `DocumentsProvider`: folders by category, streamed reads, thumbnails, search, copy-in |
| `drive/CloudRangeReader.kt` | Random-access reader over a Telegram file (2 MiB chunks, LRU, background prefetch) + `MediaDataSource` adapter for video thumbnails |
| `drive/CloudInbox.kt` | Stages files copied into the drive, registers them as pending uploads, cleans up after upload |
| `drive/CloudDrivePrefs.kt` | On/off switch, cache-clearing switch; drive hidden while App Lock is on |
| `ui/screens/CloudDriveScreen.kt` | Settings → Cloud drive screen (stats, switches, "Open in Files app") |

## Existing files touched (all additive)
| File | Change |
|---|---|
| `AndroidManifest.xml` | + `<provider>` for the drive (`${applicationId}.clouddrive`) |
| `data/db/FileRecordDao.kt` | + 4 read-only queries at the end (`cloudFilesInCategory`, `cloudById`, `cloudSearch`, `cloudInboxUploaded`). No schema change, DB version stays 3, no existing query modified |
| `telegram/TdClient.kt` | + `releaseCachedFile()` (GetMessage → DeleteFile, both already used in this class), inserted before `send()` |
| `ui/nav/AppNav.kt` | + `Routes.CLOUD_DRIVE` and its `composable(...)` |
| `ui/screens/SettingsOverviewScreen.kt` | + one "Cloud drive" row under *Backup* |
| `res/xml/backup_rules.xml`, `data_extraction_rules.xml` | + exclude `tdlib-files` (streaming cache) and `cloud-inbox` (staging) from Android backup/transfer |
| `README.md` | + feature list entry and section 12b |

## Behaviours worth knowing
- Existing files are read-only in the drive (no delete/rename) — deleting from Telegram stays in *Deleted files*, which asks first.
- Copy-in rows are saved with `LocalState.UNKNOWN`, so the scanner's missing-file sweep, Storage cleanup and Deleted files screens ignore them.
- Drive is hidden while App Lock is enabled.
- Not included: end-to-end encryption (it would change what is stored in Telegram and break search, previews, restore and verification of existing backups), offline pinning, share links.

## Verification status
Not compiled or run on a device (no Android SDK/TDLib build available where this was written).
Done instead: Kotlin grammar parse of every new/changed file (the 3 parse errors in `TdClient.kt` are
identical in the original upload, i.e. grammar-parser quirks), XML validation, symbol cross-check against
the existing code, and a fuzz test of the reader's chunk arithmetic (ported to Python).
First thing to do: run the *Build APK* workflow and send me any compiler errors.
