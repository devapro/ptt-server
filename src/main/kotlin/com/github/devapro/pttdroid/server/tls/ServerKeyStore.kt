package com.github.devapro.pttdroid.server.tls

import com.github.devapro.pttdroid.server.TlsConfig
import io.ktor.network.tls.certificates.KeyType
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.network.tls.extensions.SignatureAlgorithm
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

private val log = LoggerFactory.getLogger("Tls")

/**
 * The keypair the TLS connector serves, plus the fingerprint clients pin against.
 */
class ServerKeyStore(
    val keyStore: KeyStore,
    val certificate: X509Certificate,
    val file: File,
    val generated: Boolean,
) {
    /**
     * SHA-256 over the certificate's DER encoding, colon-separated uppercase hex.
     *
     * The same value `openssl x509 -fingerprint -sha256` and `keytool -list` print, so an
     * operator can verify what the client is being asked to trust with tools they already have.
     */
    val sha256Fingerprint: String by lazy { fingerprintOf(certificate) }

    companion object {
        /**
         * Loads the keystore at [TlsConfig.keyStorePath], generating a self-signed one if it is
         * not there yet.
         *
         * Generating on first boot rather than requiring the operator to run `keytool` is the
         * difference between "TLS is one env var" and "TLS is a wiki page". The file is what
         * makes the identity stable: clients pin the fingerprint, so regenerating on every
         * restart would lock out every client that had already paired.
         */
        fun loadOrCreate(config: TlsConfig): ServerKeyStore {
            val file = config.keyStoreFile
            val existed = file.isFile
            val store = if (existed) load(file, config) else generate(file, config)
            val certificate = store.getCertificate(config.keyAlias) as? X509Certificate
                ?: error(
                    "Keystore ${file.path} has no certificate under alias '${config.keyAlias}' — " +
                        "set PTT_TLS_KEY_ALIAS, or delete the file to regenerate it",
                )
            return ServerKeyStore(
                keyStore = store,
                certificate = certificate,
                file = file,
                generated = !existed,
            ).also { it.publishFingerprint(config) }
        }

        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }

        private fun load(file: File, config: TlsConfig): KeyStore {
            log.info("Loading TLS keystore from {}", file.absolutePath)
            return KeyStore.getInstance(file, config.keyStorePassword.toCharArray())
        }

        private fun generate(file: File, config: TlsConfig): KeyStore {
            val (dnsNames, ipLiterals) = config.subjectNames.partition { !it.isIpLiteral() }
            log.info(
                "No keystore at {} — generating a self-signed certificate for {}",
                file.absolutePath,
                config.subjectNames.joinToString(", "),
            )

            val store = buildKeyStore {
                certificate(config.keyAlias) {
                    password = config.keyPassword
                    // Ktor's default is its own sample principal (O=JetBrains, C=RU), which is
                    // a confusing thing to find inside your own relay's certificate.
                    subject = X500Principal(
                        "CN=${config.subjectNames.firstOrNull() ?: "ptt-relay"}, O=PTTdroid relay",
                    )
                    // Ktor still defaults to SHA-1, which anything current rejects outright.
                    hash = HashAlgorithm.SHA256
                    sign = SignatureAlgorithm.RSA
                    keySizeInBits = 2048
                    keyType = KeyType.Server
                    daysValid = config.validityDays.toLong()
                    domains = dnsNames
                    ipAddresses = ipLiterals.mapNotNull { literal ->
                        runCatching { InetAddress.getByName(literal) }.getOrNull()
                    }
                }
            }

            file.absoluteFile.parentFile?.mkdirs()
            store.saveToFile(file, config.keyStorePassword)
            // The private key is in here; nobody else on the box needs to read it.
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
            return store
        }

        /** An address literal goes in the SAN's IP list; a name goes in the DNS list. */
        private fun String.isIpLiteral(): Boolean =
            contains(':') || (isNotEmpty() && all { it.isDigit() || it == '.' })
    }

    private fun publishFingerprint(config: TlsConfig) {
        runCatching { config.fingerprintFile.writeText(sha256Fingerprint + "\n") }
            .onFailure { log.warn("Could not write {}: {}", config.fingerprintFile, it.toString()) }

        log.info("TLS certificate subject: {}", certificate.subjectX500Principal.name)
        log.info("TLS certificate expires: {}", certificate.notAfter)
        log.info("┌───────────────────────────────────────────────────────────────────────────┐")
        log.info("│ Certificate fingerprint (SHA-256) — paste this into the client's Settings │")
        log.info("└───────────────────────────────────────────────────────────────────────────┘")
        log.info("  {}", sha256Fingerprint)
    }
}
