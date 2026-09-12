package com.airdrive.backup.quantx

/**
 * Package-local bridge to the PiP/fullscreen state owned by MainActivity.
 */
object QuantxDrivePip {
    var isEnabled: Boolean
        get() = com.airdrive.backup.QuantxDrivePip.isEnabled
        set(value) {
            com.airdrive.backup.QuantxDrivePip.isEnabled = value
        }

    val isInPip = com.airdrive.backup.QuantxDrivePip.isInPip

    var isFullscreen: Boolean
        get() = com.airdrive.backup.QuantxDrivePip.isFullscreen
        set(value) {
            com.airdrive.backup.QuantxDrivePip.isFullscreen = value
        }
}
