# AirDrive for PC

The same idea as the phone app, for a computer: point it at the folders that matter, tell it where
to keep the copies, and it walks the tree once, skipping what you exclude and never copying the same
thing twice. It is a separate Java program, not the APK running on an emulator — no Android SDK, no
phone, no Play Store, and nothing in the phone build depends on it.

## Getting it

Every push that touches `desktop/**` publishes a prerelease tagged `pc-<commit>` with two zips:

- **`AirDrive-PC-windows.zip`** — a self-contained Windows app. Unzip it anywhere and run
  `AirDrive-PC/AirDrive-PC.exe`. Java is not needed.
- **`airdrive-pc.zip`** — the portable build for Windows, macOS and Linux. Unzip it and run
  `airdrive-pc/bin/airdrive-pc.bat` (Windows) or `airdrive-pc/bin/airdrive-pc` (macOS, Linux).
  Needs Java 17 or newer.

## Building it yourself

From the repository root — this only needs a JDK, not the Android SDK:

```
./gradlew -p desktop installDist     # build/install/airdrive-pc/bin/airdrive-pc
./gradlew -p desktop distZip         # build/distributions/airdrive-pc.zip
```

## The window

Add folders, pick a destination, list the paths to skip (one per line — any part of a path works, so
`downloads/2019` or `onedrive` both do the job), set a size cap if you want big files left alone, and
name the types you care about (`jpg,heic,png,mp4`) if you only want photos and videos.
**Dry run** shows exactly what would be copied and writes nothing; use it first if you are unsure.
**Check copies** hashes each file after writing it, so a half-written copy is thrown away instead of
being trusted. Your choices are remembered for next time.

## The command line

```
airdrive-pc --source "D:/Photos" --dest "E:/Backup" --exclude onedrive --max-mb 2000
airdrive-pc "D:/Photos" "E:/Backup" --dry-run
airdrive-pc --source "D:/Photos" --dest "E:/Backup" --only jpg,jpeg,heic,png,mp4
airdrive-pc --help
```

Two bare paths are read as source then destination, which is enough for a quick run. Any number of
`--source` and `--exclude` arguments is allowed. Exit code is 0 when the walk finished, whether or
not individual files failed — the log names every failure instead of stopping the run.

## Backing up to Telegram instead of a folder

```
airdrive-pc --source "D:/Photos" --dest "E:/Backup" --tg --tg-chat @airdrive_photos --api-id 123456 --api-hash abc123
airdrive-pc --source "D:/Photos" --dest "E:/Backup" --tg --tg-create "AirDrive PC" --dry-run
```

`--tg` uploads each file as a document to the chat you name — a channel id, `-100…`, `@username`, a
`t.me/…` link, or nothing at all for Saved Messages — instead of copying it anywhere. The rules are the
same ones the folder backup uses: the same exclusions, the same `--only` list, the same skipped hidden
folders, and the same record file in `--dest`, which is why a second run only looks at what changed. The
record notes the chat and message id of each upload (`tg:<chat>:<message>`) rather than a path.

First run signs in like any other Telegram client: it asks for your phone number, then the code Telegram
sends, then your two-step password if you have one. That needs your own `api_id` and `api_hash`, from
<https://my.telegram.org>, and they can come from the `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`
environment instead of the command line, where they would sit in a shell history. The login then lives
in `<dest>/.airdrive-telegram`, so the next run is already signed in — which is also why a run that is
interrupted can be started again without doing anything twice. Files over 2 GB are skipped unless you
set a smaller `--max-mb`: that is Telegram's own limit for a normal account.

The window does not do Telegram yet; it is a command-line option for now.

Building this needs TDLib's generated Java API, which tdlib/td publishes nowhere: it is cross-built by
`tools/build-tdlib-windows.sh` in CI and committed as `libs/tdlib.jar`, and `tools/fetch-tdlib-windows.sh`
brings back the native bridge (`tdjni.dll`) that the upload actually talks to Telegram with. Without that
DLL the program still builds and backs up to folders; `--tg-probe` tells you which half it has.

## How it decides what to copy

Half-written copies never sit beside your files: they are written into `.airdrive-tmp` in the
destination and moved into place once complete, and that folder is emptied at the start and end of a
run, so a killed run leaves nothing behind but the record it had already saved.

`.airdrive-pc.tsv` sits inside the destination and records, per file: where it came from, its size,
its last-modified time, and where it was stored. A file whose size and timestamp match the record is
counted **unchanged** and not read again — that is why the second run of 300 GB takes seconds. Change
a file and the new copy **replaces** the one the record points at, so an updated file does not pile up
duplicates; a name clash between two different files gets a number instead of an overwrite.

What it deliberately does **not** do:

- **Nothing outside the destination is modified, moved or deleted.** No source file is ever touched,
  and files that vanished from the source are left exactly where they are. If you want the
  destination trimmed, do it by hand: a tool that deletes is one you have to trust completely.
- **No compression, encryption or versioning.** Copies are byte-identical files in a folder tree you
  can open with Explorer or Finder at any time. Because an updated file replaces its own copy, an
  older version is only kept when the clash rule said so.
- **No Telegram upload yet.** `tdlib/td` publishes no prebuilt binaries — `releases` is empty — so
  getting PC files into Saved Messages means building TDLib for Windows in CI (`tdjson.dll`), which is
  a job of its own, then the login flow and the upload client on top. That is the one piece of the
  phone app that does not carry over for free.

Long paths on Windows are trimmed by the tool where it can, but Windows still dislikes paths over 260
characters. If a deep tree reports failures, back up a shallower folder or enable `LongPathsEnabled`.

## Privacy

It reads only the folders you add, writes only into the destination, and makes no network connection
at all. Nothing is uploaded anywhere.
