package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import kotlinx.coroutines.flow.Flow

interface TransportRouter {
    suspend fun start()
    suspend fun stop()
    suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray)
    fun incomingMessages(): Flow<IncomingTransportMessage>
    fun currentMode(peerFingerprint: String): TransportMode
    suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String)
    suspend fun connectWan(peerFingerprint: String): WanConnectionOffer
    suspend fun acceptWanConnection(peerFingerprint: String, offerSdp: String): String
    suspend fun completeWanConnection(peerFingerprint: String, answerSdp: String)
    fun discoveredPeers(): Flow<List<DiscoveredPeer>>
}

data class IncomingTransportMessage(val peerFingerprint: String, val payload: ByteArray, val transportMode: TransportMode)
data class DiscoveredPeer(val ipAddress: String, val port: Int, val displayName: String, val discoveryMethod: String, val transportMode: TransportMode = TransportMode.LAN)
data class WanConnectionOffer(val peerFingerprint: String, val offerSdp: String)
