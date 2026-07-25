package com.zerochat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zerochat.data.model.Peer
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
        transport: String,
        timestamp: Long,
    )

    @Query("DELETE FROM peers WHERE fingerprint = :fingerprint")
    suspend fun deletePeer(fingerprint: String)
}
