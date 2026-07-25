package com.zerochat.network.lan

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAN transport using TCP sockets + WiFi Direct + mDNS (with PIN codes).
 *
 * Protocol: 64-byte sender fingerprint prefix followed by payload.
 * Receiver reads fingerprint first to identify the peer, then reads payload.
 */
@Singleton
class LanTransportImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectReceiver: WifiDirectReceiver,
) : LanTransport {

    companion object {
        const val DEFAULT_PORT = 44231
        private const val MDNS_SERVICE_TYPE = "_zerochat._tcp.local."
        const val FINGERPRINT_LEN = 64  // SHA-256 hex
    }

    // ── Server state ────────────────────────────────────────────────

    private var serverSocket: ServerSocket? = null
    private var localFingerprint: String = "unknown"
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    // ── Peer connections ────────────────────────────────────────────

    private val activeSockets = ConcurrentHashMap<String, Socket>()

    // ── Data channels ───────────────────────────────────────────────

    private val _incomingData = Channel<LanIncoming>(Channel.BUFFERED)
    override fun incomingData(): Flow<LanIncoming> = _incomingData.receiveAsFlow()

    private val _discoveredPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override fun discoveredPeers(): Flow<List<LanPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionState = MutableStateFlow(LanConnectionState.DISCONNECTED)
    override fun connectionState(): Flow<LanConnectionState> = _connectionState.asStateFlow()

    // ── WiFi Direct ─────────────────────────────────────────────────

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    // ── mDNS ────────────────────────────────────────────────────────

    private var jmdns: javax.jmdns.JmDNS? = null
    private val mDNSPeers = mutableListOf<LanPeer>()
    private var mdnsDiscoveryJob: Job? = null
    private var mdnsAdvertiseJob: Job? = null

    // ── PIN code ────────────────────────────────────────────────────

    private var pinCode: String? = null
    private val secureRandom = SecureRandom()

    // ── Lifecycle ───────────────────────────────────────────────────

    override fun startListening() {
        if (serverSocket != null) {
            Timber.w("LAN server already running")
            return
        }
        try {
            serverSocket = ServerSocket(DEFAULT_PORT)
            _connectionState.value = LanConnectionState.CONNECTED
            Timber.i("LAN server listening on port $DEFAULT_PORT")
            serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Timber.d("New LAN connection from ${clientSocket.inetAddress.hostAddress}")
                        launch { handleIncomingConnection(clientSocket) }
                    } catch (e: Exception) {
                        if (isActive) Timber.w(e, "Error accepting LAN connection")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LAN server")
            _connectionState.value = LanConnectionState.DISCONNECTED
        }
    }

    override fun stopListening() {
        serverJob.cancel()
        activeSockets.values.forEach { runCatching { it.close() } }
        activeSockets.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        _connectionState.value = LanConnectionState.DISCONNECTED
        Timber.i("LAN server stopped")
    }

    override fun setLocalFingerprint(fingerprint: String) {
        localFingerprint = fingerprint
    }

    // ── Discovery ───────────────────────────────────────────────────

    override fun startWiFiDirectDiscovery() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return
        wifiP2pChannel = wifiP2pManager!!.initialize(context, context.mainLooper, null)
        wifiDirectReceiver.initialize(wifiP2pManager!!, wifiP2pChannel!!)

        wifiDirectReceiver.onPeersChanged = {
            wifiP2pManager?.requestPeers(wifiP2pChannel) { peerList ->
                val peers = peerList?.deviceList?.map { device ->
                    LanPeer(
                        deviceId = device.deviceAddress,
                        ipAddress = "",
                        port = DEFAULT_PORT,
                        displayName = device.deviceName,
                        discoveryMethod = "wifi_direct",
                    )
                } ?: emptyList()
                updateMergedPeers(peers)
            }
        }

        wifiDirectReceiver.onConnectionChanged = { connected ->
            if (connected) {
                wifiP2pManager?.requestConnectionInfo(wifiP2pChannel) { info ->
                    if (info.groupOwnerAddress != null && !info.isGroupOwner) {
                        serverScope.launch {
                            connectDirect(
                                info.groupOwnerAddress.hostAddress ?: return@launch,
                                DEFAULT_PORT
                            )
                        }
                    }
                }
            }
        }

        discoverPeers()
        Timber.i("WiFi Direct discovery started")
    }

    override fun stopWiFiDirectDiscovery() {
        wifiP2pManager?.stopPeerDiscovery(wifiP2pChannel, null)
    }

    override fun startMdnsDiscovery() {
        mdnsDiscoveryJob?.cancel()
        mdnsDiscoveryJob = serverScope.launch {
            try {
                val localIp = getLocalAddresses().firstOrNull() ?: return@launch
                jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))
                Timber.i("mDNS started on $localIp")
                jmdns?.addServiceListener(MDNS_SERVICE_TYPE, object : javax.jmdns.ServiceListener {
                    override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                        jmdns?.requestServiceInfo(MDNS_SERVICE_TYPE, event.name)
                    }
                    override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                        mDNSPeers.removeAll { it.deviceId == event.name }
                        refreshMergedPeers()
                    }
                    override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                        val addresses = event.info.inetAddresses
                        if (addresses.isNotEmpty()) {
                            val addr = addresses.first()
                            val peer = LanPeer(
                                displayName = event.name.replace("ZC-", ""),
                                ipAddress = addr.hostAddress ?: return,
                                port = event.info.port,
                                discoveryMethod = "mdns",
                                deviceId = event.name,
                            )
                            mDNSPeers.removeAll { it.deviceId == event.name }
                            mDNSPeers.add(peer)
                            refreshMergedPeers()
                            Timber.i("mDNS peer: ${peer.displayName} @ ${peer.ipAddress}")
                        }
                    }
                })
            } catch (e: Exception) {
                Timber.e(e, "mDNS discovery error")
            }
        }
    }

    override fun stopMdnsDiscovery() {
        mdnsDiscoveryJob?.cancel()
        runCatching { jmdns?.unregisterAllServices(); jmdns?.close(); jmdns = null }
    }

    // ── Connection ──────────────────────────────────────────────────

    override suspend fun connectDirect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), 5000)
                val key = "$ipAddress:$port"
                activeSockets[key] = socket

                // Send fingerprint handshake immediately so receiver knows who connected
                val fp = localFingerprint.take(FINGERPRINT_LEN)
                    .padEnd(FINGERPRINT_LEN, '0')
                    .toByteArray(Charsets.UTF_8)
                socket.getOutputStream().write(fp)
                socket.getOutputStream().flush()

                _connectionState.value = LanConnectionState.CONNECTED
                Timber.i("Connected to $ipAddress:$port (handshake sent)")
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to $ipAddress:$port")
                false
            }
        }
    }

    // ── Data transfer ───────────────────────────────────────────────

    /**
     * Send raw data — fingerprint already sent during connectDirect handshake.
     */
    override suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int) {
        withContext(Dispatchers.IO) {
            val key = "$ipAddress:$port"
            val existingSocket = activeSockets[key]

            if (existingSocket != null && existingSocket.isConnected) {
                sendToSocket(existingSocket, data)
            } else {
                // Short-lived connection: need to send fingerprint first, then data
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ipAddress, port), 5000)
                    val fp = localFingerprint.take(FINGERPRINT_LEN)
                        .padEnd(FINGERPRINT_LEN, '0')
                        .toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().write(fp + data)
                    socket.getOutputStream().flush()
                }
                Timber.d("Sent ${data.size}B to $ipAddress:$port (short-lived)")
            }
        }
    }

    override suspend fun getLocalAddresses(): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                        .map { it.hostAddress ?: "" }
                        .filter { it.isNotBlank() }
                } ?: emptyList()
            } catch (e: Exception) {
                Timber.w(e, "Failed to get local addresses")
                emptyList()
            }
        }
    }

    // ── PIN Code (8-digit) ──────────────────────────────────────────

    override fun getOrCreatePinCode(): String {
        pinCode?.let { return it }
        val code = String.format("%08d", secureRandom.nextInt(100_000_000))
        pinCode = code
        Timber.i("PIN code generated: $code")
        serverScope.launch { advertisePinCode() }
        return code
    }

    override suspend fun advertisePinCode() {
        val pin = pinCode ?: getOrCreatePinCode()
        mdnsAdvertiseJob?.cancel()
        mdnsAdvertiseJob = serverScope.launch {
            try {
                val localIp = getLocalAddresses().firstOrNull() ?: return@launch
                if (jmdns == null) {
                    jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))
                }
                val serviceName = "ZC-$pin-${android.os.Build.MODEL.replace(" ", "-").take(20)}"
                val serviceInfo = javax.jmdns.ServiceInfo.create(
                    MDNS_SERVICE_TYPE, serviceName, DEFAULT_PORT, "ZeroChat PIN:$pin"
                )
                jmdns?.registerService(serviceInfo)
                Timber.i("PIN $pin advertised via mDNS as '$serviceName'")
            } catch (e: Exception) {
                Timber.e(e, "Failed to advertise PIN via mDNS")
                throw e
            }
        }
        mdnsAdvertiseJob?.join()
    }

    override suspend fun resolvePinCode(pin: String): LanPeer? {
        return withContext(Dispatchers.IO) {
            try {
                val localIp = getLocalAddresses().firstOrNull() ?: return@withContext null
                if (jmdns == null) {
                    jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))
                }
                val prefix = "ZC-$pin-"
                val services = jmdns?.list(MDNS_SERVICE_TYPE) ?: emptyArray()
                Timber.d("PIN lookup '$pin': found ${services.size} mDNS services")
                for (svc in services) {
                    if (svc.name.startsWith(prefix)) {
                        val info = jmdns?.getServiceInfo(MDNS_SERVICE_TYPE, svc.name, 3000)
                        val addresses = info?.inetAddresses
                        if (addresses != null && addresses.isNotEmpty()) {
                            val addr = addresses.first()
                            val peer = LanPeer(
                                displayName = svc.name.replace("ZC-$pin-", "").replace("-", " "),
                                ipAddress = addr.hostAddress ?: continue,
                                port = info.port,
                                discoveryMethod = "pin",
                                deviceId = svc.name,
                            )
                            Timber.i("PIN $pin → ${peer.ipAddress}:${peer.port}")
                            return@withContext peer
                        }
                    }
                }
                Timber.w("No device found with PIN $pin")
                null
            } catch (e: Exception) {
                Timber.e(e, "PIN resolution failed")
                throw e
            }
        }
    }

    // ── Private helpers ─────────────────────────────────────────────

    /**
     * Handle incoming TCP connection: read fingerprint header, then payload.
     * Emit LanIncoming with resolved fingerprint so TransportRouter knows the sender.
     */
    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                val senderIp = socket.inetAddress.hostAddress

                // Read fingerprint header (64 bytes)
                val fpBuf = ByteArray(FINGERPRINT_LEN)
                var totalRead = 0
                while (totalRead < FINGERPRINT_LEN) {
                    val n = input.read(fpBuf, totalRead, FINGERPRINT_LEN - totalRead)
                    if (n == -1) break
                    totalRead += n
                }
                if (totalRead < FINGERPRINT_LEN) {
                    Timber.w("Connection closed before fingerprint from $senderIp")
                    return@withContext
                }

                val fingerprint = String(fpBuf, Charsets.UTF_8).trimEnd('0')
                Timber.i("Incoming connection from fingerprint=$fingerprint @ $senderIp")

                // Now read payload — one read() per message (raw TCP)
                val buffer = ByteArray(65536)
                while (isActive && !socket.isClosed) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    val data = buffer.copyOf(bytesRead)
                    _incomingData.send(LanIncoming(fingerprint, data, senderIp))
                    Timber.d("Received ${data.size}B from $fingerprint @ $senderIp")
                }
            } catch (e: java.net.SocketException) {
                Timber.d("Connection closed: ${e.message}")
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "Error reading from LAN connection")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun sendToSocket(socket: Socket, data: ByteArray) {
        withContext(Dispatchers.IO) {
            val output = socket.getOutputStream()
            output.write(data)
            output.flush()
            Timber.d("Sent ${data.size}B to ${socket.inetAddress.hostAddress}")
        }
    }

    private fun discoverPeers() {
        wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Timber.d("WiFi Direct peer discovery initiated") }
            override fun onFailure(reason: Int) { Timber.w("WiFi Direct peer discovery failed: reason=$reason") }
        })
    }

    private fun updateMergedPeers(wifiDirectPeers: List<LanPeer>) {
        _discoveredPeers.value = (wifiDirectPeers + mDNSPeers).distinctBy { it.deviceId + it.ipAddress }
    }

    private fun refreshMergedPeers() {
        updateMergedPeers(
            _discoveredPeers.value.filter { it.discoveryMethod == "wifi_direct" }
        )
    }
}
