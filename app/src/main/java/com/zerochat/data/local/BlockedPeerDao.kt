package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.BlockedPeer
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockedPeerDao {

    @Query("SELECT * FROM blocked_peers ORDER BY blocked_at DESC")
    fun getAllBlocked(): Flow<List<BlockedPeer>>

    @Query("SELECT EXISTS(SELECT 1 FROM blocked_peers WHERE fingerprint = :fingerprint)")
    suspend fun isBlocked(fingerprint: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocked(blocked: BlockedPeer)

    @Query("DELETE FROM blocked_peers WHERE fingerprint = :fingerprint")
    suspend fun unblock(fingerprint: String)
}
