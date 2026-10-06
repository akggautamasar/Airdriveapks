package com.airdrive.pc

import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A Telegram error, with Telegram's own code so 429 and 500 can be told apart from a real problem. */
class TelegramException(val code: Int, message: String) : Exception(message)

/**
 * One TDLib session for the PC app: sign in, find a chat, upload files to it.
 *
 * The point of this class is that a folder tree backed up from a PC lands in the same chat the phone
 * app uses, so the phone can restore it. Everything is deliberately plain threads and futures: the
 * desktop build has no third-party dependencies at all, and TDLib's own Java interface is callback
 * based, so a future per request is the whole asynchronous machinery needed.
 *
 * The generated `TdApi` classes and the JNI bridge come from the same pinned TDLib commit the phone
 * app builds, which is why the field names below are the same ones `TdClient.kt` uses.
 */
class TelegramSession(
    private val workDir: Path,
    private val apiId: Int,
    private val apiHash: String,
    private val applicationVersion: String,
    private val log: (String) -> Unit,
    /** Asked for the phone number, the code and the password; returns null when there is no answer. */
    private val ask: (question: String, secret: Boolean) -> String?
) {
    enum class State { STARTING, NEEDS_PARAMETERS, NEEDS_PHONE, NEEDS_CODE, NEEDS_PASSWORD, READY, BLOCKED }

    private val state = AtomicReference(State.STARTING)
    private val stateToken = AtomicLong()
    private val answeredToken = AtomicLong(-1)
    private var client: Client? = null

    /** Sent-file progress, keyed by the local path TDLib reports for the file being uploaded. */
    private val progressByPath = ConcurrentHashMap<String, Pair<Long, Long>>()
    @Volatile
    private var lastProgressLineAt = 0L

    /** tempId -> the outcome of a send, filled in by UpdateMessageSendSucceeded / ...Failed. */
    private val sendWaiters = ConcurrentHashMap<Long, CompletableFuture<SendOutcome>>()
    private val earlyOutcomes = ConcurrentHashMap<Long, SendOutcome>()
    private val sendLock = Any()

    sealed class SendOutcome {
        class Sent(val messageId: Long) : SendOutcome()
        class Failed(val code: Int, val reason: String) : SendOutcome()
    }

    val isReady: Boolean
        get() = state.get() == State.READY

    fun start() {
        if (client != null) return
        loadNative(log)
        // TDLib's receiver thread is a daemon, so a run that finishes while an upload is still
        // settling exits the process instead of hanging; nothing else has to be torn down.
        client = Client.create({ update -> onResult(update) }, null, null)
    }

    companion object {
        @Volatile
        private var nativeLoaded = false

        /**
         * TDLib's Java bindings load `tdjni` by name, which only works when the JVM already knows where
         * it is. A packaged app does not, so look for the library beside the jar first and load it by
         * path; `java.library.path` remains the fallback for a system-wide install.
         */
        fun loadNative(log: (String) -> Unit): Boolean {
            if (nativeLoaded) return true
            val candidates = ArrayList<File>()
            try {
                val here = File(TelegramSession::class.java.protectionDomain.codeSource.location.toURI())
                for (root in listOfNotNull(here.parentFile, here.parentFile?.parentFile)) {
                    for (name in NATIVE_NAMES) {
                        for (dir in listOf(root, File(root, "lib"), File(root, "app"), File(root, "bin"))) {
                            val candidate = File(dir, name)
                            if (candidate.isFile && candidate !in candidates) candidates.add(candidate)
                        }
                    }
                }
            } catch (e: Exception) {
                // No readable code source location; the fallback below is the only option then.
            }
            for (candidate in candidates) {
                try {
                    System.load(candidate.absolutePath)
                    nativeLoaded = true
                    log("loaded " + candidate.name + " from " + candidate.parent)
                    return true
                } catch (e: UnsatisfiedLinkError) {
                    log(candidate.name + " is there but would not load: " + (e.message ?: "the linker refused it"))
                }
            }
            return try {
                System.loadLibrary("tdjni")
                nativeLoaded = true
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        fun isNativeLoaded(): Boolean = nativeLoaded

        private val NATIVE_NAMES = arrayOf("tdjni.dll", "libtdjni.so", "libtdjni.dylib", "tdjni.so")
    }

    private fun requireClient(): Client =
        client ?: throw TelegramException(0, "the Telegram client was never started")

    private fun onResult(result: TdApi.Object) {
        when (result) {
            is TdApi.UpdateAuthorizationState -> onAuthorizationState(result.authorizationState)
            is TdApi.UpdateMessageSendSucceeded ->
                completeSend(result.oldMessageId, SendOutcome.Sent(result.message.id))
            is TdApi.UpdateMessageSendFailed -> completeSend(
                result.oldMessageId,
                SendOutcome.Failed(result.error.code, result.error.message ?: "the message could not be sent")
            )
            is TdApi.UpdateFile -> reportProgress(result.file)
            else -> Unit
        }
    }

    private fun onAuthorizationState(authorizationState: TdApi.AuthorizationState) {
        state.set(
            when (authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> State.NEEDS_PARAMETERS
                is TdApi.AuthorizationStateWaitPhoneNumber -> State.NEEDS_PHONE
                is TdApi.AuthorizationStateWaitCode -> State.NEEDS_CODE
                is TdApi.AuthorizationStateWaitRegistration -> State.BLOCKED
                is TdApi.AuthorizationStateWaitPassword -> State.NEEDS_PASSWORD
                is TdApi.AuthorizationStateReady -> State.READY
                else -> state.get()
            }
        )
        if (authorizationState is TdApi.AuthorizationStateWaitRegistration) {
            log("this phone number has no Telegram account yet; register it once with an official client")
        }
        // Every update re-arms the question, so a code typed wrong can be typed again.
        stateToken.incrementAndGet()
    }

    private fun reportProgress(file: TdApi.File) {
        val local = file.local ?: return
        val remote = file.remote ?: return
        val path = local.path ?: return
        if (path.isBlank()) return
        val total = if (file.expectedSize > 0) file.expectedSize.toLong() else file.size.toLong()
        progressByPath[path] = remote.uploadedSize.toLong() to total
        val now = System.currentTimeMillis()
        if (now - lastProgressLineAt < 4000) return
        lastProgressLineAt = now
        val uploaded = progressByPath.values.sumOf { it.first }
        val overall = progressByPath.values.sumOf { it.second.coerceAtLeast(it.first) }
        log("uploading " + Fmt.bytes(uploaded) + " of " + Fmt.bytes(overall))
    }

    /** One request, waited for. TDLib hands an `Error` object back through the callback, not an exception. */
    private fun request(function: TdApi.Function<*>, timeoutSeconds: Long): TdApi.Object {
        val future = CompletableFuture<TdApi.Object>()
        requireClient().send(function) { result ->
            if (result is TdApi.Error) {
                future.completeExceptionally(TelegramException(result.code, result.message ?: "Telegram refused"))
            } else {
                future.complete(result)
            }
        }
        val waited = try {
            future.get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is TelegramException) throw cause
            throw TelegramException(0, cause?.message ?: e.message ?: "the request did not complete")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TelegramException(0, "interrupted while waiting for Telegram")
        } catch (e: java.util.concurrent.TimeoutException) {
            throw TelegramException(408, "Telegram did not answer within ${timeoutSeconds}s")
        }
        if (waited is TdApi.Error) {
            throw TelegramException(waited.code, waited.message ?: "Telegram refused")
        }
        return waited
    }

    /**
     * Drives sign-in until the session is ready, asking for whatever is missing. Returns false when it
     * could not get there, with the reason already in the log.
     */
    fun awaitReady(timeoutSeconds: Long = 300): Boolean {
        start()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            when (state.get()) {
                State.READY -> return true
                State.BLOCKED -> return false
                State.NEEDS_PARAMETERS -> if (claimTurn()) {
                    try {
                        request(parameters(), 30)
                    } catch (e: TelegramException) {
                        log("Telegram refused those settings: ${e.message}")
                        state.set(State.BLOCKED)
                        return false
                    }
                }
                State.NEEDS_PHONE -> if (claimTurn()) {
                    val phone = ask("Telegram phone number (with country code, e.g. +9198........)", false)
                    if (phone.isNullOrBlank()) {
                        log("no phone number, so nothing can be uploaded")
                        state.set(State.BLOCKED)
                        return false
                    }
                    if (!authStep { request(TdApi.SetAuthenticationPhoneNumber(phone.trim(), null), 60) }) return false
                }
                State.NEEDS_CODE -> if (claimTurn()) {
                    val code = ask("the code Telegram just sent you", true)
                    if (code.isNullOrBlank()) {
                        log("no code, so nothing can be uploaded")
                        state.set(State.BLOCKED)
                        return false
                    }
                    if (!authStep { request(TdApi.CheckAuthenticationCode(code.trim()), 60) }) return false
                }
                State.NEEDS_PASSWORD -> if (claimTurn()) {
                    val password = ask("your two-step-verification password", true)
                    if (password.isNullOrBlank()) {
                        log("no password, so nothing can be uploaded")
                        state.set(State.BLOCKED)
                        return false
                    }
                    if (!authStep { request(TdApi.CheckAuthenticationPassword(password), 60) }) return false
                }
                State.STARTING -> Unit
            }
            try {
                Thread.sleep(120)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        log("Telegram did not reach a signed-in state within ${timeoutSeconds}s; it is still running and the next run will not have to sign in again")
        return false
    }

    /** True once per authorization-state update, so a question is never asked twice for one state. */
    private fun claimTurn(): Boolean {
        val turn = stateToken.get()
        if (answeredToken.get() == turn) return false
        answeredToken.set(turn)
        return true
    }

    private fun authStep(action: () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (e: TelegramException) {
            log("Telegram: ${e.message}")
            // Let the same state ask again: a mistyped code is worth a second try.
            answeredToken.set(-1)
            true
        }
    }

    private fun parameters(): TdApi.SetTdlibParameters {
        val database = workDir.resolve("tdlib").toFile()
        val files = workDir.resolve("tdlib-files").toFile()
        database.mkdirs()
        files.mkdirs()
        return TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = database.absolutePath
            filesDirectory = files.absolutePath
            useFileDatabase = true
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = this@TelegramSession.apiId
            apiHash = this@TelegramSession.apiHash
            systemLanguageCode = "en"
            deviceModel = System.getProperty("os.name", "pc") + " " + System.getProperty("os.arch", "")
            systemVersion = System.getProperty("os.version", "")
            applicationVersion = this@TelegramSession.applicationVersion
        }
    }

    /**
     * The chat a backup goes to, from whatever the user typed: a channel id, `-100…`, `@name`, a
     * `t.me/…` link, or nothing at all for Saved Messages. Same rules as the phone app, so a chat
     * that works there works here.
     */
    fun resolveChat(raw: String?): ResolvedChat {
        val text = (raw ?: "").trim()
        if (text.isEmpty()) return ResolvedChat(savedMessages(), "Saved Messages")
        if (text.startsWith("@") || looksLikeUsername(text)) {
            val username = text.removePrefix("@").substringBefore('/').substringBefore('?')
            val chat = request(TdApi.SearchPublicChat(username), 30)
            if (chat is TdApi.Chat) return ResolvedChat(chat.id, chat.title)
            throw TelegramException(0, "Telegram has no public chat called @$username")
        }
        if (text.contains("t.me/") || text.contains("telegram.me/")) {
            val path = text.substringAfter("t.me/", text).substringAfter("telegram.me/").trim().trimEnd('/')
            if (path.startsWith("+") || path.startsWith("c/")) {
                if (path.startsWith("c/")) {
                    val digits = path.removePrefix("c/").substringBefore('/').filter { it.isDigit() }
                    val id = digits.toLongOrNull() ?: throw TelegramException(0, "that link has no chat id in it")
                    return ResolvedChat(requireChat(-1_000_000_000_000L - id), "chat from the link")
                }
                val info = request(TdApi.CheckChatInviteLink().apply { inviteLink = "https://t.me/$path" }, 30)
                if (info is TdApi.ChatInviteLinkInfo && info.chatId != 0L) {
                    return ResolvedChat(requireChat(info.chatId), "chat from the invite link")
                }
                val joined = request(TdApi.JoinChatByInviteLink().apply { inviteLink = "https://t.me/$path" }, 30)
                if (joined is TdApi.Chat) return ResolvedChat(joined.id, joined.title)
                throw TelegramException(0, "that invite link could not be joined")
            }
            return resolveChat("@" + path.substringBefore('/'))
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isEmpty()) throw TelegramException(0, "not a chat id, @username or t.me link")
        val asLong = digits.toLongOrNull() ?: throw TelegramException(0, "that number is too large to be a chat id")
        val id = if (text.startsWith("-") || asLong >= 1_000_000_000_000L) -asLong else -1_000_000_000_000L - asLong
        return ResolvedChat(requireChat(id), "chat $id")
    }

    private fun looksLikeUsername(text: String): Boolean =
        Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$").matches(text)

    private fun requireChat(chatId: Long): TdApi.Chat {
        val chat = request(TdApi.GetChat().apply { this.chatId = chatId }, 30)
        if (chat is TdApi.Chat) return chat
        throw TelegramException(0, "Telegram did not return that chat")
    }

    fun savedMessages(): Long {
        val me = request(TdApi.GetMe(), 30)
        if (me !is TdApi.User) throw TelegramException(0, "Telegram did not say who we are signed in as")
        val chat = request(TdApi.CreatePrivateChat().apply { userId = me.id; force = false }, 30)
        if (chat is TdApi.Chat) return chat.id
        throw TelegramException(0, "Saved Messages could not be opened")
    }

    /** A channel to back up into, when the user does not have one yet. */
    fun createChannel(title: String): ResolvedChat {
        val name = title.trim().take(128).ifBlank { "AirDrive Backup" }
        val chat = request(
            TdApi.CreateNewSupergroupChat().apply {
                this.title = name
                isChannel = true
                description = "Created by AirDrive for PC"
                location = null
                forImport = false
            },
            60
        )
        if (chat is TdApi.Chat) return ResolvedChat(chat.id, chat.title)
        throw TelegramException(0, "the channel could not be created")
    }

    /**
     * Upload one file as a document, so nothing is re-compressed and the name is kept. Returns the
     * message id, which is the only handle a restore needs later.
     */
    fun upload(localPath: Path, chatId: Long, caption: String, sizeBytes: Long): Long {
        requireChat(chatId)
        var attempt = 0
        while (true) {
            try {
                return sendDocument(localPath, chatId, caption, sizeBytes)
            } catch (e: TelegramException) {
                val retryable = e.code == 429 || e.code == 500 || e.code == 408
                if (!retryable || attempt >= 8) throw e
                attempt++
                val seconds = Regex("\\d+").find(e.message ?: "")?.value?.toLongOrNull()
                    ?: minOf(300L, 2L shl attempt.coerceAtMost(7))
                log("Telegram said ${e.message}; trying again in ${seconds}s")
                try {
                    Thread.sleep(seconds * 1000)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
    }

    private fun sendDocument(localPath: Path, chatId: Long, caption: String, sizeBytes: Long): Long {
        val document = TdApi.InputDocument().apply {
            document = TdApi.InputFileLocal(localPath.toString())
            thumbnail = null
            disableContentTypeDetection = true
        }
        val content = TdApi.InputMessageDocument().apply {
            this.document = document
            this.caption = TdApi.FormattedText(caption, emptyArray())
        }
        val queued = request(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                inputMessageContent = content
                // A backup channel that pings for every file is a channel nobody keeps. This version
                // of TDLib puts it on the send options rather than on the message.
                options = TdApi.MessageSendOptions().apply { disableNotification = true }
            },
            120
        )
        if (queued !is TdApi.Message) throw TelegramException(0, "Telegram did not accept the upload")
        val tempId = queued.id
        val waiter = registerSend(tempId)
        try {
            // A 20 GB film on a home line takes a while, so the wait grows with the file but is never
            // open-ended: a stuck upload has to end the run rather than hang it.
            val budgetSeconds = minOf(3 * 60 * 60L, 180L + sizeBytes / 20_000L)
            val outcome = try {
                waiter.get(budgetSeconds, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw TelegramException(408, "the upload did not finish within ${budgetSeconds}s")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw TelegramException(0, e.cause?.message ?: "the upload was interrupted")
            }
            return when (outcome) {
                is SendOutcome.Sent -> outcome.messageId
                is SendOutcome.Failed -> throw TelegramException(outcome.code, outcome.reason)
            }
        } finally {
            forgetSend(tempId)
            progressByPath.remove(localPath.toString())
        }
    }

    /**
     * What a stored file still weighs on Telegram's side, or null when the message no longer holds it.
     * `--verify` uses this so a verified PC backup means the copy really is on Telegram, not just sent.
     */
    fun remoteSize(chatId: Long, messageId: Long): Long? {
        val message = try {
            request(TdApi.GetMessage().apply { this.chatId = chatId; this.messageId = messageId }, 60)
        } catch (e: TelegramException) {
            return null
        }
        if (message !is TdApi.Message) return null
        return when (val content = message.content) {
            is TdApi.MessageDocument -> content.document.document.size.toLong()
            is TdApi.MessageVideo -> content.video.video.size.toLong()
            is TdApi.MessagePhoto -> content.photo.sizes.maxOfOrNull { size -> size.photo.size.toLong() }
            else -> null
        }
    }

    private fun registerSend(tempId: Long): CompletableFuture<SendOutcome> {
        val waiter = CompletableFuture<SendOutcome>()
        synchronized(sendLock) {
            val already = earlyOutcomes.remove(tempId)
            if (already != null) waiter.complete(already) else sendWaiters[tempId] = waiter
        }
        return waiter
    }

    private fun completeSend(tempId: Long, outcome: SendOutcome) {
        synchronized(sendLock) {
            val waiter = sendWaiters.remove(tempId)
            if (waiter != null) {
                waiter.complete(outcome)
            } else {
                // The update can arrive before the send call has its waiter in place.
                if (earlyOutcomes.size > 256) earlyOutcomes.clear()
                earlyOutcomes[tempId] = outcome
            }
        }
    }

    private fun forgetSend(tempId: Long) {
        synchronized(sendLock) {
            sendWaiters.remove(tempId)
            earlyOutcomes.remove(tempId)
        }
    }
}

data class ResolvedChat(val chatId: Long, val title: String)

/**
 * Where an uploaded file goes. The record file still lives in `--dest`: what Telegram changes is only
 * where the bytes are put, never how the run decides what needs backing up.
 */
data class TelegramTarget(val session: TelegramSession, val chatId: Long, val chatTitle: String)
