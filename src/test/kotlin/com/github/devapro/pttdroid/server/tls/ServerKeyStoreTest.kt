package com.github.devapro.pttdroid.server.tls

import com.github.devapro.pttdroid.server.TlsConfig
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerKeyStoreTest {

    private val workDir: File = Files.createTempDirectory("ptt-tls-test").toFile()

    private fun config(
        san: List<String> = listOf("localhost", "127.0.0.1"),
        alias: String = "ptt",
        password: String = "hunter2",
        fileName: String = "ptt.p12",
    ) = TlsConfig(
        enabled = true,
        port = 8_443,
        keyStorePath = File(workDir, fileName).path,
        keyStorePassword = password,
        keyAlias = alias,
        keyPassword = password,
        subjectNames = san,
        validityDays = 30,
    )

    @AfterTest
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    @Test
    fun `a missing keystore is generated on first use`() {
        val config = config()
        assertFalse(config.keyStoreFile.exists())

        val store = ServerKeyStore.loadOrCreate(config)

        assertTrue(store.generated)
        assertTrue(config.keyStoreFile.isFile)
        assertEquals("ptt", store.keyStore.aliases().toList().single())
    }

    @Test
    fun `the identity survives a restart`() {
        // Clients pin the fingerprint, so regenerating on every boot would lock out everyone
        // who had already paired with this relay.
        val config = config()

        val first = ServerKeyStore.loadOrCreate(config)
        val second = ServerKeyStore.loadOrCreate(config)

        assertTrue(first.generated)
        assertFalse(second.generated)
        assertEquals(first.sha256Fingerprint, second.sha256Fingerprint)
    }

    @Test
    fun `the fingerprint is colon-separated uppercase hex over 32 bytes`() {
        val fingerprint = ServerKeyStore.loadOrCreate(config()).sha256Fingerprint

        val octets = fingerprint.split(":")
        assertEquals(32, octets.size, fingerprint)
        assertTrue(octets.all { it.length == 2 && it.all { c -> c in "0123456789ABCDEF" } }, fingerprint)
    }

    @Test
    fun `the fingerprint is written next to the keystore for the operator to copy`() {
        val config = config()

        val store = ServerKeyStore.loadOrCreate(config)

        assertEquals(store.sha256Fingerprint, config.fingerprintFile.readText().trim())
    }

    @Test
    fun `configured names and addresses both reach the certificate`() {
        val store = ServerKeyStore.loadOrCreate(
            config(san = listOf("radio.example.com", "192.168.1.10")),
        )

        // SubjectAlternativeName entries are (type, value); 2 is dNSName, 7 is iPAddress.
        val names = store.certificate.subjectAlternativeNames.orEmpty()
            .map { it[0] as Int to it[1].toString() }

        assertContains(names, 2 to "radio.example.com")
        assertContains(names, 7 to "192.168.1.10")
    }

    @Test
    fun `the generated certificate is not signed with SHA-1`() {
        // Everything current rejects a SHA-1 signature outright, and Ktor's builder still
        // defaults to it.
        val store = ServerKeyStore.loadOrCreate(config())

        assertEquals("SHA256withRSA", store.certificate.sigAlgName)
    }

    @Test
    fun `an alias that is not in the keystore fails loudly`() {
        val config = config()
        ServerKeyStore.loadOrCreate(config)

        val failure = runCatching { ServerKeyStore.loadOrCreate(config.copy(keyAlias = "other")) }

        assertTrue(failure.isFailure)
        assertContains(failure.exceptionOrNull()?.message.orEmpty(), "PTT_TLS_KEY_ALIAS")
    }

    @Test
    fun `nested keystore directories are created`() {
        val config = config(fileName = "nested/deeper/ptt.p12")

        val store = ServerKeyStore.loadOrCreate(config)

        assertTrue(store.file.isFile)
    }
}
