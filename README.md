# AirDrive

A personal Android backup app that uploads your photos, videos, documents, PDFs, audio,
call recordings, and everything else to your own private Telegram channels — no Termux,
no Python, no PC required.

## Features

- Native Android app (Kotlin, Jetpack Compose, Material 3)
- Real Telegram user-account login (phone number + code + 2FA), built on the official
  TDLib library — not a bot, so there's no 50MB upload cap
- Folder picker (Storage Access Framework) — choose exactly which folders to back up
- Automatic categorization into Photos / Videos / PDFs / Documents / Audio /
  Call Recordings / Other, each going to its own configurable Telegram channel
- Duplicate detection by content fingerprint, not just filename — renamed files aren't
  re-uploaded
- Background automatic backup (WorkManager) with Wi-Fi-only, charging-only, and
  battery-conscious options, plus a live progress notification
- Pause/resume, retry-on-failure, FloodWait-aware upload queue that survives app restarts
  and device reboots
- Per-file failure log with one-tap retry
- Persistent stats: files backed up, storage uploaded, per-category breakdown

## 1. Telegram setup

1. Get your own Telegram API credentials at **https://my.telegram.org** → API Development
   Tools → create an app. You'll get an **api_id** and **api_hash**. (Do not reuse anyone
   else's — each app should have its own.)
2. Create the Telegram channels you want AirDrive to back up into (e.g. one each for
   Photos, Videos, PDFs, Documents, Audio, Call Recordings, Other). For each channel, open
   it in Telegram → Channel info → and note its numeric ID (looks like `-100xxxxxxxxxx`;
   you can get this via any "get chat id" bot, or from the channel's invite/export link
   tools). You can also just launch AirDrive once, connect Telegram, and fill these in on
   the **Channel Configuration** screen — it defaults to placeholder IDs you must replace.
3. Make sure the Telegram account you'll log into AirDrive with is an **admin** of every
   channel it needs to post into.

## 2. GitHub setup

1. Create a new **private** GitHub repository and push this project to it:
   ```bash
   cd AirDrive
   git init
   git add .
   git commit -m "Initial AirDrive commit"
   git branch -M main
   git remote add origin https://github.com/<you>/AirDrive.git
   git push -u origin main
   ```
2. In the repo, go to **Settings → Secrets and variables → Actions** and add:
   - `TELEGRAM_API_ID` — the numeric api_id from step 1
   - `TELEGRAM_API_HASH` — the api_hash from step 1
   
   (Optional, only if you want a signed release build instead of debug: `RELEASE_KEYSTORE_B64`
   — a base64-encoded `.jks`/`.keystore` file (`base64 -w0 your.keystore`), plus
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.)
3. Go to the **Actions** tab → select **Build APK** → **Run workflow** → Run workflow.
4. **The first run will take a long time (45–90+ minutes)** — it's compiling TDLib
   (OpenSSL + the Telegram client library) from official source for every Android
   architecture using Telegram's own Docker build. This is normal and only happens once;
   the result is cached, so future runs (after you edit app code) finish in a few minutes.
5. When the run finishes (look for a green check), open the run → scroll to
   **Artifacts** → download **AirDrive-debug-apk**.

## 3. Installing the APK

1. Unzip the downloaded artifact — you'll get an `.apk` file.
2. Transfer it to your Android phone (email it to yourself, use Google Drive, ADB, etc).
3. On your phone, tap the file. If prompted, allow installs from that source
   (Settings → Apps → Special access → Install unknown apps).
4. Tap **Install**.

## 4. First launch

1. Grant the notification permission when prompted (needed for the backup progress
   notification).
2. **Connect Telegram** — enter your phone number, the login code Telegram sends you, and
   your two-step-verification password if you have one set.
3. **Choose what to back up** — toggle categories and tap **Choose Folders** to grant
   AirDrive access to specific folders via Android's folder picker. Repeat for each folder
   (e.g. `DCIM/Camera`, `Download`, `Recordings/Call`, etc).
4. AirDrive scans and shows how many files/how much data it found, then tap
   **Start First Backup**.
5. Afterwards, open the menu (⋮ on the Dashboard) → **Channel Configuration** to confirm
   or edit the Telegram channel ID each category uploads to.

## 5. Automatic backup

Configure this under Dashboard → ⋮ → **Backup Settings**:
- **Automatic backup** on/off
- **Wi-Fi only** / **Allow mobile data**
- **Charging only**
- **Battery-conscious mode** (skips runs when battery is low)
- **Backup frequency** (1–24 hours)

Because Android aggressively restricts background apps, also check your phone's battery
settings and make sure AirDrive isn't in a restricted/"optimized" battery mode
(Settings → Apps → AirDrive → Battery → **Unrestricted**), or scheduled auto-backups may
run less reliably.

## 6. Security

- Your Telegram session lives only in AirDrive's private app storage
  (`filesDir/tdlib`), which other apps cannot read, and is excluded from Android's
  auto-backup to the cloud.
- `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` are **never** committed to this repository —
  they're injected at build time from GitHub Actions secrets (or a local, gitignored
  `local.properties` for Android Studio builds — copy `local.properties.example`).
- **Never commit** a real `local.properties`, your keystore file, or any keystore
  passwords to git.
- AirDrive never deletes your original files — it only uploads copies.

## 7. Troubleshooting

- **Workflow fails at "Build TDLib for Android via official Docker build"** — this step
  compiles native code and occasionally hits transient network issues fetching sources.
  Re-run the workflow (Actions → failed run → **Re-run all jobs**).
- **Workflow fails at "Build debug APK" with a missing `org.drinkless.tdlib` class** — the
  previous step didn't produce Java bindings. Check the "Vendor TDLib" step's log for
  `Vendored Java API` output; if empty, the Docker build ran with the wrong
  `TDLIB_INTERFACE` — this repo's workflow already sets `TDLIB_INTERFACE=JAVA`, so this
  usually means an upstream tdlib/td change; open an issue in tdlib/td or pin
  `tdlib_ref` (workflow_dispatch input) to an older known-good tag.
- **App crashes immediately on launch** — double-check `TELEGRAM_API_ID` /
  `TELEGRAM_API_HASH` secrets are set correctly (an api_id of `0` will fail TDLib
  initialization).
- **Login code never arrives** — Telegram sends the first login code inside the Telegram
  app itself (as a message from "Telegram") if you're logged in elsewhere, otherwise via
  SMS.
- **Files stuck as "Pending"** — check Backup Settings constraints (Wi-Fi only/charging
  only might be blocking the run) and your phone's battery optimization setting for
  AirDrive.
- **"Failed" uploads** — open Dashboard → ⋮ → **Failed Uploads** to see the specific
  error per file and retry individually, or retry all at once.

## Project structure

```
AirDrive/
├── .github/workflows/build-apk.yml   # CI: builds TDLib from source, then the APK
├── app/
│   └── src/main/java/com/airdrive/backup/
│       ├── data/            # Room database, DataStore settings, repository
│       ├── scanner/         # SAF folder scanning + categorization
│       ├── telegram/        # TDLib client wrapper (login, upload, FloodWait)
│       ├── work/            # WorkManager background/foreground backup
│       └── ui/               # Compose screens and navigation
├── gradlew, gradlew.bat, gradle/     # Gradle wrapper (no local Gradle install needed)
└── local.properties.example          # Copy to local.properties for local dev builds
```

`org.drinkless.tdlib` (TDLib's generated Java API) and `app/src/main/jniLibs` (TDLib's
native `.so` libraries) are **not** committed — they're built fresh from official
`tdlib/td` source and copied in by the CI workflow before compilation, so nothing here
depends on a third-party prebuilt binary.
