package com.zerochat.data.repository

import com.zerochat.data.local.ConnectionRequestDao
import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.RequestStatus
import com.zerochat.domain.ConnectionRequestRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionRequestRepositoryImpl @Inject constructor(
    private val dao: ConnectionRequestDao,
) : ConnectionRequestRepository {

    override fun getPendingRequests(): Flow<List<ConnectionRequest>> =
        dao.getPendingRequests()

    override fun getAllRequests(): Flow<List<ConnectionRequest>> =
        dao.getAllRequests()

    override fun getPendingCount(): Flow<Int> =
        dao.getPendingCount()

    override suspend fun insertRequest(request: ConnectionRequest) =
        dao.insertRequest(request)

    override suspend fun acceptRequest(requestId: String) =
        dao.updateStatus(requestId, RequestStatus.ACCEPTED)

    override suspend fun rejectRequest(requestId: String) =
        dao.updateStatus(requestId, RequestStatus.REJECTED)

    override suspend fun deleteRequest(requestId: String) =
        dao.deleteRequest(requestId)

    override suspend fun deleteExpired() =
        dao.deleteExpired(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
}
