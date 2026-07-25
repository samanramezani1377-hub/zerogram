package com.zerochat.network.wan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC-based WAN transport implementation.
 *
 * Uses Stream WebRTC (stream-webrtc-android) for peer-to-peer connections
 * over the internet with STUN/TURN for NAT traversal.
 *
 * Architecture:
 * - Each peer connection is identified by the peer's fingerprint.
 * - DataChannel is used for message transfer (reliable, ordered).
 * - ICE candidates are exchanged out-of-band (QR code, manual entry, etc.).
 * - All WebRTC operations run on the main thread (required by the library),
 *   but data transfer uses Dispatchers.IO.
 *
 * Design notes (inspired by Signal's WebRTC usage):
 * - Connection lifecycle is managed per-peer.
 * - Failed connections are cleaned up automatically.
 * - DataChannel sends are non-blocking on the calling thread.
 */
@Singleton
class WebRtcTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : WanTransport {

    // ── Peer connections ────────────────────────────────────────────

    private data class PeerConnectionState(
        val fingerprint: String,
        val factory: org.webrtc.PeerConnectionFactory,
        val peerConnection: org.webrtc.PeerConnection,
        var dataChannel: org.webrtc.DataChannel? = null,
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    )

    private val peers = ConcurrentHashMap<String, PeerConnectionState>()

    // ── ICE candidate channels ──────────────────────────────────────

    private val _localIceCandidates = Channel<IceCandidate>(Channel.BUFFERED)
    override fun localIceCandidates(): Flow<IceCandidate> = _localIceCandidates.receiveAsFlow()

    // ── Incoming data ───────────────────────────────────────────────

    private val _incomingData = Channel<ByteArray>(Channel.BUFFERED)
    override fun incomingData(): Flow<ByteArray> = _incomingData.receiveAsFlow()

    // ── Connection state ────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(WebRtcConnectionState.NEW)
    override fun connectionState(): Flow<WebRtcConnectionState> = _connectionState.asStateFlow()

    // ── ICE servers ─────────────────────────────────────────────────

    private var iceServers: List<IceServer> = emptyList()

    override fun configureIceServers(servers: List<IceServer>) {
        iceServers = servers
        Timber.i("ICE servers configured: ${servers.size} server(s)")
    }

    // ── Offer / Answer (signaling) ──────────────────────────────────

    override suspend fun createOffer(): String {
        ensureInitialized()

        return withContext(Dispatchers.Main) {
            val factory = createPeerConnectionFactory()
            val peerConnection = createPeerConnection(factory)

            val dataChannel = peerConnection.createDataChannel(
                "zerochat",
                org.webrtc.DataChannel.Init().apply {
                    ordered = true
                }
            )
            setupDataChannelObserver(dataChannel, "offer_peer")

            val state = PeerConnectionState(
                fingerprint = "offer_peer",
                factory = factory,
                peerConnection = peerConnection,
                dataChannel = dataChannel,
            )
            peers["offer_peer"] = state

            // Create offer
            val sdpPromise = CompletableDeferred<String>()
            peerConnection.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sessionDescription: org.webrtc.SessionDescription?) {
                    peerConnection.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            sdpPromise.complete(sessionDescription?.description ?: "")
                        }
                        override fun onCreateFailure(p0: String?) {
                            sdpPromise.completeExceptionally(RuntimeException("setLocalDescription failed: $p0"))
                        }
                        override fun onSetFailure(p0: String?) {
                            sdpPromise.completeExceptionally(RuntimeException("setLocalDescription failed: $p0"))
                        }
                    }, sessionDescription)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(reason: String?) {
                    sdpPromise.completeExceptionally(RuntimeException("createOffer failed: $reason"))
                }
                override fun onSetFailure(p0: String?) {}
            }, org.webrtc.MediaConstraints())

            Timber.d("WebRTC offer created")
            sdpPromise.await()
        }
    }

    override suspend fun createAnswer(offerSdp: String): String {
        ensureInitialized()

        return withContext(Dispatchers.Main) {
            val factory = createPeerConnectionFactory()
            val peerConnection = createPeerConnection(factory)

            setupPeerConnectionObserver(peerConnection, "answer_peer")

            val state = PeerConnectionState(
                fingerprint = "answer_peer",
                factory = factory,
                peerConnection = peerConnection,
            )
            peers["answer_peer"] = state

            // Set remote offer
            val remoteDesc = org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offerSdp
            )
            peerConnection.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                override fun onSetSuccess() {
                    Timber.d("Remote offer set, creating answer...")
                }
                override fun onCreateFailure(p0: String?) {
                    Timber.e("setRemoteDescription failed: $p0")
                }
                override fun onSetFailure(p0: String?) {
                    Timber.e("setRemoteDescription failed: $p0")
                }
            }, remoteDesc)

            // Create answer
            val answerPromise = CompletableDeferred<String>()
            peerConnection.createAnswer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sessionDescription: org.webrtc.SessionDescription?) {
                    peerConnection.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            answerPromise.complete(sessionDescription?.description ?: "")
                        }
                        override fun onCreateFailure(p0: String?) {
                            answerPromise.completeExceptionally(RuntimeException(p0))
                        }
                        override fun onSetFailure(p0: String?) {
                            answerPromise.completeExceptionally(RuntimeException(p0))
                        }
                    }, sessionDescription)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(reason: String?) {
                    answerPromise.completeExceptionally(RuntimeException(reason))
                }
                override fun onSetFailure(p0: String?) {}
            }, org.webrtc.MediaConstraints())

            Timber.d("WebRTC answer created")
            answerPromise.await()
        }
    }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        withContext(Dispatchers.Main) {
            val state = peers["offer_peer"]
                ?: throw IllegalStateException("No offer peer found. Call createOffer() first.")

            val remoteDesc = org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.ANSWER,
                answerSdp
            )
            val promise = CompletableDeferred<Unit>()
            state.peerConnection.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                override fun onSetSuccess() { promise.complete(Unit) }
                override fun onCreateFailure(p0: String?) {
                    promise.completeExceptionally(RuntimeException(p0))
                }
                override fun onSetFailure(p0: String?) {
                    promise.completeExceptionally(RuntimeException(p0))
                }
            }, remoteDesc)
            promise.await()
            Timber.d("WebRTC remote answer set")
        }
    }

    override suspend fun setRemoteOffer(offerSdp: String) {
        // Used by the answering peer — already handled in createAnswer
        Timber.d("setRemoteOffer called — use createAnswer() instead")
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        withContext(Dispatchers.Main) {
            val state = peers.values.firstOrNull()
                ?: return@withContext

            val iceCandidate = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
            state.peerConnection.addIceCandidate(iceCandidate)
        }
    }

    // ── Data transfer ───────────────────────────────────────────────

    override suspend fun sendData(data: ByteArray) {
        val state = peers.values.firstOrNull { it.dataChannel != null }
            ?: throw IllegalStateException("No active WebRTC data channel. Create a connection first.")

        val dataChannel = state.dataChannel!!
        if (dataChannel.state() != org.webrtc.DataChannel.State.OPEN) {
            throw IllegalStateException("DataChannel is not open (state: ${dataChannel.state()})")
        }

        val buffer = ByteBuffer.wrap(data)
        val success = dataChannel.send(org.webrtc.DataChannel.Buffer(buffer, true))
        if (!success) {
            throw RuntimeException("DataChannel.send() returned false — buffer full")
        }
        Timber.d("Sent ${data.size} bytes via WebRTC to ${state.fingerprint}")
    }

    override suspend fun close() {
        peers.values.forEach { state ->
            runCatching {
                state.dataChannel?.close()
                state.peerConnection.close()
                state.factory.dispose()
                state.scope.cancel()
            }
        }
        peers.clear()
        _connectionState.value = WebRtcConnectionState.CLOSED
        Timber.i("WebRTC transport closed")
    }

    // ── Private helpers ─────────────────────────────────────────────

    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        org.webrtc.PeerConnectionFactory.initialize(
            org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )
        initialized = true
    }

    private fun createPeerConnectionFactory(): org.webrtc.PeerConnectionFactory {
        val options = org.webrtc.PeerConnectionFactory.Options()
        return org.webrtc.PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    private fun createPeerConnection(factory: org.webrtc.PeerConnectionFactory): org.webrtc.PeerConnection {
        val rtcConfig = org.webrtc.PeerConnection.RTCConfiguration(
            iceServers.map { server ->
                org.webrtc.PeerConnection.IceServer.builder(server.urls)
                    .apply {
                        if (server.username != null) setUsername(server.username)
                        if (server.credential != null) setPassword(server.credential)
                    }
                    .createIceServer()
            }
        ).apply {
            continualGatheringPolicy = org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return factory.createPeerConnection(rtcConfig, object : org.webrtc.PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                candidate?.let {
                    _localIceCandidates.trySend(IceCandidate(
                        sdp = it.sdp,
                        sdpMid = it.sdpMid,
                        sdpMLineIndex = it.sdpMLineIndex,
                    ))
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
            override fun onSignalingChange(p0: org.webrtc.PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState?) {
                _connectionState.value = when (state) {
                    org.webrtc.PeerConnection.IceConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                    org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                    org.webrtc.PeerConnection.IceConnectionState.FAILED -> WebRtcConnectionState.FAILED
                    org.webrtc.PeerConnection.IceConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                    else -> WebRtcConnectionState.CONNECTING
                }
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: org.webrtc.PeerConnection.IceGatheringState?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                channel?.let { setupDataChannelObserver(it, "remote_peer") }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
            override fun onIceCandidateError(p0: org.webrtc.PeerConnection.IceCandidateErrorEvent?) {
                Timber.w("ICE candidate error: ${p0?.errorText}")
            }
        }) ?: throw RuntimeException("Failed to create PeerConnection")
    }

    private fun setupDataChannelObserver(channel: org.webrtc.DataChannel, peerLabel: String) {
        channel.registerObserver(object : org.webrtc.DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Timber.d("DataChannel state for $peerLabel: ${channel.state()}")
                if (channel.state() == org.webrtc.DataChannel.State.OPEN) {
                    _connectionState.value = WebRtcConnectionState.CONNECTED
                }
            }

            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _incomingData.trySend(bytes)
                Timber.d("WebRTC received ${bytes.size} bytes from $peerLabel")
            }
        })
    }

    private fun setupPeerConnectionObserver(
        peerConnection: org.webrtc.PeerConnection,
        peerLabel: String,
    ) {
        // Observer is set up in createPeerConnection() — this is a no-op
        // for the answer path since we use the same Observer implementation.
    }
}
