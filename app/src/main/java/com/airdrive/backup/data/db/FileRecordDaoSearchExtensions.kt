package com.airdrive.backup.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Compatibility helpers for callers that use the advanced search signature. */
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
): Flow<List<FileRecord>> = searchFlow(
    query, categoryName, statusName, localStateName, folder,
    chatId, minBytes, maxBytes, fromMillis, toMillis, sort, limit
)

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
): Flow<Int> = searchCountFlow(
    query, categoryName, statusName, localStateName, folder,
    chatId, minBytes, maxBytes, fromMillis, toMillis
)
