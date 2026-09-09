package com.airdrive.backup.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import com.airdrive.backup.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private enum class ViewerType { IMAGE, VIDEO, PDF, EPUB, AUDIO, OTHER }
private fun typeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE
    "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO
    "pdf" -> ViewerType.PDF
    "epub" -> ViewerType.EPUB
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO
    else -> ViewerType.OTHER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val repository = remember { BackupRepository.get(context) }
    val record by dao.byIdFlow(recordId).collectAsState(initial = null)
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(record?.uri, record?.localState, record?.restoredAtMillis) {
        localUri = record?.let { findLocalUri(context, it) }
    }
    BackHandler { nav.popBackStack() }
    if (record == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    val file = record!!

    Scaffold(containerColor = Color(0xFFF7F9FD), topBar = {
        SmallTopAppBar(
            title = { Column { Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold); Text("${categoryLabel(file.category)} • ${Format.bytes(file.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (localUri != null) {
                when (typeOf(file.displayName)) {
                    ViewerType.IMAGE -> ImageViewer(localUri!!)
                    ViewerType.VIDEO -> VideoViewer(localUri!!)
                    ViewerType.PDF -> PdfViewer(localUri!!)
                    ViewerType.EPUB -> EpubViewer(localUri!!)
                    ViewerType.AUDIO -> AudioViewer(localUri!!, file.displayName)
                    ViewerType.OTHER -> OtherViewer(file)
                }
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Surface(Modifier.size(104.dp), RoundedCornerShape(28.dp), color = Color(0xFFEAF2FF)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Download, null, tint = Color(0xFF2F6FEA), modifier = Modifier.size(46.dp)) } }
                    Spacer(Modifier.height(18.dp)); Text("Cloud copy ready", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp)); Text("The local copy is unavailable, but this backed-up file can be downloaded from Telegram and previewed here.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(18.dp))
                    Button(enabled = !restoring && file.status == UploadStatus.UPLOADED && file.telegramMessageId != null, onClick = {
                        restoring = true; error = null
                        scope.launch { runCatching { repository.restoreFile(file) }.onSuccess { localUri = Uri.fromFile(it) }.onFailure { error = it.message ?: "Download failed" }; restoring = false }
                    }) { if (restoring) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text(if (restoring) "Downloading…" else "Download & Preview") }
                    error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

@Composable private fun ImageViewer(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }
    Box(Modifier.fillMaxSize().background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) else CircularProgressIndicator(color = Color.White) }
}

@Composable private fun VideoViewer(uri: Uri) {
    AndroidView(Modifier.fillMaxSize().background(Color.Black), factory = { ctx -> VideoView(ctx).apply { setVideoURI(uri); setMediaController(android.widget.MediaController(ctx)); setOnPreparedListener { start() }; layoutParams = ViewGroup.LayoutParams(-1, -1) } }, update = { it.setVideoURI(uri) })
}

@Composable private fun PdfViewer(uri: Uri) {
    val context = LocalContext.current
    var pageBitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember(uri) { mutableIntStateOf(0) }
    var pageIndex by remember(uri) { mutableIntStateOf(0) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri, pageIndex) {
        withContext(Dispatchers.IO) { runCatching {
            val file = copyToCache(context, uri, "pdf_${uri.hashCode()}.pdf")
            val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(descriptor)
            pageCount = renderer.pageCount
            val page = renderer.openPage(pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0)))
            val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); page.close(); renderer.close(); descriptor.close(); pageBitmap = bitmap
        }.onFailure { error = it.message ?: "Unable to render PDF" } }
    }
    if (error != null) CenterMessage("PDF preview unavailable", error!!) else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Page ${pageIndex + 1} of ${pageCount.coerceAtLeast(1)}", fontWeight = FontWeight.SemiBold)
            Row { TextButton(enabled = pageIndex > 0, onClick = { pageIndex-- }) { Text("Previous") }; TextButton(enabled = pageIndex + 1 < pageCount, onClick = { pageIndex++ }) { Text("Next") } }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFE8EBF0)).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) { if (pageBitmap != null) Image(pageBitmap!!.asImageBitmap(), "PDF page", Modifier.fillMaxWidth().padding(12.dp), contentScale = ContentScale.FillWidth) else CircularProgressIndicator(Modifier.align(Alignment.Center)) }
    }
}

@Composable private fun EpubViewer(uri: Uri) {
    val context = LocalContext.current
    var chapterUri by remember(uri) { mutableStateOf<String?>(null) }
    var error by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) { withContext(Dispatchers.IO) { runCatching {
        val epub = copyToCache(context, uri, "book_${uri.hashCode()}.epub")
        val dir = File(context.cacheDir, "epub_${uri.hashCode()}").apply { mkdirs() }
        ZipFile(epub).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) } ?: error("No readable EPUB chapter found")
            val out = File(dir, entry.name.substringAfterLast('/').ifBlank { "chapter.xhtml" }); zip.getInputStream(entry).use { input -> FileOutputStream(out).use { input.copyTo(it) } }; chapterUri = out.toURI().toString()
        }
    }.onFailure { error = it.message ?: "Unable to open EPUB" } } }
    if (error != null) CenterMessage("EPUB preview unavailable", error!!) else if (chapterUri == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } else AndroidView(Modifier.fillMaxSize(), factory = { ctx -> WebView(ctx).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = true; webViewClient = WebViewClient() } }, update = { it.loadUrl(chapterUri!!) })
}

@Composable private fun AudioViewer(uri: Uri, name: String) {
    val context = LocalContext.current
    var player by remember(uri) { mutableStateOf<MediaPlayer?>(null) }; var playing by remember(uri) { mutableStateOf(false) }; var position by remember(uri) { mutableLongStateOf(0L) }; var duration by remember(uri) { mutableLongStateOf(0L) }
    DisposableEffect(uri) { val mp = MediaPlayer().apply { setDataSource(context, uri); setOnPreparedListener { duration = it.duration.toLong(); player = it }; setOnCompletionListener { playing = false; position = duration }; prepareAsync() }; onDispose { mp.release(); player = null } }
    LaunchedEffect(player, playing) { while (playing && player != null) { position = player!!.currentPosition.toLong(); delay(400) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp)); Surface(Modifier.size(180.dp), RoundedCornerShape(42.dp), color = Color(0xFFF0E8FF)) { Box(contentAlignment = Alignment.Center) { Text("AUDIO", color = Color(0xFF7C3AED), fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.height(20.dp)); Text(name, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { if (duration > 0) position.toFloat() / duration else 0f }, Modifier.fillMaxWidth()); Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position)); Text(formatTime(duration)) }
        Spacer(Modifier.height(22.dp)); Surface(Modifier.size(72.dp), RoundedCornerShape(36.dp), color = Color(0xFF7C3AED)) { IconButton(enabled = player != null, onClick = { player?.let { if (it.isPlaying) { it.pause(); playing = false } else { it.start(); playing = true } } }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(34.dp)) } }
    }
}

@Composable private fun OtherViewer(record: FileRecord) { CenterMessage("No built-in preview for this type", "${record.displayName.substringAfterLast('.', "FILE").uppercase()} files remain fully searchable, restorable and available to open with another app.") }
@Composable private fun CenterMessage(title: String, detail: String) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold); Spacer(Modifier.height(6.dp)); Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

private fun findLocalUri(context: android.content.Context, record: FileRecord): Uri? { val uri = Uri.parse(record.uri); if (uri.scheme.equals("file", true)) return uri.takeIf { it.path?.let(::File)?.isFile == true }; if (uri.scheme.equals("content", true)) return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri } }.getOrNull(); return null }
private suspend fun copyToCache(context: android.content.Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) { val target = File(context.cacheDir, "viewer_$name"); context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } } ?: throw IllegalStateException("Cannot read file"); target }
private fun formatTime(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
