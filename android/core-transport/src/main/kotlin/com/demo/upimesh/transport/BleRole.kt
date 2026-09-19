package com.demo.upimesh.transport

/**
 * Logical device roles in the mesh transport layer.
 *
 * NOTE: The transport layer itself does NOT contain financial authority.
 * Logical roles are used only for network topology, advertising metadata,
 * and routing hints.
 */
enum class BleRole(val code: Byte) {
    /** Payer device originating offline payment intents */
    PAYER(1),

    /** Intermediate mesh relay node forwarding packets */
    RELAY(2),

    /** Merchant recipient device collecting payments */
    MERCHANT(3),

    /** Online bridge node connecting mesh to backend WAN */
    BRIDGE(4);

    companion object {
        fun fromCode(code: Byte): BleRole =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown BleRole code: $code")
    }
}
