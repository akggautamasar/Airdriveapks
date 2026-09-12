package com.airdrive.backup.quantx

/**
 * Package-local bridge to the PiP state owned by MainActivity.
 * Keeping this bridge in the Quantx package lets the viewer toggle PiP
 * without coupling its package to Activity implementation details.
 */
object QuantxDrivePip {
    @Volatile
    var isEnabled: Boolean
        get() = com.airdrive.backup.QuantxDrivePip.isEnabled
        set(value) {
            com.airdrive.backup.QuantxDrivePip.isEnabled = value
        }
}
