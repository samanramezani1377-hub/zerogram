package com.zerochat.network.signaling

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebSocket signaling client for ZeroGram.
 *
 * Connects to the ZeroGram Signaling Server to exchange WebRTC
 * SDP offers/answers and ICE candidates via an 8-digit PIN.
 *
 * Protocol: JSON over WebSocket. Each 8-digit PIN is a "room"
 * where exactly 2 peers can meet and establish a WebRTC connection.
 */
@Singleton
class SignalingClient @Inject constructor() {

    companion object {
        // Default server URL — change this to your deployed server
                // Change this to your deployed signaling server URL
        // For local dev: "ws://10.0.2.2:8080" (Android emulator)
        // For production: "wss://zerogram-signaling.fly.dev"
        const val DEFAULT_SIGNALING_URL = "wss://zerogram.onrender.com"
        private const val PING_INTERVAL_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 3_000L
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // ── State ───────────────────────────────────────────────────────

    private val _events = Channel<SignalingEvent>(Channel.BUFFERED)
    val events: Flow<SignalingEvent> = _events.receiveAsFlow()

    @Volatile var isConnected = false
        private set
    @Volatile var currentPin: String? = null
        private set
    @Volatile var peerCount: Int = 0
        private set

    private var reconnectJob: Job? = null

    // ── Lifecycle ───────────────────────────────────────────────────

    /**
     * Connect to signaling server and join a PIN room.
     *
     * @param pin 8-digit room PIN
     * @param serverUrl WebSocket URL of signaling server
     */
    fun connect(pin: String, serverUrl: String = DEFAULT_SIGNALING_URL) {
        if (!pin.matches(Regex("^\\d{8}$"))) {
            Timber.w("Invalid PIN: $pin (must be 8 digits)")
            return
        }

        // Close existing connection
        disconnect()

        currentPin = pin

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.i("Signaling connected to $serverUrl")
                isConnected = true
                _events.trySend(SignalingEvent.Connected)

                // Join room
                val joinMsg = JSONObject().apply {
                    put("type", "join")
                    put("pin", pin)
                }
                webSocket.send(joinMsg.toString())
                Timber.i("Joining room: $pin")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    handleMessage(msg)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse signaling message: $text")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Signaling closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("Signaling closed: $code $reason")
                isConnected = false
                _events.trySend(SignalingEvent.Disconnected)
                scheduleReconnect(pin, serverUrl)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Signaling failure")
                isConnected = false
                _events.trySend(SignalingEvent.Disconnected)
                scheduleReconnect(pin, serverUrl)
            }
        })
    }

    fun disconnect() {
        reconnectJob?.cancel()
        currentPin = null
        peerCount = 0
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
    }

    // ── Send ────────────────────────────────────────────────────────

    /**
     * Send WebRTC SDP (offer or answer) to the peer in the room.
     */
    fun sendSdp(sdp: String, sdpType: String) {
        sendMessage(JSONObject().apply {
            put("type", "sdp")
            put("pin", currentPin ?: return)
            put("sdp", sdp)
            put("sdpType", sdpType)
        })
    }

    /**
     * Send ICE candidate to the peer in the room.
     */
    fun sendIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        sendMessage(JSONObject().apply {
            put("type", "ice")
            put("pin", currentPin ?: return)
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        })
    }

    private fun sendMessage(json: JSONObject) {
        if (!isConnected) {
            Timber.w("Cannot send — not connected")
            return
        }
        webSocket?.send(json.toString())
    }

    // ── Private ─────────────────────────────────────────────────────

    private fun handleMessage(msg: JSONObject) {
        val type = msg.optString("type")
        Timber.d("← Signaling: $type")

        when (type) {
            "joined" -> {
                val pin = msg.optString("pin")
                val position = msg.optInt("position")
                Timber.i("Joined room $pin (position $position)")
                _events.trySend(SignalingEvent.Joined(pin, position))
            }

            "peer_joined" -> {
                peerCount = 2
                Timber.i("Peer joined room!")
                _events.trySend(SignalingEvent.PeerJoined)
            }

            "peer_left" -> {
                peerCount = 1
                Timber.i("Peer left room")
                _events.trySend(SignalingEvent.PeerLeft)
            }

            "sdp" -> {
                val sdp = msg.optString("sdp")
                val sdpType = msg.optString("sdpType")
                Timber.i("Received $sdpType from peer")
                _events.trySend(SignalingEvent.SdpReceived(sdp, sdpType))
            }

            "ice" -> {
                val candidate = msg.optString("candidate")
                val sdpMid = msg.optString("sdpMid")
                val sdpMLineIndex = msg.optInt("sdpMLineIndex")
                _events.trySend(
                    SignalingEvent.IceReceived(candidate, sdpMid, sdpMLineIndex)
                )
            }

            "error" -> {
                val message = msg.optString("message", "Unknown error")
                Timber.w("Signaling error: $message")
                _events.trySend(SignalingEvent.Error(message))
            }

            "pong" -> { /* heartbeat — ok */ }
        }
    }

    private fun scheduleReconnect(pin: String, serverUrl: String) {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(RECONNECT_DELAY_MS)
            if (!isConnected && currentPin == pin) {
                Timber.i("Reconnecting...")
                connect(pin, serverUrl)
            }
        }
    }
}

/**
 * Events emitted by the signaling client.
 */
sealed class SignalingEvent {
    data object Connected : SignalingEvent()
    data object Disconnected : SignalingEvent()
    data class Joined(val pin: String, val position: Int) : SignalingEvent()
    data object PeerJoined : SignalingEvent()
    data object PeerLeft : SignalingEvent()
    data class SdpReceived(val sdp: String, val sdpType: String) : SignalingEvent()
    data class IceReceived(
        val candidate: String, val sdpMid: String, val sdpMLineIndex: Int,
    ) : SignalingEvent()
    data class Error(val message: String) : SignalingEvent()
}
