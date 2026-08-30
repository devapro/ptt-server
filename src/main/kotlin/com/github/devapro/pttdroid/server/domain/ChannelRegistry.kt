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
     * Joins [channelId] unless it already holds [capacity] sessions, in which case null comes
     * back and the channel is left exactly as it was — including being reaped again if this
     * call is what created it.
     */
    suspend fun tryJoinChannel(channelId: Int, session: PttSession, capacity: Int): PttChannel? {
        val channel = mutex.withLock { channels.getOrPut(channelId) { PttChannel(channelId) } }
        if (channel.tryJoin(session, capacity) != null) return channel
        mutex.withLock {
            if (channels[channelId] === channel && channel.isEmpty()) channels.remove(channelId)
        }
        return null
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
