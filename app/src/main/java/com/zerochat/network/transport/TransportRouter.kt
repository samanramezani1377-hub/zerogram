package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import com.zerochat.network.wan.IceCandidate
import kotlinx.coroutines.flow.Flow

/**
 * Central routing interface for message transport.
 *
 * TransportRouter abstracts over LAN and WAN transports, choosing the best
 * path for each peer. It is the single entry point for sending and receiving
 * messages at the transport level.
 *
 * Design inspired by Signal's transport abstraction — a single interface
 * that hides the complexity of multiple underlying channels.
 */
interface TransportRouter {

    // ── Lifecycle ──────────────────────────────────────────────────

    /** Start listening on all available transports */
    suspend fun start()

    /** Set the local device fingerprint for protocol headers */
    fun setLocalFingerprint(fingerprint: String)

    /** Stop all transports and release resources */
    suspend fun stop()

    // ── Send ───────────────────────────────────────────────────────

    /**
     * Send an encrypted payload to a specific peer.
     *
     * The router chooses the best transport (LAN > WAN) based on
     * current connectivity. The payload is already encrypted — the
     * transport layer never sees plaintext.
     *
     * @param peerFingerprint the recipient's identity fingerprint
     * @param encryptedPayload the ciphertext bytes to send
     */
    suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray)

    // ── Receive ────────────────────────────────────────────────────

    /**
     * Flow of incoming messages from any transport.
     * Each emission contains the raw payload and metadata about which
     * peer sent it and via which transport.
     */
    fun incomingMessages(): Flow<IncomingTransportMessage>

    // ── Discovery ──────────────────────────────────────────────────

    /** Flow of all discovered peers (LAN + WAN) */
    fun discoveredPeers(): Flow<List<DiscoveredPeer>>

    // ── Connection Management ──────────────────────────────────────

    /** Current transport mode for a peer */
    fun currentMode(peerFingerprint: String): TransportMode

    /** Establish a LAN connection to a peer */
    suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String): String

    /** Create a WAN (WebRTC) connection offer */
    suspend fun connectWan(peerFingerprint: String): WanConnectionOffer

    /** Accept a WAN connection offer and return the answer */
    suspend fun acceptWanConnection(
        peerFingerprint: String,
        offerSdp: String,
    ): String

    /** Complete a WAN connection by setting the remote answer */
    suspend fun completeWanConnection(peerFingerprint: String, answerSdp: String)

    /** Add a remote ICE candidate for WAN connection */
    suspend fun addIceCandidate(
        candidate: String, sdpMid: String, sdpMLineIndex: Int,
    )

    /** Flow of locally generated ICE candidates for WAN connections */
    fun localIceCandidates(): Flow<IceCandidate>

    /**
     * Send raw bytes to a peer at a specific IP:port.
     * Used for control messages (connection requests, etc).
     */
    suspend fun sendRaw(
        peerFingerprint: String, ipAddress: String, port: Int, data: ByteArray,
    )
}

/**
 * An incoming message from a transport, carrying the raw encrypted payload
 * and metadata for routing to the correct handler.
 */
data class IncomingTransportMessage(
    val peerFingerprint: String,
    val payload: ByteArray,
    val transportMode: TransportMode,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IncomingTransportMessage
        return peerFingerprint == other.peerFingerprint &&
                payload.contentEquals(other.payload) &&
                transportMode == other.transportMode
    }

    override fun hashCode(): Int {
        var result = peerFingerprint.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + transportMode.hashCode()
        return result
    }
}

data class DiscoveredPeer(
    val ipAddress: String,
    val port: Int,
    val displayName: String,
    val discoveryMethod: String,
    val transportMode: TransportMode,
)

data class WanConnectionOffer(
    val peerFingerprint: String,
    val offerSdp: String,
)
