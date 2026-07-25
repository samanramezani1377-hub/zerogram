package com.zerochat.domain

import com.zerochat.data.model.Peer
import kotlinx.coroutines.flow.Flow

interface PeerRepository {
    fun getAllPeers(): Flow<List<Peer>>
    suspend fun getPeer(fingerprint: String): Peer?
    fun getPeerFlow(fingerprint: String): Flow<Peer?>
    suspend fun savePeer(peer: Peer)
    suspend fun updateConnectionInfo(
        fingerprint: String,
        ipAddress: String,
        transport: String,
        timestamp: Long,
    )
    suspend fun deletePeer(fingerprint: String)
}
