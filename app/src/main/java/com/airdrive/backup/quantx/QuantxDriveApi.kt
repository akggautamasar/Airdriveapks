package com.airdrive.backup.quantx

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

data class QuantStats(val totalFiles: Int, val totalBytes: Long, val favorites: Int)

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

    suspend fun files(category: String? = null, query: String? = null, favorites: Boolean = false): Result<List<QuantFile>> = requestResult(
        "GET", buildString {
            append("/api/files?page=1&limit=100&sort_by=date&sort_dir=desc")
            if (!category.isNullOrBlank()) append("&category=").append(URLEncoder.encode(category, "UTF-8"))
            if (!query.isNullOrBlank()) append("&q=").append(URLEncoder.encode(query, "UTF-8"))
            if (favorites) append("&favorites=true")
        }
    ).map { root ->
        val array = root.optJSONArray("files") ?: JSONArray()
        buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(QuantFile(
                    id = o.optLong("id"), filename = o.optString("filename", "file"),
                    size = o.optLong("size"), mime = o.optString("mime"),
                    category = o.optString("category"), favorite = o.optBoolean("favorite"),
                    date = o.optString("date"), tgLink = o.optString("tg_link").ifBlank { null }
                ))
            }
        }
    }

    suspend fun stats(): Result<QuantStats> = requestResult("GET", "/api/stats").map { o ->
        QuantStats(
            totalFiles = o.optInt("total_files"),
            totalBytes = o.optLong("total_size"),
            favorites = o.optInt("favorites_count")
        )
    }

    suspend fun sync(): Result<JSONObject> = requestResult("POST", "/api/sync/all")

    suspend fun toggleFavorite(id: Long): Result<Boolean> = requestResult("POST", "/api/favorite/$id").map { it.optBoolean("favorited") }

    suspend fun createShare(id: Long, expiresIn: Long = 604800, password: String = ""): Result<QuantShare> = requestResult("POST", "/api/share/$id", JSONObject().apply {
        put("expires_in", expiresIn)
        put("password", password)
    }).map { o -> QuantShare(o.optString("share_token"), o.optLong("expires_at"), o.optBoolean("password_protected")) }

    fun mediaUrl(fileId: Long): String? = savedToken?.let { "$BASE_URL/api/media/$it/$fileId" }

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
}
