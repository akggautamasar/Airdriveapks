package com.airdrive.backup.ui.screens

/**
 * Small local route facade used by RestoreScreen so its UI module does not need
 * to depend on the navigation object's package just for the migration route.
 * The canonical destination remains the same AppNav route: "migrate".
 */
internal object Routes {
    const val MIGRATE = "migrate"
}
