package com.zerochat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Peer(
    val id: Long = 0,
    val identity: PeerIdentity,
    val lastKnownIp: String? = null,
    val lastKnownPort: Int = DEFAULT_PORT,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val discoveryMethod: DiscoveryMethod = DiscoveryMethod.MANUAL,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
) {
    companion object { const val DEFAULT_PORT = 45454 }
}

enum class DiscoveryMethod { MANUAL, MDNS, WIFI_DIRECT, DHT, }
enum class ConnectionStatus { CONNECTED, CONNECTING, DISCONNECTED }
