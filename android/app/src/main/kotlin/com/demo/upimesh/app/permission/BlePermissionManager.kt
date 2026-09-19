package com.demo.upimesh.app.permission

/**
 * Encapsulates Android Bluetooth & Location runtime permission checking and discovery across API levels.
 *
 * Android <= 30 (Android 8.0 - 11):
 * - android.permission.BLUETOOTH
 * - android.permission.BLUETOOTH_ADMIN
 * - android.permission.ACCESS_FINE_LOCATION (required by platform for BLE scanning)
 *
 * Android >= 31 (Android 12+):
 * - android.permission.BLUETOOTH_SCAN
 * - android.permission.BLUETOOTH_ADVERTISE
 * - android.permission.BLUETOOTH_CONNECT
 */
object BlePermissionManager {

    const val PERMISSION_BLUETOOTH = "android.permission.BLUETOOTH"
    const val PERMISSION_BLUETOOTH_ADMIN = "android.permission.BLUETOOTH_ADMIN"
    const val PERMISSION_ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val PERMISSION_BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
    const val PERMISSION_BLUETOOTH_ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val PERMISSION_BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"

    /**
     * Returns the exact list of required BLE permissions for the specified Android API level.
     */
    fun getRequiredBlePermissions(apiLevel: Int): List<String> {
        return if (apiLevel >= 31) {
            listOf(
                PERMISSION_BLUETOOTH_SCAN,
                PERMISSION_BLUETOOTH_ADVERTISE,
                PERMISSION_BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                PERMISSION_BLUETOOTH,
                PERMISSION_BLUETOOTH_ADMIN,
                PERMISSION_ACCESS_FINE_LOCATION
            )
        }
    }

    /**
     * Evaluates permission state using a generic permission query lambda.
     */
    fun evaluatePermissions(
        apiLevel: Int,
        isPermissionGranted: (String) -> Boolean
    ): BlePermissionState {
        val required = getRequiredBlePermissions(apiLevel)
        val missing = required.filter { !isPermissionGranted(it) }
        return BlePermissionState(
            allGranted = missing.isEmpty(),
            missingPermissions = missing,
            apiLevel = apiLevel
        )
    }
}

/**
 * Diagnostic representation of runtime BLE permission state.
 */
data class BlePermissionState(
    val allGranted: Boolean,
    val missingPermissions: List<String>,
    val apiLevel: Int
) {
    fun toDiagnosticString(): String {
        return if (allGranted) {
            "All BLE permissions granted (API $apiLevel)"
        } else {
            "Missing permissions (${missingPermissions.size}): ${missingPermissions.joinToString(", ") { it.substringAfterLast(".") }}"
        }
    }
}
