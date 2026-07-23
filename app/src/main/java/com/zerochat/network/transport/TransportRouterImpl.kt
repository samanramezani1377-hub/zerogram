package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import com.zerochat.network.lan.LanConnectionState
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.wan.DefaultIceServers
import com.zerochat.network.wan.WanTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TransportRouter.
 *
 * Manages both LAN and WAN transports, routing messages
 * through the best available path for each peer.
 */
@Singleton
class TransportRouterImpl @Inject constructor(
    private val lanTransport: LanTransport,
    private val wanTransport: WanTransport,
) : TransportRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Incoming message funnel
    private val _incomingMessages = Channel<IncomingTransportMessage>(Channel.BUFFERED)
    override fun incomingMessages(): Flow<IncomingTransportMessage> = _incomingMessages.receiveAsFlow()

    // Discovery funnel
    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    // Per-peer transport state
    private data class PeerConnectionState(
        val fingerprint: String,
        var mode: TransportMode = TransportMode.UNKNOWN,
        val wanEstablished: CompletableDeferred<Unit>? = null,
    )

    private val peerStates = mutableMapOf<String, PeerConnectionState>()

    // Inbound message collector jobs
    private val inboundJobs = mutableMapOf<String, Job>()

    override suspend fun start() {
        // Configure WAN with default STUN servers
        wanTransport.configureIceServers(DefaultIceServers.ALL)

        // Start LAN listener
        lanTransport.startListening()

        // Collect LAN incoming data
        scope.launch {
            lanTransport.incomingData().collect { data ->
                // Data from LAN — route to appropriate handler
                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = "lan_peer", // Will be resolved via protocol header
                        payload = data,
                        transportMode = TransportMode.LAN,
                    )
                )
            }
        }

        // Collect WAN incoming data
        scope.launch {
            wanTransport.incomingData().collect { data ->
                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = "wan_peer", // Will be resolved via protocol header
                        payload = data,
                        transportMode = TransportMode.WAN,
                    )
                )
            }
        }

        // Collect LAN discovered peers → forward to unified flow
        scope.launch {
            lanTransport.discoveredPeers().collect { lanPeers ->
                val discovered = lanPeers.map { peer ->
                    DiscoveredPeer(
                        ipAddress = peer.ipAddress,
                        port = peer.port,
                        displayName = peer.displayName,
                        discoveryMethod = peer.discoveryMethod,
                        transportMode = TransportMode.LAN,
                    )
                }
                _discoveredPeers.value = discovered
            }
        }

        // Start LAN discovery
        lanTransport.startWiFiDirectDiscovery()
        lanTransport.startMdnsDiscovery()

        Timber.i("TransportRouter started — listening on LAN + WAN")
    }

    override suspend fun stop() {
        scope.cancel()
        lanTransport.stopListening()
        lanTransport.stopWiFiDirectDiscovery()
        lanTransport.stopMdnsDiscovery()
        wanTransport.close()
    }

    override suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray) {
        val state = peerStates[peerFingerprint] ?: run {
            Timber.w("No connection state for peer $peerFingerprint")
            return
        }

        when (state.mode) {
            TransportMode.LAN -> {
                lanTransport.sendData(encryptedPayload)
            }
            TransportMode.WAN -> {
                wanTransport.sendData(encryptedPayload)
            }
            TransportMode.UNKNOWN -> {
                // Try LAN first
                if (lanTransport.connectionState().first() == LanConnectionState.CONNECTED) {
                    state.mode = TransportMode.LAN
                    lanTransport.sendData(encryptedPayload)
                } else {
                    Timber.w("No transport available for peer $peerFingerprint")
                }
            }
        }
    }

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerStates[peerFingerprint]?.mode ?: TransportMode.UNKNOWN
    }

    override suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String) {
        peerStates[peerFingerprint] = PeerConnectionState(
            fingerprint = peerFingerprint,
            mode = TransportMode.LAN,
        )
        val connected = lanTransport.connectDirect(ipAddress, port)
        if (!connected) {
            Timber.e("Failed to connect to $peerFingerprint via LAN")
            peerStates[peerFingerprint]?.mode = TransportMode.UNKNOWN
        }
    }

    override suspend fun connectWan(peerFingerprint: String): WanConnectionOffer {
        peerStates[peerFingerprint] = PeerConnectionState(
            fingerprint = peerFingerprint,
            mode = TransportMode.WAN,
        )

        val offerSdp = wanTransport.createOffer()
        Timber.d("Created WebRTC offer for $peerFingerprint")

        // Forward ICE candidates as they arrive
        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                // In production: send candidate to peer via side channel
                Timber.d("ICE candidate for $peerFingerprint: ${candidate.sdp.take(50)}...")
            }
        }

        return WanConnectionOffer(
            peerFingerprint = peerFingerprint,
            offerSdp = offerSdp,
        )
    }

    override suspend fun acceptWanConnection(
        peerFingerprint: String,
        offerSdp: String,
    ): String {
        peerStates[peerFingerprint] = PeerConnectionState(
            fingerprint = peerFingerprint,
            mode = TransportMode.WAN,
        )

        // Forward ICE candidates
        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for responder $peerFingerprint")
            }
        }

        return wanTransport.createAnswer(offerSdp)
    }

    override suspend fun completeWanConnection(peerFingerprint: String, answerSdp: String) {
        wanTransport.setRemoteAnswer(answerSdp)
    }

    override fun discoveredPeers(): Flow<List<DiscoveredPeer>> = _discoveredPeers
}
