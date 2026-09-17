package com.demo.upimesh.bridge.role

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BridgeRoleAndCapabilityTest {

    @Test
    fun testDefaultBridgeConfiguration() {
        val config = BridgeConfig()
        assertTrue(config.isBridgeCapable)
        assertEquals("android-bridge-01", config.bridgeNodeId)
        assertEquals(50, config.maxBatchSize)
        assertEquals(1000, config.maxQueueDepth)

        val manager = BridgeCapabilityManager(config)
        assertTrue(manager.canActAsBridge())
        assertEquals(DeviceRole.BRIDGE, manager.getRole())
    }

    @Test
    fun testBatchSizeLimitsEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgeConfig(maxBatchSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeConfig(maxBatchSize = 51)
        }
    }

    @Test
    fun testRoleSwitchingDisablesBridgeActivity() {
        val manager = BridgeCapabilityManager()
        assertTrue(manager.canActAsBridge())

        manager.setRole(DeviceRole.PAYER)
        assertFalse(manager.canActAsBridge())
        assertEquals(DeviceRole.PAYER, manager.getRole())

        manager.setRole(DeviceRole.RELAY)
        assertFalse(manager.canActAsBridge())

        manager.setRole(DeviceRole.MERCHANT)
        assertFalse(manager.canActAsBridge())

        manager.setRole(DeviceRole.BRIDGE)
        assertTrue(manager.canActAsBridge())
    }

    @Test
    fun testToggleBridgeCapable() {
        val manager = BridgeCapabilityManager()
        assertTrue(manager.canActAsBridge())

        manager.setBridgeCapable(false)
        assertFalse(manager.canActAsBridge())
        assertEquals(DeviceRole.RELAY, manager.getRole())

        manager.setBridgeCapable(true)
        assertTrue(manager.canActAsBridge())
        assertEquals(DeviceRole.BRIDGE, manager.getRole())
    }
}
