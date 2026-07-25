package com.zerochat.domain

import com.zerochat.data.model.BlockedPeer
import kotlinx.coroutines.flow.Flow

interface BlockedPeerRepository {
    fun getAllBlocked(): Flow<List<BlockedPeer>>
    suspend fun isBlocked(fingerprint: String): Boolean
    suspend fun blockPeer(fingerprint: String, displayName: String, reason: String)
    suspend fun unblockPeer(fingerprint: String)
}
