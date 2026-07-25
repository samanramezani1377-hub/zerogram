package com.zerochat.domain

import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.Peer
import com.zerochat.data.model.RequestStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.transport.TransportRouter
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Business logic for connection requests.
 *
 * Handles the full lifecycle:
 * - Send request → peer sees it in their inbox
 * - Accept → establish LAN route + save peer
 * - Reject → mark as rejected
 * - Block → add to blocked list + reject
 */
@Singleton
class ConnectionRequestUseCase @Inject constructor(
    private val requestRepo: ConnectionRequestRepository,
    private val blockedRepo: BlockedPeerRepository,
    private val peerRepo: PeerRepository,
    private val transportRouter: TransportRouter,
    private val lanTransport: LanTransport,
    private val cryptoEngine: com.zerochat.crypto.CryptoEngine,
) {

    /**
     * Send a connection request to a peer (found via discovery).
     * The request is saved locally so the sender can track its status.
     * The request is also sent over the network via the LAN connection.
     */
    suspend fun sendRequest(
        targetFingerprint: String,
        targetIp: String,
        targetPort: Int,
        displayName: String,
        pin: String? = null,
    ): ConnectionRequest {
        // Check if we're blocked by the peer… can't check directly,
        // but if they blocked us, they won't respond.

        val myIp = getMyIp()

        val request = ConnectionRequest(
            id = UUID.randomUUID().toString().take(12),
            senderFingerprint = cryptoEngine.getLocalFingerprint(),
            senderDisplayName = displayName,
            senderIp = myIp,
            senderPort = 44231,
            pin = pin,
            status = RequestStatus.PENDING,
            isOutgoing = true,
            timestamp = System.currentTimeMillis(),
        )

        requestRepo.insertRequest(request)

        // Send request over the wire — the peer's IncomingMessageHandler
        // will receive it and create a matching entry on their side.
        try {
            val requestJson = """{"type":"connection_request","id":"${request.id}","fingerprint":"${request.senderFingerprint}","display_name":"$displayName"}"""
            transportRouter.sendRaw(targetFingerprint, targetIp, targetPort, requestJson.toByteArray())
        } catch (e: Exception) {
            Timber.w(e, "Failed to send connection request to $targetFingerprint")
        }

        return request
    }

    /**
     * Accept an incoming connection request.
     * - Mark request as accepted
     * - Save the peer to contacts
     * - Establish LAN route
     */
    suspend fun acceptRequest(request: ConnectionRequest) {
        requestRepo.acceptRequest(request.id)

        // Save peer
        val existing = peerRepo.getPeer(request.senderFingerprint)
        if (existing == null) {
            peerRepo.savePeer(
                Peer(
                    fingerprint = request.senderFingerprint,
                    displayName = request.senderDisplayName.ifBlank { request.senderFingerprint.take(8) },
                    ipAddress = request.senderIp,
                    port = request.senderPort,
                    preferredTransport = TransportMode.LAN,
                    lastSeen = System.currentTimeMillis(),
                )
            )
        }

        // Establish route so we can message them
        try {
            if (request.senderIp.isNotBlank()) {
                transportRouter.connectLan(request.senderIp, request.senderPort, request.senderFingerprint)
            }
        } catch (e: Exception) {
            Timber.w(e, "Accept: could not establish route to ${request.senderFingerprint}")
        }
    }

    /**
     * Reject an incoming connection request.
     */
    suspend fun rejectRequest(requestId: String) {
        requestRepo.rejectRequest(requestId)
    }

    /**
     * Block the sender of a connection request.
     * - Add to blocked list
     * - Delete the peer if exists
     * - Reject the request
     */
    suspend fun blockRequest(request: ConnectionRequest) {
        blockedRepo.blockPeer(
            fingerprint = request.senderFingerprint,
            displayName = request.senderDisplayName,
            reason = "Blocked from connection request",
        )

        // Delete from contacts if they were a peer
        try { peerRepo.deletePeer(request.senderFingerprint) } catch (_: Exception) {}

        // Reject the request
        requestRepo.rejectRequest(request.id)
    }

    /**
     * Process an incoming connection request received over the network.
     * This is called by IncomingMessageHandler when it detects a
     * connection_request type message.
     */
    suspend fun receiveRequest(
        senderFingerprint: String,
        senderDisplayName: String,
        senderIp: String,
        requestId: String,
    ) {
        // If blocked, silently ignore
        if (blockedRepo.isBlocked(senderFingerprint)) {
            Timber.d("Ignoring request from blocked peer: $senderFingerprint")
            return
        }

        val request = ConnectionRequest(
            id = requestId.ifBlank { UUID.randomUUID().toString().take(12) },
            senderFingerprint = senderFingerprint,
            senderDisplayName = senderDisplayName,
            senderIp = senderIp,
            senderPort = 44231,
            status = RequestStatus.PENDING,
            isOutgoing = false,
            timestamp = System.currentTimeMillis(),
        )

        requestRepo.insertRequest(request)
        Timber.i("Connection request received from $senderFingerprint")
    }

    /**
     * Unblock a previously blocked peer.
     */
    suspend fun unblockPeer(fingerprint: String) {
        blockedRepo.unblockPeer(fingerprint)
    }

    private suspend fun getMyIp(): String {
        return try {
            lanTransport.getLocalAddresses().firstOrNull() ?: ""
        } catch (_: Exception) { "" }
    }
}
