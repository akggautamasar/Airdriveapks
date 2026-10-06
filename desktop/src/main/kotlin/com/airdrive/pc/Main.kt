package com.airdrive.pc

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

const val TITLE = "AirDrive for PC"

/**
 * One program, two ways in. With no arguments it opens a window, because that is what people expect
 * from a PC app; with them it is a plain command-line run, which is what makes it scriptable from a
 * scheduled task or a shell loop. Both use the same engine and the same record file, so a run
 * started in the window can be finished from a script without duplicating anything.
 */
fun main(args: Array<String>) {
    // A self-check for the packagers and for CI: does the generated TDLib API exist here, and can the
    // JNI bridge beside it be loaded? No network, no login, nothing written.
    if (args.any { it == "--tg-probe" }) {
        val loaded = TelegramSession.loadNative { line -> println(line) }
        if (loaded) {
            println("probe ok: the Telegram half of this build is usable")
        } else {
            println("probe: TDLib's Java classes are here, but tdjni could not be loaded.")
            println("       put tdjni.dll beside airdrive-pc.jar (or on java.library.path) to upload.")
        }
        return
    }
    val cli = parseArgs(args)
    if (cli == null) {
        printHelp()
        return
    }
    if (cli.help) {
        printHelp()
        return
    }
    val wantsWindow = cli.gui || (cli.destination == null && cli.sources.isEmpty())
    if (wantsWindow && cli.telegram == null && !java.awt.GraphicsEnvironment.isHeadless()) {
        Ui(cli).show()
        return
    }
    if (cli.telegram != null && cli.destination == null) {
        println("A Telegram run still needs --dest: that is where the record of what is already")
        println("backed up is kept. Nothing is copied there, only the record file.")
        return
    }
    if (cli.destination == null || cli.sources.isEmpty()) {
        println("Nothing to do: pass --source and --dest (or run without arguments for the window).")
        println("Try --help.")
        return
    }
    runCommand(cli)
}

/** Everything the command line can say, and the same values the window starts from. */
data class Cli(
    val gui: Boolean,
    val help: Boolean,
    val sources: List<Path>,
    val destination: Path?,
    val exclusions: List<String>,
    val onlyExtensions: Set<String>,
    val maxFileBytes: Long,
    val skipHidden: Boolean,
    val dryRun: Boolean,
    val verify: Boolean,
    val telegram: TelegramCli?
)

/**
 * What `--tg` needs. The keys are Telegram's, from my.telegram.org, and are kept only in the record
 * folder's session data; nothing about them is written to the log.
 */
data class TelegramCli(
    val chat: String,
    val createTitle: String,
    val apiId: Int,
    val apiHash: String,
    val sessionDir: Path?
)

private const val MB = 1024L * 1024L

private fun parseArgs(args: Array<String>): Cli? {
    val sources = ArrayList<Path>()
    val exclusions = ArrayList<String>()
    val only = LinkedHashSet<String>()
    var destination: Path? = null
    var maxMb = 0L
    var skipHidden = true
    var dryRun = false
    var verify = true
    var tg = false
    var tgChat = ""
    var tgCreate = ""
    var apiId = System.getenv("TELEGRAM_API_ID")?.trim()?.toIntOrNull() ?: 0
    var apiHash = System.getenv("TELEGRAM_API_HASH")?.trim().orEmpty()
    var tgSession: Path? = null
    var gui = false
    var help = false
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        val next = if (index + 1 < args.size) args[index + 1] else null
        when (arg) {
            "--source", "-s" -> {
                if (next == null) return null
                sources.add(Paths.get(next))
                index++
            }
            "--dest", "-d" -> {
                if (next == null) return null
                destination = Paths.get(next)
                index++
            }
            "--exclude", "-x" -> {
                if (next == null) return null
                exclusions.add(next.lowercase())
                index++
            }
            "--only" -> {
                if (next == null) return null
                next.split(',').forEach { piece ->
                    val cleaned = piece.trim().lowercase().removePrefix(".")
                    if (cleaned.isNotEmpty()) only.add(cleaned)
                }
                index++
            }
            "--max-mb" -> {
                if (next == null) return null
                maxMb = next.toLongOrNull() ?: return null
                index++
            }
            "--tg" -> tg = true
            "--tg-chat" -> {
                if (next == null) return null
                tgChat = next
                index++
            }
            "--tg-create" -> {
                if (next == null) return null
                tgCreate = next
                index++
            }
            "--api-id" -> {
                if (next == null) return null
                apiId = next.toIntOrNull() ?: return null
                index++
            }
            "--api-hash" -> {
                if (next == null) return null
                apiHash = next
                index++
            }
            "--tg-session" -> {
                if (next == null) return null
                tgSession = Paths.get(next)
                index++
            }
            "--keep-hidden" -> skipHidden = false
            "--dry-run" -> dryRun = true
            "--no-verify" -> verify = false
            "--gui" -> gui = true
            "--help", "-h" -> help = true
            else -> {
                // A bare path is a source, so "airdrive-pc C:\Photos D:\Backup" reads as
                // "back this folder up into that one" instead of dying on an unknown option.
                if (!arg.startsWith("-") && destination == null) {
                    sources.add(Paths.get(arg))
                } else if (!arg.startsWith("-") && sources.isEmpty()) {
                    sources.add(Paths.get(arg))
                } else {
                    println("I do not understand: $arg")
                    return null
                }
            }
        }
        index++
    }
    // Two paths and no --dest: treat the last one as the destination, which is the one thing
    // almost every copy tool does and the least surprising way to be lazy on the command line.
    if (destination == null && sources.size >= 2) {
        destination = sources.removeAt(sources.size - 1)
    }
    return Cli(
        gui = gui,
        help = help,
        sources = sources,
        destination = destination,
        exclusions = exclusions,
        onlyExtensions = only.toSet(),
        maxFileBytes = maxMb * MB,
        skipHidden = skipHidden,
        dryRun = dryRun,
        verify = verify,
        telegram = if (tg) TelegramCli(tgChat, tgCreate, apiId, apiHash, tgSession) else null
    )
}

private fun askOnConsole(question: String, secret: Boolean): String? {
    val console = System.console()
    if (console != null) {
        return if (secret) {
            String(console.readPassword("$question: ") ?: CharArray(0))
        } else {
            console.readLine("$question: ")
        }
    }
    // No console: piped input still works, which is what a scheduled task or a test would use.
    println("$question: ")
    return readLine()
}

private fun runCommand(cli: Cli) {
    val telegramCli = cli.telegram
    val telegram = if (telegramCli == null) {
        null
    } else {
        if (telegramCli.apiId <= 0 || telegramCli.apiHash.isBlank()) {
            println("Telegram needs your api_id and api_hash: get both at my.telegram.org, then pass")
            println("--api-id 12345 --api-hash 0123abc... or set TELEGRAM_API_ID and TELEGRAM_API_HASH.")
            return
        }
        val sessionDir = telegramCli.sessionDir
            ?: cli.destination!!.resolve(".airdrive-telegram")
        val session = TelegramSession(
            workDir = sessionDir,
            apiId = telegramCli.apiId,
            apiHash = telegramCli.apiHash,
            applicationVersion = "pc-1.0",
            log = { line -> println(line) },
            ask = { question, secret -> askOnConsole(question, secret) }
        )
        println("Signing in to Telegram. The session lives in $sessionDir, so a second run is already signed in.")
        if (!session.awaitReady()) {
            println("Could not sign in, so nothing was uploaded.")
            return
        }
        val chat = if (telegramCli.createTitle.isNotBlank()) {
            session.createChannel(telegramCli.createTitle)
        } else {
            session.resolveChat(telegramCli.chat)
        }
        println("Uploading to ${chat.title} (chat ${chat.chatId}).")
        TelegramTarget(session, chat.chatId, chat.title)
    }
    // Telegram's own limit for a non-premium account, and a file over it fails on every run rather
    // than being tried forever. Only applied when nothing smaller was asked for.
    val effectiveMax = if (telegram != null && cli.maxFileBytes == 0L) 2000L * MB else cli.maxFileBytes
    val options = Options(
        sources = cli.sources,
        destination = cli.destination!!,
        exclusions = cli.exclusions,
        onlyExtensions = cli.onlyExtensions,
        maxFileBytes = effectiveMax,
        skipHidden = cli.skipHidden,
        dryRun = cli.dryRun,
        verify = cli.verify,
        telegram = telegram
    )
    val report = Report()
    val cancel = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(Thread { cancel.set(true) })
    println(
        "Backing up " + cli.sources.size + " folder(s) into " + options.destination +
            (if (cli.dryRun) "  (dry run, nothing will be written)" else "")
    )
    BackupEngine(options, report, cancel).run()
    report.tail(200).forEach { println("  $it") }
    println(report.human())
}

private fun printHelp() {
    println(
        """
        AirDrive for PC - back up folders the way the phone app does, with the same rules.

          (no arguments)        open the window
          --source PATH         folder to back up, repeat it for more than one
          --dest PATH           where to keep the copies
          --exclude FRAGMENT    skip anything whose path contains this, repeat as needed
          --only LIST           take only these types, comma separated (jpg,heic,png,mp4)
          --max-mb N            leave files bigger than this alone (0 = no limit)
          --dry-run             say what would be copied, write nothing
          --no-verify           do not hash copies to check them
          --keep-hidden         include folders starting with a dot, and caches
          --gui                 open the window even if paths were given

        To Telegram instead of a folder:
          --tg                  upload to Telegram; --dest then only holds the record file
          --tg-chat <chat>      a channel id, -100..., @name or a t.me link; default Saved Messages
          --tg-create <title>   make a new channel with that title and use it
          --api-id / --api-hash the api_id and api_hash from my.telegram.org (or the environment of
                                the same names); the first run asks for your phone number and the code,
                                after that the session in --tg-session is reused
          --tg-session <folder> where Telegram's own login data is kept (default <dest>/.airdrive-telegram)

        Two bare paths are read as source and destination. What has already been stored is kept in
        .airdrive-pc.tsv inside the destination, so a second run only looks at what changed. Nothing
        outside the destination is ever modified or deleted.
        """.trimIndent()
    )
}
