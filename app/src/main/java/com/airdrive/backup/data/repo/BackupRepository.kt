package com.airdrive.backup.data.repo

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.text.format.DateFormat
import android.util.Log
import com.airdrive.backup.data.backup.ManifestSync
import com.airdrive.backup.data.db.AppDatabase
import com.airdrive.backup.data.db.BackupCategory
import com.airdrive.backup.data.db.BackupRun
import com.airdrive.backup.data.db.FileRecord
import com.airdrive.backup.data.db.FileVersion
import com.airdrive.backup.data.db.LocalState
import com.airdrive.backup.data.db.RunOutcome
import com.airdrive.backup.data.db.RunTrigger
import com.airdrive.backup.data.db.UploadStatus
import com.airdrive.backup.data.db.VerifyState
import com.airdrive.backup.data.prefs.DestinationConfig
import com.airdrive.backup.data.prefs.DestinationMode
import com.airdrive.backup.data.prefs.SettingsStore
import com.airdrive.backup.data.prefs.UploadOrder
import com.airdrive.backup.scanner.FileScanner
import com.airdrive.backup.scanner.ScanProgress
import com.airdrive.backup.telegram.ChannelCheck
import com.airdrive.backup.telegram.RemoteFile
import com.airdrive.backup.telegram.ResolvedChat
import com.airdrive.backup.telegram.TdClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// This file is intentionally kept functionally identical except for the restore picker size.
// The UI used to receive only 200 records, which made the Restore screen look like the backup
// contained only 200 files. A large bounded window keeps the existing repository/TDLib restore
// path intact while allowing the complete current library to be selected in one operation.

