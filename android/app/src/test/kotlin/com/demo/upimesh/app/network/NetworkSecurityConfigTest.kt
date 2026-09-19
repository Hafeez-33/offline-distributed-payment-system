package com.demo.upimesh.app.network

import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NetworkSecurityConfigTest {

    @Test
    fun testNetworkSecurityConfigFileExistsAndEnforcesStrictScoping() {
        val file = File("src/main/res/xml/network_security_config.xml")
        assertTrue(file.exists(), "network_security_config.xml must exist under res/xml/")

        val content = file.readText()
        assertTrue(content.contains("<network-security-config>"), "Must be valid network-security-config root")
        assertTrue(content.contains("cleartextTrafficPermitted=\"false\""), "Must disallow cleartext traffic in base-config")
        assertTrue(content.contains("<domain includeSubdomains=\"false\">10.0.2.2</domain>"), "Must permit 10.0.2.2 for local emulator testing")
        assertTrue(content.contains("<domain includeSubdomains=\"false\">localhost</domain>"), "Must permit localhost")
        assertTrue(content.contains("192.168.1.100"), "Must permit local development LAN IPs")
    }

    @Test
    fun testAndroidManifestReferencesNetworkSecurityConfig() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue(manifestFile.exists(), "AndroidManifest.xml must exist")

        val content = manifestFile.readText()
        assertTrue(
            content.contains("android:networkSecurityConfig=\"@xml/network_security_config\""),
            "Manifest must reference network_security_config"
        )
    }
}
