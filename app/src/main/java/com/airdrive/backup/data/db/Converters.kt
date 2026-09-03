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

    @TypeConverter
    fun localStateToString(value: LocalState): String = value.name

    /**
     * Unknown names fall back instead of throwing: an older APK reading a column written by a
     * newer one would otherwise crash every query that touches the row.
     */
    @TypeConverter
    fun stringToLocalState(value: String): LocalState =
        runCatching { LocalState.valueOf(value) }.getOrDefault(LocalState.PRESENT)

    @TypeConverter
    fun verifyStateToString(value: VerifyState): String = value.name

    @TypeConverter
    fun stringToVerifyState(value: String): VerifyState =
        runCatching { VerifyState.valueOf(value) }.getOrDefault(VerifyState.UNVERIFIED)

    @TypeConverter
    fun runTriggerToString(value: RunTrigger): String = value.name

    @TypeConverter
    fun stringToRunTrigger(value: String): RunTrigger =
        runCatching { RunTrigger.valueOf(value) }.getOrDefault(RunTrigger.MANUAL)

    @TypeConverter
    fun runOutcomeToString(value: RunOutcome): String = value.name

    @TypeConverter
    fun stringToRunOutcome(value: String): RunOutcome =
        runCatching { RunOutcome.valueOf(value) }.getOrDefault(RunOutcome.COMPLETED)
}
