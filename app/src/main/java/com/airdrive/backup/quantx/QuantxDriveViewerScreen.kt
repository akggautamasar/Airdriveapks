package com.airdrive.backup.quantx

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val ViewerBg = Color(0xFFF7F8FC)
private val ViewerPurple = Color(0xFF6D4AFF)
private enum class QuantViewerType { IMAGE, VIDEO, AUDIO, PDF, TEXT, OTHER }

private fun quantViewerType(file: QuantFile): QuantViewerType {
    val name = file.filename.lowercase(); val mime = file.mime.lowercase()
    return when {
        mime.startsWith("image/") || name.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic|heif)$")) -> QuantViewerType.IMAGE
        mime.startsWith("video/") || name.matches(Regex(".*\\.(mp4|m4v|mkv|webm|mov|avi|3gp)$")) -> QuantViewerType.VIDEO
        mime.startsWith("audio/") || name.matches(Regex(".*\\.(mp3|m4a|aac|ogg|opus|wav|flac|amr)$")) -> QuantViewerType.AUDIO
        mime.contains("pdf") || name.endsWith(".pdf") -> QuantViewerType.PDF
        mime.startsWith("text/") || name.matches(Regex(".*\\.(txt|csv|json|xml|html|htm|md|log)$")) -> QuantViewerType.TEXT
        else -> QuantViewerType.OTHER
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun QuantxDriveViewerScreen(file: QuantFile, api: QuantxDriveApi, onBack: () -> Unit, onFavorite: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val type = remember(file.id) { quantViewerType(file) }
    var shareBusy by remember(file.id) { mutableStateOf(false) }; var downloadBusy by remember(file.id) { mutableStateOf(false) }; var error by remember(file.id) { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(containerColor = if (type == QuantViewerType.VIDEO || type == QuantViewerType.AUDIO) Color.Black else ViewerBg, topBar = { TopAppBar(
        title = { Column(Modifier.fillMaxWidth()) { Text(file.filename, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold); Text("${formatBytes(file.size)} • ${file.category.ifBlank { "other" }}", style = MaterialTheme.typography.labelSmall) } },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
        actions = {
            IconButton(onClick = onFavorite) { Icon(Icons.Default.Favorite, "Favorite", tint = if(file.favorite) ViewerPurple else MaterialTheme.colorScheme.onSurface) }
            IconButton(enabled = !downloadBusy, onClick = { downloadBusy = true; scope.launch { downloadFile(context, api, file).onFailure { error = it.message ?: "Download failed" }; downloadBusy = false } }) { if(downloadBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Download, "Download") }
            IconButton(enabled = !shareBusy, onClick = { shareBusy = true; scope.launch { api.createShare(file.id).onSuccess { share -> shareText(context, api.sharedStreamUrl(share.token), file.filename) }.onFailure { error = it.message ?: "Unable to create share link" }; shareBusy = false } }) { Icon(Icons.Default.Share, "Share") }
        }
    ) }) { padding -> Column(Modifier.fillMaxSize().padding(padding)) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        when(type) {
            QuantViewerType.IMAGE -> QuantRemoteImage(api.mediaUrl(file.id))
            QuantViewerType.VIDEO -> QuantRemoteMedia(api.mediaUrl(file.id), file.mime, false)
            QuantViewerType.AUDIO -> QuantRemoteMedia(api.mediaUrl(file.id), file.mime, true)
            QuantViewerType.PDF -> QuantRemotePdf(api.mediaUrl(file.id))
            QuantViewerType.TEXT -> QuantRemoteText(api.mediaUrl(file.id))
            QuantViewerType.OTHER -> QuantOtherFile(file, api)
        }
    } }
}

@Composable @OptIn(UnstableApi::class)
private fun QuantRemoteMedia(url: String?, mime: String, audio: Boolean) {
    val context = LocalContext.current
    if(url.isNullOrBlank()) { ViewerMessage("Streaming unavailable", "No authenticated media URL is available."); return }
    val player = remember(url) { ExoPlayer.Builder(context).build() }; var error by remember(url) { mutableStateOf<String?>(null) }; var speed by rememberSaveable(url) { mutableFloatStateOf(1f) }
    DisposableEffect(player, url) {
        val listener = object : Player.Listener { override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error = e.message ?: "Playback failed" } }
        player.addListener(listener); player.setMediaItem(MediaItem.Builder().setUri(Uri.parse(url)).apply { if(mime.isNotBlank()) setMimeType(mime) }.build()); player.prepare(); player.playWhenReady = true; player.setPlaybackSpeed(speed)
        onDispose { player.removeListener(listener); player.release() }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } }, update = { it.player = player }, modifier = Modifier.fillMaxWidth().aspectRatio(if(audio) 1.35f else 1.777f))
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(if(audio) "Audio streaming" else "Video streaming", color = Color.White, fontWeight = FontWeight.SemiBold); Spacer(Modifier.weight(1f)); Text("Speed", color = Color.White, style = MaterialTheme.typography.labelMedium); Spacer(Modifier.width(8.dp)); AssistChip(onClick = { speed = when(speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }; player.setPlaybackSpeed(speed) }, label = { Text("${speed}x") }) }
        error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(16.dp)) }
    }
}

@Composable private fun QuantRemoteImage(url: String?) { val bitmap by produceState<Bitmap?>(null, url) { value = withContext(Dispatchers.IO) { openStream(url) { BitmapFactory.decodeStream(it) } } }; if(bitmap == null) ViewerMessage("Loading image…") else QuantZoomImage(bitmap!!) }
@Composable private fun QuantZoomImage(bitmap: Bitmap) { var scale by remember(bitmap) { mutableFloatStateOf(1f) }; var x by remember(bitmap) { mutableFloatStateOf(0f) }; var y by remember(bitmap) { mutableFloatStateOf(0f) }; val transform = rememberTransformableState { zoom, pan, _ -> scale = (scale * zoom).coerceIn(1f,5f); x += pan.x; y += pan.y }; Box(Modifier.fillMaxSize().background(Color(0xFF101216)), contentAlignment = Alignment.Center) { Image(bitmap.asImageBitmap(), "Image preview", Modifier.fillMaxSize().graphicsLayer { scaleX=scale; scaleY=scale; translationX=x; translationY=y }.transformable(transform), contentScale=ContentScale.Fit) } }
@Composable private fun QuantRemotePdf(url: String?) { val context = LocalContext.current; val pdfFile by produceState<File?>(null, url) { value = withContext(Dispatchers.IO) { downloadToCache(context,url,".pdf") } }; if(pdfFile == null) ViewerMessage("Loading PDF…", "The PDF is cached only for viewing.") else QuantPdfPages(pdfFile!!) }
@Composable private fun QuantPdfPages(file: File) { val pages by produceState<List<Bitmap>>(emptyList(), file) { value = withContext(Dispatchers.IO) { runCatching { ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { renderer -> buildList { for(i in 0 until renderer.pageCount) renderer.openPage(i).use { page -> val bitmap=Bitmap.createBitmap(page.width,page.height,Bitmap.Config.ARGB_8888); bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); add(bitmap) } } } } }.getOrDefault(emptyList()) } }; if(pages.isEmpty()) ViewerMessage("Unable to render PDF", "Use Download to save the original PDF.") else LazyColumn(contentPadding=PaddingValues(10.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) { items(pages) { bitmap -> Image(bitmap.asImageBitmap(), "PDF page", Modifier.fillMaxWidth(), contentScale=ContentScale.FillWidth) } } }
@Composable private fun QuantRemoteText(url: String?) { val text by produceState<String?>(null,url) { value=withContext(Dispatchers.IO) { openStream(url) { it.bufferedReader().use { r -> r.readText() } } } }; if(text == null) ViewerMessage("Loading text…") else LazyColumn(contentPadding=PaddingValues(18.dp)) { item { Text(text!!, style=MaterialTheme.typography.bodyMedium) } } }
@Composable private fun QuantOtherFile(file: QuantFile, api: QuantxDriveApi) { val context=LocalContext.current; val scope=rememberCoroutineScope(); var busy by remember { mutableStateOf(false) }; Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.Center) { Icon(Icons.Default.InsertDriveFile,null,tint=ViewerPurple,modifier=Modifier.size(72.dp)); Spacer(Modifier.height(16.dp)); Text(file.filename,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center); Spacer(Modifier.height(8.dp)); Text("${formatBytes(file.size)} • ${file.mime.ifBlank { "unknown type" }}",color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center); Spacer(Modifier.height(22.dp)); Button(enabled=!busy,onClick={ busy=true; scope.launch { downloadFile(context,api,file); busy=false } }) { if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.Download,null); Spacer(Modifier.width(8.dp)); Text("Download") }; file.tgLink?.let { Spacer(Modifier.height(8.dp)); OutlinedButton(onClick={ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(it))) } }) { Text("Open in Telegram") } } } }
@Composable private fun ViewerMessage(title:String, detail:String?=null) { Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center) { Column(horizontalAlignment=Alignment.CenterHorizontally) { CircularProgressIndicator(color=ViewerPurple); Spacer(Modifier.height(14.dp)); Text(title,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center); detail?.let { Spacer(Modifier.height(6.dp)); Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center) } } } }

private suspend fun downloadFile(context: Context, api: QuantxDriveApi, file: QuantFile): Result<Uri?> = withContext(Dispatchers.IO) { val url=api.mediaUrl(file.id) ?: return@withContext Result.failure(IllegalStateException("Download URL unavailable")); runCatching { downloadStreamToDownloads(context,url,file.filename,file.mime,api.savedToken) } }
private suspend fun downloadStreamToDownloads(context: Context,url:String,filename:String,mime:String,token:String?):Uri? { val safeName=filename.substringAfterLast('/').ifBlank { "download" }; if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { val values=ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME,safeName); put(MediaStore.Downloads.MIME_TYPE,mime.ifBlank { "application/octet-stream" }); put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING,1) }; val resolver=context.contentResolver; val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values) ?: error("Could not create Downloads file"); try { openHttpConnection(url,token).inputStream.use { input -> resolver.openOutputStream(uri)?.use { output -> input.copyTo(output) } ?: error("Could not open Downloads file") }; values.clear(); values.put(MediaStore.Downloads.IS_PENDING,0); resolver.update(uri,values,null,null); withContext(Dispatchers.Main) { Toast.makeText(context,"Downloaded to Downloads: $safeName",Toast.LENGTH_LONG).show() }; uri } catch(t:Throwable) { resolver.delete(uri,null,null); throw t } } else { val manager=context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager; val request=android.app.DownloadManager.Request(Uri.parse(url)).setTitle(safeName).setDescription("Downloading from QuantxDrive").setMimeType(mime.ifBlank { "application/octet-stream" }).setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setAllowedOverMetered(true).setAllowedOverRoaming(true).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,safeName); token?.let { request.addRequestHeader("Authorization","Bearer $it") }; val id=manager.enqueue(request); withContext(Dispatchers.Main) { Toast.makeText(context,"Download started",Toast.LENGTH_SHORT).show() }; Uri.parse("content://downloads/my_downloads/$id") } }
private fun openHttpConnection(url:String,token:String?):HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout=20000; readTimeout=120000; useCaches=false; setRequestProperty("Accept","*/*"); token?.let { setRequestProperty("Authorization","Bearer $it") }; if(responseCode !in 200..299) error("Download failed ($responseCode)") }
private fun <T> openStream(url:String?,reader:(java.io.InputStream)->T):T? { if(url.isNullOrBlank()) return null; return runCatching { openHttpConnection(url,null).inputStream.use(reader) }.getOrNull() }
private fun shareText(context:Context,url:String,filename:String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_SUBJECT,filename); putExtra(Intent.EXTRA_TEXT,url) },"Share file")) }
private fun downloadToCache(context:Context,url:String?,suffix:String):File? { if(url.isNullOrBlank()) return null; val file=File.createTempFile("quantx_",suffix,context.cacheDir); return runCatching { openHttpConnection(url,null).inputStream.use { input -> file.outputStream().use { output -> input.copyTo(output) } }; file }.getOrElse { file.delete(); null } }
private fun formatBytes(bytes:Long):String = when { bytes<=0L -> "0 B"; bytes<1024L*1024L -> "${bytes/1024L} KB"; bytes<1024L*1024L*1024L -> "${"%.1f".format(bytes/1024.0/1024.0)} MB"; else -> "${"%.2f".format(bytes/1024.0/1024.0/1024.0)} GB" }
