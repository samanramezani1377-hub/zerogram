package com.zerochat.data.repository

import com.zerochat.data.local.PeerDao
import com.zerochat.data.model.Peer
import com.zerochat.domain.PeerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val peerDao: PeerDao,
) : PeerRepository {

    override fun getAllPeers(): Flow<List<Peer>> = peerDao.getAllPeers()

    override suspend fun getPeer(fingerprint: String): Peer? = peerDao.getPeer(fingerprint)

    override fun getPeerFlow(fingerprint: String): Flow<Peer?> = peerDao.getPeerFlow(fingerprint)

    override suspend fun savePeer(peer: Peer) = peerDao.insertPeer(peer)

    override suspend fun updateConnectionInfo(
        fingerprint: String,
        ipAddress: String,
        transport: String,
        timestamp: Long,
    ) = peerDao.updateConnectionInfo(fingerprint, ipAddress, transport, timestamp)

    override suspend fun deletePeer(fingerprint: String) = peerDao.deletePeer(fingerprint)
}
