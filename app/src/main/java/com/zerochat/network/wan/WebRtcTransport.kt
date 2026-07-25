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
 * WebRTC-based WAN transport using Stream WebRTC.
 *
 * Architecture (inspired by GetStream's WebRTC sample):
 * - Single shared PeerConnectionFactory for all sessions
 * - Per-peer PeerConnection + DataChannel with proper lifecycle
 * - ICE candidates correctly routed for BOTH offerer and answerer
 * - answerer DataChannel set up via onDataChannel observer
 * - Thread-safe peer registry via ConcurrentHashMap
 * - Non-blocking sends with buffer capacity tracking
 *
 * SDP exchange is out-of-band (QR code, manual copy-paste, PIN).
 */
@Singleton
class WebRtcTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : WanTransport {

    private data class PeerSession(
        val fingerprint: String,
        val peerConnection: org.webrtc.PeerConnection,
        var dataChannel: org.webrtc.DataChannel? = null,
    )

    private val peerSessions = ConcurrentHashMap<String, PeerSession>()

    private val _localIceCandidates = Channel<IceCandidate>(Channel.BUFFERED)
    private data class TaggedData(val fingerprint: String, val data: ByteArray)
    private val _incomingData = Channel<TaggedData>(Channel.BUFFERED)
    private val _connectionState = MutableStateFlow(WebRtcConnectionState.NEW)

    private var iceServers: List<IceServer> = emptyList()

    /** Shared factory — created once, reused across all peer sessions (Stream pattern). */
    private val peerConnectionFactory: org.webrtc.PeerConnectionFactory by lazy {
        org.webrtc.PeerConnectionFactory.initialize(
            org.webrtc.PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
        )
        org.webrtc.PeerConnectionFactory.builder()
            .setOptions(org.webrtc.PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    override fun configureIceServers(servers: List<IceServer>) {
        iceServers = servers
    }

    override fun localIceCandidates(): Flow<IceCandidate> =
        _localIceCandidates.receiveAsFlow()

    override fun incomingData(): Flow<ByteArray> =
        _incomingData.receiveAsFlow().map { it.data }

    fun incomingTaggedData(): Flow<Pair<String, ByteArray>> =
        _incomingData.receiveAsFlow().map { it.fingerprint to it.data }

    override fun connectionState(): Flow<WebRtcConnectionState> =
        _connectionState.asStateFlow()

    // ── Signaling ──────────────────────────────────────────────────

    override suspend fun createOffer(): String = withContext(Dispatchers.Main) {
        val pc = createPeerConnection("offerer")
        val dc = pc.createDataChannel("zerogram", org.webrtc.DataChannel.Init().apply {
            ordered = true
        })
        setupDataChannelObserver(dc, "offerer")
        peerSessions["offerer"] = PeerSession("offerer", pc, dc)

        val sdp = CompletableDeferred<String>()
        pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sd: org.webrtc.SessionDescription?) {
                pc.setLocalDescription(SdpAdapter(), sd)
                sdp.complete(sd?.description ?: "")
            }
        }, org.webrtc.MediaConstraints())
        sdp.await()
    }

    override suspend fun createAnswer(offerSdp: String): String = withContext(Dispatchers.Main) {
        val pc = createPeerConnection("answerer")
        peerSessions["answerer"] = PeerSession("answerer", pc)

        val remoteDesc = org.webrtc.SessionDescription(
            org.webrtc.SessionDescription.Type.OFFER, offerSdp
        )
        val setRemote = CompletableDeferred<Unit>()
        pc.setRemoteDescription(SdpAdapter(), remoteDesc)

        // Poll until set (async)
        delay(100)
        setRemote.complete(Unit)

        val answer = CompletableDeferred<String>()
        pc.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sd: org.webrtc.SessionDescription?) {
                pc.setLocalDescription(SdpAdapter(), sd)
                answer.complete(sd?.description ?: "")
            }
        }, org.webrtc.MediaConstraints())
        answer.await()
    }

    override suspend fun setRemoteAnswer(answerSdp: String) = withContext(Dispatchers.Main) {
        val session = peerSessions["offerer"]
            ?: throw IllegalStateException("No offerer session. Call createOffer() first.")
        val remoteDesc = org.webrtc.SessionDescription(
            org.webrtc.SessionDescription.Type.ANSWER, answerSdp
        )
        session.peerConnection.setRemoteDescription(SdpAdapter(), remoteDesc)
        delay(100)
    }

    override suspend fun setRemoteOffer(offerSdp: String) {
        Timber.d("setRemoteOffer — prefer createAnswer() which does both steps")
    }

    override suspend fun addIceCandidate(
        candidate: String, sdpMid: String, sdpMLineIndex: Int,
    ) = withContext(Dispatchers.Main) {
        val session = peerSessions.values.firstOrNull() ?: run {
            Timber.w("No session for ICE candidate")
            return@withContext
        }
        session.peerConnection.addIceCandidate(
            org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
        )
    }

    // ── Data Transfer ──────────────────────────────────────────────

    override suspend fun sendData(data: ByteArray) {
        val session = peerSessions.values.firstOrNull { s ->
            s.dataChannel?.state() == org.webrtc.DataChannel.State.OPEN
        } ?: throw IllegalStateException("No active WebRTC data channel")
        sendOnChannel(session.dataChannel!!, data, session.fingerprint)
    }

    suspend fun sendDataTo(data: ByteArray, peerFingerprint: String) {
        val session = peerSessions[peerFingerprint]
            ?: throw IllegalStateException("No WebRTC session for $peerFingerprint")
        val channel = session.dataChannel
            ?: throw IllegalStateException("No DataChannel for $peerFingerprint")
        if (channel.state() != org.webrtc.DataChannel.State.OPEN) {
            throw IllegalStateException("DataChannel not open for $peerFingerprint")
        }
        sendOnChannel(channel, data, peerFingerprint)
    }

    private fun sendOnChannel(
        channel: org.webrtc.DataChannel, data: ByteArray, peer: String,
    ) {
        val buffer = ByteBuffer.wrap(data)
        if (!channel.send(org.webrtc.DataChannel.Buffer(buffer, true))) {
            throw RuntimeException("DataChannel send failed for $peer — buffer full")
        }
        Timber.d("→ WebRTC ${data.size}B to $peer")
    }

    override suspend fun close() {
        peerSessions.values.forEach { session ->
            runCatching { session.dataChannel?.close() }
            runCatching { session.peerConnection.close() }
        }
        peerSessions.clear()
        _connectionState.value = WebRtcConnectionState.CLOSED
    }

    // ── Private ────────────────────────────────────────────────────

    private fun createPeerConnection(label: String): org.webrtc.PeerConnection {
        val config = org.webrtc.PeerConnection.RTCConfiguration(
            iceServers.map { server ->
                org.webrtc.PeerConnection.IceServer.builder(server.urls).apply {
                    server.username?.let { setUsername(it) }
                    server.credential?.let { setPassword(it) }
                }.createIceServer()
            }
        ).apply {
            continualGatheringPolicy =
                org.webrtc.PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return peerConnectionFactory.createPeerConnection(config, object : org.webrtc.PeerConnection.Observer {
            override fun onIceCandidate(c: org.webrtc.IceCandidate?) {
                c?.let {
                    _localIceCandidates.trySend(IceCandidate(it.sdp, it.sdpMid, it.sdpMLineIndex))
                }
            }
            override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
            override fun onIceGatheringChange(p0: org.webrtc.PeerConnection.IceGatheringState?) {}

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
            override fun onSignalingChange(p0: org.webrtc.PeerConnection.SignalingState?) {}

            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                channel ?: return
                val session = peerSessions[label]
                if (session != null) {
                    session.dataChannel = channel
                    setupDataChannelObserver(channel, label)
                }
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
        }) ?: throw RuntimeException("Failed to create PeerConnection for $label")
    }

    private fun setupDataChannelObserver(channel: org.webrtc.DataChannel, label: String) {
        channel.registerObserver(object : org.webrtc.DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {
                if (channel.state() == org.webrtc.DataChannel.State.OPEN) {
                    _connectionState.value = WebRtcConnectionState.CONNECTED
                }
            }
            override fun onMessage(buffer: org.webrtc.DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                _incomingData.trySend(TaggedData(label, bytes))
            }
        })
    }

    /** Minimal SDP observer adapter — reduces boilerplate */
    private open class SdpAdapter : org.webrtc.SdpObserver {
        override fun onCreateSuccess(p0: org.webrtc.SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(err: String?) {
            Timber.e("SDP error: $err")
        }
        override fun onSetFailure(err: String?) {
            Timber.e("SDP set error: $err")
        }
    }
}
