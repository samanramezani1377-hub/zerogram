package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.Peer
import com.zerochat.data.model.TransportMode
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY last_seen DESC")
    fun getAllPeers(): Flow<List<Peer>>

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    suspend fun getPeer(fingerprint: String): Peer?

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint")
    fun getPeerFlow(fingerprint: String): Flow<Peer?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: Peer)

    @Update
    suspend fun updatePeer(peer: Peer)

    @Query("""
        UPDATE peers
        SET last_seen = :timestamp,
            ip_address = :ipAddress,
            preferred_transport = :transport
        WHERE fingerprint = :fingerprint
    """)
    suspend fun updateConnectionInfo(
        fingerprint: String,
        ipAddress: String,
        transport: TransportMode,
        timestamp: Long,
    )

    // ── Profile Picture ──────────────────────────────────────────

    @Query("""
        UPDATE peers
        SET profile_image_id = :imageId,
            profile_image_path = :imagePath,
            profile_image_hash = :imageHash,
            profile_updated_at = :updatedAt
        WHERE fingerprint = :fingerprint
    """)
    suspend fun updatePeerProfileImage(
        fingerprint: String,
        imageId: String?,
        imagePath: String?,
        imageHash: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM peers WHERE fingerprint = :fingerprint")
    suspend fun deletePeer(fingerprint: String)
}
