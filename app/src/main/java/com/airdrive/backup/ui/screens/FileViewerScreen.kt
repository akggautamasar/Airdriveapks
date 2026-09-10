package com.airdrive.backup.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
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
import androidx.compose.material.icons.filled.*
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

private fun viewerType(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif" -> ViewerType.IMAGE
    "mp4", "m4v", "mkv", "webm", "3gp", "mov", "avi" -> ViewerType.VIDEO
    "pdf" -> ViewerType.PDF
    "epub" -> ViewerType.EPUB
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr" -> ViewerType.AUDIO
    "txt", "csv", "json", "xml", "html", "htm", "md", "log", "kt", "java", "py", "js", "css", "yaml", "yml" -> ViewerType.TEXT
    "docx", "xlsx", "pptx" -> ViewerType.OFFICE
    else -> ViewerType.OTHER
}

private fun canStream(r: FileRecord) = r.status == UploadStatus.UPLOADED && r.telegramMessageId != null && r.destinationChannelId != 0L

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun FileViewerScreen(nav: NavHostController, recordId: Long) {
    val context = LocalContext.current
    val activity = context as? Activity
    val dao = remember { AppDatabase.get(context).fileRecordDao() }
    val td = remember { TdClient.get(context) }
    var record by remember(recordId) { mutableStateOf<FileRecord?>(null) }
    var local by remember(recordId) { mutableStateOf<Uri?>(null) }
    var loading by remember(recordId) { mutableStateOf(true) }
    var fullscreen by rememberSaveable(recordId) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }

    LaunchedEffect(recordId) {
        record = withContext(Dispatchers.IO) { dao.knownFiles().firstOrNull { it.id == recordId }?.let { dao.findByUri(it.uri) } }
        local = record?.let { findLocalUri(context, it) }
        loading = false
    }

    DisposableEffect(fullscreen) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
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
    BackHandler { if (fullscreen) fullscreen = false else nav.popBackStack() }

    Scaffold(containerColor = if (fullscreen) Color.Black else Page) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (!fullscreen) TopAppBar(
                title = { Column(Modifier.fillMaxWidth()) {
                    Text(record?.displayName ?: "File", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    record?.let { Text("${categoryLabel(it.category)} • ${Format.bytes(it.sizeBytes)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(enabled = local != null, onClick = { local?.let { shareLocal(context, it, record?.displayName ?: "file") } }) { Icon(Icons.Default.Share, "Share") }
                    Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("File details") }, onClick = { menu = false })
                        DropdownMenuItem(enabled = local != null, text = { Text("Open with…") }, onClick = { local?.let { openWith(context, it) }; menu = false })
                    } }
                }
            )
            when {
                loading -> LoadingPreview()
                record == null -> CenterMessage("File unavailable", "The AirDrive file record could not be loaded.")
                local != null -> LocalViewer(record!!, local!!) { fullscreen = it }
                canStream(record!!) -> CloudViewer(record!!, td) { fullscreen = it }
                else -> CenterMessage("Preview unavailable", "This file is not on the device and has no usable Telegram backup reference.")
            }
        }
    }
}

@Composable private fun LoadingPreview() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) } }

@Composable private fun LocalViewer(r: FileRecord, uri: Uri, onFullscreen: (Boolean) -> Unit) = when (viewerType(r.displayName)) {
    ViewerType.IMAGE -> ImageViewer(uri)
    ViewerType.VIDEO -> MediaViewer(r, uri, null, false, false, onFullscreen)
    ViewerType.AUDIO -> MediaViewer(r, uri, null, true, false, onFullscreen)
    ViewerType.PDF -> PdfViewer(r, uri, null)
    ViewerType.EPUB -> EpubViewer(r, uri, null)
    ViewerType.TEXT -> TextViewer(r, uri, null)
    ViewerType.OFFICE -> OfficeViewer(r, uri, null)
    ViewerType.OTHER -> OtherViewer(r)
}

@Composable private fun CloudViewer(r: FileRecord, td: TdClient, onFullscreen: (Boolean) -> Unit) {
    val chat = r.destinationChannelId
    val msg = r.telegramMessageId ?: return
    when (viewerType(r.displayName)) {
        ViewerType.VIDEO -> MediaViewer(r, null, td, false, false, onFullscreen, chat, msg)
        ViewerType.AUDIO -> MediaViewer(r, null, td, true, false, onFullscreen, chat, msg)
        ViewerType.IMAGE -> CloudImageViewer(r, td, chat, msg)
        ViewerType.PDF -> PdfViewer(r, null, td, chat, msg)
        ViewerType.EPUB -> EpubViewer(r, null, td, chat, msg)
        ViewerType.TEXT -> TextViewer(r, null, td, chat, msg)
        ViewerType.OFFICE -> OfficeViewer(r, null, td, chat, msg)
        ViewerType.OTHER -> CloudOtherViewer(r, td, chat, msg)
    }
}

@Composable private fun PreviewBanner(text: String) { Surface(color = Color(0xFFEAF8F2), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Text(text, color = Green, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold) } } }

@Composable private fun ImageViewer(uri: Uri) { val context = LocalContext.current; val bitmap by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull() } }; ZoomableImage(bitmap) }

@Composable private fun CloudImageViewer(r: FileRecord, td: TdClient, chat: Long, msg: Long) {
    var bitmap by remember(r.id) { mutableStateOf<Bitmap?>(null) }; var error by remember(r.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id) { withContext(Dispatchers.IO) { runCatching { File(td.downloadMessageFile(chat, msg).path) }.mapCatching { f -> if (!f.isFile) error("Telegram preview cache was not created"); BitmapFactory.decodeFile(f.path) ?: error("Unreadable Telegram image") }.onSuccess { bitmap = it }.onFailure { error = it.message ?: "Unable to preview image" } } }
    Column(Modifier.fillMaxSize()) { PreviewBanner("Previewing from Telegram • private app cache only"); if (error != null) CenterMessage("Image preview unavailable", error!!) else ZoomableImage(bitmap) }
}

@Composable private fun ZoomableImage(bitmap: Bitmap?) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }; var x by remember(bitmap) { mutableFloatStateOf(0f) }; var y by remember(bitmap) { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x += pan.x; y += pan.y }
    Box(Modifier.fillMaxSize().background(Color(0xFF101216)), contentAlignment = Alignment.Center) { if (bitmap == null) CircularProgressIndicator(color = Color.White) else Image(bitmap.asImageBitmap(), "Image preview", Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y }.transformable(transform), contentScale = ContentScale.Fit) }
}

@OptIn(UnstableApi::class)
@Composable private fun MediaViewer(
    r: FileRecord,
    uri: Uri?,
    td: TdClient?,
    audio: Boolean,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    chat: Long = 0L,
    msg: Long = 0L
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val player = remember(r.id, uri, chat, msg) {
        val builder = ExoPlayer.Builder(context)
        if (td != null) builder.setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(TelegramCloudDataSource.Factory(td, chat, msg, r.sizeBytes)))
        builder.build()
    }
    var error by remember(r.id, uri, chat, msg) { mutableStateOf<String?>(null) }
    var controlsLocked by rememberSaveable(r.id, uri, chat, msg) { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by rememberSaveable(r.id, uri, chat, msg) { mutableFloatStateOf(1f) }

    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e.message ?: "Playback failed" } }
        player.addListener(listener)
        val mediaUri = uri ?: Uri.parse("airdrive://telegram/$chat/$msg/${Uri.encode(r.displayName)}")
        val mime = if (audio) audioMime(r.displayName) else videoMime(r.displayName)
        val item = MediaItem.Builder().setUri(mediaUri).apply { if (mime != null) setMimeType(mime) }.build()
        player.setMediaItem(item); player.prepare(); player.playWhenReady = true; player.setPlaybackSpeed(speed)
        onDispose { player.removeListener(listener); player.release(); onFullscreen(false) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        if (!fullscreen) PreviewBanner(if (td != null) "Streaming from Telegram • no user download" else "Playing from device")
        if (error != null) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFE53935), modifier = Modifier.size(48.dp)); Spacer(Modifier.height(12.dp)); Text("Playback unavailable", color = Color.White, fontWeight = FontWeight.Bold); Spacer(Modifier.height(8.dp)); Text(error!!, color = Color.LightGray, textAlign = TextAlign.Center); Spacer(Modifier.height(18.dp)); Button(onClick = { error = null; player.seekTo(0); player.prepare(); player.playWhenReady = true }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Retry") }
            }
        } else {
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = !controlsLocked; controllerAutoShow = true; controllerHideOnTouch = true; controllerShowTimeoutMs = 5000; keepScreenOn = true } }, update = { view -> view.player = player; view.useController = !controlsLocked }, modifier = Modifier.fillMaxSize())
                Surface(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = .68f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!audio) IconButton(onClick = { onFullscreen(!fullscreen) }) { Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Full screen", tint = Color.White) }
                        IconButton(onClick = { activity?.requestedOrientation = if (configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) { Icon(Icons.Default.ScreenRotation, "Rotate screen", tint = Color.White) }
                        IconButton(onClick = { speedMenu = true }) { Text("${speed}×", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                        if (!audio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) IconButton(onClick = { activity?.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build()) }) { Icon(Icons.Default.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                        IconButton(onClick = { controlsLocked = !controlsLocked }) { Icon(if (controlsLocked) Icons.Default.Lock else Icons.Default.LockOpen, if (controlsLocked) "Unlock controls" else "Lock controls", tint = Color.White) }
                    }
                }
                if (controlsLocked) {
                    Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), shape = RoundedCornerShape(20.dp), color = Color.Black.copy(alpha = .62f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(18.dp)); Text("Controls locked", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp)); TextButton(onClick = { controlsLocked = false }) { Text("Unlock", color = Color.White) }
                        }
                    }
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, modifier = Modifier.align(Alignment.TopEnd).padding(top = 58.dp)) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, leadingIcon = { if (speed == value) Icon(Icons.Default.Check, null) }, onClick = { speed = value; player.setPlaybackSpeed(value); speedMenu = false }) }
                }
            }
            if (!fullscreen) MediaInfoCard(r, audio)
        }
    }
}

@Composable private fun MediaInfoCard(r: FileRecord, audio: Boolean) { Surface(color = Color.White, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp)) { Text(r.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text("${if (audio) "Audio" else "Video"} • ${Format.bytes(r.sizeBytes)}", color = Green, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.height(6.dp)); Text("Play, pause, seek, scrub, full screen and rotate are available in the player.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

private fun videoMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "mp4", "m4v", "3gp", "mov" -> MimeTypes.VIDEO_MP4; "webm" -> MimeTypes.VIDEO_WEBM; "mkv" -> "video/x-matroska"; "avi" -> "video/x-msvideo"; else -> null }
private fun audioMime(name: String) = when (name.substringAfterLast('.', "").lowercase()) { "mp3" -> MimeTypes.AUDIO_MPEG; "m4a" -> MimeTypes.AUDIO_MP4; "aac" -> MimeTypes.AUDIO_AAC; "ogg", "opus" -> MimeTypes.AUDIO_OGG; "wav" -> "audio/wav"; "flac" -> "audio/flac"; else -> null }

@Composable private fun PdfViewer(r: FileRecord, uri: Uri?, td: TdClient?, chat: Long = 0L, msg: Long = 0L) {
    val context = LocalContext.current; var file by remember(r.id) { mutableStateOf<File?>(null) }; var error by remember(r.id) { mutableStateOf<String?>(null) }; var page by remember(r.id) { mutableIntStateOf(0) }; var count by remember(r.id) { mutableIntStateOf(0) }; var bitmap by remember(r.id, page) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(r.id, uri, chat, msg) { withContext(Dispatchers.IO) { runCatching { if (uri != null) copyToCache(context, uri, "pdf_${r.id}.pdf") else File(td!!.downloadMessageFile(chat, msg).path) }.onSuccess { if (!it.isFile) throw IllegalStateException("PDF cache file was not created"); file = it }.onFailure { error = it.message ?: "Unable to prepare PDF" } } }
    LaunchedEffect(file, page) { val source = file ?: return@LaunchedEffect; withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer -> count = renderer.pageCount; if (count > 0) renderer.openPage(page.coerceIn(0, count - 1)).use { p -> val out = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888); out.eraseColor(android.graphics.Color.WHITE); p.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap = out } } } }.onFailure { error = it.message ?: "Unable to render PDF" } } }
    Column(Modifier.fillMaxSize()) { PreviewBanner(if (td != null) "PDF preview from Telegram • private app cache only" else "PDF preview"); when { error != null -> CenterMessage("PDF preview unavailable", error!!); file == null -> LoadingPreview(); else -> { Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) { Text("Page ${page + 1} of ${count.coerceAtLeast(1)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); OutlinedButton(enabled = page > 0, onClick = { page-- }) { Text("Previous") }; Spacer(Modifier.width(8.dp)); Button(enabled = page + 1 < count, onClick = { page++ }, colors = ButtonDefaults.buttonColors(containerColor = Purple)) { Text("Next") } }; Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(Color(0xFFE8EBF0)), contentAlignment = Alignment.TopCenter) { bitmap?.let { Image(it.asImageBitmap(), "PDF page", Modifier.fillMaxWidth().padding(12.dp), contentScale = ContentScale.FillWidth) } ?: CircularProgressIndicator(color = Purple) } } } }
}

@Composable private fun EpubViewer(r: FileRecord, uri: Uri?, td: TdClient?, chat: Long = 0L, msg: Long = 0L) {
    val context = LocalContext.current; var html by remember(r.id) { mutableStateOf<String?>(null) }; var error by remember(r.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id, uri, chat, msg) { withContext(Dispatchers.IO) { runCatching { val f = if (uri != null) copyToCache(context, uri, "epub_${r.id}.epub") else File(td!!.downloadMessageFile(chat, msg).path); ZipFile(f).use { z -> val e = z.entries().asSequence().firstOrNull { !it.isDirectory && (it.name.endsWith(".xhtml", true) || it.name.endsWith(".html", true) || it.name.endsWith(".htm", true)) } ?: error("No readable EPUB chapter found"); z.getInputStream(e).bufferedReader().use { it.readText() } } }.onSuccess { html = it }.onFailure { error = it.message ?: "Unable to open EPUB" } } }
    Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("EPUB preview from Telegram • private app cache only"); when { error != null -> CenterMessage("EPUB preview unavailable", error!!); html == null -> LoadingPreview(); else -> AndroidView(factory = { android.webkit.WebView(it).apply { settings.javaScriptEnabled = false; settings.allowFileAccess = false } }, update = { it.loadDataWithBaseURL("https://airdrive.local/", html!!, "application/xhtml+xml", "UTF-8", null) }, modifier = Modifier.fillMaxSize()) } }
}

@Composable private fun TextViewer(r: FileRecord, uri: Uri?, td: TdClient?, chat: Long = 0L, msg: Long = 0L) {
    val context = LocalContext.current; var text by remember(r.id) { mutableStateOf<String?>(null) }; var error by remember(r.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id, uri, chat, msg) { withContext(Dispatchers.IO) { runCatching { if (uri != null) context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8).take(2_000_000) } ?: error("Cannot read file") else td!!.downloadFileRange(chat, msg, 0, minOf(2_000_000L, r.sizeBytes.coerceAtLeast(1)).toInt()).toString(Charsets.UTF_8) }.onSuccess { text = it }.onFailure { error = it.message ?: "Unable to preview text" } } }
    Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("Text preview from Telegram • byte-range fetch"); when { error != null -> CenterMessage("Text preview unavailable", error!!); text == null -> LoadingPreview(); else -> Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), style = MaterialTheme.typography.bodySmall) } }
}

@Composable private fun OfficeViewer(r: FileRecord, uri: Uri?, td: TdClient?, chat: Long = 0L, msg: Long = 0L) {
    val context = LocalContext.current; var text by remember(r.id) { mutableStateOf<String?>(null) }; var error by remember(r.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(r.id, uri, chat, msg) { withContext(Dispatchers.IO) { runCatching { val f = if (uri != null) copyToCache(context, uri, "office_${r.id}.bin") else File(td!!.downloadMessageFile(chat, msg).path); extractOfficeText(f, r.displayName) }.onSuccess { text = it }.onFailure { error = it.message ?: "Unable to preview document" } } }
    Column(Modifier.fillMaxSize()) { if (td != null) PreviewBanner("Document preview from Telegram • private app cache only"); when { error != null -> CenterMessage("Document preview unavailable", error!!); text == null -> LoadingPreview(); else -> Text(text!!, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) } }
}

private fun extractOfficeText(file: File, name: String): String { ZipFile(file).use { zip -> val prefix = when (name.substringAfterLast('.', "").lowercase()) { "docx" -> "word/"; "xlsx" -> "xl/"; "pptx" -> "ppt/slides/"; else -> "" }; return zip.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".xml", true) && it.name.startsWith(prefix) }.flatMap { e -> Regex(">([^<>]{1,500})<").findAll(zip.getInputStream(e).bufferedReader().use { it.readText() }).map { it.groupValues[1] } }.filter { it.isNotBlank() }.joinToString(" ").take(2_000_000).ifBlank { "No readable text was found in this Office file." } } }

@Composable private fun CloudOtherViewer(r: FileRecord, td: TdClient, chat: Long, msg: Long) { var bytes by remember(r.id) { mutableStateOf<ByteArray?>(null) }; LaunchedEffect(r.id) { bytes = withContext(Dispatchers.IO) { runCatching { td.downloadFileRange(chat, msg, 0, minOf(32 * 1024L, r.sizeBytes.coerceAtLeast(1)).toInt()) }.getOrNull() } }; Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { PreviewBanner("Cloud file • no user download"); Spacer(Modifier.height(20.dp)); Text(r.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(10.dp)); Text("${Format.bytes(r.sizeBytes)} • format has no built-in renderer"); Spacer(Modifier.height(20.dp)); Text(bytes?.joinToString(" ") { "%02X".format(it) } ?: "Reading preview bytes…", style = MaterialTheme.typography.bodySmall) } }
@Composable private fun OtherViewer(r: FileRecord) { CenterMessage("Preview not available for this format", "${r.displayName.substringAfterLast('.', "FILE").uppercase()} is safely backed up. AirDrive will not silently copy it to Downloads just to preview it.") }
@Composable private fun CenterMessage(title: String, detail: String) { Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(46.dp)); Spacer(Modifier.height(12.dp)); Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center); Spacer(Modifier.height(7.dp)); Text(detail, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }

private fun findLocalUri(context: Context, r: FileRecord): Uri? { val u = Uri.parse(r.uri); return when { u.scheme.equals("file", true) -> u.takeIf { it.path?.let(::File)?.isFile == true }; u.scheme.equals("content", true) -> runCatching { context.contentResolver.openAssetFileDescriptor(u, "r")?.use { u } }.getOrNull(); else -> null } }
private suspend fun copyToCache(context: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) { val target = File(context.cacheDir, "airdrive_preview_$name"); context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { output -> input.copyTo(output); output.flush() } } ?: error("Cannot read file"); if (!target.isFile || target.length() == 0L) error("Preview cache file was not created"); target }
private fun shareLocal(context: Context, uri: Uri, name: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "*/*"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, name); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share file")) }
private fun openWith(context: Context, uri: Uri) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply { data = uri; type = "*/*"; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Open with")) }