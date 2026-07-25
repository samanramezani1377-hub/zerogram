package com.zerochat.data.repository

import com.zerochat.data.local.BlockedPeerDao
import com.zerochat.data.model.BlockedPeer
import com.zerochat.domain.BlockedPeerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BlockedPeerRepositoryImpl @Inject constructor(
    private val dao: BlockedPeerDao,
) : BlockedPeerRepository {

    override fun getAllBlocked(): Flow<List<BlockedPeer>> =
        dao.getAllBlocked()

    override suspend fun isBlocked(fingerprint: String): Boolean =
        dao.isBlocked(fingerprint)

    override suspend fun blockPeer(
        fingerprint: String, displayName: String, reason: String,
    ) = dao.insertBlocked(BlockedPeer(fingerprint, displayName, reason = reason))

    override suspend fun unblockPeer(fingerprint: String) =
        dao.unblock(fingerprint)
}
