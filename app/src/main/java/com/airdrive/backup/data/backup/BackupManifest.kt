package com.airdrive.backup.data.backup

import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.prefs.ApiCredentials
import com.airdrive.backup.data.prefs.DestinationMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Caption marker on the manifest message — this exact string is what [ManifestSync] searches
 * Saved Messages for, so a fresh install can find its own backup data with zero local state.
 */
const val MANIFEST_MARKER = "AIRDRIVE_MANIFEST_V1"

/** One already-uploaded file, exactly what a reinstall needs to recognise it and skip it. */
data class ManifestEntry(
    val fingerprint: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val category: BackupCategory,
    val chatId: Long,
    val messageId: Long,
    val uploadedAtMillis: Long
)

/** Everything a reinstall needs: which files are already up, and where things were going. */
data class BackupManifest(
    val generatedAtMillis: Long,
    val entryCount: Int,
    val destinationMode: DestinationMode,
    val singleChatId: Long,
    val perCategoryChannels: Map<BackupCategory, Long>,
    val captionTemplate: String,
    val entries: List<ManifestEntry>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("marker", MANIFEST_MARKER)
        put("generatedAtMillis", generatedAtMillis)
        put("entryCount", entryCount)
        put("destinationMode", destinationMode.name)
        put("singleChatId", singleChatId)
        put("captionTemplate", captionTemplate)
        put("perCategoryChannels", JSONObject().apply {
            perCategoryChannels.forEach { (cat, id) -> put(cat.name, id) }
        })
        put("entries", JSONArray().apply {
            entries.forEach { e ->
                put(JSONObject().apply {
                    put("fp", e.fingerprint)
                    put("name", e.displayName)
                    put("size", e.sizeBytes)
                    put("mod", e.modifiedAtMillis)
                    put("cat", e.category.name)
                    put("chat", e.chatId)
                    put("msg", e.messageId)
                    put("up", e.uploadedAtMillis)
                })
            }
        })
    }

    companion object {
        fun fromRecords(
            records: List<FileRecord>,
            destinationMode: DestinationMode,
            singleChatId: Long,
            perCategoryChannels: Map<BackupCategory, Long>,
            captionTemplate: String
        ): BackupManifest {
            val entries = records.mapNotNull { r ->
                val messageId = r.telegramMessageId ?: return@mapNotNull null
                ManifestEntry(
                    fingerprint = r.fingerprint,
                    displayName = r.displayName,
                    sizeBytes = r.sizeBytes,
                    modifiedAtMillis = r.modifiedAtMillis,
                    category = r.category,
                    chatId = r.destinationChannelId,
                    messageId = messageId,
                    uploadedAtMillis = r.uploadedAtMillis ?: r.addedAtMillis
                )
            }
            return BackupManifest(
                generatedAtMillis = System.currentTimeMillis(),
                entryCount = entries.size,
                destinationMode = destinationMode,
                singleChatId = singleChatId,
                perCategoryChannels = perCategoryChannels,
                captionTemplate = captionTemplate,
                entries = entries
            )
        }

        fun parse(json: JSONObject): BackupManifest {
            val entriesArray = json.optJSONArray("entries") ?: JSONArray()
            val entries = (0 until entriesArray.length()).mapNotNull { i ->
                val o = entriesArray.optJSONObject(i) ?: return@mapNotNull null
                val category = runCatching { BackupCategory.valueOf(o.getString("cat")) }.getOrNull()
                    ?: return@mapNotNull null
                ManifestEntry(
                    fingerprint = o.getString("fp"),
                    displayName = o.optString("name", "restored file"),
                    sizeBytes = o.optLong("size", 0L),
                    modifiedAtMillis = o.optLong("mod", 0L),
                    category = category,
                    chatId = o.optLong("chat", 0L),
                    messageId = o.optLong("msg", 0L),
                    uploadedAtMillis = o.optLong("up", 0L)
                )
            }
            val destMode = runCatching {
                DestinationMode.valueOf(json.optString("destinationMode", DestinationMode.SAVED_MESSAGES.name))
            }.getOrDefault(DestinationMode.SAVED_MESSAGES)
            val perCategory = mutableMapOf<BackupCategory, Long>()
            json.optJSONObject("perCategoryChannels")?.let { obj ->
                obj.keys().forEach { key ->
                    runCatching { BackupCategory.valueOf(key) }.getOrNull()?.let { cat ->
                        perCategory[cat] = obj.optLong(key, 0L)
                    }
                }
            }
            return BackupManifest(
                generatedAtMillis = json.optLong("generatedAtMillis", 0L),
                entryCount = json.optInt("entryCount", entries.size),
                destinationMode = destMode,
                singleChatId = json.optLong("singleChatId", 0L),
                perCategoryChannels = perCategory,
                captionTemplate = json.optString("captionTemplate", ""),
                entries = entries
            )
        }
    }
}

/** Turns a restored [ManifestEntry] into a FileRecord row already marked UPLOADED. */
fun ManifestEntry.toUploadedRecord(): FileRecord = FileRecord(
    uri = "restored://$fingerprint",
    displayName = displayName,
    sizeBytes = sizeBytes,
    modifiedAtMillis = modifiedAtMillis,
    category = category,
    fingerprint = fingerprint,
    status = UploadStatus.UPLOADED,
    destinationChannelId = chatId,
    telegramMessageId = messageId,
    uploadedAtMillis = uploadedAtMillis
)
