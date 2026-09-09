package com.airdrive.backup.telegram

import android.content.Context
import android.util.Log
import com.airdrive.backup.BuildConfig
import com.airdrive.backup.data.prefs.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class AuthState { NEEDS_CREDENTIALS, UNKNOWN, WAIT_PHONE_NUMBER, WAIT_CODE, WAIT_PASSWORD, READY, LOGGED_OUT, CLOSED }
class TdLibException(val code: Int, message: String) : Exception(message)
sealed class ChannelCheck { data class Ok(val title: String) : ChannelCheck(); data class Failed(val reason: String) : ChannelCheck() }
data class ResolvedChat(val chatId: Long, val title: String)
data class DownloadedFile(val path: String, val fileName: String, val sizeBytes: Long)
sealed class RemoteFile { data class Present(val fileName: String, val sizeBytes: Long) : RemoteFile(); data class Missing(val reason: String) : RemoteFile(); data class Unknown(val reason: String) : RemoteFile() }
data class TelegramChannelFile(val chatId: Long, val messageId: Long, val fileName: String, val sizeBytes: Long, val categoryName: String, val dateMillis: Long)
private sealed class SendOutcome { data class Success(val messageId: Long) : SendOutcome(); data class Failed(val code: Int, val reason: String) : SendOutcome() }

class TdClient private constructor(private val appContext: Context) {
    private val tag = "AirDrive.TdClient"
    private var client: Client? = null
    private val settings = SettingsStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _authState = MutableStateFlow(AuthState.UNKNOWN)
    val authState: StateFlow<AuthState> = _authState
    private val _lastAuthError = MutableStateFlow<String?>(null)
    val lastAuthError: StateFlow<String?> = _lastAuthError
    val isReady: Boolean get() = _authState.value == AuthState.READY
    private val knownChatIds: MutableSet<Long> = Collections.synchronizedSet(HashSet<Long>())
    private val chatListMutex = Mutex()
    @Volatile private var chatListLoaded = false
    @Volatile private var awaitingParameters = false
    @Volatile private var selfChatId = 0L
    private val sendLock = Any()
    private val pendingSends = HashMap<Long, CompletableDeferred<SendOutcome>>()
    private val earlyOutcomes = HashMap<Long, SendOutcome>()
    @Volatile var onUploadProgress: ((String, Long, Long) -> Unit)? = null
    @Volatile var onDownloadProgress: ((Int, Long, Long) -> Unit)? = null

    fun start() { if (client != null) return; client = Client.create({ update -> handleUpdate(update) }, null, null) }
    private fun handleUpdate(update: TdApi.Object) { when (update) { is TdApi.UpdateAuthorizationState -> onAuthorizationState(update.authorizationState); is TdApi.UpdateNewChat -> knownChatIds.add(update.chat.id); is TdApi.UpdateMessageSendSucceeded -> completeSend(update.oldMessageId, SendOutcome.Success(update.message.id)); is TdApi.UpdateMessageSendFailed -> completeSend(update.oldMessageId, SendOutcome.Failed(update.error.code, update.error.message ?: "send failed")); is TdApi.UpdateFile -> reportFileProgress(update.file); else -> Unit } }
    private fun reportFileProgress(file: TdApi.File) { val total = (if (file.expectedSize > 0) file.expectedSize else file.size).toLong(); val downloadListener = onDownloadProgress; val local = file.local; if (downloadListener != null && local != null && local.downloadedSize > 0) downloadListener(file.id, local.downloadedSize.toLong(), total); val uploadListener = onUploadProgress ?: return; val path = local?.path ?: return; val remote = file.remote ?: return; uploadListener(path, remote.uploadedSize.toLong(), total) }
    private fun onAuthorizationState(state: TdApi.AuthorizationState) { when (state) { is TdApi.AuthorizationStateWaitTdlibParameters -> { awaitingParameters = true; sendTdlibParameters() }; is TdApi.AuthorizationStateWaitPhoneNumber -> { awaitingParameters = false; _authState.value = AuthState.WAIT_PHONE_NUMBER }; is TdApi.AuthorizationStateWaitCode -> _authState.value = AuthState.WAIT_CODE; is TdApi.AuthorizationStateWaitPassword -> _authState.value = AuthState.WAIT_PASSWORD; is TdApi.AuthorizationStateReady -> { awaitingParameters = false; chatListLoaded = false; _authState.value = AuthState.READY }; is TdApi.AuthorizationStateLoggingOut -> resetSession(AuthState.LOGGED_OUT); is TdApi.AuthorizationStateClosed -> resetSession(AuthState.CLOSED); else -> Unit } }
    private fun resetSession(next: AuthState) { knownChatIds.clear(); chatListLoaded = false; selfChatId = 0L; _authState.value = next }
    private fun sendTdlibParameters() { scope.launch { val creds = settings.apiCredentials.first(); if (!creds.isUsable) { _authState.value = AuthState.NEEDS_CREDENTIALS; return@launch }; val params = TdApi.SetTdlibParameters().apply { useTestDc = false; databaseDirectory = appContext.filesDir.absolutePath + "/tdlib"; filesDirectory = appContext.filesDir.absolutePath + "/tdlib-files"; useFileDatabase = true; useChatInfoDatabase = true; useMessageDatabase = true; useSecretChats = false; apiId = creds.apiId; apiHash = creds.apiHash; systemLanguageCode = "en"; deviceModel = android.os.Build.MODEL ?: "Android"; systemVersion = android.os.Build.VERSION.RELEASE ?: "unknown"; applicationVersion = BuildConfig.VERSION_NAME }; try { send(params); _lastAuthError.value = null } catch (e: Exception) { _lastAuthError.value = e.message ?: e.javaClass.simpleName; _authState.value = AuthState.NEEDS_CREDENTIALS } } }
    fun retryTdlibParameters() { if (!awaitingParameters) return; _authState.value = AuthState.UNKNOWN; sendTdlibParameters() }
    suspend fun submitPhoneNumber(phoneNumber: String) { authStep { send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null)) } }
    suspend fun submitCode(code: String) { authStep { send(TdApi.CheckAuthenticationCode(code)) } }
    suspend fun submitPassword(password: String) { authStep { send(TdApi.CheckAuthenticationPassword(password)) } }
    suspend fun logOut() { send(TdApi.LogOut()) }
    private suspend fun authStep(block: suspend () -> Unit) { try { _lastAuthError.value = null; block() } catch (e: Exception) { _lastAuthError.value = e.message ?: e.javaClass.simpleName; throw e } }
    suspend fun awaitReady(timeoutMs: Long = 45_000): Boolean = _authState.value == AuthState.READY || withTimeoutOrNull(timeoutMs) { authState.first { it == AuthState.READY } } != null
    suspend fun ensureChatListLoaded(force: Boolean = false) { if (chatListLoaded && !force) return; chatListMutex.withLock { if (chatListLoaded && !force) return; for (list in listOf<TdApi.ChatList>(TdApi.ChatListMain(), TdApi.ChatListArchive())) { var pages = 0; while (pages < 40) { pages++; try { send(TdApi.LoadChats().apply { chatList = list; limit = 500 }) } catch (e: TdLibException) { if (e.code != 404) Log.w(tag, "loadChats failed: ${e.code} ${e.message}"); break }; delay(120) } }; chatListLoaded = true } }
    private suspend fun tryGetChat(chatId: Long): TdApi.Chat? = try { send(TdApi.GetChat().apply { this.chatId = chatId }) as TdApi.Chat } catch (_: Exception) { null }
    private suspend fun requireChat(chatId: Long): TdApi.Chat { if (chatId == 0L) throw TdLibException(400, "No destination is set yet"); tryGetChat(chatId)?.let { knownChatIds.add(chatId); return it }; ensureChatListLoaded(); tryGetChat(chatId)?.let { knownChatIds.add(chatId); return it }; if (chatId < CHANNEL_ID_BASE) { val opened = runCatching { send(TdApi.CreateSupergroupChat().apply { supergroupId = CHANNEL_ID_BASE - chatId; force = false }) as TdApi.Chat }.getOrNull(); if (opened != null) { knownChatIds.add(opened.id); return opened } }; ensureChatListLoaded(true); tryGetChat(chatId)?.let { knownChatIds.add(chatId); return it }; throw TdLibException(400, chatNotFoundHelp(chatId)) }
    private fun chatNotFoundHelp(chatId: Long): String { val hint = if (chatId >= 0 || chatId > CHANNEL_ID_BASE) " That does not look like a channel ID — channel IDs look like -100xxxxxxxxxx." else ""; return "Chat not found ($chatId).$hint Check that the signed-in account is a member (ideally an admin) of the channel." }
    suspend fun savedMessagesChatId(): Long { selfChatId.takeIf { it != 0L }?.let { return it }; val me = send(TdApi.GetMe()) as TdApi.User; val chat = send(TdApi.CreatePrivateChat().apply { userId = me.id; force = false }) as TdApi.Chat; selfChatId = chat.id; knownChatIds.add(chat.id); return chat.id }
    suspend fun resolveChatInput(raw: String): ResolvedChat { val text = raw.trim(); if (text.isEmpty()) throw TdLibException(400, "Enter a channel ID, @username or t.me link"); inviteLinkOf(text)?.let { link -> val info = send(TdApi.CheckChatInviteLink().apply { inviteLink = link }) as TdApi.ChatInviteLinkInfo; if (info.chatId != 0L) { val chat = requireChat(info.chatId); return ResolvedChat(chat.id, chat.title.orEmpty().ifBlank { "(untitled chat)" }) }; val joined = send(TdApi.JoinChatByInviteLink().apply { inviteLink = link }) as TdApi.Chat; knownChatIds.add(joined.id); return ResolvedChat(joined.id, joined.title.orEmpty().ifBlank { "(untitled chat)" }) }; usernameOf(text)?.let { username -> val chat = send(TdApi.SearchPublicChat(username)) as TdApi.Chat; knownChatIds.add(chat.id); return ResolvedChat(chat.id, chat.title.orEmpty().ifBlank { "@$username" }) }; val id = internalLinkChatId(text) ?: normalizeChannelId(text) ?: throw TdLibException(400, "Not a channel ID, @username or t.me link"); val chat = requireChat(id); return ResolvedChat(chat.id, chat.title.orEmpty().ifBlank { "(untitled channel)" }) }
    private val usernamePattern = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
    private fun inviteLinkOf(text: String): String? { if (text.startsWith("+") && text.length > 4) return "https://t.me/$text"; val path = telegramPath(text) ?: return null; return if (path.startsWith("+") || path.startsWith("joinchat/")) "https://t.me/$path" else null }
    private fun usernameOf(text: String): String? { if (text.startsWith("@")) return text.drop(1).takeIf { usernamePattern.matches(it) }; val path = telegramPath(text); if (path != null) { val first = path.substringBefore('/').substringBefore('?'); return first.takeIf { usernamePattern.matches(it) } }; return text.takeIf { !it.contains('/') && usernamePattern.matches(it) } }
    private fun internalLinkChatId(text: String): Long? { val path = telegramPath(text) ?: return null; if (!path.startsWith("c/")) return null; val digits = path.removePrefix("c/").substringBefore('/').filter { it.isDigit() }; return digits.toLongOrNull()?.let { CHANNEL_ID_BASE - it } }
    private fun telegramPath(text: String): String? { val lower = text.lowercase(); for (host in listOf("t.me/", "telegram.me/", "telegram.dog/")) { val at = lower.indexOf(host); if (at >= 0) return text.substring(at + host.length).trim().trimEnd('/') }; return null }
    suspend fun createChannel(title: String): ResolvedChat { val name = title.trim().take(128).ifBlank { "AirDrive Backup" }; val chat = send(TdApi.CreateNewSupergroupChat().apply { this.title = name; isChannel = true; description = "Created by AirDrive"; location = null; forImport = false }) as TdApi.Chat; knownChatIds.add(chat.id); return ResolvedChat(chat.id, chat.title.orEmpty().ifBlank { name }) }
    suspend fun checkChannel(chatId: Long): ChannelCheck = try { val chat = requireChat(chatId); ChannelCheck.Ok(chat.title.orEmpty().ifBlank { "(untitled channel)" }) } catch (e: Exception) { ChannelCheck.Failed(e.message ?: e.javaClass.simpleName) }
    suspend fun scanChannelFiles(chatId: Long, onPage: (Int) -> Unit = {}): List<TelegramChannelFile> { requireChat(chatId); val result = ArrayList<TelegramChannelFile>(); var fromMessageId = 0L; var retryAttempt = 0; while (true) { val history = try { send(TdApi.GetChatHistory().apply { this.chatId = chatId; this.fromMessageId = fromMessageId; offset = 0; limit = 100; onlyLocal = false }) as TdApi.Messages } catch (e: TdLibException) { val retryable = e.code == 429 || e.code == 500 || e.code == 408; if (!retryable || retryAttempt >= 12) throw e; val retrySeconds = Regex("\\d+").find(e.message ?: "")?.value?.toLongOrNull()?.coerceIn(1L, 600L) ?: (2L shl retryAttempt.coerceAtMost(8)); retryAttempt++; delay(retrySeconds * 1000L); continue }; retryAttempt = 0; if (history.messages.isEmpty()) break; history.messages.forEach { message -> telegramFileFromMessage(message, chatId)?.let { result += it } }; onPage(result.size); val oldest = history.messages.last().id; if (oldest <= 1L || oldest == fromMessageId) break; fromMessageId = oldest }; return result }
    private fun telegramFileFromMessage(message: TdApi.Message, chatId: Long): TelegramChannelFile? { val content = message.content; val nameAndSize = when (content) { is TdApi.MessageDocument -> content.document.fileName.orEmpty().ifBlank { "file" } to content.document.document.size.toLong(); is TdApi.MessageVideo -> content.video.fileName.orEmpty().ifBlank { "video.mp4" } to content.video.video.size.toLong(); is TdApi.MessageAudio -> content.audio.fileName.orEmpty().ifBlank { "audio" } to content.audio.audio.size.toLong(); is TdApi.MessageAnimation -> content.animation.fileName.orEmpty().ifBlank { "animation" } to content.animation.animation.size.toLong(); is TdApi.MessagePhoto -> "photo.jpg" to (content.photo.sizes.maxByOrNull { it.photo.size }?.photo?.size?.toLong() ?: 0L); else -> null } ?: return null; return TelegramChannelFile(chatId, message.id, nameAndSize.first, nameAndSize.second, categoryForName(nameAndSize.first), message.date.toLong() * 1000L) }
    private fun categoryForName(name: String): String { val n = name.lowercase(); return when { n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".heic") || n.endsWith(".gif") -> "PHOTOS"; n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov") || n.endsWith(".avi") -> "VIDEOS"; n.endsWith(".pdf") -> "PDFS"; n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".ppt") || n.endsWith(".pptx") || n.endsWith(".txt") || n.endsWith(".csv") -> "WORD_EXCEL"; n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") || n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg") -> "AUDIO"; else -> "OTHER_FILES" } }
    suspend fun uploadFile(localPath: String, chatId: Long, caption: String, sizeBytes: Long): Long { var attempt = 0; while (true) { try { requireChat(chatId); return sendDocumentAndAwait(localPath, chatId, caption, sizeBytes) } catch (e: TdLibException) { val retryable = e.code == 429 || e.code == 500; if (!retryable || attempt >= 10) throw e; val retryAfter = Regex("\\d+").find(e.message ?: "")?.value?.toLongOrNull() ?: 5L; delay(retryAfter.coerceIn(1L, 600L) * 1000L); attempt++ } } }
    private suspend fun sendDocumentAndAwait(localPath: String, chatId: Long, caption: String, sizeBytes: Long): Long { val inputDocument = TdApi.InputDocument().apply { document = TdApi.InputFileLocal(localPath); thumbnail = null; disableContentTypeDetection = true }; val content = TdApi.InputMessageDocument().apply { document = inputDocument; this.caption = TdApi.FormattedText(caption, emptyArray()) }; val request = TdApi.SendMessage().apply { this.chatId = chatId; inputMessageContent = content }; val queued = send(request) as TdApi.Message; val tempId = queued.id; val waiter = registerSend(tempId); try { val budget = (180_000L + (sizeBytes / 20_000L) * 1000L).coerceAtMost(3 * 60 * 60 * 1000L); val outcome = withTimeoutOrNull(budget) { waiter.await() } ?: throw TdLibException(408, "Upload timed out after ${budget / 1000}s"); return when (outcome) { is SendOutcome.Success -> outcome.messageId; is SendOutcome.Failed -> throw TdLibException(outcome.code, outcome.reason) } } finally { forgetSend(tempId) } }
    private fun registerSend(tempId: Long): CompletableDeferred<SendOutcome> { val waiter = CompletableDeferred<SendOutcome>(); synchronized(sendLock) { val already = earlyOutcomes.remove(tempId); if (already != null) waiter.complete(already) else pendingSends[tempId] = waiter }; return waiter }
    private fun completeSend(tempId: Long, outcome: SendOutcome) { synchronized(sendLock) { val waiter = pendingSends.remove(tempId); if (waiter != null) waiter.complete(outcome) else { if (earlyOutcomes.size > 256) earlyOutcomes.clear(); earlyOutcomes[tempId] = outcome } } }
    private fun forgetSend(tempId: Long) { synchronized(sendLock) { pendingSends.remove(tempId); earlyOutcomes.remove(tempId) } }

    suspend fun downloadFileRange(chatId: Long, messageId: Long, offset: Long, limit: Int): ByteArray {
        requireChat(chatId)
        require(offset >= 0L)
        require(limit > 0)
        val message = send(TdApi.GetMessage().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.Message
        val payload = fileOf(message.content) ?: throw TdLibException(404, "That message no longer holds a file")
        val file = send(TdApi.DownloadFile().apply {
            fileId = payload.first.id
            priority = 32
            this.offset = offset
            this.limit = limit.toLong()
            synchronous = true
        }) as TdApi.File
        val local = file.local ?: throw TdLibException(500, "Telegram did not return a readable file range")
        val path = local.path.takeIf { it.isNotBlank() } ?: throw TdLibException(500, "Telegram did not return a local file path")
        val downloadOffset = local.downloadOffset.toLong().coerceAtLeast(0L)
        val downloadedPrefixSize = local.downloadedPrefixSize.toLong().coerceAtLeast(0L)
        if (downloadedPrefixSize <= 0L) return ByteArray(0)
        if (offset < downloadOffset || offset >= downloadOffset + downloadedPrefixSize) {
            throw TdLibException(500, "Telegram returned a range outside the requested offset")
        }
        val relativeOffset = offset - downloadOffset
        val available = downloadedPrefixSize - relativeOffset
        val toRead = minOf(limit.toLong(), available).toInt()
        if (toRead <= 0) return ByteArray(0)
        return RandomAccessFile(File(path), "r").use { raf ->
            if (downloadOffset >= raf.length()) return@use ByteArray(0)
            raf.seek((downloadOffset + relativeOffset).coerceAtMost(raf.length()))
            val out = ByteArray(minOf(toRead.toLong(), raf.length() - raf.filePointer).toInt())
            var done = 0
            while (done < out.size) {
                val n = raf.read(out, done, out.size - done)
                if (n <= 0) break
                done += n
            }
            if (done == out.size) out else out.copyOf(done)
        }
    }

    suspend fun downloadMessageFile(chatId: Long, messageId: Long): DownloadedFile { requireChat(chatId); val message = send(TdApi.GetMessage().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.Message; val payload = fileOf(message.content) ?: throw TdLibException(404, "That message no longer holds a file"); val done = send(TdApi.DownloadFile().apply { fileId = payload.first.id; priority = 16; offset = 0; limit = 0; synchronous = true }) as TdApi.File; val path = done.local?.path ?: throw TdLibException(500, "Telegram did not return the file"); val size = (if (done.size > 0) done.size else done.expectedSize).toLong(); return DownloadedFile(path, payload.second, size) }
    suspend fun editMessageDocument(chatId: Long, messageId: Long, localPath: String, caption: String) { val inputDocument = TdApi.InputDocument().apply { document = TdApi.InputFileLocal(localPath); thumbnail = null; disableContentTypeDetection = true }; val content = TdApi.InputMessageDocument().apply { document = inputDocument; this.caption = TdApi.FormattedText(caption, emptyArray()) }; send(TdApi.EditMessageMedia().apply { this.chatId = chatId; this.messageId = messageId; inputMessageContent = content }) }
    suspend fun pinMessage(chatId: Long, messageId: Long) { send(TdApi.PinChatMessage().apply { this.chatId = chatId; this.messageId = messageId; disableNotification = true; onlyForSelf = false }) }
    suspend fun deleteMessages(chatId: Long, messageIds: LongArray) { if (messageIds.isEmpty()) return; requireChat(chatId); messageIds.toList().chunked(100).forEach { batch -> send(TdApi.DeleteMessages().apply { this.chatId = chatId; this.messageIds = batch.toLongArray(); revoke = true }) } }
    suspend fun probeMessage(chatId: Long, messageId: Long): RemoteFile = try { requireChat(chatId); val message = send(TdApi.GetMessage().apply { this.chatId = chatId; this.messageId = messageId }) as TdApi.Message; val payload = fileOf(message.content); if (payload == null) RemoteFile.Missing("The message no longer holds a file") else { val file = payload.first; RemoteFile.Present(payload.second, (if (file.size > 0) file.size else file.expectedSize).toLong()) } } catch (e: TdLibException) { val gone = e.code == 404 || e.message?.contains("not found", true) == true || e.message?.contains("MESSAGE_ID_INVALID", true) == true; if (gone) RemoteFile.Missing(e.message ?: "Telegram has no such message") else RemoteFile.Unknown(e.message ?: "Telegram error ${e.code}") } catch (e: Exception) { RemoteFile.Unknown(e.message ?: e.javaClass.simpleName) }
    private fun fileOf(content: TdApi.MessageContent?): Pair<TdApi.File, String>? = when (content) { is TdApi.MessageDocument -> content.document.document to content.document.fileName.orEmpty().ifBlank { "file" }; is TdApi.MessageVideo -> content.video.video to content.video.fileName.orEmpty().ifBlank { "video.mp4" }; is TdApi.MessageAudio -> content.audio.audio to content.audio.fileName.orEmpty().ifBlank { "audio.mp3" }; is TdApi.MessageAnimation -> content.animation.animation to content.animation.fileName.orEmpty().ifBlank { "animation.mp4" }; is TdApi.MessagePhoto -> content.photo.sizes.maxByOrNull { it.photo.expectedSize }?.let { it.photo to "photo.jpg" }; else -> null }
    suspend fun findLatestOwnDocument(caption: String): TdApi.Message? { val chatId = savedMessagesChatId(); val found = send(TdApi.SearchChatMessages().apply { this.chatId = chatId; query = caption; fromMessageId = 0L; offset = 0; limit = 5; filter = TdApi.SearchMessagesFilterDocument() }) as TdApi.FoundChatMessages; return found.messages.firstOrNull() }
    suspend fun downloadFile(message: TdApi.Message): DownloadedFile { val payload = fileOf(message.content) ?: throw TdLibException(404, "That message holds no file"); val done = send(TdApi.DownloadFile().apply { fileId = payload.first.id; priority = 16; offset = 0; limit = 0; synchronous = true }) as TdApi.File; val path = done.local?.path ?: throw TdLibException(500, "Telegram did not return the file"); val size = (if (done.size > 0) done.size else done.expectedSize).toLong(); return DownloadedFile(path, payload.second, size) }
    private suspend fun send(function: TdApi.Function<*>): TdApi.Object = suspendCancellableCoroutine { cont -> client?.send(function) { result -> if (result is TdApi.Error) cont.resumeWithException(TdLibException(result.code, result.message)) else cont.resume(result) } ?: cont.resumeWithException(IllegalStateException("TDLib client not started")) }
    companion object { const val CHANNEL_ID_BASE = -1_000_000_000_000L; @Volatile private var instance: TdClient? = null; fun get(context: Context): TdClient = instance ?: synchronized(this) { instance ?: TdClient(context.applicationContext).also { it.start(); instance = it } }; fun normalizeChannelId(raw: String): Long? { val digits = raw.trim().filter { it.isDigit() }; if (digits.isEmpty()) return null; val asLong = digits.toLongOrNull() ?: return null; return when { digits.startsWith("100") && digits.length >= 13 -> -asLong; digits.length in 9..12 -> -("100$digits".toLongOrNull() ?: return null); else -> -asLong } } }
}
