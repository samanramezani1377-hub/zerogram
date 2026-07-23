package com.zerochat.network.wan

import kotlinx.coroutines.flow.Flow

interface WanTransport {
    fun configureIceServers(servers: List<IceServer>)
    suspend fun createOffer(): String
    suspend fun createAnswer(offerSdp: String): String
    suspend fun setRemoteAnswer(answerSdp: String)
    suspend fun setRemoteOffer(offerSdp: String)
    suspend fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
    fun localIceCandidates(): Flow<IceCandidate>
    suspend fun sendData(data: ByteArray)
    fun incomingData(): Flow<ByteArray>
    fun connectionState(): Flow<WebRtcConnectionState>
    suspend fun close()
}

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
data class IceCandidate(val sdp: String, val sdpMid: String, val sdpMLineIndex: Int)
enum class WebRtcConnectionState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED }

object DefaultIceServers {
    // STUN servers — help discover public IP / behind NAT
    val STUN_GOOGLE = IceServer(urls = listOf("stun:stun.l.google.com:19302"))
    val STUN_GOOGLE2 = IceServer(urls = listOf("stun:stun1.google.com:19302"))
    val STUN_CLOUD = IceServer(urls = listOf("stun:stun.cloudflare.com:3478"))

    // Free TURN servers — required for connections through symmetric NATs
    // These are from the Open Relay project (https://www.metered.ca/tools/openrelay/)
    // They have bandwidth limits; for production, deploy your own TURN server.
    val TURN_OPENRELAY = IceServer(
        urls = listOf(
            "turn:openrelay.metered.ca:443",
            "turn:openrelay.metered.ca:443?transport=tcp",
        ),
        username = "openrelayproject",
        credential = "openrelayproject",
    )
    val TURN_OPENRELAY2 = IceServer(
        urls = listOf(
            "turn:openrelay.metered.ca:80",
            "turn:openrelay.metered.ca:80?transport=tcp",
        ),
        username = "openrelayproject",
        credential = "openrelayproject",
    )

    /** LAN-only: just STUN (peer discovery only, works on same network) */
    val LAN_ONLY = listOf(STUN_GOOGLE, STUN_CLOUD)

    /** Full internet: STUN + TURN (works through any NAT/firewall) */
    val ALL = listOf(STUN_GOOGLE, STUN_GOOGLE2, STUN_CLOUD, TURN_OPENRELAY, TURN_OPENRELAY2)
}
