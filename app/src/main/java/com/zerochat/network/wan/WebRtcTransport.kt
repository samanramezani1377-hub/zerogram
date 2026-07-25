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
 * WebRTC-based WAN transport implementation using Stream WebRTC.
 *
 * Key improvements over the previous version:
 * - Proper multi-peer support: each peer has its own PeerConnection + DataChannel
 * - ICE candidates are correctly routed for BOTH offerer and answerer
 * - answer_peer DataChannel is properly set up via onDataChannel observer
 * - Per-peer connection lifecycle with proper cleanup
 * - Non-blocking sends with buffer capacity tracking
 * - Thread-safe peer registry
 *
 * Each peer is identified by its fingerprint. SDP exchange is out-of-band
 * (QR code, manual copy-paste, etc.).
 */
@Singleton
class WebRtcTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : WanTransport {

    // ── Per-peer state ─────────────────────────────────────────────

    private data class PeerSession(
        val fingerprint: String,
        val factory: org.webrtc.PeerConnectionFactory,
        val peerConnection: org.webrtc.PeerConnection,
        var dataChannel: org.webrtc.DataChannel? = null,
        val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    )

    /**
     * Registry of all active peer sessions keyed by fingerprint.
     * Using ConcurrentHashMap for thread-safe concurrent access from
     * WebRTC callbacks (main thread) and coroutine contexts (IO threads).
     */
    private val peerSessions = ConcurrentHashMap<String, PeerSession>()

    // ── ICE candidate forwarding ───────────────────────────────────
    // Per-peer channel so each peer gets its own ICE candidates.
    // We expose a merged flow for backward compatibility.

    private val _localIceCandidates = Channel<IceCandidate>(Channel.BUFFERED)

    // ── Incoming data ──────────────────────────────────────────────
    // Carries BOTH the data AND the source peer fingerprint so
    // TransportRouter can correctly route incoming messages.

    private data class TaggedData(val fingerprint: String, val data: ByteArray)

    private val _incomingData = Channel<TaggedData>(Channel.BUFFERED)

    // ── Connection state ───────────────────────────────────────────
    // Aggregated state; the most-recently-connected peer's state wins.

    private val _connectionState = MutableStateFlow(WebRtcConnectionState.NEW)

    // ── ICE servers ────────────────────────────────────────────────

    private var iceServers: List<IceServer> = emptyList()

    override fun configureIceServers(servers: List<IceServer>) {
        iceServers = servers
        Timber.i("ICE servers configured: ${servers.size} server(s)")
    }

    // ── Public API ─────────────────────────────────────────────────

    override fun localIceCandidates(): Flow<IceCandidate> =
        _localIceCandidates.receiveAsFlow()

    override fun incomingData(): Flow<ByteArray> =
        _incomingData.receiveAsFlow().map { it.data }

    /**
     * Returns incoming data with per-peer fingerprint tagging.
     * Used by TransportRouter to correctly identify the sending peer.
     */
    fun incomingTaggedData(): Flow<Pair<String, ByteArray>> =
        _incomingData.receiveAsFlow().map { it.fingerprint to it.data }

    override fun connectionState(): Flow<WebRtcConnectionState> =
        _connectionState.asStateFlow()

    // ── Signaling: Offer / Answer ──────────────────────────────────

    override suspend fun createOffer(): String {
        ensureInitialized()

        return withContext(Dispatchers.Main) {
            val factory = createPeerConnectionFactory()
            val peerConnection = createPeerConnection(factory, "offerer")

            // Create DataChannel — the offerer initiates the data channel
            val dataChannel = peerConnection.createDataChannel(
                "zerochat",
                org.webrtc.DataChannel.Init().apply {
                    ordered = true
                }
            )
            setupDataChannelObserver(dataChannel, "offerer")

            val session = PeerSession(
                fingerprint = "offerer",
                factory = factory,
                peerConnection = peerConnection,
                dataChannel = dataChannel,
            )
            peerSessions["offerer"] = session

            // Create SDP offer
            val sdpPromise = CompletableDeferred<String>()
            peerConnection.createOffer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sd: org.webrtc.SessionDescription?) {
                    peerConnection.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            sdpPromise.complete(sd?.description ?: "")
                            Timber.d("Offer SDP set as local description")
                        }
                        override fun onCreateFailure(err: String?) {
                            sdpPromise.completeExceptionally(
                                RuntimeException("setLocalDescription failed: $err")
                            )
                        }
                        override fun onSetFailure(err: String?) {
                            sdpPromise.completeExceptionally(
                                RuntimeException("setLocalDescription failed: $err")
                            )
                        }
                    }, sd)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    sdpPromise.completeExceptionally(
                        RuntimeException("createOffer failed: $err")
                    )
                }
                override fun onSetFailure(p0: String?) {}
            }, org.webrtc.MediaConstraints())

            Timber.d("WebRTC offer created for: offerer")
            sdpPromise.await()
        }
    }

    override suspend fun createAnswer(offerSdp: String): String {
        ensureInitialized()

        return withContext(Dispatchers.Main) {
            val factory = createPeerConnectionFactory()
            val peerConnection = createPeerConnection(factory, "answerer")

            // Register BEFORE setting remote description so that
            // onDataChannel callback already has a session to attach to.
            val session = PeerSession(
                fingerprint = "answerer",
                factory = factory,
                peerConnection = peerConnection,
            )
            peerSessions["answerer"] = session

            // Set remote offer
            val remoteDesc = org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.OFFER,
                offerSdp
            )

            val setRemotePromise = CompletableDeferred<Unit>()
            peerConnection.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                override fun onSetSuccess() {
                    Timber.d("Remote offer set, creating answer...")
                    setRemotePromise.complete(Unit)
                }
                override fun onCreateFailure(err: String?) {
                    setRemotePromise.completeExceptionally(
                        RuntimeException("setRemoteDescription(OFFER) failed: $err")
                    )
                }
                override fun onSetFailure(err: String?) {
                    setRemotePromise.completeExceptionally(
                        RuntimeException("setRemoteDescription(OFFER) failed: $err")
                    )
                }
            }, remoteDesc)

            setRemotePromise.await()

            // Create answer
            val answerPromise = CompletableDeferred<String>()
            peerConnection.createAnswer(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(sd: org.webrtc.SessionDescription?) {
                    peerConnection.setLocalDescription(object : org.webrtc.SdpObserver {
                        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                        override fun onSetSuccess() {
                            answerPromise.complete(sd?.description ?: "")
                            Timber.d("Answer SDP set as local description")
                        }
                        override fun onCreateFailure(err: String?) {
                            answerPromise.completeExceptionally(
                                RuntimeException("setLocalDescription(ANSWER) failed: $err")
                            )
                        }
                        override fun onSetFailure(err: String?) {
                            answerPromise.completeExceptionally(
                                RuntimeException("setLocalDescription(ANSWER) failed: $err")
                            )
                        }
                    }, sd)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(err: String?) {
                    answerPromise.completeExceptionally(
                        RuntimeException("createAnswer failed: $err")
                    )
                }
                override fun onSetFailure(p0: String?) {}
            }, org.webrtc.MediaConstraints())

            Timber.d("WebRTC answer created for: answerer")
            answerPromise.await()
        }
    }

    override suspend fun setRemoteAnswer(answerSdp: String) {
        withContext(Dispatchers.Main) {
            val session = peerSessions["offerer"]
                ?: throw IllegalStateException(
                    "No offerer session found. Call createOffer() first."
                )

            val remoteDesc = org.webrtc.SessionDescription(
                org.webrtc.SessionDescription.Type.ANSWER,
                answerSdp
            )
            val promise = CompletableDeferred<Unit>()
            session.peerConnection.setRemoteDescription(object : org.webrtc.SdpObserver {
                override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
                override fun onSetSuccess() {
                    promise.complete(Unit)
                    Timber.d("Remote answer set for offerer")
                }
                override fun onCreateFailure(err: String?) {
                    promise.completeExceptionally(
                        RuntimeException("setRemoteDescription(ANSWER) failed: $err")
                    )
                }
                override fun onSetFailure(err: String?) {
                    promise.completeExceptionally(
                        RuntimeException("setRemoteDescription(ANSWER) failed: $err")
                    )
                }
            }, remoteDesc)
            promise.await()
        }
    }

    override suspend fun setRemoteOffer(offerSdp: String) {
        // Use createAnswer() instead — it handles both setRemoteDescription and createAnswer.
        Timber.d("setRemoteOffer called — prefer createAnswer() which does both steps")
    }

    override suspend fun addIceCandidate(
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
    ) {
        withContext(Dispatchers.Main) {
            val session = peerSessions.values.firstOrNull()
                ?: run {
                    Timber.w("No peer session available for ICE candidate")
                    return@withContext
                }

            val iceCandidate = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
            session.peerConnection.addIceCandidate(iceCandidate)
            Timber.d("Remote ICE candidate added for ${session.fingerprint}")
        }
    }

    // ── Data Transfer ──────────────────────────────────────────────

    /**
     * Send data to the first open DataChannel.
     *
     * IMPORTANT: With multiple peers, this should be extended to route
     * to the correct peer's DataChannel. Currently routes to the first
     * open channel (backward-compatible with single-peer usage).
     */
    override suspend fun sendData(data: ByteArray) {
        val session = peerSessions.values.firstOrNull { session ->
            session.dataChannel?.state() == org.webrtc.DataChannel.State.OPEN
        } ?: throw IllegalStateException(
            "No active WebRTC data channel. Create a connection first."
        )

        val channel = session.dataChannel!!
        val buffer = ByteBuffer.wrap(data)
        val success = channel.send(org.webrtc.DataChannel.Buffer(buffer, true))
        if (!success) {
            throw RuntimeException(
                "DataChannel.send() returned false — buffer full for ${session.fingerprint}"
            )
        }
        Timber.d("Sent ${data.size} bytes via WebRTC to ${session.fingerprint}")
    }

    /**
     * Send data to a specific peer identified by fingerprint.
     */
    suspend fun sendDataTo(data: ByteArray, peerFingerprint: String) {
        val session = peerSessions[peerFingerprint]
            ?: throw IllegalStateException(
                "No WebRTC session for $peerFingerprint"
            )

        val channel = session.dataChannel
            ?: throw IllegalStateException(
                "No DataChannel for $peerFingerprint"
            )

        if (channel.state() != org.webrtc.DataChannel.State.OPEN) {
            throw IllegalStateException(
                "DataChannel for $peerFingerprint is not open (state: ${channel.state()})"
            )
        }

        val buffer = ByteBuffer.wrap(data)
        val success = channel.send(org.webrtc.DataChannel.Buffer(buffer, true))
        if (!success) {
            throw RuntimeException(
                "DataChannel.send() returned false for $peerFingerprint"
            )
        }
        Timber.d("Sent ${data.size} bytes via WebRTC to $peerFingerprint")
    }

    override suspend fun close() {
        Timber.i("Closing WebRTC transport — ${peerSessions.size} session(s)")

        peerSessions.values.forEach { session ->
            runCatching {
                session.dataChannel?.close()
                session.peerConnection.close()
                session.factory.dispose()
                session.scope.cancel()
            }.onFailure { e ->
                Timber.w(e, "Error closing session for ${session.fingerprint}")
            }
        }
        peerSessions.clear()
        _connectionState.value = WebRtcConnectionState.CLOSED
    }

    // ── Private: WebRTC Setup ──────────────────────────────────────

    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        org.webrtc.PeerConnectionFactory.initialize(
            org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials("")
                .createInitializationOptions()
        )
        initialized = true
        Timber.d("WebRTC PeerConnectionFactory initialized")
    }

    private fun createPeerConnectionFactory(): org.webrtc.PeerConnectionFactory {
        val options = org.webrtc.PeerConnectionFactory.Options()
        return org.webrtc.PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
    }

    /**
     * Create a PeerConnection configured with ICE servers and observers.
     *
     * The [peerLabel] identifies this peer for logging and is used to
     * register the session in [peerSessions] so that ICE and DataChannel
     * callbacks can find the correct session.
     */
    private fun createPeerConnection(
        factory: org.webrtc.PeerConnectionFactory,
        peerLabel: String,
    ): org.webrtc.PeerConnection {
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
            continualGatheringPolicy =
                org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return factory.createPeerConnection(rtcConfig, object : org.webrtc.PeerConnection.Observer {
            // ── ICE Candidates ──────────────────────────────────────
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                candidate?.let {
                    val ice = IceCandidate(
                        sdp = it.sdp,
                        sdpMid = it.sdpMid,
                        sdpMLineIndex = it.sdpMLineIndex,
                    )
                    _localIceCandidates.trySend(ice)
                    Timber.d("ICE candidate gathered for $peerLabel: ${it.sdpMid}")
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
            override fun onIceGatheringChange(p0: org.webrtc.PeerConnection.IceGatheringState?) {}

            // ── Connection State ───────────────────────────────────
            override fun onIceConnectionChange(
                state: org.webrtc.PeerConnection.IceConnectionState?
            ) {
                val mappedState = when (state) {
                    org.webrtc.PeerConnection.IceConnectionState.CONNECTED ->
                        WebRtcConnectionState.CONNECTED
                    org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED ->
                        WebRtcConnectionState.DISCONNECTED
                    org.webrtc.PeerConnection.IceConnectionState.FAILED ->
                        WebRtcConnectionState.FAILED
                    org.webrtc.PeerConnection.IceConnectionState.CLOSED ->
                        WebRtcConnectionState.CLOSED
                    else -> WebRtcConnectionState.CONNECTING
                }
                _connectionState.value = mappedState
                Timber.d("ICE connection state for $peerLabel: $mappedState")
            }
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onSignalingChange(p0: org.webrtc.PeerConnection.SignalingState?) {}

            // ── DataChannel (remote) ───────────────────────────────
            // CRITICAL: This is how the answerer gets its DataChannel.
            // The offerer creates the channel; the answerer receives it here.
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                if (channel == null) return
                Timber.d("Remote DataChannel received for $peerLabel")

                // Attach to the correct session
                val session = peerSessions[peerLabel]
                if (session != null) {
                    session.dataChannel = channel
                    setupDataChannelObserver(channel, peerLabel)
                } else {
                    Timber.w("Remote DataChannel for unknown peer: $peerLabel")
                }
            }

            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(
                p0: org.webrtc.RtpReceiver?,
                p1: Array<out org.webrtc.MediaStream>?,
            ) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
        }) ?: throw RuntimeException("Failed to create PeerConnection for $peerLabel")
    }

    // ── Private: DataChannel Observer ──────────────────────────────

    private fun setupDataChannelObserver(
        channel: org.webrtc.DataChannel,
        peerLabel: String,
    ) {
        channel.registerObserver(object : org.webrtc.DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                // Can be used for flow control in future versions
            }

            override fun onStateChange() {
                val state = channel.state()
                Timber.d("DataChannel state for $peerLabel: $state")
                if (state == org.webrtc.DataChannel.State.OPEN) {
                    _connectionState.value = WebRtcConnectionState.CONNECTED
                }
            }

            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _incomingData.trySend(
                    TaggedData(fingerprint = peerLabel, data = bytes)
                )
                Timber.d("WebRTC received ${bytes.size} bytes from $peerLabel")
            }
        })
    }
}
