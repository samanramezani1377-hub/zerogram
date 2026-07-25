package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.RequestStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ConnectionRequestDao {

    /** All pending incoming requests (PENDING status, not ours) */
    @Query("""
        SELECT * FROM connection_requests 
        WHERE status = 'PENDING' AND is_outgoing = 0
        ORDER BY timestamp DESC
    """)
    fun getPendingRequests(): Flow<List<ConnectionRequest>>

    /** All requests (both directions) */
    @Query("SELECT * FROM connection_requests ORDER BY timestamp DESC")
    fun getAllRequests(): Flow<List<ConnectionRequest>>

    /** Count of pending incoming requests */
    @Query("""
        SELECT COUNT(*) FROM connection_requests 
        WHERE status = 'PENDING' AND is_outgoing = 0
    """)
    fun getPendingCount(): Flow<Int>

    /** Insert or replace a request */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: ConnectionRequest)

    /** Update request status */
    @Query("""
        UPDATE connection_requests 
        SET status = :status 
        WHERE id = :requestId
    """)
    suspend fun updateStatus(requestId: String, status: RequestStatus)

    /** Delete a specific request */
    @Query("DELETE FROM connection_requests WHERE id = :requestId")
    suspend fun deleteRequest(requestId: String)

    /** Delete all expired requests */
    @Query("DELETE FROM connection_requests WHERE timestamp < :expireBefore")
    suspend fun deleteExpired(expireBefore: Long)
}
