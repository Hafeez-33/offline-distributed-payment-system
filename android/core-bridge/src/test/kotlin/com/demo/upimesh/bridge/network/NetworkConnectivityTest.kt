package com.demo.upimesh.bridge.network

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NetworkConnectivityTest {

    @Test
    fun testDefaultNetworkProvider() {
        val provider = DefaultNetworkConnectivityProvider()
        assertTrue(provider.isConnected())
        assertEquals(WanNetworkType.WIFI, provider.getNetworkType())

        provider.setConnected(false)
        assertFalse(provider.isConnected())
        assertEquals(WanNetworkType.NONE, provider.getNetworkType())

        provider.setConnected(true, WanNetworkType.CELLULAR)
        assertTrue(provider.isConnected())
        assertEquals(WanNetworkType.CELLULAR, provider.getNetworkType())
    }

    @Test
    fun testFakeNetworkProviderTransitions() {
        val fake = FakeNetworkConnectivityProvider()
        assertTrue(fake.isConnected())

        fake.goOffline()
        assertFalse(fake.isConnected())
        assertEquals(WanNetworkType.NONE, fake.getNetworkType())

        fake.goOnline(WanNetworkType.CELLULAR)
        assertTrue(fake.isConnected())
        assertEquals(WanNetworkType.CELLULAR, fake.getNetworkType())
    }
}
