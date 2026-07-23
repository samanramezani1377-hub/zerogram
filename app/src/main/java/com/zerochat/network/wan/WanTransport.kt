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
    val STUN_GOOGLE = IceServer(urls = listOf("stun:stun.l.google.com:19302"))
    val STUN_GOOGLE2 = IceServer(urls = listOf("stun:stun1.google.com:19302"))
    val STUN_CLOUD = IceServer(urls = listOf("stun:stun.cloudflare.com:3478"))
    val ALL = listOf(STUN_GOOGLE, STUN_GOOGLE2, STUN_CLOUD)
}
