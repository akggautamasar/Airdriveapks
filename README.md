# AirDrive

A personal Android backup app that uploads your photos, videos, documents, PDFs, audio,
call recordings, and everything else to your own private Telegram channels — no Termux,
no Python, no PC required.

## Features

- Native Android app (Kotlin, Jetpack Compose, Material 3)
- Real Telegram user-account login (phone number + code + 2FA), built on the official
  TDLib library — not a bot, so there's no 50MB upload cap
- **No folder picking** — grant All files access once and AirDrive scans every folder in
  internal storage (and the SD card, if you want), including new folders that appear later.
  The Storage Access Framework folder picker is still there as an optional extra for
  anything outside that.
- Automatic categorization into Photos / Videos / PDFs / Documents / Audio /
  Call Recordings / Other, each going to its own configurable Telegram channel
- **Test all channels** button that resolves every configured channel ID against your
  Telegram account and tells you exactly which one is wrong before you start a backup
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
3. **Allow storage access** — tap **Grant all files access**, which opens Android's
   "All files access" screen; turn AirDrive on there and come back. AirDrive detects the
   grant as soon as you return, then scans every folder in internal storage on its own.
   Two switches on the same screen control the scope:
   - **Scan every folder on the phone** (on by default)
   - **Include SD card / USB storage** (on by default, ignored if you have none)

   Toggle off any categories you don't want, then tap **Continue** — nothing is blocked on
   picking folders. If you'd rather not grant All files access, use the optional
   **Choose folders** link instead and AirDrive backs up only the trees you pick.
4. AirDrive scans and shows how many files/how much data it found, then tap
   **Start First Backup**.
5. Open the menu (⋮ on the Dashboard) → **Channel Configuration**, paste in the channel ID
   for each category, and tap **Test all channels** — every row should come back with the
   channel's title. Fix any that report an error before the first backup, otherwise those
   files fail with "Chat not found".

Android does not show a normal permission popup for All files access; it is a Settings
toggle. Until it is on, AirDrive can only see folders you picked by hand, and the
Dashboard shows a warning saying so.

## 5. Automatic backup

Configure this under Dashboard → ⋮ → **Backup Settings**:
- **Automatic backup** on/off
- **Wi-Fi only** / **Allow mobile data**
- **Charging only**
- **Battery-conscious mode** (skips runs when battery is low)
- **Backup frequency** (1–24 hours)
- **Storage** — the same *Scan every folder on the phone* / *Include SD card* switches as
  onboarding, plus a line showing which roots are actually being scanned and a link to
  re-open Android's All files access screen if the permission got revoked.

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
- AirDrive declares `MANAGE_EXTERNAL_STORAGE` (All files access) because backing up every
  folder without hand-picking each one is not possible any other way on modern Android.
  Nothing is uploaded except files in the categories you enable, and the permission is
  never required — decline it and the app falls back to folders you pick yourself. Note
  that this permission is why AirDrive is meant to be built and installed by you for your
  own phone; Google Play grants it only to apps whose core function requires it.
- `Android/data` and `Android/obb` are skipped (Android blocks them anyway) and so are
  hidden files and folders.

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
- **Every upload fails with "Chat not found"** — the channel ID is wrong, or the account you
  logged in with isn't a member of that channel. Open Dashboard → ⋮ → **Channel
  Configuration** and tap **Test all channels**: each row reports either the channel's title
  or the reason it failed. Channel IDs look like `-1004291403787`; AirDrive also accepts
  `1004291403787` or the bare `4291403787` that some clients show and normalizes them on
  save. Editing a channel ID re-points files already queued for that category, so you can
  fix the ID and retry without re-scanning.
- **Nothing is found outside the folders I picked** — All files access is off. Dashboard →
  ⋮ → **Backup Settings** → Storage → **Manage storage access**, turn AirDrive on, and come
  back; the next scan picks up the whole device.
- **Files stuck as "Pending"** — check Backup Settings constraints (Wi-Fi only/charging
  only might be blocking the run) and your phone's battery optimization setting for
  AirDrive.
- **"Failed" uploads** — open Dashboard → ⋮ → **Failed Uploads** to see the specific
  error per file and retry individually, or retry all at once. When many files share one
  error, a banner at the top summarizes it and links straight to the screen that fixes it.

## Project structure

```
AirDrive/
├── .github/workflows/build-apk.yml   # CI: builds TDLib from source, then the APK
├── app/
│   └── src/main/java/com/airdrive/backup/
│       ├── data/            # Room database, DataStore settings, repository
│       ├── scanner/         # Whole-device + SAF file walk, categorization, fingerprints
│       ├── telegram/        # TDLib client wrapper (login, chat resolution, upload)
│       ├── util/            # All-files-access helpers, scan roots
│       ├── work/            # WorkManager background/foreground backup
│       └── ui/               # Compose screens and navigation
├── gradlew, gradlew.bat, gradle/     # Gradle wrapper (no local Gradle install needed)
└── local.properties.example          # Copy to local.properties for local dev builds
```

`org.drinkless.tdlib` (TDLib's generated Java API) and `app/src/main/jniLibs` (TDLib's
native `.so` libraries) are **not** committed — they're built fresh from official
`tdlib/td` source and copied in by the CI workflow before compilation, so nothing here
depends on a third-party prebuilt binary.
