package com.zerochat.network.wan

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.webrtc.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of WanTransport using Google's WebRTC library.
 *
 * Connection flow:
 *  1. Both peers create PeerConnection with STUN/TURN config
 *  2. Initiator creates Offer → sends to responder
 *  3. Responder sets Remote Offer → creates Answer → sends back
 *  4. Initiator sets Remote Answer
 *  5. ICE candidates exchanged via side channel
 *  6. DataChannel opens → encrypted messaging begins
 */
@Singleton
class WebRtcTransport @Inject constructor(
    private val context: Context,
) : WanTransport {

    // ── WebRTC core ──
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // ── ICE config ──
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    // ── State ──
    private val _connectionState = MutableStateFlow(WebRtcConnectionState.NEW)
    override fun connectionState(): StateFlow<WebRtcConnectionState> = _connectionState

    private val _incomingData = Channel<ByteArray>(Channel.BUFFERED)
    override fun incomingData(): Flow<ByteArray> = _incomingData.receiveAsFlow()

    private val _localIceCandidates = Channel<IceCandidate>(Channel.BUFFERED)
    override fun localIceCandidates(): Flow<IceCandidate> = _localIceCandidates.receiveAsFlow()

    // ── SDP exchange coroutines ──
    private var pendingOffer: CompletableDeferred<String>? = null
    private var pendingAnswer: CompletableDeferred<String>? = null
    private var answerReceived: CompletableDeferred<Unit>? = null

    // ── EGL context (needed for Android WebRTC) ──
    private var eglBase: EglBase? = null

    private var initialized = false

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════

    private fun ensureInitialized() {
        if (initialized) return

        // Initialize WebRTC
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Create EGL context
        eglBase = EglBase.create()

        // Create factory
        val factoryOptions = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(factoryOptions)
            .createPeerConnectionFactory()

        initialized = true
        Timber.i("WebRTC initialized")
    }

    override fun configureIceServers(servers: List<IceServer>) {
        iceServers = servers.map { server ->
            PeerConnection.IceServer.builder(server.urls)
                .apply {
                    if (server.username != null && server.credential != null) {
                        setUsername(server.username)
                        setPassword(server.credential)
                    }
                }
                .createIceServer()
        }
    }

    override suspend fun createOffer(): String {
        ensureInitialized()
        createPeerConnectionInternal()

        val deferred = CompletableDeferred<String>()
        pendingOffer = deferred

        dataChannel = peerConnection?.createDataChannel("zerochat", DataChannel.Init().apply {
            ordered = true
        })
        dataChannel?.registerObserver(createDataChannelObserver())

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sessionDescription)
                deferred.complete(sessionDescription.description)
            }

            override fun onCreateFailure(reason: String) {
                deferred.completeExceptionally(RuntimeException("Failed to create offer: $reason"))
            }
        }, MediaConstraints())

        return deferred.await()
    }

    override suspend fun createAnswer(offerSdp: String): String {
        ensureInitialized()
        createPeerConnectionInternal()

        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred

        // Set remote offer
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), remoteSdp)

        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sessionDescription)
                deferred.complete(sessionDescription.description)
            }

            override fun onCreateFailure(reason: String) {
                deferred.completeExceptionally(RuntimeException("Failed to create answer: $reason"))
            }
        }, MediaConstraints())

        return deferred.await()
    }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        val deferred = CompletableDeferred<Unit>()
        answerReceived = deferred

        val remoteSdp = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onSetFailure(reason: String) {
                deferred.completeExceptionally(RuntimeException("Failed to set remote answer: $reason"))
            }
        }, remoteSdp)

        deferred.await()
    }

    override suspend fun setRemoteOffer(offerSdp: String) {
        val remoteSdp = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

        withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Unit> { cont ->
                peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {

                    override fun onSetSuccess() {
                        cont.resume(Unit) { }
                    }

                    override fun onSetFailure(reason: String) {
                        cont.resumeWith(
                            Result.failure(RuntimeException("Failed: $reason"))
                        )
                    }

                }, remoteSdp)

                cont.invokeOnCancellation { }
            }
        }
    }
    override suspend fun addIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    override suspend fun sendData(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        val bufferInfo = DataChannel.Buffer(buffer, false)
        if (dataChannel?.state() == DataChannel.State.OPEN) {
            dataChannel?.send(bufferInfo)
        } else {
            Timber.w("DataChannel not open, cannot send")
        }
    }

    override suspend fun close() {
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
        _connectionState.value = WebRtcConnectionState.CLOSED
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal
    // ═══════════════════════════════════════════════════════════════

    private fun createPeerConnectionInternal() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Enable continual gathering of ICE candidates
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            createPeerConnectionObserver()
        )
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                if (candidate != null) {
                    _localIceCandidates.trySend(
                        IceCandidate(
                            sdp = candidate.sdp,
                            sdpMid = candidate.sdpMid,
                            sdpMLineIndex = candidate.sdpMLineIndex,
                        )
                    )
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) {}

            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Timber.d("Signaling state: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Timber.d("ICE connection state: $state")
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED ->
                        _connectionState.value = WebRtcConnectionState.CONNECTED
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        _connectionState.value = WebRtcConnectionState.DISCONNECTED
                    PeerConnection.IceConnectionState.FAILED ->
                        _connectionState.value = WebRtcConnectionState.FAILED
                    PeerConnection.IceConnectionState.CLOSED ->
                        _connectionState.value = WebRtcConnectionState.CLOSED
                    else -> {}
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Timber.d("ICE gathering state: $state")
            }

            override fun onAddStream(stream: MediaStream?) {}

            override fun onRemoveStream(stream: MediaStream?) {}

            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                channel.registerObserver(createDataChannelObserver())
            }

            override fun onRenegotiationNeeded() {
                Timber.d("Renegotiation needed")
            }

            override fun onAddTrack(
                receiver: RtpReceiver?,
                streams: Array<out MediaStream>?
            ) {}
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}

            override fun onStateChange() {
                Timber.d("DataChannel state: ${dataChannel?.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _incomingData.trySend(bytes)
            }
        }
    }
}

/**
 * Minimal no-op SDP observer.
 */
private open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(reason: String) {}
    override fun onSetFailure(reason: String) {}
}
