package com.zerochat.network.lan

import kotlinx.coroutines.flow.Flow

/**
 * Transport over local network (WiFi Direct / mDNS).
 *
 * LAN transport enables direct device-to-device communication without
 * internet access. It uses WiFi Direct for peer discovery and a simple
 * TCP socket connection for data transfer.
 */
interface LanTransport {

    // ── Lifecycle ──────────────────────────────────────────────────

    fun startListening()
    fun stopListening()

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
     * Send raw data to the currently connected peer.
     * This sends to the peer that was most recently connected via connectDirect().
     */
    suspend fun sendData(data: ByteArray)

    /**
     * Send data to a specific peer identified by IP and port.
     * Preferred over sendData() when multiple LAN peers may be active.
     */
    suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int)

    /** Flow of incoming data from any connected LAN peer */
    fun incomingData(): Flow<ByteArray>

    // ── Utility ────────────────────────────────────────────────────

    /** Get local IP addresses of this device */
    suspend fun getLocalAddresses(): List<String>
}

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
