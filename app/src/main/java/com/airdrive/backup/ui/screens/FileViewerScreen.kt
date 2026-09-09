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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.airdrive.backup.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private enum class ViewerType { IMAGE, VIDEO, PDF, EPUB, AUDIO, OTHER }
private val Purple = Color(0xFF6D4AFF)
private val Green = Color(0xFF18A86B)
private val Orange = Color(0xFFF2A20B)
private val Page = Color(0xFFF8F9FD)

private fun viewerType(name: String): ViewerType = when (name.substringAfterLast('.', "").lowercase()) { "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE; "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO; "pdf" -> ViewerType.PDF; "epub" -> ViewerType.EPUB; "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO; else -> ViewerType.OTHER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current; val dao = remember { AppDatabase.get(context).fileRecordDao() }; val repository = remember { BackupRepository.get(context) }
    var record by remember(recordId) { mutableStateOf<FileRecord?>(null) }; var localUri by remember(recordId) { mutableStateOf<Uri?>(null) }; var loading by remember(recordId) { mutableStateOf(true) }; var restoring by remember(recordId) { mutableStateOf(false) }; var error by remember(recordId) { mutableStateOf<String?>(null) }; val scope = rememberCoroutineScope()
    LaunchedEffect(recordId) { loading = true; record = withContext(Dispatchers.IO) { dao.knownFiles().firstOrNull { it.id == recordId }?.let { dao.findByUri(it.uri) } }; localUri = record?.let { findLocalUri(context, it) }; loading = false }
    BackHandler { nav.popBackStack() }
    Scaffold(containerColor = Page) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        TopAppBar(title = { Column { Text(record?.displayName ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); record?.let { Text("${categoryLabel(it.category)} • ${Format.bytes(it.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }, actions = { IconButton(onClick = {}) { Icon(Icons.Default.Share, "Share") }; IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") } })
        when { loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }; record == null -> CenterMessage("File unavailable", "This file record could not be loaded from the local AirDrive index."); localUri != null -> when (viewerType(record!!.displayName)) { ViewerType.IMAGE -> ImageViewer(localUri!!, record!!); ViewerType.VIDEO -> VideoViewer(localUri!!, record!!); ViewerType.PDF -> PdfViewer(localUri!!); ViewerType.EPUB -> EpubViewer(localUri!!); ViewerType.AUDIO -> AudioViewer(localUri!!, record!!.displayName); ViewerType.OTHER -> OtherViewer(record!!) }; else -> CloudPreview(record!!, restoring, error) { restoring = true; error = null; scope.launch { runCatching { repository.restoreFile(record!!) }.onSuccess { localUri = Uri.fromFile(it) }.onFailure { error = it.message ?: "Download failed" }; restoring = false } } }
    } }
}

@Composable private fun ImageViewer(uri: Uri, record: FileRecord) { val context = LocalContext.current; val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }; Column(Modifier.fillMaxSize()) { Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit) else CircularProgressIndicator(color = Color.White) }; ViewerInfo(record, record.status == UploadStatus.PENDING) } }
@Composable private fun VideoViewer(uri: Uri, record: FileRecord) { Column(Modifier.fillMaxSize()) { Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) { AndroidView(factory = { context -> VideoView(context).apply { setVideoURI(uri); setMediaController(MediaController(context)); setOnPreparedListener { start() } } }, modifier = Modifier.fillMaxSize()) }; ViewerInfo(record, record.status == UploadStatus.PENDING, true) } }
@Composable private fun ViewerInfo(record: FileRecord, showUpload: Boolean, video: Boolean = false) { Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 13.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(30.dp), CircleShape, color = if (record.status == UploadStatus.UPLOADED) Color(0xFFE8F8F0) else Color(0xFFFFF5DF)) { Box(contentAlignment = Alignment.Center) { Icon(if (record.status == UploadStatus.UPLOADED) Icons.Default.CheckCircle else Icons.Default.Upload, null, tint = if (record.status == UploadStatus.UPLOADED) Green else Orange, modifier = Modifier.size(19.dp)) } }; Spacer(Modifier.width(9.dp)); Column(Modifier.weight(1f)) { Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Text(if (record.status == UploadStatus.UPLOADED) "Backed up to Telegram" else "Pending upload", style = MaterialTheme.typography.labelSmall, color = if (record.status == UploadStatus.UPLOADED) Green else Orange) } }; Spacer(Modifier.height(8.dp)); DetailLine(Icons.Default.Download, Format.bytes(record.sizeBytes)); DetailLine(Icons.Default.CalendarMonth, "${record.modifiedAtMillis}"); DetailLine(Icons.Default.Folder, record.uri.substringAfterLast("/", record.uri)); Spacer(Modifier.height(10.dp)); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { ViewerAction("Share", Icons.Default.Share, Modifier.weight(1f)) {}; ViewerAction(if (showUpload) "Upload now" else "Download", if (showUpload) Icons.Default.Upload else Icons.Default.Download, Modifier.weight(1f)) {}; ViewerAction(if (video) "Play" else "Preview", Icons.Default.PlayArrow, Modifier.weight(1f)) {}; ViewerAction("More", Icons.Default.MoreVert, Modifier.weight(1f)) {} } } }
@Composable private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) { Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
@Composable private fun ViewerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = modifier.height(50.dp), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, modifier = Modifier.size(18.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) } } }
@Composable private fun CloudPreview(record: FileRecord, restoring: Boolean, error: String?, onDownload: () -> Unit) { Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Surface(Modifier.size(104.dp), RoundedCornerShape(28.dp), color = Color(0xFFEAF2FF)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = Color(0xFF2F6FEA), modifier = Modifier.size(46.dp)) } }; Spacer(Modifier.height(18.dp)); Text("Cloud copy ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text("The local copy is unavailable. AirDrive can restore the Telegram-backed file and open it in the correct viewer.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp)); Button(enabled = !restoring && record.status == UploadStatus.UPLOADED && record.telegramMessageId != null, onClick = onDownload, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { if (restoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (restoring) "Downloading…" else "Download & Preview") }; if (error != null) { Spacer(Modifier.height(12.dp)); Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) } } }
@Composable private fun PdfViewer(uri: Uri) { val context = LocalContext.current; var pageBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }; var pageCount by remember(uri) { mutableIntStateOf(0) }; var pageIndex by remember(uri) { mutableIntStateOf(0) }; var error by remember(uri) { mutableStateOf<String?>(null) }; LaunchedEffect(uri, pageIndex) { withContext(Dispatchers.IO) { runCatching { val file = copyToCache(context, uri, "pdf_${uri.hashCode()}.pdf"); val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY); val renderer = android.graphics.pdf.PdfRenderer(descriptor); pageCount = renderer.pageCount; if (pageCount > 0) { val page = renderer.openPage(pageIndex.coerceIn(0, pageCount - 1)); val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close(); renderer.close(); descriptor.close(); pageBitmap = bitmap } else { renderer.close(); descriptor.close() } }.onFailure { error = it.message ?: "Unable to render PDF" } } }; if (error != null) CenterMessage("PDF preview unavailable", error!!) else Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("Page ${pageIndex + 1} of ${pageCount.coerceAtLeast(1)}", fontWeight = FontWeight.SemiBold); Row { TextButton(enabled = pageIndex > 0, onClick = { pageIndex-- }) { Text("Previous") }; TextButton(enabled = pageIndex + 1 < pageCount, onClick = { pageIndex++ }) { Text("Next") } } }; Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFE8EBF0)), contentAlignment = Alignment.TopCenter) { if (pageBitmap != null) Image(pageBitmap!!.asImageBitmap(), "PDF page", Modifier.fillMaxWidth().padding(12.dp), contentScale = ContentScale.FillWidth) else CircularProgressIndicator(Modifier.align(Alignment.Center)) } } }
@Composable private fun EpubViewer(uri: Uri) { val context = LocalContext.current; var html by remember(uri) { mutableStateOf<String?>(null) }; var error by remember(uri) { mutableStateOf<String?>(null) }; LaunchedEffect(uri) { withContext(Dispatchers.IO) { runCatching { val epub = copyToCache(context, uri, "book_${uri.hashCode()}.epub"); ZipFile(epub).use { zip -> val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) } ?: error("No readable EPUB chapter found"); html = zip.getInputStream(entry).bufferedReader().use { it.readText() } } }.onFailure { error = it.message ?: "Unable to open EPUB" } } }; if (error != null) CenterMessage("EPUB preview unavailable", error!!) else if (html == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } else AndroidView(factory = { context -> WebView(context).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false; webViewClient = WebViewClient() } }, modifier = Modifier.fillMaxSize(), update = { it.loadDataWithBaseURL("https://airdrive.local/", html!!, "application/xhtml+xml", "UTF-8", null) }) }
@Composable private fun AudioViewer(uri: Uri, name: String) { val context = LocalContext.current; var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }; var playing by remember(uri) { mutableStateOf(false) }; var position by remember(uri) { mutableLongStateOf(0L) }; var duration by remember(uri) { mutableLongStateOf(0L) }; DisposableEffect(uri) { val mp = MediaPlayer().apply { setDataSource(context, uri); setOnPreparedListener { duration = it.duration.toLong(); player = it }; setOnCompletionListener { playing = false; position = duration }; prepareAsync() }; onDispose { mp.release(); player = null } }; LaunchedEffect(player, playing) { while (playing && player != null) { position = player!!.currentPosition.toLong(); delay(400) } }; Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Spacer(Modifier.height(28.dp)); Surface(Modifier.size(180.dp), RoundedCornerShape(42.dp), color = Color(0xFFF0EBFF)) { Box(contentAlignment = Alignment.Center) { Text("AUDIO", color = Purple, fontWeight = FontWeight.Bold) } }; Spacer(Modifier.height(20.dp)); Text(name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(24.dp)); LinearProgressIndicator(progress = { if (duration > 0) position.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position)); Text(formatTime(duration)) }; Spacer(Modifier.height(22.dp)); Surface(Modifier.size(72.dp), CircleShape, color = Purple) { IconButton(enabled = player != null, onClick = { player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) } } } }
@Composable private fun OtherViewer(record: FileRecord) { CenterMessage("No built-in preview for this type", "${record.displayName.substringAfterLast('.', "FILE").uppercase()} files remain searchable and restorable. Restore them to open with another compatible Android app.") }
@Composable private fun CenterMessage(title: String, detail: String) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
private fun findLocalUri(context: android.content.Context, record: FileRecord): Uri? { val uri = Uri.parse(record.uri); if (uri.scheme.equals("file", true)) return uri.takeIf { it.path?.let(::File)?.isFile == true }; if (uri.scheme.equals("content", true)) return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri } }.getOrNull(); return null }
private suspend fun copyToCache(context: android.content.Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) { val target = File(context.cacheDir, "viewer_$name"); context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } } ?: throw IllegalStateException("Cannot read file"); target }
private fun formatTime(ms: Long): String { val seconds = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(seconds / 60, seconds % 60) }
