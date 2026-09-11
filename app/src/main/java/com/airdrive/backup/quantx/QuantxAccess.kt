package com.airdrive.backup.quantx

import android.content.Context

/** QuantxDrive is intentionally an account-gated AirDrive feature. */
object QuantxAccess {
    const val ALLOWED_PHONE = "+916307868952"

    fun normalizePhone(value: String?): String = value.orEmpty()
        .filter { !it.isWhitespace() }
        .replace("-", "")

    fun isAllowed(context: Context): Boolean {
        val phone = context.getSharedPreferences("quantxdrive_identity", Context.MODE_PRIVATE)
            .getString("phone", null)
        return normalizePhone(phone) == ALLOWED_PHONE
    }
}
