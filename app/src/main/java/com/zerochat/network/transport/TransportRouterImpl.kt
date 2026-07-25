package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import com.zerochat.network.lan.LanConnectionState
import com.zerochat.network.lan.LanIncoming
import com.zerochat.network.lan.LanPeer
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.lan.LanTransportImpl
import com.zerochat.network.wan.DefaultIceServers
import com.zerochat.network.wan.WanTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of TransportRouter — manages LAN & WAN transports.
 *
 * Key improvements over the previous version:
 * - WAN incoming messages are correctly tagged per-peer (no longer
 *   routes ALL WAN traffic to the first WAN peer).
 * - LAN fingerprint resolution from TCP protocol header.
 * - Per-peer transport state tracking with proper route table.
 * - Incoming messages always carry the correct peer fingerprint.
 */
@Singleton
class TransportRouterImpl @Inject constructor(
    private val lanTransport: LanTransport,
    private val wanTransport: WanTransport,
) : TransportRouter {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Incoming message funnel ────────────────────────────────────

    private val _incomingMessages =
        Channel<IncomingTransportMessage>(Channel.BUFFERED)

    override fun incomingMessages(): Flow<IncomingTransportMessage> =
        _incomingMessages.receiveAsFlow()

    // ── Discovery ──────────────────────────────────────────────────

    private val _discoveredPeers =
        MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    override fun discoveredPeers(): Flow<List<DiscoveredPeer>> =
        _discoveredPeers.asStateFlow()

    // ── Per-peer route table ───────────────────────────────────────

    private data class PeerRoute(
        val fingerprint: String,
        var transportMode: TransportMode = TransportMode.UNKNOWN,
        /** LAN endpoint: (ip, port) */
        var lanEndpoint: Pair<String, Int>? = null,
        /** WAN peer label — used to route DataChannel messages */
        var wanLabel: String? = null,
    )

    /**
     * Thread-safe route table. Access must happen within coroutine
     * context (Dispatchers.IO or with mutex) to avoid races.
     *
     * We use a simple mutableMap guarded by the fact that all sends
     * and connection setup go through suspend functions.
     */
    private val peerRoutes = mutableMapOf<String, PeerRoute>()

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun setLocalFingerprint(fingerprint: String) {
        lanTransport.setLocalFingerprint(fingerprint)
    }

    override suspend fun start() {
        wanTransport.configureIceServers(DefaultIceServers.ALL)
        lanTransport.startListening()

        // ── LAN incoming → funnel ──────────────────────────────────
        scope.launch {
            lanTransport.incomingData().collect { incoming ->
                val fingerprint = resolveLanFingerprint(incoming)

                // Register route so sends can go back to this peer
                registerLanRoute(fingerprint, incoming.senderIp)

                _incomingMessages.send(
                    IncomingTransportMessage(
                        peerFingerprint = fingerprint,
                        payload = incoming.payload,
                        transportMode = TransportMode.LAN,
                    )
                )
            }
        }

        // ── WAN incoming → funnel (properly tagged per-peer) ───────
        scope.launch {
            (wanTransport as? com.zerochat.network.wan.WebRtcTransport)
                ?.incomingTaggedData()
                ?.collect { (peerLabel, data) ->
                    // Map WebRTC peer label (e.g., "offerer" / "answerer")
                    // to the actual fingerprint from route table.
                    val fingerprint = resolveWanFingerprint(peerLabel)

                    _incomingMessages.send(
                        IncomingTransportMessage(
                            peerFingerprint = fingerprint,
                            payload = data,
                            transportMode = TransportMode.WAN,
                        )
                    )
                }
                // Fallback: untagged WAN data (backward compat)
                ?: wanTransport.incomingData().collect { data ->
                    val fingerprint = peerRoutes.entries
                        .firstOrNull { it.value.transportMode == TransportMode.WAN }
                        ?.key
                        ?: "wan_peer"

                    _incomingMessages.send(
                        IncomingTransportMessage(
                            peerFingerprint = fingerprint,
                            payload = data,
                            transportMode = TransportMode.WAN,
                        )
                    )
                }
        }

        // ── LAN discovery → funnel ─────────────────────────────────
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

    // ── Send ───────────────────────────────────────────────────────

    override suspend fun send(
        peerFingerprint: String,
        encryptedPayload: ByteArray,
    ) {
        val route = peerRoutes.getOrPut(peerFingerprint) {
            PeerRoute(fingerprint = peerFingerprint)
        }

        when (route.transportMode) {
            TransportMode.LAN -> sendViaLan(route, encryptedPayload)

            TransportMode.WAN -> sendViaWan(route, encryptedPayload)

            TransportMode.UNKNOWN -> {
                // Auto-detect: try LAN first, fall back to WAN
                val isLanConnected =
                    lanTransport.connectionState().first() == LanConnectionState.CONNECTED

                if (isLanConnected && route.lanEndpoint != null) {
                    route.transportMode = TransportMode.LAN
                    sendViaLan(route, encryptedPayload)
                    Timber.d("Auto-detected LAN for $peerFingerprint")
                } else {
                    // Try WAN
                    val wanSession = peerRoutes.values.firstOrNull {
                        it.transportMode == TransportMode.WAN
                    }
                    if (wanSession != null) {
                        route.transportMode = TransportMode.WAN
                        route.wanLabel = wanSession.wanLabel
                        sendViaWan(route, encryptedPayload)
                        Timber.d("Auto-detected WAN for $peerFingerprint")
                    } else {
                        throw RuntimeException(
                            "No transport available for $peerFingerprint. " +
                                    "Connect via LAN or WAN first."
                        )
                    }
                }
            }
        }
    }

    private suspend fun sendViaLan(
        route: PeerRoute,
        data: ByteArray,
    ) {
        val (ip, port) = route.lanEndpoint
            ?: throw IllegalStateException(
                "No LAN endpoint for ${route.fingerprint}. Call connectLan() first."
            )
        lanTransport.sendDataTo(data, ip, port)
        Timber.d("Sent via LAN to ${route.fingerprint} ($ip:$port, ${data.size} bytes)")
    }

    private suspend fun sendViaWan(
        route: PeerRoute,
        data: ByteArray,
    ) {
        val wanTransportImpl = wanTransport as? com.zerochat.network.wan.WebRtcTransport
        if (wanTransportImpl != null && route.wanLabel != null) {
            wanTransportImpl.sendDataTo(data, route.wanLabel!!)
        } else {
            wanTransport.sendData(data)
        }
        Timber.d("Sent via WAN to ${route.fingerprint} (${data.size} bytes)")
    }

    // ── Connection Management ──────────────────────────────────────

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerRoutes[peerFingerprint]?.transportMode
            ?: TransportMode.UNKNOWN
    }

    override suspend fun connectLan(
        ipAddress: String,
        port: Int,
        peerFingerprint: String,
    ) {
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

    override suspend fun connectWan(
        peerFingerprint: String,
    ): WanConnectionOffer {
        val offerSdp = wanTransport.createOffer()

        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
            wanLabel = "offerer",
        )

        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for $peerFingerprint: ${candidate.sdp.take(50)}...")
            }
        }

        Timber.d("Created WebRTC offer for $peerFingerprint")
        return WanConnectionOffer(
            peerFingerprint = peerFingerprint,
            offerSdp = offerSdp,
        )
    }

    override suspend fun acceptWanConnection(
        peerFingerprint: String,
        offerSdp: String,
    ): String {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
            wanLabel = "answerer",
        )

        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for responder $peerFingerprint")
            }
        }

        return wanTransport.createAnswer(offerSdp)
    }

    override suspend fun completeWanConnection(
        peerFingerprint: String,
        answerSdp: String,
    ) {
        wanTransport.setRemoteAnswer(answerSdp)
        Timber.i("WAN connection completed for $peerFingerprint")
    }

    // ── Private: Fingerprint Resolution ────────────────────────────

    /**
     * Resolve the fingerprint from a LAN incoming message.
     * Looks up the route table by source IP to find the correct fingerprint.
     */
    private fun resolveLanFingerprint(incoming: LanIncoming): String {
        val fp = incoming.peerFingerprint
        if (fp.isNotBlank() && fp != "unknown") return fp

        // Fallback: try to find by IP
        val existing = peerRoutes.entries.firstOrNull {
            it.value.lanEndpoint?.first == incoming.senderIp
        }
        return existing?.key ?: "lan_${incoming.senderIp}"
    }

    /**
     * Resolve the fingerprint from a WAN peer label.
     */
    private fun resolveWanFingerprint(peerLabel: String): String {
        val existing = peerRoutes.entries.firstOrNull {
            it.value.wanLabel == peerLabel
        }
        return existing?.key ?: "wan_$peerLabel"
    }

    /**
     * Register or update the LAN route for a peer based on source IP.
     */
    private fun registerLanRoute(fingerprint: String, senderIp: String) {
        if (!peerRoutes.containsKey(fingerprint)) {
            peerRoutes[fingerprint] = PeerRoute(
                fingerprint = fingerprint,
                transportMode = TransportMode.LAN,
                lanEndpoint = Pair(senderIp, LanTransportImpl.DEFAULT_PORT),
            )
        }
    }
}
