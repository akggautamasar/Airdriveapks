package com.airdrive.backup.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.repo.BackupRepository
import com.airdrive.backup.util.Format
import com.airdrive.backup.util.Sharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

private enum class ViewerType { IMAGE, VIDEO, PDF, EPUB, AUDIO, UNSUPPORTED }

private fun viewerType(name: String): ViewerType = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE
    "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO
    "pdf" -> ViewerType.PDF
    "epub" -> ViewerType.EPUB
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO
    else -> ViewerType.UNSUPPORTED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val scope = rememberCoroutineScope()
    val record by dao.byIdFlow(recordId).collectAsStateCompat(null)
    var working by remember { mutableStateOf(false) }
    var localUri by remember(record?.uri) { mutableStateOf(record?.let { localUriIfAvailable(context, it) }) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(record?.uri, record?.localState, record?.restoredAtMillis) {
        localUri = record?.let { localUriIfAvailable(context, it) }
    }

    BackHandler { nav.popBackStack() }

    if (record == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val current = record!!
    val type = viewerType(current.displayName)
    val uri = localUri

    Scaffold(
        containerColor = Color(0xFFF7F9FD),
        topBar = {
            SmallTopAppBar(
                title = {
                    Column {
                        Text(current.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${categoryLabel(current.category)} • ${Format.bytes(current.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (uri != null) {
                when (type) {
                    ViewerType.IMAGE -> ImageViewer(uri)
                    ViewerType.VIDEO -> VideoViewer(uri)
                    ViewerType.PDF -> PdfViewer(uri)
                    ViewerType.EPUB -> EpubViewer(uri)
                    ViewerType.AUDIO -> AudioViewer(uri, current.displayName)
                    ViewerType.UNSUPPORTED -> UnsupportedViewer(current)
                }
            } else {
                CloudOnlyViewer(
                    record = current,
                    working = working,
                    error = error,
                    onRestore = {
                        working = true
                        error = null
                        scope.launch {
                            runCatching { repository.restoreFile(current) }
                                .onSuccess { file -> localUri = Uri.fromFile(file) }
                                .onFailure { error = it.message ?: "Could not download this file from Telegram." }
                            working = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ImageViewer(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF111318)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        } else CircularProgressIndicator(color = Color.White)
    }
}

@Composable
private fun VideoViewer(uri: Uri) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri)
                setMediaController(android.widget.MediaController(ctx))
                setOnPreparedListener { it.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING); start() }
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
        update = { it.setVideoURI(uri) }
    )
}

@Composable
private fun PdfViewer(uri: Uri) {
    val context = LocalContext.current
    val pages = remember(uri) { mutableStateOf<List<Bitmap>>(emptyList()) }
    val error = remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val file = copyToCache(context, uri, "viewer.pdf")
                val renderer = android.graphics.pdf.PdfRenderer(ParcelFileDescriptorCompat.open(file))
                val rendered = buildList {
                    val count = renderer.pageCount
                    for (index in 0 until count.coerceAtMost(120)) {
                        val page = renderer.openPage(index)
                        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        add(bitmap)
                    }
                }
                renderer.close()
                pages.value = rendered
            }.onFailure { error.value = it.message ?: "Unable to render PDF" }
        }
    }
    if (error.value != null) {
        CenterMessage("PDF preview unavailable", error.value!!)
    } else if (pages.value.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFE8EBF0)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            pages.value.forEachIndexed { index, bitmap ->
                Surface(shape = RoundedCornerShape(8.dp), shadowElevation = 2.dp) {
                    Column {
                        androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
                        Text("Page ${index + 1}", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun EpubViewer(uri: Uri) {
    val context = LocalContext.current
    var chapter by remember(uri) { mutableStateOf<String?>(null) }
    var title by remember(uri) { mutableStateOf("EPUB reader") }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val targetDir = File(context.cacheDir, "epub_viewer").apply { mkdirs() }
                val epub = copyToCache(context, uri, "book.epub")
                ZipFile(epub).use { zip ->
                    val html = zip.entries().asSequence().firstOrNull { e ->
                        !e.isDirectory && (e.name.endsWith(".xhtml", true) || e.name.endsWith(".html", true) || e.name.endsWith(".htm", true))
                    } ?: error("No readable EPUB chapter was found")
                    zip.getInputStream(html).use { input ->
                        val file = File(targetDir, html.name.substringAfterLast('/').ifBlank { "chapter.xhtml" })
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                        chapter = file.toURI().toString()
                        title = html.name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace('-', ' ').ifBlank { "EPUB reader" }
                    }
                }
            }.onFailure { error.value = it.message ?: "Unable to open EPUB" }
        }
    }
    if (error.value != null) CenterMessage("EPUB preview unavailable", error.value!!)
    else if (chapter == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else {
        Column(Modifier.fillMaxSize()) {
            Text(title, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontWeight = FontWeight.SemiBold)
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = true
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        setBackgroundColor(AndroidColor.WHITE)
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                },
                update = { it.loadUrl(chapter!!) }
            )
        }
    }
}

@Composable
private fun AudioViewer(uri: Uri, name: String) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var position by remember(uri) { mutableLongStateOf(0L) }
    var duration by remember(uri) { mutableLongStateOf(0L) }

    DisposableEffect(uri) {
        val mp = MediaPlayer().apply {
            setDataSource(context, uri)
            setOnPreparedListener { duration = it.duration.toLong(); player = it }
            setOnCompletionListener { playing = false; position = duration }
            prepareAsync()
        }
        onDispose { mp.release(); player = null }
    }
    LaunchedEffect(player, playing) {
        while (playing && player != null) {
            position = player!!.currentPosition.toLong()
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Surface(Modifier.size(180.dp), RoundedCornerShape(42.dp), color = Color(0xFFF0E8FF)) {
            Box(contentAlignment = Alignment.Center) {
                Text(name.substringAfterLast('.', "AUDIO").uppercase(), color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("Audio • ${Format.bytes(File(uri.path ?: "").length())}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(28.dp))
        LinearProgressIndicator(progress = { if (duration > 0) position.toFloat() / duration else 0f }, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position)); Text(formatDuration(duration))
        }
        Spacer(Modifier.height(20.dp))
        Surface(Modifier.size(72.dp), RoundedCornerShape(36.dp), color = Color(0xFF7C3AED)) {
            IconButton(enabled = player != null, onClick = {
                player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } }
            }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
        }
    }
}

@Composable
private fun UnsupportedViewer(record: FileRecord) {
    CenterMessage("Preview not available yet", "${record.displayName.substringAfterLast('.', "FILE").uppercase()} files can still be restored, shared or opened with another installed app.")
}

@Composable
private fun CloudOnlyViewer(record: FileRecord, working: Boolean, error: String?, onRestore: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(Modifier.size(104.dp), RoundedCornerShape(28.dp), color = Color(0xFFEAF2FF)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = Color(0xFF2F6FEA), modifier = Modifier.size(46.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Text("Cloud copy ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("This file is backed up to Telegram but its local copy is not on this device.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRestore, enabled = !working && record.status == UploadStatus.UPLOADED && record.telegramMessageId != null) {
            if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null)
            Spacer(Modifier.size(8.dp)); Text(if (working) "Downloading…" else "Download & Preview")
        }
        error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

@Composable
private fun CenterMessage(title: String, detail: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp))
            Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun localUriIfAvailable(context: android.content.Context, record: FileRecord): Uri? {
    val uri = Uri.parse(record.uri)
    if (uri.scheme.equals("file", true)) return uri.takeIf { !it.path.isNullOrBlank() && File(it.path!!).isFile }
    if (uri.scheme.equals("content", true)) {
        return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri } }.getOrNull()
    }
    return null
}

private suspend fun copyToCache(context: android.content.Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
    val target = File(context.cacheDir, "viewer_$name")
    context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        ?: throw IllegalStateException("Cannot read file")
    target
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Small compatibility wrapper so this screen remains usable with the project's Flow API. */
@Composable
private fun <T> kotlinx.coroutines.flow.Flow<T>.collectAsStateCompat(initial: T): androidx.compose.runtime.State<T> =
    androidx.compose.runtime.collectAsState(initial = initial)

private object ParcelFileDescriptorCompat {
    fun open(file: File): android.os.ParcelFileDescriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
}
