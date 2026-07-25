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
 * Manages both LAN and WAN transports, routing messages through the best
 * available path for each peer. LAN is always preferred when available
 * (lower latency, no internet required).
 *
 * Sends data through connectDirect() → sendData() on the LAN transport.
 * Each peer is routed through the most recent LAN connection.
 */
@Singleton
class TransportRouterImpl @Inject constructor(
    private val lanTransport: LanTransport,
    private val wanTransport: WanTransport,
) : TransportRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _incomingMessages = Channel<IncomingTransportMessage>(Channel.BUFFERED)
    override fun incomingMessages(): Flow<IncomingTransportMessage> = _incomingMessages.receiveAsFlow()

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override fun discoveredPeers(): Flow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private data class PeerRoute(
        val fingerprint: String,
        var transportMode: TransportMode = TransportMode.UNKNOWN,
    )

    private val peerRoutes = mutableMapOf<String, PeerRoute>()

    override suspend fun start() {
        wanTransport.configureIceServers(DefaultIceServers.ALL)
        lanTransport.startListening()

        // Collect LAN incoming data
        scope.launch {
            lanTransport.incomingData().collect { data ->
                val lanPeer = peerRoutes.entries.firstOrNull {
                    it.value.transportMode == TransportMode.LAN
                }
                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = lanPeer?.key ?: "lan_peer",
                        payload = data,
                        transportMode = TransportMode.LAN,
                    )
                )
            }
        }

        // Collect WAN incoming data
        scope.launch {
            wanTransport.incomingData().collect { data ->
                val wanPeer = peerRoutes.entries.firstOrNull {
                    it.value.transportMode == TransportMode.WAN
                }
                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = wanPeer?.key ?: "wan_peer",
                        payload = data,
                        transportMode = TransportMode.WAN,
                    )
                )
            }
        }

        // Forward discovered LAN peers
        scope.launch {
            lanTransport.discoveredPeers().collect { lanPeers ->
                _discoveredPeers.value = lanPeers.map { peer ->
                    DiscoveredPeer(
                        ipAddress = peer.ipAddress,
                        port = peer.port,
                        displayName = peer.displayName,
                        discoveryMethod = peer.discoveryMethod,
                        transportMode = TransportMode.LAN,
                    )
                }
            }
        }

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
        Timber.i("TransportRouter stopped")
    }

    override suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray) {
        val route = peerRoutes.getOrPut(peerFingerprint) {
            PeerRoute(fingerprint = peerFingerprint)
        }

        when (route.transportMode) {
            TransportMode.LAN -> {
                try {
                    lanTransport.sendData(encryptedPayload)
                    Timber.d("Sent via LAN to $peerFingerprint")
                } catch (e: Exception) {
                    Timber.w(e, "LAN send failed, falling back to WAN")
                    route.transportMode = TransportMode.WAN
                    wanTransport.sendData(encryptedPayload)
                }
            }
            TransportMode.WAN -> {
                try {
                    wanTransport.sendData(encryptedPayload)
                    Timber.d("Sent via WAN to $peerFingerprint")
                } catch (e: Exception) {
                    Timber.w(e, "WAN send failed, falling back to LAN")
                    route.transportMode = TransportMode.LAN
                    lanTransport.sendData(encryptedPayload)
                }
            }
            TransportMode.UNKNOWN -> {
                val isLanConnected = lanTransport.connectionState().first() == LanConnectionState.CONNECTED
                if (isLanConnected) {
                    route.transportMode = TransportMode.LAN
                    lanTransport.sendData(encryptedPayload)
                    Timber.d("Auto-detected LAN for $peerFingerprint")
                } else {
                    try {
                        route.transportMode = TransportMode.WAN
                        wanTransport.sendData(encryptedPayload)
                        Timber.d("Auto-detected WAN for $peerFingerprint")
                    } catch (e: Exception) {
                        route.transportMode = TransportMode.UNKNOWN
                        throw RuntimeException("No transport available for $peerFingerprint")
                    }
                }
            }
        }
    }

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerRoutes[peerFingerprint]?.transportMode ?: TransportMode.UNKNOWN
    }

    override suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String) {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.LAN,
        )

        val connected = lanTransport.connectDirect(ipAddress, port)
        if (!connected) {
            Timber.e("Failed to connect to $peerFingerprint via LAN ($ipAddress:$port)")
            peerRoutes[peerFingerprint]?.transportMode = TransportMode.UNKNOWN
            throw RuntimeException("LAN connection failed to $ipAddress:$port")
        }

        Timber.i("Connected to $peerFingerprint via LAN ($ipAddress:$port)")
    }

    override suspend fun connectWan(peerFingerprint: String): WanConnectionOffer {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
        )

        val offerSdp = wanTransport.createOffer()
        Timber.d("Created WebRTC offer for $peerFingerprint")

        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for $peerFingerprint: ${candidate.sdp.take(50)}...")
            }
        }

        return WanConnectionOffer(peerFingerprint = peerFingerprint, offerSdp = offerSdp)
    }

    override suspend fun acceptWanConnection(
        peerFingerprint: String,
        offerSdp: String,
    ): String {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
        )

        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for responder $peerFingerprint")
            }
        }

        return wanTransport.createAnswer(offerSdp)
    }

    override suspend fun completeWanConnection(peerFingerprint: String, answerSdp: String) {
        wanTransport.setRemoteAnswer(answerSdp)
        Timber.i("WAN connection completed for $peerFingerprint")
    }
}
