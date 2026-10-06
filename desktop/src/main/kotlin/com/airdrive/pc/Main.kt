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
    if (wantsWindow && !java.awt.GraphicsEnvironment.isHeadless()) {
        Ui(cli).show()
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
    val verify: Boolean
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
        verify = verify
    )
}

private fun runCommand(cli: Cli) {
    val options = Options(
        sources = cli.sources,
        destination = cli.destination!!,
        exclusions = cli.exclusions,
        onlyExtensions = cli.onlyExtensions,
        maxFileBytes = cli.maxFileBytes,
        skipHidden = cli.skipHidden,
        dryRun = cli.dryRun,
        verify = cli.verify
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

        Two bare paths are read as source and destination. What has already been stored is kept in
        .airdrive-pc.tsv inside the destination, so a second run only looks at what changed. Nothing
        outside the destination is ever modified or deleted.
        """.trimIndent()
    )
}
