package com.zerochat.network.transport

import com.zerochat.data.model.TransportMode
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

        // LAN incoming
        scope.launch {
            lanTransport.incomingData().collect { incoming ->
                val fingerprint = resolveLanFingerprint(incoming)
                registerLanRoute(fingerprint, incoming.senderIp)
                _incomingMessages.send(
                    IncomingTransportMessage(fingerprint, incoming.payload, TransportMode.LAN)
                )
            }
        }

        // WAN incoming
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
                    val fp = peerRoutes.entries
                        .firstOrNull { it.value.transportMode == TransportMode.WAN }?.key ?: "wan_peer"
                    _incomingMessages.send(IncomingTransportMessage(fp, data, TransportMode.WAN))
                }
        }

        // Discovery → merged peers
        scope.launch {
            lanTransport.discoveredPeers().collect { lanPeers ->
                _discoveredPeers.value = lanPeers.map { peer ->
                    DiscoveredPeer(peer.ipAddress, peer.port, peer.displayName, peer.discoveryMethod, TransportMode.LAN)
                }
            }
        }

        lanTransport.startWiFiDirectDiscovery()
        lanTransport.startMdnsDiscovery()
        Timber.i("TransportRouter started — dual-channel discovery active")
    }

    override suspend fun stop() {
        scope.cancel()
        lanTransport.stopListening()
        lanTransport.stopWiFiDirectDiscovery()
        lanTransport.stopMdnsDiscovery()
        wanTransport.close()
    }

    override suspend fun send(peerFingerprint: String, encryptedPayload: ByteArray) {
        val route = peerRoutes[peerFingerprint]
        if (route == null) {
            throw IllegalStateException(
                "No route for $peerFingerprint. Connect first via Find Peers."
            )
        }

        when (route.transportMode) {
            TransportMode.LAN -> {
                val (ip, port) = route.lanEndpoint
                    ?: throw IllegalStateException("No LAN address for $peerFingerprint")
                lanTransport.sendDataTo(encryptedPayload, ip, port)
                Timber.d("→ Sent to $peerFingerprint ($ip:$port, ${encryptedPayload.size}B)")
            }

            TransportMode.WAN -> {
                val label = route.wanLabel
                if (label != null) {
                    (wanTransport as? com.zerochat.network.wan.WebRtcTransport)
                        ?.sendDataTo(encryptedPayload, label)
                } else {
                    wanTransport.sendData(encryptedPayload)
                }
            }

            TransportMode.UNKNOWN ->
                throw IllegalStateException("Not connected to $peerFingerprint")
        }
    }

    override fun currentMode(peerFingerprint: String): TransportMode {
        return peerRoutes[peerFingerprint]?.transportMode ?: TransportMode.UNKNOWN
    }

    /**
     * Connect to a peer via LAN.
     *
     * Tries TCP connection first (3s timeout). Only registers the route
     * on success. The fingerprint is used as the stable peer identifier.
     */
    override suspend fun connectLan(ipAddress: String, port: Int, peerFingerprint: String): String {
        val actualFingerprint = lanTransport.connectDirect(ipAddress, port)
            ?: throw RuntimeException(
                "Can't reach $peerFingerprint at $ipAddress:$port.\n\n" +
                "Make sure both devices are on the same WiFi and ZeroGram is open."
            )

        peerRoutes[actualFingerprint] = PeerRoute(
            fingerprint = actualFingerprint,
            transportMode = TransportMode.LAN,
            lanEndpoint = Pair(ipAddress, port),
        )

        Timber.i("✓ Route: $actualFingerprint → $ipAddress:$port [LAN]")
        actualFingerprint
    }
    }

    override suspend fun connectWan(peerFingerprint: String): WanConnectionOffer {
        val offerSdp = wanTransport.createOffer()
        peerRoutes[peerFingerprint] = PeerRoute(
            fingerprint = peerFingerprint,
            transportMode = TransportMode.WAN,
            wanLabel = "offerer",
        )
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
    }

    private fun resolveLanFingerprint(incoming: LanIncoming): String {
        val fp = incoming.peerFingerprint
        if (fp.isNotBlank() && fp != "unknown") return fp
        return peerRoutes.entries
            .firstOrNull { it.value.lanEndpoint?.first == incoming.senderIp }?.key
            ?: "lan_${incoming.senderIp}"
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
            Timber.i("✓ Auto-route registered for incoming $fingerprint @ $senderIp")
        }
    }
}
