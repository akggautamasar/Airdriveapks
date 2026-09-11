package com.airdrive.backup.quantx

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val BASE_URL = "https://quantxdrive.onrender.com"
private const val PREFS = "quantxdrive"
private const val TOKEN = "token"


data class QuantFile(
    val id: Long,
    val filename: String,
    val size: Long,
    val mime: String,
    val category: String,
    val favorite: Boolean,
    val date: String,
    val tgLink: String?
)

data class QuantPage(
    val files: List<QuantFile>,
    val page: Int,
    val limit: Int,
    val total: Int?,
    val hasMore: Boolean
)

data class QuantStats(
    val totalFiles: Int,
    val totalBytes: Long,
    val favorites: Int,
    val categoryCounts: Map<String, Int> = emptyMap()
)

data class QuantShare(val token: String, val expiresAt: Long, val passwordProtected: Boolean)

class QuantxDriveApi(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val savedToken: String? get() = prefs.getString(TOKEN, null)

    suspend fun login(phone: String, password: String): Result<String> = requestResult("POST", "/api/login", body = JSONObject().apply {
        put("phone", phone)
        put("password", password)
    }).map { json ->
        val token = json.optString("token").takeIf { it.isNotBlank() } ?: error("Server did not return a token")
        prefs.edit().putString(TOKEN, token).apply()
        token
    }

    fun logout() { prefs.edit().remove(TOKEN).apply() }

    suspend fun files(
        page: Int = 1,
        limit: Int = 100,
        category: String? = null,
        query: String? = null,
        favorites: Boolean = false
    ): Result<QuantPage> = requestResult(
        "GET", buildString {
            append("/api/files?page=").append(page).append("&limit=").append(limit).append("&sort_by=date&sort_dir=desc")
            if (!category.isNullOrBlank()) append("&category=").append(URLEncoder.encode(category, "UTF-8"))
            if (!query.isNullOrBlank()) append("&q=").append(URLEncoder.encode(query, "UTF-8"))
            if (favorites) append("&favorites=true")
        }
    ).map { root ->
        val array = root.optJSONArray("files") ?: JSONArray()
        val parsed = buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(QuantFile(
                    id = o.optLong("id"),
                    filename = o.optString("filename", "file"),
                    size = o.optLong("size"),
                    mime = o.optString("mime"),
                    category = o.optString("category"),
                    favorite = o.optBoolean("favorite"),
                    date = o.optString("date"),
                    tgLink = o.optString("tg_link").ifBlank { null }
                ))
            }
        }
        val pagination = root.optJSONObject("pagination") ?: root.optJSONObject("meta")
        val total = firstInt(root, pagination, "total", "total_files", "total_count", "count")
        val serverHasMore = firstBoolean(root, pagination, "has_more", "hasMore", "has_next", "hasNext")
        val nextPage = firstInt(root, pagination, "next_page", "nextPage")
        val pages = firstInt(root, pagination, "pages", "total_pages", "totalPages")
        val hasMore = serverHasMore ?: when {
            nextPage != null -> nextPage > page
            pages != null -> page < pages
            else -> parsed.size >= limit
        }
        QuantPage(parsed, page, limit, total, hasMore)
    }

    suspend fun stats(): Result<QuantStats> = requestResult("GET", "/api/stats").map { o ->
        val categoryObject = o.optJSONObject("category_counts")
            ?: o.optJSONObject("categoryCounts")
            ?: o.optJSONObject("categories")
        val categoryCounts = buildMap {
            categoryObject?.keys()?.forEach { key -> put(normalizeCategory(key), categoryObject.optInt(key)) }
        }
        QuantStats(
            totalFiles = o.optInt("total_files", o.optInt("total", 0)),
            totalBytes = o.optLong("total_size", o.optLong("total_bytes", 0L)),
            favorites = o.optInt("favorites_count", o.optInt("favorites", 0)),
            categoryCounts = categoryCounts
        )
    }

    /** Uses the server's paginated total for each category when available. */
    suspend fun categoryCounts(): Result<Map<String, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val categories = listOf(
                "photos", "videos", "audio", "pdfs", "word_excel", "call_recordings", "other_files"
            )
            coroutineScope {
                categories.map { category ->
                    async {
                        val result = files(page = 1, limit = 1, category = category)
                        category to result.getOrNull()?.total
                    }
                }.awaitAll().mapNotNull { (category, total) -> total?.let { normalizeCategory(category) to it } }.toMap()
            }
        }
    }

    suspend fun sync(): Result<JSONObject> = requestResult("POST", "/api/sync/all")

    suspend fun toggleFavorite(id: Long): Result<Boolean> = requestResult("POST", "/api/favorite/$id").map { it.optBoolean("favorited") }

    suspend fun createShare(id: Long, expiresIn: Long = 604800, password: String = ""): Result<QuantShare> = requestResult("POST", "/api/share/$id", JSONObject().apply {
        put("expires_in", expiresIn)
        put("password", password)
    }).map { o -> QuantShare(o.optString("share_token"), o.optLong("expires_at"), o.optBoolean("password_protected")) }

    /** Authenticated, Range-capable media endpoint used by ExoPlayer and DownloadManager. */
    fun mediaUrl(fileId: Long): String? = savedToken?.let { "$BASE_URL/api/media/$it/$fileId" }

    /** Public share stream endpoint created by /api/share/{id}; no app login is required. */
    fun sharedStreamUrl(shareToken: String): String = "$BASE_URL/api/shared/$shareToken/stream"

    private suspend fun requestResult(method: String, path: String, body: JSONObject? = null): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 60_000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                savedToken?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() } }.orEmpty()
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                if (code !in 200..299) throw IllegalStateException(json.optString("detail").ifBlank { "Request failed ($code)" })
                json
            } finally { connection.disconnect() }
        }
    }

    private fun firstInt(root: JSONObject, nested: JSONObject?, vararg keys: String): Int? {
        for (key in keys) {
            if (root.has(key) && !root.isNull(key)) return root.optInt(key)
            if (nested?.has(key) == true && !nested.isNull(key)) return nested.optInt(key)
        }
        return null
    }

    private fun firstBoolean(root: JSONObject, nested: JSONObject?, vararg keys: String): Boolean? {
        for (key in keys) {
            if (root.has(key) && !root.isNull(key)) return root.optBoolean(key)
            if (nested?.has(key) == true && !nested.isNull(key)) return nested.optBoolean(key)
        }
        return null
    }
}

fun normalizeCategory(value: String): String = when (value.lowercase()) {
    "photos", "photo", "images", "image" -> "photos"
    "videos", "video" -> "videos"
    "audio", "audios", "music" -> "audio"
    "pdf", "pdfs" -> "pdfs"
    "word_excel", "office", "documents", "docs", "word", "excel" -> "word_excel"
    "call_recordings", "calls", "call" -> "call_recordings"
    else -> "other_files"
}
