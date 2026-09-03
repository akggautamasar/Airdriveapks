package com.airdrive.backup.ui.screens

import com.airdrive.backup.data.db.BackupCategory

/** One place for the human names of the seven categories, used by several screens. */
fun categoryLabel(c: BackupCategory): String = when (c) {
    BackupCategory.PHOTOS -> "Photos"
    BackupCategory.VIDEOS -> "Videos"
    BackupCategory.PDFS -> "PDFs"
    BackupCategory.WORD_EXCEL -> "Documents"
    BackupCategory.AUDIO -> "Audio"
    BackupCategory.CALL_RECORDINGS -> "Call recordings"
    BackupCategory.OTHER_FILES -> "Other files"
}
