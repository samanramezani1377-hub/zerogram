package com.zerochat.network.lan

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAN transport using TCP sockets with length-prefixed framing.
 *
 * Protocol (v2):
 * ┌────────────────┬─────────────────────┬──────────────────────┐
 * │ Fingerprint    │ Payload Length       │ Payload               │
 * │ (64 bytes)     │ (4 bytes, big-endian)│ (variable)            │
 * └────────────────┴─────────────────────┴──────────────────────┘
 *
 * Key improvements over the previous version:
 * - Length-prefixed framing prevents partial/corrupt reads and message
 *   boundary ambiguity.
 * - Socket timeout (30s idle) detects zombie connections.
 * - Proper WiFi Direct group owner connection logic.
 * - Thread-safe peer lists with atomic state updates.
 * - Structured concurrency: each connection runs in its own child scope.
 */
@Singleton
class LanTransportImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectReceiver: WifiDirectReceiver,
) : LanTransport {

    companion object {
        const val DEFAULT_PORT = 44231
        private const val MDNS_SERVICE_TYPE = "_zerochat._tcp.local."
        const val FINGERPRINT_LEN = 64
        private const val LENGTH_FIELD_SIZE = 4
        private const val MAX_PAYLOAD_SIZE = 1_048_576 // 1 MB
        private const val SOCKET_TIMEOUT_MS = 30_000   // 30 seconds
        private const val HEARTBEAT_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15).toInt()
    }

    // ── Server ─────────────────────────────────────────────────────

    private var serverSocket: ServerSocket? = null
    private var localFingerprint: String = "unknown"
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    // ── Active connections ─────────────────────────────────────────

    /**
     * Map of "ip:port" → active client socket.
     * Server-side connections are tracked separately by fingerprint.
     */
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    // ── Data channels ──────────────────────────────────────────────

    private val _incomingData = Channel<LanIncoming>(Channel.BUFFERED)
    override fun incomingData(): Flow<LanIncoming> = _incomingData.receiveAsFlow()

    private val _discoveredPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override fun discoveredPeers(): Flow<List<LanPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionState = MutableStateFlow(LanConnectionState.DISCONNECTED)
    override fun connectionState(): Flow<LanConnectionState> = _connectionState.asStateFlow()

    // ── WiFi Direct ────────────────────────────────────────────────

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private val wifiDirectPeers = mutableListOf<LanPeer>()
    private var wifiDirectDiscoveryJob: Job? = null

    // ── mDNS ───────────────────────────────────────────────────────

    private var jmdns: javax.jmdns.JmDNS? = null
    private val mDNSPeers = mutableListOf<LanPeer>()
    private var mdnsDiscoveryJob: Job? = null
    private var mdnsAdvertiseJob: Job? = null

    // ── PIN code ───────────────────────────────────────────────────

    private var pinCode: String? = null
    private val secureRandom = SecureRandom()

    // ── Lifecycle ──────────────────────────────────────────────────

    override fun startListening() {
        if (serverSocket != null) {
            Timber.w("LAN server already running")
            return
        }
        try {
            serverSocket = ServerSocket(DEFAULT_PORT)
            // Set accept timeout so the loop can be cancelled
            serverSocket?.soTimeout = 1000
            _connectionState.value = LanConnectionState.CONNECTED
            Timber.i("LAN server listening on port $DEFAULT_PORT")

            serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        val remoteAddr = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        Timber.d("New LAN connection from $remoteAddr")

                        // Each connection runs in its own child scope for structured concurrency
                        launch {
                            handleIncomingConnection(clientSocket, remoteAddr)
                        }
                    } catch (_: SocketTimeoutException) {
                        // Timeout on accept() — loop back to check isActive
                    } catch (e: CancellationException) {
                        throw e
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
        // Close server socket synchronously (cancelled scope handles the loop)
        runCatching { serverSocket?.close() }
        serverSocket = null
        _connectionState.value = LanConnectionState.DISCONNECTED
        Timber.i("LAN server stopped")
    }

    override fun setLocalFingerprint(fingerprint: String) {
        localFingerprint = fingerprint
    }

    // ── Discovery ──────────────────────────────────────────────────

    override fun startWiFiDirectDiscovery() {
        wifiP2pManager =
            context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                ?: run {
                    Timber.w("WiFi Direct not available on this device")
                    return
                }
        wifiP2pChannel =
            wifiP2pManager!!.initialize(context, context.mainLooper, null)
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

                synchronized(this) {
                    wifiDirectPeers.clear()
                    wifiDirectPeers.addAll(peers)
                }
                refreshMergedPeers()
            }
        }

        wifiDirectReceiver.onConnectionChanged = { connected ->
            if (connected) {
                wifiP2pManager?.requestConnectionInfo(wifiP2pChannel) { info ->
                    // Both group owner and clients should connect to the
                    // GO's IP. The group owner hosts the TCP server.
                    val goAddress = info.groupOwnerAddress
                    if (goAddress != null) {
                        val ip = goAddress.hostAddress ?: return@requestConnectionInfo
                        serverScope.launch {
                            if (!info.isGroupOwner) {
                                // Connect to the group owner's server
                                connectDirect(ip, DEFAULT_PORT)
                            }
                            // If we ARE the group owner, the other peer will connect to us.
                            // Our server is already listening from startListening().
                        }
                    }
                }
            }
        }

        discoverPeersPeriodic()
        Timber.i("WiFi Direct discovery started")
    }

    override fun stopWiFiDirectDiscovery() {
        wifiDirectDiscoveryJob?.cancel()
        wifiP2pManager?.stopPeerDiscovery(wifiP2pChannel, null)
    }

    /**
     * Periodically re-discover peers to keep the list fresh.
     */
    private fun discoverPeersPeriodic() {
        wifiDirectDiscoveryJob?.cancel()
        wifiDirectDiscoveryJob = serverScope.launch {
            while (isActive) {
                discoverPeers()
                delay(10_000) // Every 10 seconds
            }
        }
    }

    override fun startMdnsDiscovery() {
        mdnsDiscoveryJob?.cancel()
        mdnsDiscoveryJob = serverScope.launch {
            try {
                val localIp = getLocalAddresses().firstOrNull() ?: return@launch
                jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))
                Timber.i("mDNS started on $localIp")

                jmdns?.addServiceListener(
                    MDNS_SERVICE_TYPE,
                    object : javax.jmdns.ServiceListener {
                        override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                            jmdns?.requestServiceInfo(
                                MDNS_SERVICE_TYPE,
                                event.name,
                            )
                        }

                        override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                            synchronized(this@LanTransportImpl) {
                                mDNSPeers.removeAll { it.deviceId == event.name }
                            }
                            refreshMergedPeers()
                        }

                        override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                            val addresses = event.info.inetAddresses
                            if (addresses.isNotEmpty()) {
                                val addr = addresses.first()
                                val peer = LanPeer(
                                    displayName = event.name.removePrefix("ZC-"),
                                    ipAddress = addr.hostAddress ?: return,
                                    port = event.info.port,
                                    discoveryMethod = "mdns",
                                    deviceId = event.name,
                                )
                                synchronized(this@LanTransportImpl) {
                                    mDNSPeers.removeAll { it.deviceId == event.name }
                                    mDNSPeers.add(peer)
                                }
                                refreshMergedPeers()
                                Timber.i("mDNS peer: ${peer.displayName} @ ${peer.ipAddress}")
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "mDNS discovery error")
            }
        }
    }

    override fun stopMdnsDiscovery() {
        mdnsDiscoveryJob?.cancel()
        runCatching {
            jmdns?.unregisterAllServices()
            jmdns?.close()
            jmdns = null
        }
    }

    // ── Connection ─────────────────────────────────────────────────

    override suspend fun connectDirect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.connect(InetSocketAddress(ipAddress, port), 5000)

                val key = "$ipAddress:$port"
                activeSockets[key] = socket

                // Send fingerprint handshake
                sendFingerprint(socket)

                _connectionState.value = LanConnectionState.CONNECTED
                Timber.i("Connected to $ipAddress:$port (handshake sent)")

                // Start reading from this socket in the background
                serverScope.launch {
                    readFromConnectedSocket(socket, key, ipAddress)
                }

                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to $ipAddress:$port")
                false
            }
        }
    }

    // ── Data Transfer ──────────────────────────────────────────────

    override suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int) {
        withContext(Dispatchers.IO) {
            val key = "$ipAddress:$port"
            val existingSocket = activeSockets[key]

            if (existingSocket != null && existingSocket.isConnected && !existingSocket.isClosed) {
                sendWithFraming(existingSocket, data)
            } else {
                // Short-lived connection
                Socket().use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.connect(InetSocketAddress(ipAddress, port), 5000)

                    // Send fingerprint, then framed data
                    sendFingerprint(socket)
                    sendWithFraming(socket, data)
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
                        .filter {
                            !it.isLoopbackAddress &&
                            it is java.net.Inet4Address
                        }
                        .map { it.hostAddress ?: "" }
                        .filter { it.isNotBlank() }
                } ?: emptyList()
            } catch (e: Exception) {
                Timber.w(e, "Failed to get local addresses")
                emptyList()
            }
        }
    }

    // ── PIN Code ───────────────────────────────────────────────────

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
                val serviceName = "ZC-${pin}-${android.os.Build.MODEL.replace(" ", "-").take(20)}"
                val serviceInfo = javax.jmdns.ServiceInfo.create(
                    MDNS_SERVICE_TYPE,
                    serviceName,
                    DEFAULT_PORT,
                    "ZeroGram PIN:$pin",
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
                Timber.d("PIN lookup '$pin': found ${services.size} mDNS service(s)")

                for (svc in services) {
                    if (svc.name.startsWith(prefix)) {
                        val info = jmdns?.getServiceInfo(
                            MDNS_SERVICE_TYPE,
                            svc.name,
                            3000,
                        )
                        val addresses = info?.inetAddresses
                        if (addresses != null && addresses.isNotEmpty()) {
                            val addr = addresses.first()
                            val peer = LanPeer(
                                displayName = svc.name
                                    .removePrefix("ZC-$pin-")
                                    .replace("-", " "),
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

    // ── Private: Framing Protocol ──────────────────────────────────

    /**
     * Send data with a 4-byte big-endian length prefix.
     *
     * Format: | length (4 bytes, big-endian) | payload |
     */
    private fun sendWithFraming(socket: Socket, data: ByteArray) {
        val lengthBytes = ByteArray(LENGTH_FIELD_SIZE)
        lengthBytes[0] = ((data.size shr 24) and 0xFF).toByte()
        lengthBytes[1] = ((data.size shr 16) and 0xFF).toByte()
        lengthBytes[2] = ((data.size shr 8) and 0xFF).toByte()
        lengthBytes[3] = (data.size and 0xFF).toByte()

        val output = socket.getOutputStream()
        // Write length + payload in one operation to avoid fragmentation
        val combined = lengthBytes + data
        output.write(combined)
        output.flush()
        Timber.d("Sent ${data.size}B (framed) to ${socket.inetAddress.hostAddress}")
    }

    /**
     * Read exactly [n] bytes from the input stream, blocking if needed.
     * Returns null if EOF is reached before [n] bytes.
     */
    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buffer = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buffer, offset, n - offset)
            if (read == -1) return null
            offset += read
        }
        return buffer
    }

    /**
     * Read a length-prefixed message from a socket.
     * Returns null on EOF or if the length exceeds MAX_PAYLOAD_SIZE.
     */
    private fun readFramedMessage(input: InputStream): ByteArray? {
        // Read 4-byte length prefix
        val lengthBuf = readExact(input, LENGTH_FIELD_SIZE) ?: return null
        val length = ((lengthBuf[0].toInt() and 0xFF) shl 24) or
                ((lengthBuf[1].toInt() and 0xFF) shl 16) or
                ((lengthBuf[2].toInt() and 0xFF) shl 8) or
                (lengthBuf[3].toInt() and 0xFF)

        if (length < 0 || length > MAX_PAYLOAD_SIZE) {
            Timber.w("Invalid payload length: $length — rejecting message")
            // Drain the claimed bytes to stay in sync
            input.skip(length.toLong())
            return null
        }

        // Read payload
        return readExact(input, length)
    }

    // ── Private: Connection Handling ───────────────────────────────

    /**
     * Send the local fingerprint to a newly connected socket.
     * This is the first thing sent after TCP connects.
     */
    private fun sendFingerprint(socket: Socket) {
        val fp = localFingerprint
            .take(FINGERPRINT_LEN)
            .padEnd(FINGERPRINT_LEN, '0')
            .toByteArray(Charsets.UTF_8)
        socket.getOutputStream().write(fp)
        socket.getOutputStream().flush()
        Timber.d("Fingerprint handshake sent to ${socket.inetAddress.hostAddress}")
    }

    /**
     * Handle an incoming TCP connection as the server.
     * Reads the fingerprint header, then continuously reads
     * length-prefixed messages.
     */
    private suspend fun handleIncomingConnection(
        socket: Socket,
        remoteAddr: String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream()
                val senderIp = socket.inetAddress.hostAddress

                // Read fingerprint header (64 bytes)
                val fpBuf = readExact(input, FINGERPRINT_LEN)
                if (fpBuf == null) {
                    Timber.w("Connection from $remoteAddr closed before fingerprint")
                    return@withContext
                }

                val fingerprint = String(fpBuf, Charsets.UTF_8).trimEnd('0')
                Timber.i("Incoming connection: fingerprint=$fingerprint @ $remoteAddr")

                // Read length-prefixed messages in a loop
                while (isActive && !socket.isClosed) {
                    val data = readFramedMessage(input) ?: break
                    _incomingData.send(
                        LanIncoming(fingerprint, data, senderIp)
                    )
                    Timber.d("Received ${data.size}B from $fingerprint @ $senderIp")
                }
            } catch (_: SocketTimeoutException) {
                Timber.d("Socket timeout from $remoteAddr — closing idle connection")
            } catch (_: EOFException) {
                Timber.d("Connection closed by peer: $remoteAddr")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "Error reading from LAN connection $remoteAddr")
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Read messages from a socket we connected to (client side).
     */
    private suspend fun readFromConnectedSocket(
        socket: Socket,
        key: String,
        senderIp: String,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val input = socket.getInputStream()
                // On the client side, we already sent our fingerprint,
                // so we read the peer's fingerprint first
                val fpBuf = readExact(input, FINGERPRINT_LEN)
                val fingerprint = if (fpBuf != null) {
                    String(fpBuf, Charsets.UTF_8).trimEnd('0')
                } else {
                    "unknown"
                }

                while (isActive && !socket.isClosed) {
                    val data = readFramedMessage(input) ?: break
                    _incomingData.send(
                        LanIncoming(fingerprint, data, senderIp)
                    )
                    Timber.d("Received ${data.size}B from $fingerprint @ $senderIp")
                }
            } catch (_: SocketTimeoutException) {
                Timber.d("Socket timeout — closing idle connection $key")
            } catch (_: EOFException) {
                Timber.d("Connection closed by peer: $key")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "Error reading from socket $key")
            } finally {
                activeSockets.remove(key)
                runCatching { socket.close() }
            }
        }
    }

    // ── Private: Discovery Helpers ─────────────────────────────────

    private fun discoverPeers() {
        wifiP2pManager?.discoverPeers(
            wifiP2pChannel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Timber.d("WiFi Direct peer discovery initiated")
                }

                override fun onFailure(reason: Int) {
                    Timber.w("WiFi Direct peer discovery failed: reason=$reason")
                }
            },
        )
    }

    private fun refreshMergedPeers() {
        val wfd: List<LanPeer>
        val mdns: List<LanPeer>
        synchronized(this) {
            wfd = wifiDirectPeers.toList()
            mdns = mDNSPeers.toList()
        }
        _discoveredPeers.value = (wfd + mdns).distinctBy {
            "${it.deviceId}:${it.ipAddress}"
        }
    }
}
