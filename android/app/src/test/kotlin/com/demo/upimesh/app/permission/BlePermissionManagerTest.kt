package com.demo.upimesh.app.permission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BlePermissionManagerTest {

    @Test
    fun testLegacyApiPermissions() {
        val permissions = BlePermissionManager.getRequiredBlePermissions(apiLevel = 29)
        assertEquals(3, permissions.size)
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_BLUETOOTH))
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_BLUETOOTH_ADMIN))
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_ACCESS_FINE_LOCATION))
    }

    @Test
    fun testModernApiPermissions() {
        val permissions = BlePermissionManager.getRequiredBlePermissions(apiLevel = 33)
        assertEquals(3, permissions.size)
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_BLUETOOTH_SCAN))
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_BLUETOOTH_ADVERTISE))
        assertTrue(permissions.contains(BlePermissionManager.PERMISSION_BLUETOOTH_CONNECT))
    }

    @Test
    fun testAllPermissionsGrantedEvaluation() {
        val state = BlePermissionManager.evaluatePermissions(apiLevel = 34) { true }
        assertTrue(state.allGranted)
        assertTrue(state.missingPermissions.isEmpty())
        assertTrue(state.toDiagnosticString().contains("All BLE permissions granted"))
    }

    @Test
    fun testMissingPermissionsEvaluation() {
        val state = BlePermissionManager.evaluatePermissions(apiLevel = 34) { permission ->
            permission != BlePermissionManager.PERMISSION_BLUETOOTH_SCAN
        }
        assertFalse(state.allGranted)
        assertEquals(1, state.missingPermissions.size)
        assertEquals(BlePermissionManager.PERMISSION_BLUETOOTH_SCAN, state.missingPermissions[0])
        assertTrue(state.toDiagnosticString().contains("Missing permissions (1): BLUETOOTH_SCAN"))
    }
}
