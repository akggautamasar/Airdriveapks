package com.airdrive.pc

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** What a run needs to know, and nothing it does not. */
data class Options(
    val sources: List<Path>,
    val destination: Path,
    /** Lower-case path fragments; anything whose path contains one is never read. */
    val exclusions: List<String>,
    /** Lower-case extensions without dots; empty means every file type. */
    val onlyExtensions: Set<String>,
    /** 0 means no cap. Anything larger is skipped instead of failing on it every run. */
    val maxFileBytes: Long,
    val skipHidden: Boolean,
    val dryRun: Boolean,
    val verify: Boolean
)

/** Live counters plus a rolling log, read by the window while the walk is running. */
class Report {
    val seen = AtomicLong()
    val copied = AtomicLong()
    val bytes = AtomicLong()
    val unchanged = AtomicLong()
    val skipped = AtomicLong()
    val failed = AtomicLong()
    val lines = ConcurrentLinkedQueue<String>()

    @Volatile
    var current: String = ""

    @Volatile
    var startedAtMillis: Long = 0

    fun note(text: String) {
        lines.add(text)
        while (lines.size > 400) lines.poll()
    }

    fun human(): String {
        val base = Fmt.count(seen.get()) + " files seen | " + Fmt.count(copied.get()) + " copied (" +
            Fmt.bytes(bytes.get()) + ") | " + Fmt.count(unchanged.get()) + " already backed up | " +
            Fmt.count(skipped.get()) + " skipped | " + Fmt.count(failed.get()) + " failed"
        val started = startedAtMillis
        if (started == 0L) return base
        val seconds = (System.currentTimeMillis() - started) / 1000L
        if (seconds < 3) return base
        val perMinute = bytes.get() * 60L / seconds
        return base + " | about " + Fmt.bytes(if (perMinute < 0) 0L else perMinute) + " a minute"
    }

    /** The newest few notes, oldest first. A queue copy, no collection extensions to resolve. */
    fun tail(limit: Int): List<String> {
        val all = ArrayList<String>()
        for (line in lines) all.add(line)
        if (all.size <= limit) return all
        return ArrayList(all.subList(all.size - limit, all.size))
    }
}

object Fmt {
    fun count(value: Long): String =
        java.text.NumberFormat.getInstance(java.util.Locale.US).format(value)

    fun bytes(value: Long): String {
        if (value < 1024) return value.toString() + " B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = value.toDouble() / 1024.0
        var index = 0
        while (size >= 1024.0 && index < units.size - 1) {
            size /= 1024.0
            index++
        }
        return String.format(java.util.Locale.US, "%.1f %s", size, units[index])
    }
}

/**
 * `.airdrive-pc.tsv` in the destination: where each stored file came from, how big it was and when it
 * last changed. That is what makes a second run finish in seconds, and what lets a later run tell
 * "unchanged" from "new" without reading the disk again. Tabs or line breaks in a path would corrupt
 * a row, so those files are reported and left alone rather than guessed at.
 */
class Manifest(private val file: Path) {
    class Entry(val size: Long, val modifiedMillis: Long, val stored: String)

    private val rows = HashMap<String, Entry>()
    private val owners = HashMap<String, String>()
    private var dirty = false

    fun load() {
        rows.clear()
        owners.clear()
        dirty = false
        if (!Files.isRegularFile(file)) return
        try {
            Files.readAllLines(file, Charsets.UTF_8).forEach { line ->
                val parts = line.split('\t')
                if (parts.size >= 4) {
                    val size = parts[1].toLongOrNull()
                    val mtime = parts[2].toLongOrNull()
                    if (size != null && mtime != null) {
                        rows[parts[3]] = Entry(size, mtime, parts[0])
                        owners[parts[0]] = parts[3]
                    }
                }
            }
        } catch (e: IOException) {
            // A manifest we cannot read is a fresh start, not a licence to delete anything.
        }
    }

    /** Matches on size and mtime only, the same test the phone app uses for "unchanged". */
    fun matches(sourceKey: String, size: Long, modifiedMillis: Long): Boolean {
        val known = rows[sourceKey] ?: return false
        return known.size == size && known.modifiedMillis == modifiedMillis
    }

    /** Where this file went last time, so an update replaces its own copy instead of joining it. */
    fun storedFor(sourceKey: String): String? = rows[sourceKey]?.stored

    /** True when that stored path belongs to this source and to nothing else. */
    fun isOurs(stored: String, sourceKey: String): Boolean = owners[stored] == sourceKey

    fun remember(sourceKey: String, stored: String, size: Long, modifiedMillis: Long) {
        rows[sourceKey] = Entry(size, modifiedMillis, stored)
        owners[stored] = sourceKey
        dirty = true
    }

    fun isDirty(): Boolean = dirty

    fun save() {
        val parent = file.parent ?: return
        try {
            Files.createDirectories(parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".part")
            Files.newBufferedWriter(tmp, Charsets.UTF_8).use { writer ->
                rows.forEach { (key, entry) ->
                    writer.write(entry.stored + "\t" + entry.size + "\t" + entry.modifiedMillis + "\t" + key)
                    writer.newLine()
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } catch (e: IOException) {
            // The next run just re-checks what this one already copied. Nothing is lost.
        }
    }
}

/**
 * The walk itself. No database, no background service, no permission prompt: a folder tree in, a
 * folder tree out, with the rules that make the phone app bearable - exclusions, a size cap, a type
 * filter, and never copying the same thing twice. Unreadable and excluded places are counted and
 * named instead of quietly dropped, because a backup that skipped half your files has to be able to
 * say so.
 */
class BackupEngine(
    private val options: Options,
    private val report: Report,
    private val cancel: AtomicBoolean
) {
    private val reserved = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )

    /** Junction loops and self-referencing mounts must not be able to hang a run. */
    private val maxDepth = 48

    private val junk = setOf(
        "\$recycle.bin", "system volume information", "temp", "tmp", "cache", "caches",
        "thumbnails", "node_modules", "__pycache__", ".spotlight-v100"
    )

    /** Everything half-written lives in one folder, so a killed run cannot scatter .part files. */
    private val scratchName = ".airdrive-tmp"

    private val claimed = HashSet<String>()
    private var scratch: Path = Paths.get(".")
    private var copiesSinceSave = 0

    /** One directory on the stack, with where it sits under the folder it came from. */
    private class Frame(val dir: Path, val relative: Path, val depth: Int, val rootKey: String)

    fun run() {
        val dest = options.destination
        try {
            Files.createDirectories(dest)
        } catch (e: IOException) {
            report.note("cannot use the destination: " + e.message)
            return
        }
        if (options.sources.isEmpty()) {
            report.note("nothing to do - add a folder to back up first")
            return
        }
        scratch = dest.resolve(scratchName)
        if (!options.dryRun) {
            try {
                Files.createDirectories(scratch)
                clearScratch()
            } catch (e: IOException) {
                report.note("the scratch folder is not usable, so copies will be written in place")
                scratch = dest
            }
        }
        report.startedAtMillis = System.currentTimeMillis()

        val manifest = Manifest(dest.resolve(".airdrive-pc.tsv"))
        manifest.load()
        val lowerKeys = namesFoldedByDefault()

        val stack = ArrayDeque<Frame>()
        val empty = Paths.get("")
        options.sources.forEach { root ->
            val absolute = try {
                root.toAbsolutePath().normalize().toString()
            } catch (e: IOException) {
                root.toString()
            }
            stack.addLast(Frame(root, empty, 0, fold(absolute, lowerKeys)))
        }

        while (stack.isNotEmpty()) {
            if (cancel.get()) {
                report.note("stopped by you")
                break
            }
            val frame = stack.removeLast()
            val dir = frame.dir
            val children = try {
                Files.newDirectoryStream(dir).use { stream -> stream.toList() }
            } catch (e: IOException) {
                report.skipped.incrementAndGet()
                report.note("cannot read $dir (" + e.message + ")")
                continue
            } catch (e: SecurityException) {
                report.skipped.incrementAndGet()
                report.note("not allowed to read $dir")
                continue
            }

            for (child in children) {
                if (cancel.get()) break
                val name = child.fileName.toString()
                if (isSymlink(child)) continue
                val attributes = try {
                    Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                } catch (e: IOException) {
                    report.skipped.incrementAndGet()
                    continue
                } catch (e: SecurityException) {
                    report.skipped.incrementAndGet()
                    continue
                }
                val relative = frame.relative.resolve(name)

                if (attributes.isDirectory) {
                    if (skips(name, relative)) continue
                    if (frame.depth + 1 > maxDepth) {
                        report.note("too deep, not following: $child")
                    } else {
                        stack.addLast(Frame(child, relative, frame.depth + 1, frame.rootKey))
                    }
                    continue
                }
                if (!attributes.isRegularFile) continue
                if (skips(name, relative) || !wantedType(name)) {
                    report.skipped.incrementAndGet()
                    continue
                }

                report.seen.incrementAndGet()
                report.current = child.toString()

                val pathKey = relative.toString()
                if (pathKey.indexOf('\t') >= 0 || pathKey.indexOf('\n') >= 0) {
                    report.skipped.incrementAndGet()
                    report.note("name has a tab or a line break in it, left alone: $name")
                    continue
                }
                val size = attributes.size()
                if (options.maxFileBytes > 0L && size > options.maxFileBytes) {
                    report.skipped.incrementAndGet()
                    report.note("over the size limit (" + Fmt.bytes(size) + "): $pathKey")
                    continue
                }
                val key = frame.rootKey + "/" + fold(pathKey, lowerKeys)
                val modified = attributes.lastModifiedTime().toMillis()
                if (manifest.matches(key, size, modified)) {
                    report.unchanged.incrementAndGet()
                    continue
                }
                copyOne(child, targetFor(dest, manifest, key, relative, lowerKeys), key, size, modified, manifest)
            }
        }
        if (!options.dryRun) {
            if (manifest.isDirty()) manifest.save()
            try {
                clearScratch()
            } catch (e: IOException) {
                // Leftover scratch is harmless; it is not a stored file.
            }
            report.note(if (options.verify) "done, copies checked byte for byte" else "done")
        } else {
            report.note("dry run - nothing was written, and the record of what is already stored was left alone")
        }
    }

    /**
     * The name the last run used for this file, when it is still inside the destination; otherwise the
     * name the current rules give it. Without this, a file that had to be renamed to avoid a clash
     * would get a second copy beside the first on every change after that.
     */
    private fun targetFor(dest: Path, manifest: Manifest, key: String, relative: Path, lowerKeys: Boolean): Path {
        val fresh = dest.resolve(sanitize(relative))
        val remembered = manifest.storedFor(key) ?: return fresh
        if (remembered.isBlank()) return fresh
        val expected = if (lowerKeys) remembered.lowercase() else remembered
        val under = if (lowerKeys) dest.toString().lowercase() else dest.toString()
        // Only reuse it while it is still under this destination - a record from a backup drive that
        // has moved to another letter must not send files somewhere nobody is looking.
        if (!expected.startsWith(under)) return fresh
        return try {
            Paths.get(remembered)
        } catch (e: Exception) {
            fresh
        }
    }

    private fun clearScratch() {
        if (!Files.isDirectory(scratch)) return
        Files.newDirectoryStream(scratch).use { stream ->
            for (leftover in stream) {
                try {
                    Files.deleteIfExists(leftover)
                } catch (e: IOException) {
                    // Still open, or already gone. Either way the run is not stopped by it.
                }
            }
        }
    }

    private fun copyOne(
        source: Path,
        target: Path,
        key: String,
        size: Long,
        modifiedMillis: Long,
        manifest: Manifest
    ) {
        if (options.dryRun) {
            report.copied.incrementAndGet()
            report.bytes.addAndGet(size)
            report.note("would copy " + Fmt.bytes(size) + "  " + source)
            return
        }
        val storedName = target.toString()
        val finalTarget =
            if (Files.exists(target) && !manifest.isOurs(storedName, key) && !claimed.contains(storedName))
                freeSlot(target) else target
        val temp = scratch.resolve(tempNameFor(finalTarget))
        try {
            finalTarget.parent?.let { Files.createDirectories(it) }
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
            if (options.verify && !sameContent(source, temp)) {
                Files.deleteIfExists(temp)
                report.failed.incrementAndGet()
                report.note("the copy did not match the original, so it was thrown away: $source")
                return
            }
            try {
                Files.move(temp, finalTarget, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temp, finalTarget, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                // Different folder on a different drive, or a share that cannot move atomically.
                Files.move(temp, finalTarget, StandardCopyOption.REPLACE_EXISTING)
            }
            try {
                Files.setLastModifiedTime(finalTarget, FileTime.fromMillis(modifiedMillis))
            } catch (e: IOException) {
                // Only means the copy is stamped now rather than then.
            }
            claimed.add(finalTarget.toString())
            manifest.remember(key, finalTarget.toString(), size, modifiedMillis)
            report.copied.incrementAndGet()
            report.bytes.addAndGet(size)
            copiesSinceSave++
            // A run that dies halfway should not have to be started again from nothing, and writing
            // the record every few hundred files costs nothing next to the copying.
            if (copiesSinceSave >= 250) {
                copiesSinceSave = 0
                manifest.save()
            }
        } catch (e: IOException) {
            report.failed.incrementAndGet()
            report.note("could not copy $source (" + e.message + ")")
            try {
                Files.deleteIfExists(temp)
            } catch (ignored: IOException) {
                // Swept away by the next run, since it sits in the scratch folder.
            }
        }
    }

    private fun tempNameFor(target: Path): String {
        val name = target.fileName.toString()
        val stem = name.substringBeforeLast('.', name)
        val short = if (stem.length > 40) stem.substring(0, 40) else stem
        return short + "-" + System.nanoTime() + ".airdrive-part"
    }

    /** Two different names can end up alike after sanitizing; the second gets a number, never a rewrite. */
    private fun freeSlot(target: Path): Path {
        val parent = target.parent ?: return target
        val name = target.fileName.toString()
        val stem = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var index = 1
        var candidate = target
        while (Files.exists(candidate) && index <= 99) {
            candidate = parent.resolve(stem + " (" + index + ")" + ext)
            index++
        }
        return candidate
    }

    private fun sameContent(one: Path, two: Path): Boolean = try {
        digestOf(one) == digestOf(two)
    } catch (e: Exception) {
        false
    }

    private fun digestOf(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 20)
        Files.newInputStream(path).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return java.util.Base64.getEncoder().encodeToString(digest.digest())
    }

    private fun isSymlink(path: Path): Boolean = try {
        Files.isSymbolicLink(path)
    } catch (e: Exception) {
        false
    }

    private fun fold(value: String, lower: Boolean): String = if (lower) value.lowercase() else value

    /**
     * Windows and macOS look up paths without caring about case, so a record keyed on the exact
     * spelling of a path would call the same file new after a rename of one letter. Those two get
     * their keys folded; everywhere else the name is taken as written.
     */
    private fun namesFoldedByDefault(): Boolean {
        val os = System.getProperty("os.name", "").lowercase()
        return os.contains("windows") || os.contains("mac") || os.contains("darwin")
    }

    /** Junk names first, then the user's own fragments - matched anywhere in the path, as on the phone. */
    private fun skips(name: String, relative: Path): Boolean {
        val lower = name.lowercase()
        if (options.skipHidden && (lower.startsWith(".") || junk.contains(lower))) return true
        val pathLower = relative.toString().lowercase()
        return options.exclusions.any { fragment -> fragment.isNotEmpty() && pathLower.contains(fragment) }
    }

    private fun wantedType(name: String): Boolean {
        val only = options.onlyExtensions
        if (only.isEmpty()) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && only.contains(extension)
    }

    /**
     * Windows refuses < > : " | ? * and control characters in a name, refuses trailing dots and
     * spaces, and would leave a file called nul.txt unreadable for everything after it. Underscores
     * instead, and long names cut back before the extension so they stay openable.
     */
    private fun sanitize(relative: Path): String {
        val parts = ArrayList<String>()
        relative.forEach { part ->
            val cleaned = sanitizePart(part.toString())
            if (cleaned.isNotEmpty()) parts.add(cleaned)
        }
        if (parts.isEmpty()) return "unnamed"
        return parts.joinToString("/")
    }

    private fun sanitizePart(part: String): String {
        val builder = StringBuilder()
        for (c in part) {
            builder.append(if ("<>\":|?*\\".indexOf(c) >= 0 || c.code < 32) '_' else c)
        }
        var name = builder.toString().trim().trimEnd('.', ' ')
        val stem = name.substringBefore('.').uppercase()
        if (reserved.contains(stem)) name = "_" + name
        if (name.length > 120) {
            val extension = name.substringAfterLast('.', "")
            name = if (extension.isNotEmpty() && extension.length < 16 && extension != name) {
                name.substring(0, 120 - extension.length - 1) + "." + extension
            } else {
                name.substring(0, 120)
            }
        }
        return name
    }
}
