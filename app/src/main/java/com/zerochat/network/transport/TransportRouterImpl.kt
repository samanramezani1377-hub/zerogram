package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
import com.zerochat.network.lan.LanConnectionState
import com.zerochat.network.lan.LanIncoming
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
        var lanEndpoint: Pair<String, Int>? = null,
        var wanLabel: String? = null,
    )

    private val peerRoutes = mutableMapOf<String, PeerRoute>()

    override fun setLocalFingerprint(fingerprint: String) {
        lanTransport.setLocalFingerprint(fingerprint)
    }

    override suspend fun start() {
        wanTransport.configureIceServers(DefaultIceServers.ALL)
        lanTransport.startListening()

        scope.launch {
            lanTransport.incomingData().collect { incoming ->
                val fingerprint = resolveLanFingerprint(incoming)
                registerLanRoute(fingerprint, incoming.senderIp)
                _incomingMessages.send(
                    IncomingTransportMessage(fingerprint, incoming.payload, TransportMode.LAN)
                )
            }
        }

        scope.launch {
            (wanTransport as? com.zerochat.network.wan.WebRtcTransport)
                ?.incomingTaggedData()
                ?.collect { (peerLabel, data) ->
                    val fingerprint = resolveWanFingerprint(peerLabel)
                    _incomingMessages.send(
                        IncomingTransportMessage(fingerprint, data, TransportMode.WAN)
                    )
                }
                ?: wanTransport.incomingData().collect { data ->
                    val fingerprint = peerRoutes.entries
                        .firstOrNull { it.value.transportMode == TransportMode.WAN }?.key ?: "wan_peer"
                    _incomingMessages.send(IncomingTransportMessage(fingerprint, data, TransportMode.WAN))
                }
        }

        scope.launch {
            lanTransport.discoveredPeers().collect { lanPeers ->
                _discoveredPeers.value = lanPeers.map { peer ->
                    DiscoveredPeer(peer.ipAddress, peer.port, peer.displayName, peer.discoveryMethod, TransportMode.LAN)
                }
            }
        }

        lanTransport.startWiFiDirectDiscovery()
        lanTransport.startMdnsDiscovery()
        Timber.i("TransportRouter started")
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
        val route = peerRoutes[peerFingerprint]
            ?: throw IllegalStateException(
                "Not connected to $peerFingerprint. Return to Find Peers and tap Connect on their name."
            )

        when (route.transportMode) {
            TransportMode.LAN -> {
                val (ip, port) = route.lanEndpoint
                    ?: throw IllegalStateException("No LAN endpoint for $peerFingerprint")
                lanTransport.sendDataTo(encryptedPayload, ip, port)
                Timber.d("Sent to $peerFingerprint ($ip:$port, ${encryptedPayload.size}B)")
            }

            TransportMode.WAN -> {
                val label = route.wanLabel
                if (label != null) {
                    (wanTransport as? com.zerochat.network.wan.WebRtcTransport)
                        ?.sendDataTo(encryptedPayload, label)
                } else {
                    wanTransport.sendData(encryptedPayload)
                }
                Timber.d("Sent via WAN to $peerFingerprint")
            }

            TransportMode.UNKNOWN ->
                throw IllegalStateException(
                    "Not connected to $peerFingerprint"
                )
        }
    }

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerRoutes[peerFingerprint]?.transportMode ?: TransportMode.UNKNOWN
    }

    /**
     * Connect to a peer via LAN.
     *
     * KEY FIX: First tries the TCP connection with a short timeout.
     * Only registers the route in the table if connection succeeds.
     * This prevents the "tap to retry" cycle where users think they're
     * connected but the socket was never established.
     */
    override suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String) {
        // Try to connect first — this has a 1.5s timeout now
        val connected = withContext(Dispatchers.IO) {
            lanTransport.connectDirect(ipAddress, port)
        }

        if (!connected) {
            throw RuntimeException(
                "Could not connect to $peerFingerprint at $ipAddress:$port. " +
                        "Make sure both devices are on the same WiFi network and ZeroGram is open."
            )
        }

        // Only register after successful connection
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.LAN,
            lanEndpoint = Pair(ipAddress, port),
        )

        Timber.i("LAN route registered for $peerFingerprint ($ipAddress:$port)")
    }

    override suspend fun connectWan(peerFingerprint: String): WanConnectionOffer {
        val offerSdp = wanTransport.createOffer()
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
            wanLabel = "offerer",
        )
        scope.launch {
            wanTransport.localIceCandidates().collect { candidate ->
                Timber.d("ICE candidate for $peerFingerprint")
            }
        }
        return WanConnectionOffer(peerFingerprint, offerSdp)
    }

    override suspend fun acceptWanConnection(peerFingerprint: String, offerSdp: String): String {
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
            wanLabel = "answerer",
        )
        return wanTransport.createAnswer(offerSdp)
    }

    override suspend fun completeWanConnection(peerFingerprint: String, answerSdp: String) {
        wanTransport.setRemoteAnswer(answerSdp)
        Timber.i("WAN connection completed for $peerFingerprint")
    }

    private fun resolveLanFingerprint(incoming: LanIncoming): String {
        val fp = incoming.peerFingerprint
        if (fp.isNotBlank() && fp != "unknown") return fp
        val existing = peerRoutes.entries.firstOrNull {
            it.value.lanEndpoint?.first == incoming.senderIp
        }
        return existing?.key ?: "lan_${incoming.senderIp}"
    }

    private fun resolveWanFingerprint(peerLabel: String): String {
        return peerRoutes.entries.firstOrNull { it.value.wanLabel == peerLabel }?.key
            ?: "wan_$peerLabel"
    }

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
