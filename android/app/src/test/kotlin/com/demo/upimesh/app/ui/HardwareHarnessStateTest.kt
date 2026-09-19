package com.demo.upimesh.app.ui

import com.demo.upimesh.bridge.role.DeviceRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HardwareHarnessStateTest {

    @Test
    fun testDefaultStateFormatting() {
        val state = HardwareHarnessState(
            deviceId = "dev-a-01",
            ownerVpa = "alice@demo",
            role = DeviceRole.PAYER,
            walletAllocatedPaisa = 100000L,
            walletSpentPaisa = 25000L,
            walletRemainingPaisa = 75000L,
            walletSequenceCounter = 1L
        )

        assertEquals("Identity: alice@demo (dev-a-01)", state.deviceIdentityDisplay)
        assertEquals("Current Role: PAYER", state.deviceRoleDisplay)
        assertTrue(state.bleStatusDisplay.contains("Ready"))
        assertEquals("Advertising: IDLE", state.advStateDisplay)
        assertEquals("Scanning: IDLE (0 peers found)", state.scanStateDisplay)
        assertEquals("Connected Peer: None | MTU: 517", state.peerStateDisplay)
        assertEquals("WAN Network: OFFLINE (Mesh Only)", state.networkStatusDisplay)
        assertEquals("Bridge Queue: 0 pending packets", state.queueDepthDisplay)
        assertTrue(state.walletStatusDisplay.contains("₹1000.00 alloc"))
        assertTrue(state.walletStatusDisplay.contains("₹250.00 spent"))
        assertTrue(state.walletStatusDisplay.contains("₹750.00"))
        assertTrue(state.walletStatusDisplay.contains("C=1"))
    }

    @Test
    fun testRoleCycleTransitions() {
        var state = HardwareHarnessState(role = DeviceRole.PAYER)
        assertEquals(DeviceRole.PAYER, state.role)

        state = state.copy(role = DeviceRole.RELAY)
        assertEquals(DeviceRole.RELAY, state.role)
        assertEquals("Current Role: RELAY", state.deviceRoleDisplay)

        state = state.copy(role = DeviceRole.BRIDGE)
        assertEquals(DeviceRole.BRIDGE, state.role)
        assertEquals("Current Role: BRIDGE", state.deviceRoleDisplay)

        state = state.copy(role = DeviceRole.MERCHANT)
        assertEquals(DeviceRole.MERCHANT, state.role)
        assertEquals("Current Role: MERCHANT", state.deviceRoleDisplay)

        state = state.copy(role = DeviceRole.PAYER)
        assertEquals(DeviceRole.PAYER, state.role)
    }

    @Test
    fun testAdvertisingAndScanningStateFormatting() {
        var state = HardwareHarnessState(isAdvertising = false, isScanning = false)
        assertEquals("Advertising: IDLE", state.advStateDisplay)
        assertEquals("Scanning: IDLE (0 peers found)", state.scanStateDisplay)

        state = state.copy(isAdvertising = true, isScanning = true, discoveredPeersCount = 3)
        assertTrue(state.advStateDisplay.contains("ACTIVE"))
        assertTrue(state.scanStateDisplay.contains("ACTIVE (3 peers discovered)"))

        state = state.copy(isAdvertising = false, isScanning = false, discoveredPeersCount = 3)
        assertEquals("Advertising: IDLE", state.advStateDisplay)
        assertEquals("Scanning: IDLE (3 peers found)", state.scanStateDisplay)
    }
}
