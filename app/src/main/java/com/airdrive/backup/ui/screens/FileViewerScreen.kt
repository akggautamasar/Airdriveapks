package com.airdrive.backup.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.telegram.TelegramCloudDataSource
import com.airdrive.backup.telegram.TdClient
import com.airdrive.backup.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private enum class ViewerType { IMAGE, VIDEO, PDF, EPUB, AUDIO, TEXT, OFFICE, OTHER }
private val Purple = Color(0xFF6D4AFF)
private val Green = Color(0xFF18A86B)
private val Page = Color(0xFFF7F8FC)

private fun viewerType(name: String): ViewerType = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE
    "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO
    "pdf" -> ViewerType.PDF
    "epub" -> ViewerType.EPUB
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO
    "txt", "csv", "json", "xml", "html", "htm", "md", "log", "kt", "java", "py", "js", "css", "yaml", "yml" -> ViewerType.TEXT
    "docx", "xlsx", "pptx" -> ViewerType.OFFICE
    else -> ViewerType.OTHER
}

private fun canStream(record: FileRecord): Boolean = record.status == UploadStatus.UPLOADED && record.telegramMessageId != null && record.destinationChannelId != 0L

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current
    val activity = context as? Activity
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val tdClient = remember { TdClient.get(context) }
    var record by remember(recordId) { mutableStateOf<FileRecord?>(null) }
    var localUri by remember(recordId) { mutableStateOf<Uri?>(null) }
    var loading by remember(recordId) { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var mediaFullscreen by rememberSaveable(recordId) { mutableStateOf(false) }
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    LaunchedEffect(recordId) {
        loading = true
        record = withContext(Dispatchers.IO) { dao.knownFiles().firstOrNull { it.id == recordId }?.let { dao.findByUri(it.uri) } }
        localUri = record?.let { findLocalUri(context, it) }
        loading = false
    }
    DisposableEffect(mediaFullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (mediaFullscreen) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                it.requestedOrientation = originalOrientation
            }
        }
    }
    BackHandler { if (mediaFullscreen) mediaFullscreen = false else nav.popBackStack() }

    Scaffold(containerColor = if (mediaFullscreen) Color.Black else Page) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!mediaFullscreen) TopAppBar(
                title = { Column(Modifier.fillMaxWidth()) { Text(record?.displayName ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); record?.let { Text("${categoryLabel(it.category)} • ${Format.bytes(it.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(enabled = localUri != null, onClick = { localUri?.let { shareLocal(context, it, record?.displayName ?: "file") } }) { Icon(Icons.Default.Share, "Share") }
                    Box { IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }; DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) { DropdownMenuItem(text = { Text("File details") }, leadingIcon = { Icon(Icons.Default.Visibility, null) }, onClick = { menuOpen = false }); DropdownMenuItem(enabled = localUri != null, text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = { localUri?.let { openWith(context, it) }; menuOpen = false }) } }
                }
            )
            when {
                loading -> LoadingPreview()
                record == null -> CenterMessage("File unavailable", "The AirDrive file record could not be loaded.")
                localUri != null -> LocalViewer(record!!, localUri!!) { mediaFullscreen = it }
                canStream(record!!) -> CloudViewer(record!!, tdClient) { mediaFullscreen = it }
                else -> CenterMessage("Preview unavailable", "This file is not on the device and has no usable Telegram backup reference.")
            }
        }
    }
}

@Composable private fun LoadingPreview() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } }

@Composable private fun LocalViewer(record: FileRecord, uri: Uri, onFullscreenChange: (Boolean) -> Unit) = when (viewerType(record.displayName)) {
    ViewerType.IMAGE -> ImageViewer(uri)
    ViewerType.VIDEO -> MediaViewer(record, uri, null, onFullscreenChange = onFullscreenChange)
    ViewerType.AUDIO -> MediaViewer(record, uri, null, audioOnly = true, onFullscreenChange = onFullscreenChange)
    ViewerType.PDF -> PdfViewer(record, uri, null)
    ViewerType.EPUB -> EpubViewer(record, uri, null)
    ViewerType.TEXT -> TextViewer(record, uri, null)
    ViewerType.OFFICE -> OfficeViewer(record, uri, null)
    ViewerType.OTHER -> OtherViewer(record)
}

@Composable private fun CloudViewer(record: FileRecord, tdClient: TdClient, onFullscreenChange: (Boolean) -> Unit) {
    val chatId = record.destinationChannelId
    val messageId = record.telegramMessageId ?: return
    when (viewerType(record.displayName)) {
        ViewerType.VIDEO -> MediaViewer(record, null, tdClient, chatId, messageId, onFullscreenChange = onFullscreenChange)
        ViewerType.AUDIO -> MediaViewer(record, null, tdClient, chatId, messageId, audioOnly = true, onFullscreenChange = onFullscreenChange)
        ViewerType.IMAGE -> CloudImageViewer(record, tdClient, chatId, messageId)
        ViewerType.PDF -> PdfViewer(record, null, tdClient, chatId, messageId)
        ViewerType.EPUB -> EpubViewer(record, null, tdClient, chatId, messageId)
        ViewerType.TEXT -> TextViewer(record, null, tdClient, chatId, messageId)
        ViewerType.OFFICE -> OfficeViewer(record, null, tdClient, chatId, messageId)
        ViewerType.OTHER -> CloudOtherViewer(record, tdClient, chatId, messageId)
    }
}

@Composable private fun PreviewBanner(text: String) { Surface(color = Color(0xFFEAF8F2), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Text(text, color = Green, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) } } }

@Composable private fun ImageViewer(uri: Uri) { val context = LocalContext.current; val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }; ZoomableImage(bitmap) }

@Composable private fun CloudImageViewer(record: FileRecord, td: TdClient, chatId: Long, messageId: Long) {
    var bitmap by remember(record.id) { mutableStateOf<Bitmap?>(null) }; var errorMessage by remember(record.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(record.id) { withContext(Dispatchers.IO) { runCatching { File(td.downloadMessageFile(chatId, messageId).path) }.mapCatching { file -> if (!file.isFile) throw IllegalStateException("Telegram preview cache was not created"); BitmapFactory.decodeFile(file.absolutePath) ?: throw IllegalStateException("Telegram returned an unreadable image") }.onSuccess { bitmap = it }.onFailure { errorMessage = it.message ?: "Unable to preview image" } } }
    Column(Modifier.fillMaxSize()) { PreviewBanner("Previewing from Telegram • private app cache only"); if (errorMessage != null) CenterMessage("Image preview unavailable", errorMessage!!) else ZoomableImage(bitmap) }
}

@Composable private fun ZoomableImage(bitmap: Bitmap?) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }; var x by remember(bitmap) { mutableFloatStateOf(0f) }; var y by remember(bitmap) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x += pan.x; y += pan.y }
    Box(Modifier.fillMaxSize().background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bitmap == null) CircularProgressIndicator(color = Color.White) else Image(bitmap.asImageBitmap(), "Image preview", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y }.transformable(transformState), contentScale = ContentScale.Fit) }
}

@OptIn(UnstableApi::class)
@Composable private fun MediaViewer(record: FileRecord, localUri: Uri?, td: TdClient?, cloudChatId: Long = 0L, cloudMessageId: Long = 0L, audioOnly: Boolean = false, onFullscreenChange: (Boolean) -> Unit) {
    val context = LocalContext.current; val activity = context as? Activity; val configuration = LocalConfiguration.current
    val player = remember(record.id, localUri, cloudChatId, cloudMessageId) {
        val builder = ExoPlayer.Builder(context)
        if (td != null) builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(TelegramCloudDataSource.Factory(td, cloudChatId, cloudMessageId, record.sizeBytes)))
        builder.build()
    }
    var playbackError by remember(record.id, localUri, cloudChatId, cloudMessageId) { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onPlayerError(error: androidx.media3.common.PlaybackException) { playbackError = error.message ?: "Playback failed" } }
        player.addListener(listener)
        val mediaUri = localUri ?: Uri.parse("airdrive://telegram/$cloudChatId/$cloudMessageId/${Uri.encode(record.displayName)}")
        val mimeType = if (viewerType(record.displayName) == ViewerType.VIDEO) videoMime(record.displayName) else audioMime(record.displayName)
        val itemBuilder = MediaItem.Builder().setUri(mediaUri); if (mimeType != null) itemBuilder.setMimeType(mimeType)
        player.setMediaItem(itemBuilder.build()); player.prepare(); player.playWhenReady = true
        onDispose { player.removeListener(listener); player.release(); onFullscreenChange(false) }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!isFullscreenWindow(context)) PreviewBanner(if (td != null) "Streaming from Telegram • no user download" else "Playing from device")
        if (playbackError != null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFE53935), modifier = Modifier.size(48.dp)); Spacer(Modifier.height(12.dp)); Text("Playback unavailable", color = Color.White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(playbackError!!, color = Color.LightGray, textAlign = TextAlign.Center); Spacer(Modifier.height(18.dp)); Button(onClick = { playbackError = null; player.seekTo(0L); player.prepare(); player.playWhenReady = true }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Retry") }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; controllerAutoShow = true; controllerHideOnTouch = true; controllerShowTimeoutMs = 5000; keepScreenOn = true } }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = 0.65f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onFullscreenChange(!isFullscreenWindow(context)) }) { Icon(if (isFullscreenWindow(context)) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, if (isFullscreenWindow(context)) "Exit full screen" else "Full screen", tint = Color.White) }
                        IconButton(onClick = { activity?.requestedOrientation = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) { Icon(Icons.Default.ScreenRotation, "Rotate screen", tint = Color.White) }
                    }
                }
            }
            if (!isFullscreenWindow(context)) MediaInfoCard(record, audioOnly)
        }
    }
}

private fun isFullscreenWindow(context: Context): Boolean {
    val activity = context as? Activity ?: return false
    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return false
    return !insets.isVisible(WindowInsetsCompat.Type.systemBars())
}

private fun videoMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) { "mp4", "m4v", "3gp", "mov" -> MimeTypes.VIDEO_MP4; "webm" -> MimeTypes.VIDEO_WEBM; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"; else -> null }
private fun audioMime(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) { "mp3" -> MimeTypes.AUDIO_MPEG; "m4a" -> MimeTypes.AUDIO_MP4; "aac" -> MimeTypes.AUDIO_AAC; "ogg", "opus" -> MimeTypes.AUDIO_OGG; "wav" -> "audio/wav"; "flac" -> "audio/flac"; else -> null }

@Composable private fun MediaInfoCard(record: FileRecord, audio: Boolean) { Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(record.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${if (audio) "Audio" else "Video"} • ${Format.bytes(record.sizeBytes)}", color = Green, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(6.dp)); Text("Play, pause, seek, scrub, full screen and rotate are available in the player.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

@Composable private fun PdfViewer(record: FileRecord, uri: Uri?, td: TdClient?, chatId: Long = 0L, messageId: Long = 0L) {
    val context = LocalContext.current; var file by remember(record.id) { mutableStateOf<File?>(null) }; var errorMessage by remember(record.id) { mutableStateOf<String?>(null) }; var page by remember(record.id) { mutableIntStateOf(0) }; var pageCount by remember(record.id) { mutableIntStateOf(0) }; var bitmap by remember(record.id, page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.id, uri, chatId, messageId) { withContext(Dispatchers.IO) { runCatching { if (uri != null) copyToCache(context, uri, "pdf_${record.id}.pdf") else File(td!!.downloadMessageFile(chatId, messageId).path) }.onSuccess { if (!it.isFile) throw IllegalStateException("PDF preview cache was not created"); file = it }.onFailure { errorMessage = it.message ?: "Unable to prepare PDF" } } }
    LaunchedEffect(file, page) { val source = file ?: return@LaunchedEffect; withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer -> pageCount = renderer.pageCount; if (renderer.pageCount > 0) renderer.openPage(page.coerceIn(0, renderer.pageCount - 1)).use { pdfPage -> val out = Bitmap.createBitmap(pdfPage.width * 2, pdfPage.height * 2, Bitmap.Config.ARGB_8888); out.eraseColor(android.graphics.Color.WHITE); pdfPage.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap = out } } } }.onFailure { errorMessage = it.message ?: "Unable to render PDF" } } }
    Column(Modifier.fillMaxSize()) { PreviewBanner(if (td != null) "PDF preview from Telegram • private app cache only" else "PDF preview"); when { errorMessage != null -> CenterMessage("PDF preview unavailable", errorMessage!!); file == null -> LoadingPreview(); else -> { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text("Page ${page + 1} of ${pageCount.coerceAtLeast(1)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); OutlinedButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }; Spacer(Modifier.width(8.dp)); Button(enabled = page + 1 < pageCount, onClick = { page++ }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Next") } }; Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFE8EBF0)), contentAlignment = Alignment.TopCenter) { if (bitmap != null) Image(bitmap!!.asImageBitmap(), "PDF page", Modifier.fillMaxWidth().padding(12.dp), contentScale = ContentScale.FillWidth) else CircularProgressIndicator(color = Purple) } } } } }

@Composable private fun EpubViewer(record: FileRecord, uri: Uri?, td: TdClient?, chatId: Long = 0L, messageId: Long = 0L) { val context = LocalContext.current; var html by remember(record.id) { mutableStateOf<String?>(null) }; var errorMessage by remember(record.id) { mutableStateOf<String?>(null) }; LaunchedEffect(record.id, uri, chatId, messageId) { withContext(Dispatchers.IO) { runCatching { val file = if (uri != null) copyToCache(context, uri, "epub_${record.id}.epub") else File(td!!.downloadMessageFile(chatId, messageId).path); ZipFile(file).use { zip -> val entry = zip.entries().asSequence().firstOrNull { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) } ?: throw IllegalStateException("No readable EPUB chapter found"); zip.getInputStream(entry).bufferedReader().use { it.readText() } } }.onSuccess { html = it }.onFailure { errorMessage = it.message ?: "Unable to open EPUB" } } }; Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("EPUB preview from Telegram • private app cache only"); when { errorMessage != null -> CenterMessage("EPUB preview unavailable", errorMessage!!); html == null -> LoadingPreview(); else -> AndroidView(factory = { android.webkit.WebView(it).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false } }, update = { it.loadDataWithBaseURL("https://airdrive.local/", html!!, "application/xhtml+xml", "UTF-8", null) }, modifier = Modifier.fillMaxSize()) } } }

@Composable private fun TextViewer(record: FileRecord, uri: Uri?, td: TdClient?, chatId: Long = 0L, messageId: Long = 0L) { val context = LocalContext.current; var text by remember(record.id) { mutableStateOf<String?>(null) }; var errorMessage by remember(record.id) { mutableStateOf<String?>(null) }; LaunchedEffect(record.id, uri, chatId, messageId) { withContext(Dispatchers.IO) { runCatching { if (uri != null) context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8).take(2_000_000) } ?: throw IllegalStateException("Cannot read file") else td!!.downloadFileRange(chatId, messageId, 0L, minOf(2_000_000L, record.sizeBytes.coerceAtLeast(1L)).toInt()).toString(Charsets.UTF_8) }.onSuccess { text = it }.onFailure { errorMessage = it.message ?: "Unable to preview text" } } }; Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("Text preview from Telegram • byte-range fetch"); when { errorMessage != null -> CenterMessage("Text preview unavailable", errorMessage!!); text == null -> LoadingPreview(); else -> Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), style = MaterialTheme.typography.bodySmall) } } }

@Composable private fun OfficeViewer(record: FileRecord, uri: Uri?, td: TdClient?, chatId: Long = 0L, messageId: Long = 0L) { val context = LocalContext.current; var text by remember(record.id) { mutableStateOf<String?>(null) }; var errorMessage by remember(record.id) { mutableStateOf<String?>(null) }; LaunchedEffect(record.id, uri, chatId, messageId) { withContext(Dispatchers.IO) { runCatching { val file = if (uri != null) copyToCache(context, uri, "office_${record.id}.bin") else File(td!!.downloadMessageFile(chatId, messageId).path); extractOfficeText(file, record.displayName) }.onSuccess { text = it }.onFailure { errorMessage = it.message ?: "Unable to preview document" } } }; Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("Document preview from Telegram • private app cache only"); when { errorMessage != null -> CenterMessage("Document preview unavailable", errorMessage!!); text == null -> LoadingPreview(); else -> Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) } } }

private fun extractOfficeText(file: File, name: String): String { ZipFile(file).use { zip -> val prefix = when (name.substringAfterLast('.', "").lowercase()) { "docx" -> "word/"; "xlsx" -> "xl/"; "pptx" -> "ppt/slides/"; else -> "" }; val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".xml", true) && it.name.startsWith(prefix) }.toList(); return entries.flatMap { entry -> val xml = zip.getInputStream(entry).bufferedReader().use { it.readText() }; Regex(">([^<>]{1,500})<").findAll(xml).map { it.groupValues[1] }.toList() }.filter { it.isNotBlank() }.joinToString(" ").take(2_000_000).ifBlank { "No readable text was found in this Office file." } } }

@Composable private fun CloudOtherViewer(record: FileRecord, td: TdClient, chatId: Long, messageId: Long) { var bytes by remember(record.id) { mutableStateOf<ByteArray?>(null) }; LaunchedEffect(record.id) { bytes = withContext(Dispatchers.IO) { runCatching { td.downloadFileRange(chatId, messageId, 0L, minOf(32 * 1024L, record.sizeBytes.coerceAtLeast(1L)).toInt()) }.getOrNull() } }; Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { PreviewBanner("Cloud file • no user download"); Spacer(Modifier.height(20.dp)); Text(record.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(10.dp)); Text("${Format.bytes(record.sizeBytes)} • format has no built-in renderer"); Spacer(Modifier.height(20.dp)); Text(bytes?.joinToString(" ") { "%02X".format(it) } ?: "Reading preview bytes…", style = MaterialTheme.typography.bodySmall) } }
@Composable private fun OtherViewer(record: FileRecord) { CenterMessage("Preview not available for this format", "${record.displayName.substringAfterLast('.', "FILE").uppercase()} is safely backed up. AirDrive will not silently copy it to Downloads just to preview it.") }
@Composable private fun CenterMessage(title: String, detail: String) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(46.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(7.dp)); Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
private fun findLocalUri(context: Context, record: FileRecord): Uri? { val uri = Uri.parse(record.uri); if (uri.scheme.equals("file", true)) return uri.takeIf { it.path?.let(::File)?.isFile == true }; if (uri.scheme.equals("content", true)) return runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { uri } }.getOrNull(); return null }
private suspend fun copyToCache(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) { val target = File(context.cacheDir, "airdrive_preview_$name"); context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.flush() } } ?: throw IllegalStateException("Cannot read file"); if (!target.isFile || target.length() == 0L) throw IllegalStateException("Preview cache file was not created"); target }
private fun shareLocal(context: Context, uri: Uri, name: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, name); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share file")) }
private fun openWith(context: Context, uri: Uri) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply { data = uri; type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Open with")) }
