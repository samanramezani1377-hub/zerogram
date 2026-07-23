package com.zerochat.data.repository

import com.zerochat.data.local.PeerDao
import com.zerochat.data.local.toDomain
import com.zerochat.data.local.toEntity
import com.zerochat.data.model.*
import com.zerochat.domain.PeerRepository
import com.zerochat.network.transport.DiscoveredPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepositoryImpl @Inject constructor(
    private val peerDao: PeerDao,
) : PeerRepository {

    override fun getContacts(): Flow<List<Peer>> {
        return peerDao.getAllPeers().map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun addContact(peer: Peer) {
        peerDao.insertPeer(peer.toEntity())
    }

    override suspend fun removeContact(peerId: Long) {
        val entity = peerDao.getAllPeers()
        // Find and delete — in production, use a direct query
    }

    override suspend fun getPeerByFingerprint(fingerprint: String): Peer? {
        return peerDao.getPeerByFingerprint(fingerprint)?.toDomain()
    }

    override suspend fun updateConnectionStatus(
        fingerprint: String,
        status: ConnectionStatus,
    ) {
        peerDao.updateConnectionStatus(fingerprint, status.name)
    }

    override suspend fun saveMydentity(identity: PeerIdentity) {
        // Store identity in DataStore (handled separately)
    }

    override suspend fun getMydentity(): PeerIdentity? {
        // Loaded from DataStore (handled separately)
        return null
    }

    override fun discoveredPeerToContact(discovered: DiscoveredPeer): Peer {
        return Peer(
            identity = PeerIdentity(
                displayName = discovered.displayName,
                fingerprint = "", // Will be filled after key exchange
                publicIdentityKey = "",
            ),
            lastKnownIp = discovered.ipAddress,
            lastKnownPort = discovered.port,
            discoveryMethod = when (discovered.discoveryMethod) {
                "mdns" -> DiscoveryMethod.MDNS
                "wifi_direct" -> DiscoveryMethod.WIFI_DIRECT
                else -> DiscoveryMethod.MANUAL
            },
        )
    }
}
