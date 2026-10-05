package com.airdrive.pc

import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BoxLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.DefaultListModel
import javax.swing.WindowConstants

/**
 * The window. Deliberately Swing rather than a toolkit that needs a phone SDK: it is in every JRE,
 * it scales with the monitor, and there is nothing here that can fail to start on a machine the way
 * a system-tray or a notification does. Everything the window shows is the same set of rules the
 * command line takes, and both write to the same record file.
 */
class Ui(private val seed: Cli) {

    private val frame = JFrame(TITLE)
    private val folders = DefaultListModel<String>()
    private val folderList = JList(folders)
    private val destination = JTextField(40)
    private val exclusions = JTextArea(3, 40)
    private val maxSize = JSpinner(SpinnerNumberModel(0L, 0L, 1000000L, 50L))
    private val skipHidden = JCheckBox("Skip hidden and cache folders", true)
    private val verifyCopies = JCheckBox("Check copies byte for byte", true)
    private val dryRunBox = JCheckBox("Dry run - show what would be copied, write nothing", false)
    private val status = JLabel("Choose folders and a destination, then start.")
    private val log = JTextArea(12, 40)
    private val startButton = JButton("Back up now")
    private val stopButton = JButton("Stop")
    private val cancel = AtomicBoolean(false)
    private var report = Report()
    private var poller: Timer? = null

    fun show() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (e: Exception) {
            // Falls back to the default look; not worth bothering the user about.
        }
        loadSaved()

        frame.contentPane = JPanel(java.awt.BorderLayout())
        frame.contentPane.add(header(), java.awt.BorderLayout.NORTH)
        frame.contentPane.add(body(), java.awt.BorderLayout.CENTER)
        frame.contentPane.add(footer(), java.awt.BorderLayout.SOUTH)
        frame.defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
        frame.size = Dimension(940, 700)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun header(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6))
        panel.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        panel.add(JLabel("<html><b>" + TITLE + "</b> &nbsp; copies what you point it at into one folder, " +
            "skipping anything that matches a rule below. It never touches or deletes a source file." +
            "</html>"))
        return panel
    }

    private fun body(): JSplitPane {
        val controls = JPanel()
        controls.layout = BoxLayout(controls, BoxLayout.Y_AXIS)
        controls.border = BorderFactory.createEmptyBorder(6, 8, 6, 8)

        val folderRow = row(JLabel("Folders to back up"))
        folderRow.add(JButton("Add folder").apply { addActionListener { addFolder() } })
        folderRow.add(JButton("Remove selected").apply { addActionListener { removeFolders() } })
        controls.add(folderRow)

        folderList.selectionMode = javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        val folderScroll = JScrollPane(folderList)
        folderScroll.preferredSize = Dimension(900, 110)
        folderScroll.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 160)
        controls.add(folderScroll)

        val destRow = row(JLabel("Into this folder"))
        destRow.add(destination)
        destRow.add(JButton("Browse").apply { addActionListener { chooseDestination() } })
        destRow.add(JButton("Open").apply { addActionListener { openDestination() } })
        controls.add(destRow)

        val excludeRow = row(
            JLabel("Skip these paths"),
            JLabel("(one per line, any part of a folder name works - same rule as the phone app)")
        )
        controls.add(excludeRow)
        val excludeScroll = JScrollPane(exclusions)
        excludeScroll.preferredSize = Dimension(900, 66)
        excludeScroll.maximumSize = Dimension(Short.MAX_VALUE.toInt(), 90)
        controls.add(excludeScroll)

        val rulesRow = row(JLabel("Ignore files over (MB, 0 for no limit)"), maxSize, skipHidden, verifyCopies, dryRunBox)
        controls.add(rulesRow)

        log.isEditable = false
        val logScroll = JScrollPane(log)
        logScroll.border = BorderFactory.createTitledBorder("What it is doing")
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, controls, logScroll)
        split.resizeWeight = 0.42
        split.dividerLocation = 300
        return split
    }

    private fun footer(): JPanel {
        val panel = JPanel(java.awt.BorderLayout())
        panel.border = BorderFactory.createEmptyBorder(4, 8, 6, 8)
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        stopButton.isEnabled = false
        buttons.add(startButton)
        buttons.add(stopButton)
        startButton.addActionListener { start() }
        stopButton.addActionListener {
            cancel.set(true)
            status.text = "Stopping after the current file..."
        }
        panel.add(buttons, java.awt.BorderLayout.WEST)
        panel.add(status, java.awt.BorderLayout.CENTER)
        return panel
    }

    private fun row(vararg parts: java.awt.Component): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        panel.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        parts.forEach { panel.add(it) }
        return panel
    }

    private fun addFolder() {
        val chooser = JFileChooser(lastUsed())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.dialogTitle = "Choose a folder to back up"
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            val picked = chooser.selectedFile.toPath().toAbsolutePath().normalize().toString()
            if (!containsFolder(picked)) folders.addElement(picked)
        }
    }

    private fun removeFolders() {
        val selected = folderList.selectedIndices
        // Descending, because removing by index shifts every index after it.
        (selected.lastIndex downTo 0).forEach { index -> folders.removeElementAt(selected[index]) }
    }

    private fun containsFolder(path: String): Boolean {
        for (index in 0 until folders.size()) {
            if (folders.getElementAt(index) == path) return true
        }
        return false
    }

    private fun chooseDestination() {
        val chooser = JFileChooser(lastUsed())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.isAcceptAllFileFilterUsed = false
        chooser.dialogTitle = "Choose where the copies go"
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            destination.text = chooser.selectedFile.toAbsolutePath().toString()
        }
    }

    private fun openDestination() {
        val path = destination.text.trim()
        if (path.isEmpty()) return
        try {
            Desktop.getDesktop().open(Paths.get(path).toFile())
        } catch (e: Exception) {
            status.text = "This system would not open a folder for me: " + path
        }
    }

    private fun lastUsed(): Path {
        val text = destination.text.trim()
        if (text.isNotEmpty()) {
            val dir = Paths.get(text)
            if (java.nio.file.Files.isDirectory(dir)) return dir
        }
        return Paths.get(System.getProperty("user.home"))
    }

    /** Reads the widgets on the event thread and hands the run a plain, frozen set of choices. */
    private fun collect(): Options? {
        val sources = ArrayList<Path>()
        for (index in 0 until folders.size()) {
            val dir = Paths.get(folders.getElementAt(index))
            if (java.nio.file.Files.isDirectory(dir)) {
                sources.add(dir)
            } else {
                JOptionPane.showMessageDialog(frame, "That folder is not there any more:\n$dir", TITLE, JOptionPane.WARNING_MESSAGE)
                return null
            }
        }
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Add at least one folder to back up.", TITLE, JOptionPane.INFORMATION_MESSAGE)
            return null
        }
        val dest = destination.text.trim()
        if (dest.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Pick the folder the copies should go into.", TITLE, JOptionPane.INFORMATION_MESSAGE)
            return null
        }
        val rules = exclusions.text.lines().map { line -> line.trim().lowercase() }.filter { it.isNotEmpty() }
        val short = rules.filter { it.length < 3 }
        if (short.isNotEmpty()) {
            JOptionPane.showMessageDialog(frame, "A rule shorter than three characters would skip almost everything:\n" +
                short.joinToString(", "), TITLE, JOptionPane.WARNING_MESSAGE)
            return null
        }
        val maxMb = (maxSize.value as Number).toLong()
        save(sources, dest, rules, maxMb)
        return Options(
            sources = sources,
            destination = Paths.get(dest),
            exclusions = rules,
            maxFileBytes = maxMb * 1024L * 1024L,
            skipHidden = skipHidden.isSelected,
            dryRun = dryRunBox.isSelected,
            verify = verifyCopies.isSelected
        )
    }

    private fun start() {
        val options = collect() ?: return
        report = Report()
        cancel.set(false)
        startButton.isEnabled = false
        stopButton.isEnabled = true
        log.text = ""
        status.text = "Working..."
        val worker = Thread {
            try {
                BackupEngine(options, report, cancel).run()
            } catch (e: Exception) {
                report.note("stopped: " + e.javaClass.simpleName + " " + e.message)
            } finally {
                SwingUtilities.invokeLater {
                    startButton.isEnabled = true
                    stopButton.isEnabled = false
                    status.text = report.human()
                }
            }
        }
        worker.isDaemon = true
        worker.name = "airdrive-pc-run"
        worker.start()

        poller?.stop()
        poller = Timer(300) {
            if (report.current.isNotEmpty()) {
                status.text = report.human() + "   " + report.current
            } else {
                status.text = report.human()
            }
            val tail = report.tail(120)
            if (tail.size != lastLineCount || !startButton.isEnabled) {
                lastLineCount = tail.size
                log.text = tail.joinToString("\n")
                log.caretPosition = log.document.length
            }
        }
        poller?.start()
    }

    private var lastLineCount = -1

    private fun save(sources: List<Path>, dest: String, rules: List<String>, maxMb: Long) {
        val prefs = java.util.prefs.Preferences.userRoot().node("airdrive-pc")
        prefs.put("sources", sources.joinToString("\n"))
        prefs.put("destination", dest)
        prefs.put("exclusions", rules.joinToString("\n"))
        prefs.put("maxMb", maxMb.toString())
        prefs.putBoolean("skipHidden", skipHidden.isSelected)
        prefs.putBoolean("verify", verifyCopies.isSelected)
        prefs.putBoolean("dryRun", dryRunBox.isSelected)
        try {
            prefs.flush()
        } catch (e: Exception) {
            // Losing the saved settings is not worth interrupting a backup for.
        }
    }

    private fun loadSaved() {
        val prefs = java.util.prefs.Preferences.userRoot().node("airdrive-pc")
        val savedSources = seed.sources.map { it.toString() } +
            (prefs.get("sources", "") ?: "").lines().filter { it.isNotBlank() }
        savedSources.forEach { if (!containsFolder(it)) folders.addElement(it) }
        destination.text = if (seed.destination != null) seed.destination.toString() else prefs.get("destination", "")
        exclusions.text = ((prefs.get("exclusions", "") ?: "") + "\n" +
            seed.exclusions.joinToString("\n")).trim()
        prefs.get("maxMb", "0")?.toLongOrNull()?.let { maxSize.value = it }
        skipHidden.isSelected = prefs.getBoolean("skipHidden", true)
        verifyCopies.isSelected = prefs.getBoolean("verify", true)
        dryRunBox.isSelected = seed.dryRun || prefs.getBoolean("dryRun", false)
    }
}
