# AirDrive

A personal Android backup app that uploads your photos, videos, documents, PDFs, audio,
call recordings, and everything else to **your own Telegram** — Saved Messages or private
channels you control. No Termux, no Python, no PC, and no third-party server: your phone
talks straight to Telegram using your own Telegram API keys.

## Features

### Backup

- Native Android app (Kotlin, Jetpack Compose, Material 3)
- Real Telegram user-account login (phone number + code + 2FA), built on the official
  TDLib library — not a bot, so there is no 50 MB upload cap
- **Bring your own API keys** — enter your `api_id`/`api_hash` from my.telegram.org inside
  the app; nothing has to be baked into the build and the keys never leave the phone
- **Three destination modes**, switchable at any time without losing anything:
  - **Saved Messages** — zero setup, no channels to create (the default on a fresh install)
  - **One channel or chat** — everything goes to a single place
  - **A channel per file type** — the seven-category layout, each category configurable
- **No folder picking** — grant All files access once and AirDrive scans every folder in
  internal storage (and the SD card, if you want), including folders that appear later
- Automatic categorization into Photos / Videos / PDFs / Documents / Audio /
  Call Recordings / Other
- Duplicate detection by content fingerprint, not just filename — renamed files are not
  re-uploaded
- Background automatic backup (WorkManager) with a three-way network policy
  (Wi-Fi only / not roaming / any), charging-only and battery-conscious options, plus a
  live progress notification
- Pause/resume, retry-on-failure, FloodWait-aware upload queue that survives app restarts
  and device reboots

### Customization

- **Skip folders** by path fragment — anything matching is never scanned
- **Maximum file size** — skip anything bigger instead of failing on it repeatedly
- **Upload order** — oldest first, newest first, or smallest first (smallest first clears a
  huge queue fastest)
- **Caption template** with `{name} {date} {size} {folder} {path} {category} {ext}`
  placeholders, so the message in Telegram is searchable the way you want it
- Toggle any category off, include or exclude files under 1 KB, auto-retry failures

### Getting things back out

- **Restore from Telegram** — search everything you have uploaded and download any file
  back to `Downloads/AirDrive`, with live progress
- **Export** a CSV manifest of every backed-up file, or a text dump of your settings
- **Activity** screen with name search and status filters (uploaded / failed / uploading /
  pending / skipped), plus a per-file failure log with one-tap retry
- **Test destination** resolves whatever you configured against your Telegram account and
  reports the real chat title, or the exact reason it failed, before a backup runs

## 1. Get your Telegram API keys

1. Sign in at **https://my.telegram.org** → **API Development Tools** → create an app.
2. Note the **api_id** (a number) and the **api_hash** (a 32-character string). Keep them to
   yourself: they identify your app to Telegram.
3. You do **not** need to create any channels. AirDrive defaults to your Telegram Saved
   Messages, which already exists on every account. Channels are opt-in (see section 4).

## 2. Build the APK with GitHub Actions

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
2. Optional: in the repo, go to **Settings → Secrets and variables → Actions** and add
   `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. That only pre-fills the build so you do not
   have to type the keys on the phone. **Skip it and the app asks for them on first
   launch** — which is what you want if anyone else will install the same APK.

   (Also optional, only for a signed release build instead of debug: `RELEASE_KEYSTORE_B64`
   — a base64-encoded `.jks`/`.keystore` file (`base64 -w0 your.keystore`), plus
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.)
3. Go to the **Actions** tab → select **Build APK** → **Run workflow** → Run workflow.
4. **The first run takes a long time (45–90+ minutes)** — it compiles TDLib (OpenSSL plus
   the Telegram client library) from official source for every Android architecture using
   Telegram's own Docker build. This is normal and happens once; the result is cached, so
   later runs finish in a few minutes.
5. When the run finishes (green check), open it → **Artifacts** → download
   **AirDrive-debug-apk**.

## 3. Install and first launch

1. Unzip the artifact, transfer the `.apk` to your phone, tap it, and allow installs from
   that source if prompted (Settings → Apps → Special access → Install unknown apps).
2. Grant the notification permission — the backup progress notification needs it.
3. **Telegram API keys** — if the build shipped without keys, AirDrive opens a screen asking
   for your `api_id`/`api_hash` and links to my.telegram.org. Paste them in and tap **Save
   and connect**. You can change them later at ⋮ → Backup settings → **Telegram API keys**.
4. **Connect Telegram** — phone number, the login code Telegram sends, and your two-step
   verification password if you have one set.
5. **Allow storage access** — tap **Grant all files access**, turn AirDrive on in the Android
   screen that opens, and come back; AirDrive notices the grant and scans every folder in
   internal storage on its own. Two switches control the scope: *Scan every folder on the
   phone* and *Include SD card / USB storage*, both on by default. If you would rather not
   grant it, use **Choose folders** and AirDrive backs up only the trees you pick.
6. AirDrive scans, shows how many files and how much data it found, and offers a
   destination. **Use Saved Messages** needs nothing else; **Choose a channel instead**
   opens the destination screen. Then tap **Start First Backup**.

Android shows no popup for All files access — it is a Settings toggle. Until it is on,
AirDrive only sees folders you picked by hand, and the Dashboard says so.

## 4. Where backups go

Dashboard → ⋮ → **Backup destination**:

- **Saved Messages** — your own Telegram cloud storage. Nothing to create, nothing to
  configure, nobody else can see it. This is the default on a fresh install.
- **One channel or chat** — paste an ID (`-1004291403787`, `1004291403787`, or the bare
  `4291403787`), an `@username`, a `t.me/…` link, or a private invite link, and tap **Use
  this chat**. Or type a name, tap **Create a channel for me**, and AirDrive creates a
  private channel and points itself at it.
- **A channel per file type** — the original seven-channel layout, unchanged. Tap **Set up
  the seven channels** to fill them in; every row accepts the same formats as above, has its
  own **Create** button, and there is a **Create channels for the empty ones** shortcut.
  Editing a row re-points files already queued for that category, so you can fix a wrong ID
  and retry without re-scanning.

Whatever you pick, **Test destination** resolves it against your account and reports the
real chat title, or the exact reason it failed, before you start a backup. If you use
channels, the account you log in with must be an **admin** of them.

## 5. Customizing what gets backed up

Dashboard → ⋮ → **Backup settings** → **Scan rules, captions and export**:

- **Skip these folders** — add a path fragment (for example
  `WhatsApp/Media/WhatsApp Stickers`) and nothing matching it is ever scanned.
- **Maximum file size** — leave it empty for no limit. Oversized files are skipped instead
  of failing over and over; Telegram itself refuses anything over 4 GB regardless.
- **Upload order** — oldest first, newest first, or smallest first.
- **Caption template** — the message text posted with each file. Placeholders: `{name}`,
  `{date}`, `{size}`, `{folder}`, `{path}`, `{category}`, `{ext}`. **Reset** restores the
  default. Captions are how you find things later with Telegram's own search.
- **Export** — a CSV manifest of every backed-up file (name, category, size, timestamp, chat
  id, message id, source path) or a text dump of your settings, both written to
  `Downloads/AirDrive`.
Categories themselves are toggled in **Backup settings**, next to *Include files under 1 KB*
and *Retry failed files automatically*.

## 6. Getting files back

Dashboard → ⋮ → **Restore from Telegram**. Search by name across everything AirDrive has
uploaded, tap **Restore** on a row, and the file is downloaded from Telegram into
`Downloads/AirDrive` with a live progress bar. A backup never touches the originals on the
device, so restore is for phones that were wiped, files you deleted yourself, or pulling one
old file back without digging through Telegram.

## 7. Automatic backup

Dashboard → ⋮ → **Backup settings**:

- **Automatic backup** on/off, and **Backup frequency** (1–24 hours)
- **Upload over** — *Wi-Fi only*, *Wi-Fi or mobile data but not roaming*, or *Any
  connection*. This applies to **Back up now** as well, not only scheduled runs.
- **Charging only** and **Battery-conscious mode** (skips runs on a low battery)
- **Storage** — the same whole-device / SD-card switches as onboarding, a line showing which
  roots are actually being scanned, and a link back to Android's All files access screen if
  the permission was revoked

When nothing is running but files are queued, the progress screen names what it is waiting
for — Wi-Fi, a non-roaming connection, a charger — instead of sitting silently at zero.

Because Android aggressively restricts background apps, also set AirDrive's battery mode to
**Unrestricted** (Settings → Apps → AirDrive → Battery), or scheduled runs fire less
reliably.

## 8. Security

- Your Telegram session lives only in AirDrive's private app storage (`filesDir/tdlib`),
  which other apps cannot read, and is excluded from Android's cloud auto-backup.
- API keys you type into the app are stored in AirDrive's private DataStore on the phone and
  are sent only to Telegram, as part of the connection Telegram requires them for.
- `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` are **never** committed to this repository. If you
  use them at all, they are injected at build time from GitHub Actions secrets or a local,
  gitignored `local.properties` (copy `local.properties.example`).
- **Never commit** a real `local.properties`, your keystore, or keystore passwords.
- AirDrive never deletes your original files — it only uploads copies.
- Files go to your own account only: Saved Messages, or chats you configured yourself. There
  is no AirDrive server and no telemetry.
- AirDrive declares `MANAGE_EXTERNAL_STORAGE` (All files access) because backing up every
  folder without hand-picking each one is not possible any other way on modern Android.
  Nothing is uploaded except files in the categories you enable, and the permission is
  optional — decline it and the app falls back to folders you pick. That permission is also
  why AirDrive is meant to be built and installed by you for your own phone; Google Play
  grants it only to apps whose core function requires it.
- `Android/data` and `Android/obb` are skipped (Android blocks them anyway), as are hidden
  files and folders.
## 9. Troubleshooting

- **Workflow fails at "Build TDLib for Android via official Docker build"** — that step
  compiles native code and occasionally hits transient network issues fetching sources.
  Re-run the workflow (Actions → failed run → **Re-run all jobs**).
- **Workflow fails at "Build debug APK" with a missing `org.drinkless.tdlib` class** — the
  previous step produced no Java bindings. Check the "Vendor TDLib" step's log for
  `Vendored Java API`; if it is empty, the Docker build ran with the wrong
  `TDLIB_INTERFACE` — this repo's workflow already sets `TDLIB_INTERFACE=JAVA`, so this
  usually means an upstream tdlib/td change; pin `tdlib_ref` (a workflow_dispatch input) to
  an older known-good tag.
- **"AirDrive needs your own Telegram API keys"** — the build shipped without keys, which is
  normal if you skipped the secrets. Tap **Add Telegram API keys** and paste them in. An
  api_id of `0` or a truncated api_hash is rejected before TDLib ever sees it.
- **Login code never arrives** — Telegram sends the first code inside the Telegram app itself
  (a message from "Telegram") if you are logged in elsewhere, otherwise by SMS.
- **Uploads fail with "Chat not found"** — the chat ID is wrong, or the account you logged in
  with is not a member/admin of it. Open **Backup destination** → **Test destination**, or
  **Channel configuration** → **Test all channels**: each row reports the chat's title or the
  reason it failed. If you would rather not deal with IDs at all, switch to **Saved
  Messages**, which cannot be misconfigured.
- **Nothing is found outside the folders I picked** — All files access is off. ⋮ → **Backup
  settings** → Storage → **Manage storage access**, turn AirDrive on, come back, and the next
  scan picks up the whole device.
- **Files stuck as "Pending"** — check the *Upload over* and *Charging only* constraints plus
  your phone's battery optimization for AirDrive. The progress screen names the constraint it
  is waiting on.
- **"Failed" uploads** — ⋮ → **Failed uploads** shows the error per file, with individual or
  bulk retry. When many files share one error, a banner summarizes it and links to the screen
  that fixes it.
- **A folder I do not want keeps getting backed up** — add a fragment of its path under
  **Scan rules, captions and export** → *Skip these folders*.

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
│       └── ui/              # Compose screens and navigation
├── gradlew, gradlew.bat, gradle/     # Gradle wrapper (no local Gradle install needed)
└── local.properties.example          # Copy to local.properties for local dev builds
```

`org.drinkless.tdlib` (TDLib's generated Java API) and `app/src/main/jniLibs` (TDLib's native
`.so` libraries) are **not** committed — they are built fresh from official `tdlib/td` source
and copied in by the CI workflow before compilation, so nothing here depends on a
third-party prebuilt binary.
