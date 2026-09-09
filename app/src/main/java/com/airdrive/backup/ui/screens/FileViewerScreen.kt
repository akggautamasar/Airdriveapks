package com.airdrive.backup.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.telegram.TelegramRemoteInputStream
import com.airdrive.backup.telegram.TelegramRemoteMediaDataSource
import com.airdrive.backup.ui.view.TelegramRemoteVideoView
import com.airdrive.backup.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private enum class ViewerType { IMAGE, VIDEO, PDF, EPUB, AUDIO, TEXT, OTHER }
private val Purple = Color(0xFF6D4AFF)
private val Green = Color(0xFF18A86B)
private val Orange = Color(0xFFF2A20B)
private val Page = Color(0xFFF8F9FD)

private fun viewerType(name: String): ViewerType = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE
    "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO
    "pdf" -> ViewerType.PDF
    "epub" -> ViewerType.EPUB
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO
    "txt", "csv", "json", "xml", "html", "htm", "md", "log", "kt", "java", "py", "js", "css", "yaml", "yml" -> ViewerType.TEXT
    else -> ViewerType.OTHER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val td = remember { TdClient.get(context) }
    var record by remember(recordId) { mutableStateOf<FileRecord?>(null) }
    var local by remember(recordId) { mutableStateOf<Uri?>(null) }
    var loading by remember(recordId) { mutableStateOf(true) }
    var restoring by remember(recordId) { mutableStateOf(false) }
    var error by remember(recordId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recordId) {
        record = withContext(Dispatchers.IO) { dao.knownFiles().firstOrNull { it.id == recordId }?.let { dao.findByUri(it.uri) } }
        local = record?.let { findLocalUri(context, it) }
        loading = false
    }
    BackHandler { nav.popBackStack() }
    Scaffold(containerColor = Page) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TopAppBar(
                title = { Column { Text(record?.displayName ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); record?.let { Text("${categoryLabel(it.category)} • ${Format.bytes(it.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = { IconButton(onClick = {}) { Icon(Icons.Default.Share, "Share") }; IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") } }
            )
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
                record == null -> CenterMessage("File unavailable", "The AirDrive file record could not be loaded.")
                local != null -> LocalViewer(local!!, record!!)
                canStream(record!!) -> CloudViewer(td, record!!, { error = it })
                else -> CloudUnavailable(record!!, restoring, error) {
                    restoring = true; error = null
                    scope.launch { runCatching { repository.restoreFile(record!!) }.onSuccess { local = Uri.fromFile(it) }.onFailure { error = it.message ?: "Restore failed" }; restoring = false }
                }
            }
        }
    }
}

private fun canStream(r: FileRecord) = r.status == UploadStatus.UPLOADED && r.telegramMessageId != null && r.destinationChannelId != 0L

@Composable private fun LocalViewer(uri: Uri, r: FileRecord) = when (viewerType(r.displayName)) {
    ViewerType.IMAGE -> ImageViewer(uri, r)
    ViewerType.VIDEO -> VideoViewer(uri, r)
    ViewerType.PDF -> PdfViewer(uri)
    ViewerType.EPUB -> EpubViewer(uri)
    ViewerType.AUDIO -> AudioViewer(uri, r.displayName)
    ViewerType.TEXT -> TextViewer(uri, r.displayName)
    ViewerType.OTHER -> OtherViewer(r)
}

@Composable private fun CloudViewer(td: TdClient, r: FileRecord, onError: (String) -> Unit) {
    val chat = r.destinationChannelId; val msg = r.telegramMessageId ?: return
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFFEFFAF5)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(if (viewerType(r.displayName) == ViewerType.VIDEO || viewerType(r.displayName) == ViewerType.AUDIO) "Streaming from Telegram • no user download" else "Previewing from Telegram • no user download", color = Green, style = MaterialTheme.typography.labelMedium)
        }
        when (viewerType(r.displayName)) {
            ViewerType.IMAGE -> RemoteImage(td, chat, msg, r)
            ViewerType.VIDEO -> RemoteVideo(td, chat, msg, r)
            ViewerType.AUDIO -> RemoteAudio(td, chat, msg, r)
            ViewerType.TEXT -> RemoteText(td, chat, msg, r)
            ViewerType.PDF -> RemotePdf(td, chat, msg, r, onError)
            ViewerType.EPUB -> RemoteEpub(td, chat, msg, r, onError)
            ViewerType.OTHER -> RemoteBinary(td, chat, msg, r)
        }
    }
}

@Composable private fun RemoteImage(td: TdClient, chat: Long, msg: Long, r: FileRecord) {
    val bmp by produceState<Bitmap?>(null, chat, msg) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeStream(TelegramRemoteInputStream(td, chat, msg, r.sizeBytes)) }.getOrNull() } }
    Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) else CircularProgressIndicator(color = Color.White) }
    ViewerInfo(r, false)
}

@Composable private fun RemoteVideo(td: TdClient, chat: Long, msg: Long, r: FileRecord) {
    var view by remember { mutableStateOf<TelegramRemoteVideoView?>(null) }; var playing by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
        AndroidView(factory = { c -> TelegramRemoteVideoView(c, td, chat, msg, r.sizeBytes).also { view = it } }, modifier = Modifier.fillMaxSize())
        Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), shape = CircleShape, color = Color.Black.copy(alpha = .65f)) { IconButton(onClick = { view?.togglePlay(); playing = !playing }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) } }
    }
    ViewerInfo(r, false, true)
}

@Composable private fun RemoteAudio(td: TdClient, chat: Long, msg: Long, r: FileRecord) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }; var ready by remember { mutableStateOf(false) }; var playing by remember { mutableStateOf(false) }; var pos by remember { mutableLongStateOf(0L) }; var duration by remember { mutableLongStateOf(0L) }
    DisposableEffect(chat, msg) { val mp = MediaPlayer(); runCatching { mp.setDataSource(TelegramRemoteMediaDataSource(td, chat, msg, r.sizeBytes)); mp.setOnPreparedListener { duration = it.duration.toLong(); ready = true; player = it }; mp.setOnCompletionListener { playing = false }; mp.prepareAsync() }; onDispose { mp.release(); player = null } }
    LaunchedEffect(player, playing) { while (playing && player != null) { pos = player!!.currentPosition.toLong(); delay(400) } }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(24.dp)); Surface(modifier = Modifier.size(170.dp), shape = RoundedCornerShape(40.dp), color = Color(0xFFF0EBFF)) { Box(contentAlignment = Alignment.Center) { Text("AUDIO", color = Purple, fontWeight = FontWeight.Bold) } }; Spacer(Modifier.height(18.dp)); Text(r.displayName, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(22.dp)); LinearProgressIndicator(progress = { if (duration > 0) pos.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(pos)); Text(formatTime(duration)) }; Spacer(Modifier.height(20.dp)); Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = Purple) { IconButton(enabled = ready, onClick = { player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White) } } }
}

@Composable private fun RemoteText(td: TdClient, chat: Long, msg: Long, r: FileRecord) {
    val text by produceState<String?>(null, chat, msg) { value = withContext(Dispatchers.IO) { runCatching { TelegramRemoteInputStream(td, chat, msg, r.sizeBytes).use { readLimited(it, 256 * 1024) }.toString(Charsets.UTF_8) }.getOrNull() } }
    if (text == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { Text(text!!, style = MaterialTheme.typography.bodySmall) }
}

private fun readLimited(input: java.io.InputStream, max: Int): ByteArray { val out = java.io.ByteArrayOutputStream(); val b = ByteArray(8192); var total = 0; while (total < max) { val n = input.read(b, 0, minOf(b.size, max - total)); if (n <= 0) break; out.write(b, 0, n); total += n }; return out.toByteArray() }

@Composable private fun RemotePdf(td: TdClient, chat: Long, msg: Long, r: FileRecord, onError: (String) -> Unit) { RemoteTransientFile(td, chat, msg, r, "pdf_${r.id}.pdf", onError) { PdfViewer(Uri.fromFile(it)) } }
@Composable private fun RemoteEpub(td: TdClient, chat: Long, msg: Long, r: FileRecord, onError: (String) -> Unit) { RemoteTransientFile(td, chat, msg, r, "epub_${r.id}.epub", onError) { EpubViewer(Uri.fromFile(it)) } }

@Composable private fun RemoteTransientFile(td: TdClient, chat: Long, msg: Long, r: FileRecord, name: String, onError: (String) -> Unit, content: @Composable (File) -> Unit) {
    val context = LocalContext.current; var file by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(chat, msg) { runCatching { file = materializeTransient(context, td, chat, msg, r.sizeBytes, name) }.onFailure { onError(it.message ?: "Preview failed") } }
    if (file == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } else content(file!!)
    DisposableEffect(file) { onDispose { file?.delete() } }
}

private suspend fun materializeTransient(context: android.content.Context, td: TdClient, chat: Long, msg: Long, size: Long, name: String): File = withContext(Dispatchers.IO) { val target = File(context.cacheDir, "airdrive_preview_$name"); FileOutputStream(target).use { out -> var offset = 0L; while (offset < size) { val chunk = td.downloadFileRange(chat, msg, offset, minOf(1024 * 1024L, size - offset).toInt()); if (chunk.isEmpty()) break; out.write(chunk); offset += chunk.size }; if (offset < size) throw IllegalStateException("Preview stream ended early") }; target }

@Composable private fun RemoteBinary(td: TdClient, chat: Long, msg: Long, r: FileRecord) {
    val bytes by produceState<ByteArray?>(null, chat, msg) { value = withContext(Dispatchers.IO) { runCatching { td.downloadFileRange(chat, msg, 0L, minOf(32 * 1024L, r.sizeBytes.coerceAtLeast(1L)).toInt()) }.getOrNull() } }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(24.dp)); Text(r.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(8.dp)); Text("${Format.bytes(r.sizeBytes)} • cloud preview", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(20.dp)); Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = Color.White) { Column(Modifier.padding(16.dp)) { Text("First bytes", fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(bytes?.joinToString(" ") { "%02X".format(it) } ?: "Reading from Telegram…", style = MaterialTheme.typography.bodySmall) } } }
}

@Composable private fun ImageViewer(uri: Uri, r: FileRecord) { val context = LocalContext.current; val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }; Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bmp != null) Image(bmp!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) else CircularProgressIndicator(color = Color.White) }; ViewerInfo(r, r.status == UploadStatus.PENDING) }
@Composable private fun VideoViewer(uri: Uri, r: FileRecord) { Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) { AndroidView(factory = { c -> VideoView(c).apply { setVideoURI(uri); setMediaController(MediaController(c)); setOnPreparedListener { start() } } }, modifier = Modifier.fillMaxSize()) }; ViewerInfo(r, r.status == UploadStatus.PENDING, true) }
@Composable private fun AudioViewer(uri: Uri, name: String) { val context = LocalContext.current; var p by remember(uri) { mutableStateOf<MediaPlayer?>(null) }; var playing by remember(uri) { mutableStateOf(false) }; var duration by remember(uri) { mutableLongStateOf(0L) }; var pos by remember(uri) { mutableLongStateOf(0L) }; DisposableEffect(uri) { val mp = MediaPlayer().apply { setDataSource(context, uri); setOnPreparedListener { duration = it.duration.toLong(); p = it }; setOnCompletionListener { playing = false } ; prepareAsync() }; onDispose { mp.release(); p = null } }; LaunchedEffect(p, playing) { while (playing && p != null) { pos = p!!.currentPosition.toLong(); delay(400) } }; Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(40.dp)); Text(name, fontWeight = FontWeight.Bold); Spacer(Modifier.height(20.dp)); LinearProgressIndicator(progress = { if (duration > 0) pos.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth()); Spacer(Modifier.height(20.dp)); Button(enabled = p != null, onClick = { p?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }) { Text(if (playing) "Pause" else "Play") } } }
@Composable private fun TextViewer(uri: Uri, name: String) { val context = LocalContext.current; val text by produceState<String?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { readLimited(it, 256 * 1024).toString(Charsets.UTF_8) } }.getOrNull() } }; if (text == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) { Text(name, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp)); Text(text!!) } }
@Composable private fun PdfViewer(uri: Uri) { val context = LocalContext.current; var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }; var pages by remember(uri) { mutableIntStateOf(0) }; var page by remember(uri) { mutableIntStateOf(0) }; var error by remember(uri) { mutableStateOf<String?>(null) }; LaunchedEffect(uri, page) { withContext(Dispatchers.IO) { runCatching { val f = copyToCache(context, uri, "pdf_${uri.hashCode()}.pdf"); val d = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY); val r = android.graphics.pdf.PdfRenderer(d); pages = r.pageCount; if (pages > 0) { val p = r.openPage(page.coerceIn(0, pages - 1)); val b = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888); b.eraseColor(android.graphics.Color.WHITE); p.render(b, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close(); r.close(); d.close(); bmp = b } }.onFailure { error = it.message ?: "Unable to render PDF" } } }; if (error != null) CenterMessage("PDF preview unavailable", error!!) else Column(Modifier.fillMaxWidth().weight(1f)) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("Page ${page + 1} of ${pages.coerceAtLeast(1)}"); Row { TextButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }; TextButton(enabled = page + 1 < pages, onClick = { page++ }) { Text("Next") } } }; Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) { bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(12.dp)) } ?: CircularProgressIndicator() } } }
@Composable private fun EpubViewer(uri: Uri) { val context = LocalContext.current; var html by remember(uri) { mutableStateOf<String?>(null) }; var error by remember(uri) { mutableStateOf<String?>(null) }; LaunchedEffect(uri) { withContext(Dispatchers.IO) { runCatching { val f = copyToCache(context, uri, "epub_${uri.hashCode()}.epub"); ZipFile(f).use { z -> val e = z.entries().asSequence().firstOrNull { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) } ?: error("No readable EPUB chapter found"); html = z.getInputStream(e).bufferedReader().use { it.readText() } } }.onFailure { error = it.message ?: "Unable to open EPUB" } } }; if (error != null) CenterMessage("EPUB preview unavailable", error!!) else if (html == null) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } else AndroidView(factory = { c -> WebView(c).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false; webViewClient = WebViewClient() } }, update = { it.loadDataWithBaseURL("https://airdrive.local/", html!!, "application/xhtml+xml", "UTF-8", null) }, modifier = Modifier.fillMaxWidth().weight(1f)) }
@Composable private fun ViewerInfo(r: FileRecord, showUpload: Boolean, video: Boolean = false) { Column(Modifier.fillMaxWidth().background(Color.White).padding(16.dp)) { Text(r.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Text(if (r.status == UploadStatus.UPLOADED) "Backed up to Telegram" else "Pending upload", style = MaterialTheme.typography.labelSmall, color = if (r.status == UploadStatus.UPLOADED) Green else Orange); Spacer(Modifier.height(8.dp)); Text(Format.bytes(r.sizeBytes), style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Share") }; OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text(if (showUpload) "Upload now" else "Download") }; OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text(if (video) "Play" else "Preview") } } } }
@Composable private fun CloudUnavailable(r: FileRecord, restoring: Boolean, error: String?, onRestore: () -> Unit) { Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(48.dp), tint = Purple); Spacer(Modifier.height(14.dp)); Text("Cloud copy ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text("This file is uploaded but its Telegram destination metadata is incomplete, so live preview is unavailable.", textAlign = TextAlign.Center); Spacer(Modifier.height(18.dp)); Button(enabled = !restoring, onClick = onRestore) { if (restoring) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (restoring) "Restoring…" else "Download & Open") }; error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) } } }
@Composable private fun OtherViewer(r: FileRecord) { CenterMessage("Preview available as cloud data", "${r.displayName.substringAfterLast('.', "FILE").uppercase()} files can be inspected without putting a copy in Downloads. Restore only when another native app is required.") }
@Composable private fun CenterMessage(title: String, detail: String) { Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
private fun findLocalUri(context: android.content.Context, r: FileRecord): Uri? { val u = Uri.parse(r.uri); if (u.scheme.equals("file", true)) return u.takeIf { it.path?.let(::File)?.isFile == true }; if (u.scheme.equals("content", true)) return runCatching { context.contentResolver.openAssetFileDescriptor(u, "r")?.use { u } }.getOrNull(); return null }
private suspend fun copyToCache(context: android.content.Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) { val f = File(context.cacheDir, "viewer_$name"); context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(f).use { input.copyTo(it) } } ?: throw IllegalStateException("Cannot read file"); f }
private fun formatTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
