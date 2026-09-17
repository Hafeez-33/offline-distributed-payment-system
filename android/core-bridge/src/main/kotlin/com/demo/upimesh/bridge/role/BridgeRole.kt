package com.demo.upimesh.bridge.role

/**
 * Operating role for an Android node within the distributed payment mesh.
 * Bridge capability is a transport/gateway capability enabling WAN synchronization.
 */
enum class DeviceRole {
    PAYER,
    RELAY,
    MERCHANT,
    BRIDGE
}

/**
 * Configuration for the WAN bridge gateway subsystem.
 */
data class BridgeConfig(
    val isBridgeCapable: Boolean = true,
    val bridgeNodeId: String = "android-bridge-01",
    val maxBatchSize: Int = 50,
    val maxQueueDepth: Int = 1000,
    val defaultHopCount: Int = 1,
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 15000
) {
    init {
        require(maxBatchSize in 1..50) { "maxBatchSize must be between 1 and 50" }
        require(maxQueueDepth > 0) { "maxQueueDepth must be positive" }
    }
}

/**
 * Manages device bridge eligibility and dynamic role configuration.
 */
class BridgeCapabilityManager(
    var config: BridgeConfig = BridgeConfig()
) {
    @Volatile
    private var role: DeviceRole = if (config.isBridgeCapable) DeviceRole.BRIDGE else DeviceRole.RELAY

    fun canActAsBridge(): Boolean {
        return config.isBridgeCapable && role == DeviceRole.BRIDGE
    }

    fun setRole(newRole: DeviceRole) {
        this.role = newRole
    }

    fun getRole(): DeviceRole = role

    fun setBridgeCapable(enabled: Boolean) {
        config = config.copy(isBridgeCapable = enabled)
        if (!enabled && role == DeviceRole.BRIDGE) {
            role = DeviceRole.RELAY
        } else if (enabled && role == DeviceRole.RELAY) {
            role = DeviceRole.BRIDGE
        }
    }
}
