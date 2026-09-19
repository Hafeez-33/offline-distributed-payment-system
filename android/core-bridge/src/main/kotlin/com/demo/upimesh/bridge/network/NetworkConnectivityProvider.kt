package com.demo.upimesh.bridge.network

/**
 * WAN network connection type.
 */
enum class WanNetworkType {
    NONE,
    WIFI,
    CELLULAR,
    ETHERNET
}

/**
 * Abstraction for observing and querying WAN network connectivity.
 */
interface NetworkConnectivityProvider {
    fun isConnected(): Boolean
    fun getNetworkType(): WanNetworkType
}

/**
 * Default JVM/Android network connectivity provider.
 */
class DefaultNetworkConnectivityProvider(
    @Volatile private var connected: Boolean = true,
    @Volatile private var networkType: WanNetworkType = WanNetworkType.WIFI
) : NetworkConnectivityProvider {

    override fun isConnected(): Boolean = connected

    override fun getNetworkType(): WanNetworkType = networkType

    fun setConnected(status: Boolean, type: WanNetworkType = WanNetworkType.WIFI) {
        this.connected = status
        this.networkType = if (status) type else WanNetworkType.NONE
    }
}

/**
 * Deterministic fake network connectivity provider for testing online/offline transitions.
 */
class FakeNetworkConnectivityProvider(
    @Volatile private var connected: Boolean = true,
    @Volatile private var networkType: WanNetworkType = WanNetworkType.WIFI
) : NetworkConnectivityProvider {

    override fun isConnected(): Boolean = connected

    override fun getNetworkType(): WanNetworkType = networkType

    fun goOffline() {
        connected = false
        networkType = WanNetworkType.NONE
    }

    fun goOnline(type: WanNetworkType = WanNetworkType.WIFI) {
        connected = true
        networkType = type
    }
}
