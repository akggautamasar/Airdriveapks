package com.airdrive.backup.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun categoryToString(value: BackupCategory): String = value.name

    @TypeConverter
    fun stringToCategory(value: String): BackupCategory = BackupCategory.valueOf(value)

    @TypeConverter
    fun statusToString(value: UploadStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): UploadStatus = UploadStatus.valueOf(value)
}
