package com.zerochat.network.transport

import com.zerochat.data.model.Peer
import com.zerochat.data.model.TransportMode
import com.zerochat.domain.PeerRepository
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
    private val peerRepository: PeerRepository,
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
        return actualFingerprint
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

    override suspend fun addIceCandidate(
        candidate: String, sdpMid: String, sdpMLineIndex: Int,
    ) {
        wanTransport.addIceCandidate(candidate, sdpMid, sdpMLineIndex)
    }

    override fun localIceCandidates() = wanTransport.localIceCandidates()

    override suspend fun sendRaw(
        peerFingerprint: String, ipAddress: String, port: Int, data: ByteArray,
    ) {
        lanTransport.sendDataTo(data, ipAddress, port)
    }

    private fun resolveLanFingerprint(incoming: LanIncoming): String {
        val fp = incoming.peerFingerprint
        if (fp.isNotBlank() && fp != "unknown") {
            Timber.d("Fingerprint from handshake: $fp (sender=${incoming.senderIp})")
            return fp
        }
        // Fallback: try to match by IP
        val match = peerRoutes.entries
            .firstOrNull { it.value.lanEndpoint?.first == incoming.senderIp }
        if (match != null) {
            Timber.d("Fingerprint resolved by IP: ${match.key} (sender=${incoming.senderIp})")
            return match.key
        }
        // Last resort
        val fallback = "lan_${incoming.senderIp.replace(".", "_")}"
        Timber.w("Using fallback fingerprint: $fallback (sender=${incoming.senderIp})")
        return fallback
    }

    private fun resolveWanFingerprint(peerLabel: String): String {
        return peerRoutes.entries.firstOrNull { it.value.wanLabel == peerLabel }?.key
            ?: "wan_$peerLabel"
    }

    private fun registerLanRoute(fingerprint: String, senderIp: String) {
        val port = LanTransportImpl.DEFAULT_PORT
        val isNew = !peerRoutes.containsKey(fingerprint)
        val existingRoute = peerRoutes[fingerprint]
        
        // Always update/create the route with latest endpoint
        peerRoutes[fingerprint] = PeerRoute(
            fingerprint = fingerprint,
            transportMode = TransportMode.LAN,
            lanEndpoint = Pair(senderIp, port),
        )
        
        if (isNew) {
            Timber.i("✓ Auto-route registered for incoming $fingerprint @ $senderIp:$port")

            // Auto-save peer so the receiver can see the sender in Contacts
            // and reply without manual connection.
            scope.launch {
                try {
                    val existing = peerRepository.getPeer(fingerprint)
                    if (existing == null) {
                        peerRepository.savePeer(
                            Peer(
                                fingerprint = fingerprint,
                                displayName = fingerprint.take(8),
                                ipAddress = senderIp,
                                port = port,
                                preferredTransport = TransportMode.LAN,
                                lastSeen = System.currentTimeMillis(),
                            )
                        )
                        Timber.i("✓ Auto-saved peer $fingerprint from incoming connection")
                    } else {
                        peerRepository.updateConnectionInfo(
                            fingerprint, senderIp, TransportMode.LAN, System.currentTimeMillis()
                        )
                    }

                    // Connect back to the sender so BOTH sides have a persistent
                    // socket. Without this, the receiving side would use a
                    // short-lived "fallback" connection for every message.
                    try {
                        lanTransport.connectDirect(senderIp, port)
                        Timber.i("✓ Established persistent back-connection to $senderIp:$port")
                    } catch (e: Exception) {
                        Timber.w(e, "Back-connection to $senderIp failed (fallback will be used for sends)")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to auto-save peer $fingerprint")
                }
            }
        } else {
            // Route already exists — just log that we received from a known peer
            Timber.d("Route already exists for $fingerprint @ $senderIp:$port")
        }
    }

}
