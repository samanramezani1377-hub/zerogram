package com.zerochat.network.signaling

import com.zerochat.data.model.TransportMode
import com.zerochat.network.transport.TransportRouter
import com.zerochat.network.wan.WebRtcTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full PIN → WebRTC → messaging flow over the Internet.
 *
 * Flow:
 * 1. User enters 8-digit PIN
 * 2. SignalingClient connects to server, joins room
 * 3. When peer joins, create WebRTC offer (if position=1) or wait
 * 4. Exchange SDP + ICE candidates through signaling server
 * 5. Once WebRTC DataChannel is open, route is registered
 * 6. Messages flow P2P over DataChannel
 */
@Singleton
class WanSignalingManager @Inject constructor(
    private val signalingClient: SignalingClient,
    private val transportRouter: TransportRouter,
) {

    companion object {
        private const val SIGNALING_SERVER_URL = "wss://zerogram.onrender.com"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null

    private val _connectState = MutableStateFlow(WanConnectState())
    val connectState: StateFlow<WanConnectState> = _connectState.asStateFlow()

    /**
     * Connect to a peer over the Internet using an 8-digit PIN.
     *
     * Both peers enter the same PIN. The first one to join creates
     * the offer; the second receives it and creates the answer.
     * SDP and ICE candidates are exchanged through the signaling server.
     */
    fun connectWithPin(pin: String) {
        connectJob?.cancel()
        connectJob = scope.launch {
            _connectState.value = WanConnectState(status = WanConnectStatus.CONNECTING)

            // Start listening for signaling events
            val eventJob = launch {
                signalingClient.events.collect { event ->
                    handleEvent(event, pin)
                }
            }

            try {
                // Connect to signaling server
                signalingClient.connect(pin, SIGNALING_SERVER_URL)

                // Wait for peer to join (timeout after 60s)
                withTimeout(60_000) {
                    signalingClient.events.first { it is SignalingEvent.PeerJoined }
                }

                _connectState.value = WanConnectState(status = WanConnectStatus.PEER_FOUND)

                // First peer (lower position) creates offer
                delay(500) // Give both peers time to settle
                val state = _connectState.value
                if (state.myPosition == 1) {
                    createAndSendOffer()
                }
                // Second peer waits for offer

            } catch (e: TimeoutCancellationException) {
                Timber.w("Timeout waiting for peer on PIN $pin")
                _connectState.value = WanConnectState(
                    status = WanConnectStatus.ERROR,
                    error = "No peer found with PIN $pin. Make sure both devices entered the same PIN."
                )
                signalingClient.disconnect()
            } catch (e: Exception) {
                Timber.e(e, "WAN connection failed")
                _connectState.value = WanConnectState(
                    status = WanConnectStatus.ERROR,
                    error = e.message ?: "Connection failed"
                )
                signalingClient.disconnect()
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        signalingClient.disconnect()
        _connectState.value = WanConnectState()
    }

    // ── Private ─────────────────────────────────────────────────────

    private suspend fun handleEvent(event: SignalingEvent, pin: String) {
        when (event) {
            is SignalingEvent.Connected -> {
                Timber.d("Signaling connected")
            }

            is SignalingEvent.Disconnected -> {
                if (_connectState.value.status != WanConnectStatus.CONNECTED) {
                    _connectState.value = WanConnectState(
                        status = WanConnectStatus.ERROR,
                        error = "Lost connection to signaling server"
                    )
                }
            }

            is SignalingEvent.Joined -> {
                _connectState.value = _connectState.value.copy(
                    myPosition = event.position,
                    status = WanConnectStatus.WAITING_FOR_PEER,
                )
            }

            is SignalingEvent.PeerJoined -> {
                _connectState.value = _connectState.value.copy(
                    status = WanConnectStatus.PEER_FOUND,
                )
            }

            is SignalingEvent.PeerLeft -> {
                _connectState.value = WanConnectState(
                    status = WanConnectStatus.ERROR,
                    error = "Peer disconnected"
                )
            }

            is SignalingEvent.SdpReceived -> {
                handleSdp(event.sdp, event.sdpType)
            }

            is SignalingEvent.IceReceived -> {
                transportRouter.addIceCandidate(
                    event.candidate, event.sdpMid, event.sdpMLineIndex
                )
            }

            is SignalingEvent.Error -> {
                _connectState.value = WanConnectState(
                    status = WanConnectStatus.ERROR, error = event.message
                )
            }
        }
    }

    private suspend fun createAndSendOffer() {
        try {
            val offer = transportRouter.connectWan("wan_peer")
            signalingClient.sendSdp(offer.offerSdp, "offer")

            // Also send any ICE candidates that were generated during offer creation
            launch {
                collectAndSendIceCandidates()
            }

            _connectState.value = WanConnectState(
                status = WanConnectStatus.EXCHANGING_KEYS,
                myPosition = _connectState.value.myPosition,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to create offer")
            _connectState.value = WanConnectState(
                status = WanConnectStatus.ERROR, error = e.message
            )
        }
    }

    private suspend fun handleSdp(sdp: String, sdpType: String) {
        try {
            when (sdpType) {
                "offer" -> {
                    val answer = transportRouter.acceptWanConnection("wan_peer", sdp)
                    signalingClient.sendSdp(answer, "answer")
                    launch { collectAndSendIceCandidates() }
                    _connectState.value = WanConnectState(
                        status = WanConnectStatus.EXCHANGING_KEYS,
                        myPosition = _connectState.value.myPosition,
                    )
                }
                "answer" -> {
                    transportRouter.completeWanConnection("wan_peer", sdp)
                    _connectState.value = WanConnectState(
                        status = WanConnectStatus.CONNECTED,
                        myPosition = _connectState.value.myPosition,
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to handle SDP")
            _connectState.value = WanConnectState(
                status = WanConnectStatus.ERROR, error = e.message
            )
        }
    }

    private suspend fun collectAndSendIceCandidates() {
        transportRouter.localIceCandidates().collect { ice ->
            signalingClient.sendIceCandidate(
                ice.sdp, ice.sdpMid, ice.sdpMLineIndex
            )
        }
    }
}

data class WanConnectState(
    val status: WanConnectStatus = WanConnectStatus.IDLE,
    val myPosition: Int = 0,
    val error: String? = null,
)

enum class WanConnectStatus {
    IDLE,
    CONNECTING,
    WAITING_FOR_PEER,
    PEER_FOUND,
    EXCHANGING_KEYS,
    CONNECTED,
    ERROR,
}
