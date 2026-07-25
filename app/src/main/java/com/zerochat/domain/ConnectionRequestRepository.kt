package com.zerochat.domain

import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.RequestStatus
import kotlinx.coroutines.flow.Flow

interface ConnectionRequestRepository {
    fun getPendingRequests(): Flow<List<ConnectionRequest>>
    fun getAllRequests(): Flow<List<ConnectionRequest>>
    fun getPendingCount(): Flow<Int>
    suspend fun insertRequest(request: ConnectionRequest)
    suspend fun acceptRequest(requestId: String)
    suspend fun rejectRequest(requestId: String)
    suspend fun deleteRequest(requestId: String)
    suspend fun deleteExpired()
}
