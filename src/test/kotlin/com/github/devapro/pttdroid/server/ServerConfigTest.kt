package com.github.devapro.pttdroid.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerConfigTest {

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = pairs.toMap()
        return { map[it] }
    }

    @Test
    fun `an empty environment yields the documented defaults`() {
        val config = ServerConfig.fromEnv { null }

        assertEquals("0.0.0.0", config.host)
        assertEquals(8_000, config.port)
        assertTrue(config.plaintextEnabled)
        assertFalse(config.tls.enabled)
        assertEquals(8_443, config.tls.port)
        assertEquals(32, config.maxSessionsPerChannel)
        assertFalse(config.requiresAuth)
        assertFalse(config.trustForwardedHeaders)
    }

    @Test
    fun `booleans accept the usual spellings`() {
        for (yes in listOf("1", "true", "TRUE", "yes", "on")) {
            assertTrue(ServerConfig.fromEnv(env("PTT_TLS_ENABLED" to yes)).tls.enabled, yes)
        }
        for (no in listOf("0", "false", "No", "off")) {
            assertFalse(ServerConfig.fromEnv(env("PTT_TLS_ENABLED" to no)).tls.enabled, no)
        }
    }

    @Test
    fun `an unparseable boolean falls back to the default rather than to true`() {
        // Same contract as the numeric settings: a typo must never quietly turn something on.
        assertFalse(ServerConfig.fromEnv(env("PTT_TLS_ENABLED" to "yeah")).tls.enabled)
        assertTrue(ServerConfig.fromEnv(env("PTT_HTTP_ENABLED" to "banana")).plaintextEnabled)
    }

    @Test
    fun `a blank auth token means no auth`() {
        assertFalse(ServerConfig.fromEnv(env("PTT_AUTH_TOKEN" to "   ")).requiresAuth)
        assertTrue(ServerConfig.fromEnv(env("PTT_AUTH_TOKEN" to " s3cret ")).requiresAuth)
        assertEquals("s3cret", ServerConfig.fromEnv(env("PTT_AUTH_TOKEN" to " s3cret ")).accessToken)
    }

    @Test
    fun `the SAN list is split, trimmed and de-duplicated`() {
        val tls = ServerConfig.fromEnv(
            env("PTT_TLS_SAN" to " radio.example.com , 192.168.1.10 ,, radio.example.com "),
        ).tls

        assertEquals(listOf("radio.example.com", "192.168.1.10"), tls.subjectNames)
    }

    @Test
    fun `an out-of-range TLS port falls back to the default`() {
        assertEquals(8_443, ServerConfig.fromEnv(env("PTT_TLS_PORT" to "70000")).tls.port)
        assertEquals(9_443, ServerConfig.fromEnv(env("PTT_TLS_PORT" to "9443")).tls.port)
    }

    @Test
    fun `the key password defaults to the keystore password`() {
        val tls = ServerConfig.fromEnv(env("PTT_TLS_KEYSTORE_PASSWORD" to "hunter2")).tls

        assertEquals("hunter2", tls.keyStorePassword)
        assertEquals("hunter2", tls.keyPassword)
    }

    @Test
    fun `the fingerprint file sits next to the keystore`() {
        val tls = ServerConfig.fromEnv(env("PTT_TLS_KEYSTORE" to "/srv/ptt/relay.p12")).tls

        assertEquals("/srv/ptt/relay.p12", tls.keyStoreFile.path)
        assertEquals("/srv/ptt/relay.p12.sha256", tls.fingerprintFile.path)
    }

    @Test
    fun `a token-less relay is flagged`() {
        val warnings = ServerConfig.fromEnv { null }.warnings()

        assertTrue(warnings.any { it.contains("PTT_AUTH_TOKEN") }, warnings.toString())
    }

    @Test
    fun `TLS alongside a wide-open plaintext connector is flagged`() {
        val warnings = ServerConfig.fromEnv(
            env("PTT_TLS_ENABLED" to "true", "PTT_AUTH_TOKEN" to "t", "PTT_TLS_KEYSTORE_PASSWORD" to "p"),
        ).warnings()

        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("plaintext connector"), warnings.toString())
    }

    @Test
    fun `a fully locked-down configuration has nothing to warn about`() {
        val warnings = ServerConfig.fromEnv(
            env(
                "PTT_TLS_ENABLED" to "true",
                "PTT_HTTP_ENABLED" to "false",
                "PTT_AUTH_TOKEN" to "t",
                "PTT_TLS_KEYSTORE_PASSWORD" to "p",
            ),
        ).warnings()

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `listening on nothing at all is flagged`() {
        val warnings = ServerConfig.fromEnv(
            env("PTT_HTTP_ENABLED" to "false", "PTT_AUTH_TOKEN" to "t"),
        ).warnings()

        assertTrue(warnings.any { it.contains("listen on nothing") }, warnings.toString())
    }
}
