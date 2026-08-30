package com.github.devapro.pttdroid.server

import java.io.File

/**
 * Runtime configuration, read from the environment so the same artifact can run locally,
 * in Docker, or on a LAN box without a rebuild.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val plaintextEnabled: Boolean,
    val tls: TlsConfig,
    val maxAudioFrameBytes: Int,
    val pingSeconds: Long,
    val maxChannel: Int,
    val outboundQueueSize: Int,
    val maxSessionsPerChannel: Int,
    /** Shared secret every client must present. Blank disables the check. */
    val accessToken: String,
    /** Read `X-Forwarded-*`. Only safe when something in front of us actually sets them. */
    val trustForwardedHeaders: Boolean,
) {
    val channelRange: IntRange get() = 1..maxChannel

    val requiresAuth: Boolean get() = accessToken.isNotEmpty()

    /**
     * Reasons this configuration should not be run as-is. Not fatal on their own — the operator
     * may genuinely be behind something that handles it — but each one is worth saying out loud
     * at startup rather than discovering from a stranger on your channel.
     */
    fun warnings(): List<String> = buildList {
        if (!plaintextEnabled && !tls.enabled) {
            add("Both PTT_HTTP_ENABLED and PTT_TLS_ENABLED are off — the server would listen on nothing")
        }
        if (!requiresAuth) {
            add(
                "PTT_AUTH_TOKEN is not set: anyone who can reach this port can join a channel " +
                    "and listen. Set it before exposing the relay beyond a trusted LAN.",
            )
        }
        if (tls.enabled && tls.keyStorePassword == TlsConfig.DEFAULT_PASSWORD) {
            add("PTT_TLS_KEYSTORE_PASSWORD is the built-in default — set your own")
        }
        if (plaintextEnabled && tls.enabled && host == ALL_INTERFACES) {
            add(
                "TLS is on but the plaintext connector is still bound to $ALL_INTERFACES:$port — " +
                    "set PTT_HOST=127.0.0.1 or PTT_HTTP_ENABLED=false so audio cannot be read off the wire",
            )
        }
    }

    companion object {
        const val ALL_INTERFACES = "0.0.0.0"

        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig = ServerConfig(
            host = env("PTT_HOST") ?: ALL_INTERFACES,
            port = env("PTT_PORT").toIntOr(8000, 1..65_535),
            plaintextEnabled = env("PTT_HTTP_ENABLED").toBooleanOr(true),
            tls = TlsConfig.fromEnv(env),
            maxAudioFrameBytes = env("PTT_MAX_AUDIO_FRAME_BYTES").toIntOr(8_192, 64..1_048_576),
            pingSeconds = env("PTT_PING_SECONDS").toIntOr(15, 1..3_600).toLong(),
            maxChannel = env("PTT_MAX_CHANNEL").toIntOr(99, 1..9_999),
            outboundQueueSize = env("PTT_OUTBOUND_QUEUE").toIntOr(64, 1..8_192),
            maxSessionsPerChannel = env("PTT_MAX_SESSIONS_PER_CHANNEL").toIntOr(32, 1..10_000),
            accessToken = env("PTT_AUTH_TOKEN")?.trim().orEmpty(),
            trustForwardedHeaders = env("PTT_TRUST_FORWARDED_HEADERS").toBooleanOr(false),
        )

        private fun String?.toIntOr(default: Int, allowed: IntRange): Int {
            val parsed = this?.trim()?.toIntOrNull() ?: return default
            return if (parsed in allowed) parsed else default
        }
    }
}

/**
 * Anything that is not an explicit yes or no keeps the default, matching how the numeric
 * settings treat an unparseable value: a typo in an env var never changes behaviour silently
 * in the *unsafe* direction.
 */
internal fun String?.toBooleanOr(default: Boolean): Boolean =
    when (this?.trim()?.lowercase()) {
        null, "" -> default
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> default
    }

/**
 * TLS for the relay.
 *
 * The expected deployment is a *self-signed* certificate: there is no domain to prove ownership
 * of on a LAN, and a walkie-talkie relay is not worth a public CA. The client trusts it by
 * SHA-256 fingerprint rather than by chain, so what matters here is that the keypair is stable
 * across restarts — hence a keystore on disk, generated once and reused.
 */
data class TlsConfig(
    val enabled: Boolean,
    val port: Int,
    val keyStorePath: String,
    val keyStorePassword: String,
    val keyAlias: String,
    val keyPassword: String,
    /** Names and IPs to put in the certificate's SAN list when generating one. */
    val subjectNames: List<String>,
    val validityDays: Int,
) {
    val keyStoreFile: File get() = File(keyStorePath)

    /** Where the SHA-256 fingerprint is written for the operator to hand to clients. */
    val fingerprintFile: File get() = File("$keyStorePath.sha256")

    companion object {
        const val DEFAULT_PASSWORD = "changeit"

        fun fromEnv(env: (String) -> String?): TlsConfig {
            val storePassword = env("PTT_TLS_KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_PASSWORD
            return TlsConfig(
                enabled = env("PTT_TLS_ENABLED").toBooleanOr(false),
                port = env("PTT_TLS_PORT").toPortOr(8443),
                keyStorePath = env("PTT_TLS_KEYSTORE")?.takeIf { it.isNotBlank() } ?: "certs/ptt.p12",
                keyStorePassword = storePassword,
                keyAlias = env("PTT_TLS_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "ptt",
                keyPassword = env("PTT_TLS_KEY_PASSWORD")?.takeIf { it.isNotEmpty() } ?: storePassword,
                subjectNames = (env("PTT_TLS_SAN") ?: "localhost,127.0.0.1")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct(),
                validityDays = env("PTT_TLS_VALIDITY_DAYS")?.trim()?.toIntOrNull()
                    ?.takeIf { it in 1..36_500 } ?: 3_650,
            )
        }

        private fun String?.toPortOr(default: Int): Int {
            val parsed = this?.trim()?.toIntOrNull() ?: return default
            return if (parsed in 1..65_535) parsed else default
        }
    }
}
