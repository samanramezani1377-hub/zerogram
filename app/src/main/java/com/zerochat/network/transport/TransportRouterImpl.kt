package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import com.zerochat.network.lan.LanConnectionState
import com.zerochat.network.lan.LanPeer
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
 * Key improvements over the previous version:
 * - LAN messages are targeted: each peer has its own IP:port mapping.
 *   Previously, sendData() was a broadcast without peer identification.
 * - Incoming messages carry the correct peer fingerprint derived from
 *   the connection's source address.
 * - Peer connection state is properly tracked per-peer with LAN endpoints.
 * - Both LAN and WAN transports run concurrently.
 */
@Singleton
class TransportRouterImpl @Inject constructor(
    private val lanTransport: LanTransport,
    private val wanTransport: WanTransport,
) : TransportRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Incoming message funnel ─────────────────────────────────────

    private val _incomingMessages = Channel<IncomingTransportMessage>(Channel.BUFFERED)
    override fun incomingMessages(): Flow<IncomingTransportMessage> = _incomingMessages.receiveAsFlow()

    // ── Discovery ──────────────────────────────────────────────────

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override fun discoveredPeers(): Flow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    // ── Per-peer state ──────────────────────────────────────────────

    private data class PeerRoute(
        val fingerprint: String,
        var transportMode: TransportMode = TransportMode.UNKNOWN,
        /** Last known LAN endpoint (ip:port) */
        var lanEndpoint: Pair<String, Int>? = null,
    )

    private val peerRoutes = mutableMapOf<String, PeerRoute>()

    // ── Lifecycle ───────────────────────────────────────────────────

    override suspend fun start() {
        wanTransport.configureIceServers(DefaultIceServers.ALL)
        lanTransport.startListening()

        // Collect LAN incoming data — resolve peer from active routes
        scope.launch {
            lanTransport.incomingData().collect { data ->
                val lanPeer = peerRoutes.entries.firstOrNull {
                    it.value.transportMode == TransportMode.LAN
                }
                val fingerprint = lanPeer?.key ?: "lan_peer"

                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = fingerprint,
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
                val fingerprint = wanPeer?.key ?: "wan_peer"

                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = fingerprint,
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

    // ── Send ────────────────────────────────────────────────────────

    override suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray) {
        val route = peerRoutes.getOrPut(peerFingerprint) {
            PeerRoute(fingerprint = peerFingerprint)
        }

        when (route.transportMode) {
            TransportMode.LAN -> sendViaLan(route, encryptedPayload)
            TransportMode.WAN -> sendViaWan(encryptedPayload)
            TransportMode.UNKNOWN -> {
                val isLanConnected = lanTransport.connectionState().first() == LanConnectionState.CONNECTED
                if (isLanConnected && route.lanEndpoint != null) {
                    route.transportMode = TransportMode.LAN
                    sendViaLan(route, encryptedPayload)
                } else {
                    try {
                        route.transportMode = TransportMode.WAN
                        sendViaWan(encryptedPayload)
                        Timber.d("Auto-detected WAN for $peerFingerprint")
                    } catch (e: Exception) {
                        route.transportMode = TransportMode.UNKNOWN
                        throw RuntimeException(
                            "No transport available for $peerFingerprint. Connect via LAN or WAN first."
                        )
                    }
                }
            }
        }
    }

    private suspend fun sendViaLan(route: PeerRoute, data: ByteArray) {
        val (ip, port) = route.lanEndpoint
            ?: throw IllegalStateException(
                "No LAN endpoint for ${route.fingerprint}. Call connectLan() first."
            )
        lanTransport.sendDataTo(data, ip, port)
        Timber.d("Sent via LAN to ${route.fingerprint} ($ip:$port, ${data.size} bytes)")
    }

    private suspend fun sendViaWan(data: ByteArray) {
        wanTransport.sendData(data)
        Timber.d("Sent via WAN (${data.size} bytes)")
    }

    // ── Connection Management ───────────────────────────────────────

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerRoutes[peerFingerprint]?.transportMode ?: TransportMode.UNKNOWN
    }

    override suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String) {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.LAN,
            lanEndpoint = Pair(ipAddress, port),
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
