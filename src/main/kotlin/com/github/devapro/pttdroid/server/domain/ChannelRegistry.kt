package com.github.devapro.pttdroid.server.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the live channels.
 *
 * Channels are created on first join and reaped when their last session leaves. Creation and
 * reaping share one mutex so a join racing the last leave cannot resurrect a channel that is
 * being discarded, or strand a session in a channel nobody else can reach.
 */
class ChannelRegistry {

    private val mutex = Mutex()
    private val channels = LinkedHashMap<Int, PttChannel>()

    suspend fun joinChannel(channelId: Int, session: PttSession): PttChannel {
        val channel = mutex.withLock { channels.getOrPut(channelId) { PttChannel(channelId) } }
        channel.join(session)
        return channel
    }

    /**
     * Removes [session] from [channelId] and discards the channel if it is now empty. The
     * emptiness check happens under the registry lock, so it cannot race a concurrent join.
     */
    suspend fun leaveChannel(channelId: Int, sessionId: String) {
        val channel = mutex.withLock { channels[channelId] } ?: return
        channel.leave(sessionId)
        mutex.withLock {
            if (channels[channelId] === channel && channel.isEmpty()) {
                channels.remove(channelId)
            }
        }
    }

    suspend fun snapshot(): Map<Int, Int> = mutex.withLock { channels.toMap() }
        .mapValues { (_, channel) -> channel.peerCount() }

    suspend fun channelCount(): Int = mutex.withLock { channels.size }
}
