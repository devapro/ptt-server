package com.github.devapro.pttdroid.server

/**
 * Runtime configuration, read from the environment so the same artifact can run locally,
 * in Docker, or on a LAN box without a rebuild.
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val maxAudioFrameBytes: Int,
    val pingSeconds: Long,
    val maxChannel: Int,
    val outboundQueueSize: Int,
) {
    val channelRange: IntRange get() = 1..maxChannel

    companion object {
        fun fromEnv(env: (String) -> String? = System::getenv): ServerConfig = ServerConfig(
            host = env("PTT_HOST") ?: "0.0.0.0",
            port = env("PTT_PORT").toIntOr(8000, 1..65_535),
            maxAudioFrameBytes = env("PTT_MAX_AUDIO_FRAME_BYTES").toIntOr(8_192, 64..1_048_576),
            pingSeconds = env("PTT_PING_SECONDS").toIntOr(15, 1..3_600).toLong(),
            maxChannel = env("PTT_MAX_CHANNEL").toIntOr(99, 1..9_999),
            outboundQueueSize = env("PTT_OUTBOUND_QUEUE").toIntOr(64, 1..8_192),
        )

        private fun String?.toIntOr(default: Int, allowed: IntRange): Int {
            val parsed = this?.trim()?.toIntOrNull() ?: return default
            return if (parsed in allowed) parsed else default
        }
    }
}
