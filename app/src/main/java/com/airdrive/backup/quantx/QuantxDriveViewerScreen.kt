package com.airdrive.backup.quantx

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val ViewerBg = Color(0xFFF7F8FC)
private val ViewerPurple = Color(0xFF6D4AFF)
private enum class QuantViewerType { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private fun quantViewerType(file: QuantFile): QuantViewerType {
    val n = file.filename.lowercase()
    val m = file.mime.lowercase()
    return when {
        m.startsWith("image/") || n.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif)$")) -> QuantViewerType.IMAGE
        m.startsWith("video/") || n.matches(Regex(".*\\.(mp4|m4v|mkv|webm|mov|avi|3gp)$")) -> QuantViewerType.VIDEO
        m.startsWith("audio/") || n.matches(Regex(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|amr)$")) -> QuantViewerType.AUDIO
        m.contains("pdf") || n.endsWith(".pdf") -> QuantViewerType.PDF
        m.startsWith("text/") || n.matches(Regex(".*\\.(txt|csv|json|xml|html|htm|md|log)$")) -> QuantViewerType.TEXT
        else -> QuantViewerType.OTHER
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun QuantxDriveViewerScreen(file: QuantFile, api: QuantxDriveApi, onBack: () -> Unit, onFavorite: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = remember(file.id) { quantViewerType(file) }
    var busy by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }
    var fullscreen by remember(file.id) { mutableStateOf(false) }

    fun setFullscreen(enabled: Boolean) {
        fullscreen = enabled
        val activity = context as? Activity ?: return
        if (enabled) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            activity.window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    DisposableEffect(type) {
        val media = type == QuantViewerType.VIDEO || type == QuantViewerType.AUDIO
        QuantxDrivePip.isEnabled = media
        onDispose {
            QuantxDrivePip.isEnabled = false
            setFullscreen(false)
        }
    }

    BackHandler {
        if (fullscreen) setFullscreen(false) else onBack()
    }

    // Fullscreen is deliberately outside Scaffold. The media surface owns every pixel;
    // the QuantxDrive app bar and other viewer chrome cannot appear in this mode.
    if (fullscreen && (type == QuantViewerType.VIDEO || type == QuantViewerType.AUDIO)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (type == QuantViewerType.VIDEO) {
                RemotePlayer(api.mediaUrl(file.id), file.mime, false, true, ::setFullscreen, api.savedToken)
            } else {
                RemotePlayer(api.mediaUrl(file.id), file.mime, true, true, ::setFullscreen, api.savedToken)
            }
        }
        return
    }

    Scaffold(
        containerColor = if (type == QuantViewerType.VIDEO || type == QuantViewerType.AUDIO) Color.Black else ViewerBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(file.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                        Text("${formatBytes(file.size)} • ${file.category.ifBlank { "other" }}", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Favorite", tint = if (file.favorite) ViewerPurple else MaterialTheme.colorScheme.onSurface) }
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                downloadFile(context, api, file).onFailure { error = it.message ?: "Download failed" }
                                busy = false
                            }
                        }
                    ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, "Download") }
                    IconButton(onClick = {
                        scope.launch {
                            api.createShare(file.id).onSuccess { shareText(context, api.sharedStreamUrl(it.token), file.filename) }
                                .onFailure { error = it.message ?: "Share failed" }
                        }
                    }) { Icon(Icons.Default.Share, "Share") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            when (type) {
                QuantViewerType.IMAGE -> RemoteImage(api.mediaUrl(file.id))
                QuantViewerType.VIDEO -> RemotePlayer(api.mediaUrl(file.id), file.mime, false, false, ::setFullscreen, api.savedToken)
                QuantViewerType.AUDIO -> RemotePlayer(api.mediaUrl(file.id), file.mime, true, false, ::setFullscreen, api.savedToken)
                QuantViewerType.PDF -> RemotePdf(api.mediaUrl(file.id))
                QuantViewerType.TEXT -> RemoteText(api.mediaUrl(file.id))
                QuantViewerType.OTHER -> OtherFile(file, api)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun RemotePlayer(
    url: String?,
    mime: String,
    audio: Boolean,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    authToken: String?
) {
    val context = LocalContext.current
    if (url.isNullOrBlank()) { Message("Streaming unavailable"); return }
    val httpFactory = remember(authToken) {
        DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(120_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                buildMap {
                    put("Accept", "*/*")
                    authToken?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
                }
            )
    }
    val player = remember(url, authToken) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
    }
    var showFullscreenControl by remember(url, fullscreen) { mutableStateOf(true) }
    LaunchedEffect(showFullscreenControl, fullscreen) {
        if (showFullscreenControl) { delay(3000); showFullscreenControl = false }
    }
    DisposableEffect(player, url) {
        player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(url)).build())
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    setControllerShowTimeoutMs(3000)
                    setOnTouchListener { _, _ -> showFullscreenControl = true; false }
                }
            },
            update = {
                it.player = player
                it.useController = true
                it.controllerAutoShow = true
                it.controllerHideOnTouch = true
                it.setControllerShowTimeoutMs(3000)
            },
            modifier = if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(if (audio) 1.35f else 1.777f)
        )
        if (showFullscreenControl) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(if (fullscreen) 18.dp else 12.dp),
                shape = MaterialTheme.shapes.medium,
                color = Color.Black.copy(alpha = 0.70f)
            ) {
                IconButton(onClick = { showFullscreenControl = true; onFullscreen(!fullscreen) }) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (fullscreen) "Exit full screen" else "Full screen",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String?) {
    val bitmap by produceState<Bitmap?>(null, url) { value = withContext(Dispatchers.IO) { openStream(url) { BitmapFactory.decodeStream(it) } } }
    val imageBitmap = bitmap
    if (imageBitmap == null) Message("Loading image…") else {
        var scale by remember(imageBitmap) { mutableFloatStateOf(1f) }
        var x by remember(imageBitmap) { mutableFloatStateOf(0f) }
        var y by remember(imageBitmap) { mutableFloatStateOf(0f) }
        val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f, 5f); x += pan.x; y += pan.y }
        Box(Modifier.fillMaxSize().background(Color(0xFF101216)), contentAlignment = Alignment.Center) {
            Image(bitmap = imageBitmap.asImageBitmap(), contentDescription = "Image preview", modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y }.transformable(transform), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun RemotePdf(url: String?) {
    val context = LocalContext.current
    val file by produceState<File?>(null, url) { value = withContext(Dispatchers.IO) { cacheFile(context, url, ".pdf") } }
    if (file == null) Message("Loading PDF…", "Preview is cached temporarily for viewing.") else {
        val pdfFile = file
        val pages by produceState<List<Bitmap>>(emptyList(), pdfFile) { value = withContext(Dispatchers.IO) { renderPdf(pdfFile!!) } }
        if (pages.isEmpty()) Message("Unable to preview PDF", "Use Download to save the original file.") else LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(pages) { page -> Image(bitmap = page.asImageBitmap(), contentDescription = "PDF page", modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } }
    }
}

@Composable
private fun RemoteText(url: String?) {
    val text by produceState<String?>(null, url) { value = withContext(Dispatchers.IO) { openStream(url) { it.bufferedReader().use { r -> r.readText() } } } }
    if (text == null) Message("Loading text…") else LazyColumn(contentPadding = PaddingValues(18.dp)) { item { Text(text!!) } }
}

@Composable
private fun OtherFile(file: QuantFile, api: QuantxDriveApi) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.InsertDriveFile, null, tint = ViewerPurple, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text(file.filename, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("${formatBytes(file.size)} • ${file.mime.ifBlank { "unknown type" }}", textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Button(enabled = !busy, onClick = { busy = true; scope.launch { downloadFile(context, api, file); busy = false } }) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, null)
            Spacer(Modifier.width(8.dp))
            Text("Download")
        }
    }
}

@Composable
private fun Message(title: String, detail: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            CircularProgressIndicator(color = ViewerPurple)
            Spacer(Modifier.height(14.dp))
            Text(title, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            detail?.let { Spacer(Modifier.height(6.dp)); Text(it, textAlign = TextAlign.Center) }
        }
    }
}

private suspend fun downloadFile(context: Context, api: QuantxDriveApi, file: QuantFile): Result<Uri?> = withContext(Dispatchers.IO) {
    val url = api.mediaUrl(file.id) ?: return@withContext Result.failure(IllegalStateException("Download URL unavailable"))
    runCatching { saveToDownloads(context, url, file.filename, file.mime, api.savedToken) }
}

private suspend fun saveToDownloads(context: Context, url: String, filename: String, mime: String, token: String?): Uri? {
    val safe = filename.substringAfterLast('/').ifBlank { "download" }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create Downloads file")
        try {
            http(url, token).inputStream.use { input -> resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) } ?: error("Could not open Downloads file") }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            withContext(Dispatchers.Main) { Toast.makeText(context, "Downloaded to Downloads", Toast.LENGTH_LONG).show() }
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    } else {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val request = android.app.DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setDescription("Downloading from QuantxDrive")
            .setMimeType(mime.ifBlank { "application/octet-stream" })
            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safe)
        token?.let { request.addRequestHeader("Authorization", "Bearer $it") }
        manager.enqueue(request)
        withContext(Dispatchers.Main) { Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show() }
        null
    }
}

private fun http(url: String, token: String?): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
    connectTimeout = 20000
    readTimeout = 120000
    useCaches = false
    setRequestProperty("Accept", "*/*")
    token?.let { setRequestProperty("Authorization", "Bearer $it") }
    if (responseCode !in 200..299) error("Download failed ($responseCode)")
}

private fun <T> openStream(url: String?, reader: (java.io.InputStream) -> T): T? = if (url.isNullOrBlank()) null else runCatching { http(url, null).inputStream.use(reader) }.getOrNull()

private fun cacheFile(context: Context, url: String?, suffix: String): File? {
    if (url.isNullOrBlank()) return null
    val f = File.createTempFile("quantx_", suffix, context.cacheDir)
    return runCatching { http(url, null).inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }; f }.getOrElse { f.delete(); null }
}

private fun renderPdf(file: File): List<Bitmap> = runCatching {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        PdfRenderer(pfd).use { renderer ->
            buildList {
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        add(bitmap)
                    }
                }
            }
        }
    }
}.getOrDefault(emptyList())

private fun shareText(context: Context, url: String, filename: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, filename)
        putExtra(Intent.EXTRA_TEXT, url)
    }, "Share file"))
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    else -> "${"%.2f".format(bytes / 1024.0 / 1024.0 / 1024.0)} GB"
}