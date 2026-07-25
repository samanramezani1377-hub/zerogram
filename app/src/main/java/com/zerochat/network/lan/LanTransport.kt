package com.zerochat.network.lan

import kotlinx.coroutines.flow.Flow

/**
 * Transport over local network (WiFi Direct / mDNS).
 *
 * LAN transport enables direct device-to-device communication without
 * internet access. It uses WiFi Direct for peer discovery and a simple
 * TCP socket connection for data transfer.
 *
 * Protocol: each message is prefixed with 64-byte sender fingerprint
 * so the receiver always knows which peer sent the data.
 */
interface LanTransport {

    // ── Lifecycle ──────────────────────────────────────────────────

    fun startListening()
    fun stopListening()

    /** Set the local device fingerprint for use in protocol header */
    fun setLocalFingerprint(fingerprint: String)

    // ── Discovery ──────────────────────────────────────────────────

    fun startWiFiDirectDiscovery()
    fun stopWiFiDirectDiscovery()
    fun startMdnsDiscovery()
    fun stopMdnsDiscovery()

    /** Flow of discovered LAN peers */
    fun discoveredPeers(): Flow<List<LanPeer>>

    // ── Connection ─────────────────────────────────────────────────

    /**
     * Connect directly to a peer at the given IP and port.
     * @return true if the connection was established
     */
    suspend fun connectDirect(ipAddress: String, port: Int): Boolean

    /** Current LAN connection state */
    fun connectionState(): Flow<LanConnectionState>

    // ── Data transfer ──────────────────────────────────────────────

    /**
     * Send data to a specific peer identified by IP and port.
     * Automatically prefixes data with local fingerprint header.
     */
    suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int)

    /**
     * Flow of incoming data from any connected LAN peer.
     * Includes peer fingerprint, payload, and sender IP.
     */
    fun incomingData(): Flow<LanIncoming>

    // ── Utility ────────────────────────────────────────────────────

    /** Get local IP addresses of this device */
    suspend fun getLocalAddresses(): List<String>

    // ── PIN Code (8-digit) ────────────────────────────────────────

    fun getOrCreatePinCode(): String
    suspend fun advertisePinCode()
    suspend fun resolvePinCode(pin: String): LanPeer?
}

/**
 * Incoming data from a LAN peer, with resolved fingerprint.
 */
data class LanIncoming(
    val peerFingerprint: String,
    val payload: ByteArray,
    val senderIp: String,
)

data class LanPeer(
    val deviceId: String = "",
    val ipAddress: String,
    val port: Int = 44231,
    val displayName: String = "",
    val discoveryMethod: String = "unknown",
)

enum class LanConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}
