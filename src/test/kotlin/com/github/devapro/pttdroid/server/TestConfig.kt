package com.github.devapro.pttdroid.server

/**
 * A [ServerConfig] for tests, with every field defaulted so adding a new setting does not
 * require touching every test that only cares about one of them.
 */
fun testConfig(
    maxAudioFrameBytes: Int = 8_192,
    maxChannel: Int = 99,
    outboundQueueSize: Int = 64,
    maxSessionsPerChannel: Int = 32,
    accessToken: String = "",
): ServerConfig = ServerConfig(
    host = "0.0.0.0",
    port = 0,
    plaintextEnabled = true,
    tls = TlsConfig.fromEnv { null },
    maxAudioFrameBytes = maxAudioFrameBytes,
    pingSeconds = 15,
    maxChannel = maxChannel,
    outboundQueueSize = outboundQueueSize,
    maxSessionsPerChannel = maxSessionsPerChannel,
    accessToken = accessToken,
    trustForwardedHeaders = false,
)
