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

    fun startListening()
    fun stopListening()
    fun setLocalFingerprint(fingerprint: String)

    fun startWiFiDirectDiscovery()
    fun stopWiFiDirectDiscovery()
    fun startMdnsDiscovery()
    fun stopMdnsDiscovery()

    fun discoveredPeers(): Flow<List<LanPeer>>

    /**
     * Connect directly to a peer at the given IP and port.
     * The connection exchanges fingerprints in both directions.
     * @return the remote peer's fingerprint on success, null on failure
     */
    suspend fun connectDirect(ipAddress: String, port: Int): String?

    fun connectionState(): Flow<LanConnectionState>

    suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int)

    fun incomingData(): Flow<LanIncoming>

    suspend fun getLocalAddresses(): List<String>

    fun getOrCreatePinCode(): String
    suspend fun advertisePinCode()
    suspend fun resolvePinCode(pin: String): LanPeer?
}

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
