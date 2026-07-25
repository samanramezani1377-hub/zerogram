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

    suspend fun startListening(port: Int = 45454)
    suspend fun stopListening()

    // ── Discovery ──────────────────────────────────────────────────

    suspend fun startWiFiDirectDiscovery()
    suspend fun stopWiFiDirectDiscovery()
    suspend fun startMdnsDiscovery()
    suspend fun stopMdnsDiscovery()

    /** Flow of discovered LAN peers */
    fun discoveredPeers(): Flow<List<LanPeer>>

    // ── Connection ─────────────────────────────────────────────────

    suspend fun connectDirect(ipAddress: String, port: Int): Boolean

    /** Current LAN connection state */
    fun connectionState(): Flow<LanConnectionState>

    // ── Data transfer ──────────────────────────────────────────────

    suspend fun sendData(data: ByteArray)

    /** Flow of incoming data from any connected LAN peer */
    fun incomingData(): Flow<ByteArray>

    // ── Utility ────────────────────────────────────────────────────

    fun getLocalAddresses(): List<String>
}

data class LanPeer(
    val ipAddress: String,
    val port: Int = 45454,
    val displayName: String = "Unknown",
    val discoveryMethod: String = "wifi_direct",
    val deviceId: String = "",
)

enum class LanConnectionState {
    DISCONNECTED,
    LISTENING,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
}
