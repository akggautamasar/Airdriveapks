package com.airdrive.backup.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Advanced search kept outside the Room DAO so the existing lightweight Room
 * query remains intact while the Search screen can use its richer filter set.
 */
fun FileRecordDao.searchFlow(
    query: String,
    categoryName: String,
    statusName: String,
    localStateName: String,
    folder: String,
    chatId: Long,
    minBytes: Long,
    maxBytes: Long,
    fromMillis: Long,
    toMillis: Long,
    sort: String,
    limit: Int
): Flow<List<FileRecord>> = searchFlow(query).map { source ->
    source
        .asSequence()
        .filter { record ->
            (query.isBlank() || record.displayName.contains(query, ignoreCase = true) || record.uri.contains(query, ignoreCase = true)) &&
                (categoryName.isBlank() || record.category.name == categoryName) &&
                (statusName.isBlank() || record.status.name == statusName) &&
                (localStateName.isBlank() || record.localState.name == localStateName) &&
                (folder.isBlank() || record.uri.contains(folder, ignoreCase = true)) &&
                (chatId == 0L || record.destinationChannelId == chatId) &&
                (minBytes <= 0L || record.sizeBytes >= minBytes) &&
                (maxBytes <= 0L || record.sizeBytes <= maxBytes) &&
                (fromMillis <= 0L || record.modifiedAtMillis >= fromMillis) &&
                (toMillis <= 0L || record.modifiedAtMillis <= toMillis)
        }
        .let { filtered ->
            when (sort.lowercase()) {
                "oldest", "asc", "modified_asc" -> filtered.sortedWith(compareBy<FileRecord> { it.modifiedAtMillis }.thenBy { it.id })
                "largest", "size_desc" -> filtered.sortedWith(compareByDescending<FileRecord> { it.sizeBytes }.thenByDescending { it.id })
                "smallest", "size_asc" -> filtered.sortedWith(compareBy<FileRecord> { it.sizeBytes }.thenBy { it.id })
                "name", "name_asc" -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }.thenBy { it.id })
                else -> filtered.sortedWith(compareByDescending<FileRecord> { it.modifiedAtMillis }.thenByDescending { it.id })
            }
        }
        .take(limit)
        .toList()
}

fun FileRecordDao.searchCountFlow(
    query: String,
    categoryName: String,
    statusName: String,
    localStateName: String,
    folder: String,
    chatId: Long,
    minBytes: Long,
    maxBytes: Long,
    fromMillis: Long,
    toMillis: Long
): Flow<Int> = searchFlow(query).map { source ->
    source.count { record ->
        (query.isBlank() || record.displayName.contains(query, ignoreCase = true) || record.uri.contains(query, ignoreCase = true)) &&
            (categoryName.isBlank() || record.category.name == categoryName) &&
            (statusName.isBlank() || record.status.name == statusName) &&
            (localStateName.isBlank() || record.localState.name == localStateName) &&
            (folder.isBlank() || record.uri.contains(folder, ignoreCase = true)) &&
            (chatId == 0L || record.destinationChannelId == chatId) &&
            (minBytes <= 0L || record.sizeBytes >= minBytes) &&
            (maxBytes <= 0L || record.sizeBytes <= maxBytes) &&
            (fromMillis <= 0L || record.modifiedAtMillis >= fromMillis) &&
            (toMillis <= 0L || record.modifiedAtMillis <= toMillis)
    }
}
