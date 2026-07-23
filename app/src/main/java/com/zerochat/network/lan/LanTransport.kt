package com.zerochat.network.lan

import com.zerochat.data.model.PeerIdentity
import kotlinx.coroutines.flow.Flow

interface LanTransport {
    suspend fun startListening(port: Int = 45454)
    suspend fun stopListening()
    suspend fun startWiFiDirectDiscovery()
    suspend fun stopWiFiDirectDiscovery()
    suspend fun startMdnsDiscovery()
    suspend fun stopMdnsDiscovery()
    suspend fun connectDirect(ipAddress: String, port: Int): Boolean
    suspend fun sendData(data: ByteArray)
    fun incomingData(): Flow<ByteArray>
    fun discoveredPeers(): Flow<List<LanPeer>>
    fun connectionState(): Flow<LanConnectionState>
    fun getLocalAddresses(): List<String>
}

data class LanPeer(
    val identity: PeerIdentity? = null,
    val ipAddress: String,
    val port: Int = 45454,
    val displayName: String = "Unknown",
    val discoveryMethod: String = "wifi_direct",
    val deviceId: String = ""
)

enum class LanConnectionState { DISCONNECTED, LISTENING, DISCOVERING, CONNECTING, CONNECTED }
