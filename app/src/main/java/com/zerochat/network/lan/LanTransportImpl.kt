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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LAN transport using TCP sockets with length-prefixed framing.
 *
 * Discovery uses BOTH WiFi Direct AND mDNS simultaneously.
 * Every device registers its presence via mDNS AND scans via WiFi Direct.
 * This ensures maximum discovery probability across different devices/routers.
 *
 * Protocol (v3):
 * ┌────────────────┬─────────────────────┬──────────────────────┐
 * │ Fingerprint    │ Payload Length       │ Payload               │
 * │ (64 bytes)     │ (4 bytes, big-endian)│ (variable)            │
 * └────────────────┴─────────────────────┴──────────────────────┘
 *
 * Handshake: both sides send their fingerprint, but client sends FIRST,
 * server responds. Client reads server's fingerprint after sending its own.
 */
@Singleton
class LanTransportImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectReceiver: WifiDirectReceiver,
) : LanTransport {

    companion object {
        const val DEFAULT_PORT = 44231
        private const val MDNS_SERVICE_TYPE = "_zerogram._tcp.local."
        const val FINGERPRINT_LEN = 64
        private const val LENGTH_FIELD_SIZE = 4
        private const val MAX_PAYLOAD_SIZE = 1_048_576 // 1 MB
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val MDNS_PREFIX = "ZG-"
    }

    // ── Server ─────────────────────────────────────────────────────

    private var serverSocket: ServerSocket? = null
    private var localFingerprint: String = "unknown"
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    // ── Active connections ─────────────────────────────────────────

    /** "ip:port" → active client socket (outgoing connections we made) */
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
            serverSocket?.soTimeout = 1000
            serverSocket?.reuseAddress = true
            _connectionState.value = LanConnectionState.CONNECTED
            Timber.i("TCP server listening on 0.0.0.0:$DEFAULT_PORT")

            serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        val remoteAddr = "${clientSocket.inetAddress.hostAddress}:${clientSocket.port}"
                        Timber.i("✓ TCP connection from $remoteAddr")
                        launch { handleIncomingConnection(clientSocket, remoteAddr) }
                    } catch (_: SocketTimeoutException) {
                        // timeout — loop
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) Timber.w(e, "TCP accept error")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start TCP server")
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
        Timber.i("TCP server stopped")
    }

    override fun setLocalFingerprint(fingerprint: String) {
        localFingerprint = fingerprint
    }

    // ═══════════════════════════════════════════════════════════════
    // DISCOVERY — WiFi Direct + mDNS (dual-channel)
    // ═══════════════════════════════════════════════════════════════

    override fun startWiFiDirectDiscovery() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Timber.w("WiFi Direct not available")
            return
        }
        wifiP2pChannel = wifiP2pManager!!.initialize(context, context.mainLooper, null)
        wifiDirectReceiver.initialize(wifiP2pManager!!, wifiP2pChannel!!)

        // When peer list changes
        wifiDirectReceiver.onPeersChanged = {
            wifiP2pManager?.requestPeers(wifiP2pChannel) { peerList ->
                val peers = peerList?.deviceList?.map { device ->
                    LanPeer(
                        deviceId = device.deviceAddress,
                        ipAddress = "", // WiFi Direct doesn't give IP until connected
                        port = DEFAULT_PORT,
                        displayName = device.deviceName.ifBlank { device.deviceAddress.take(8) },
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

        // When WiFi Direct connection is established
        wifiDirectReceiver.onConnectionChanged = { connected ->
            if (connected) {
                wifiP2pManager?.requestConnectionInfo(wifiP2pChannel) { info ->
                    val goAddress = info.groupOwnerAddress
                    if (goAddress != null) {
                        val ip = goAddress.hostAddress ?: return@requestConnectionInfo
                        serverScope.launch {
                            if (!info.isGroupOwner) {
                                // We're the client — connect to group owner
                                Timber.i("WiFi Direct client — connecting to GO at $ip")
                                connectDirect(ip, DEFAULT_PORT)
                            }
                            // If we ARE the GO, the client will connect to us
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
        runCatching { wifiP2pManager?.stopPeerDiscovery(wifiP2pChannel, null) }
    }

    private fun discoverPeersPeriodic() {
        wifiDirectDiscoveryJob?.cancel()
        wifiDirectDiscoveryJob = serverScope.launch {
            while (isActive) {
                runCatching { discoverPeers() }
                delay(5_000)
            }
        }
    }

    // ── mDNS — register ourselves AND browse for others ────────────

    override fun startMdnsDiscovery() {
        mdnsDiscoveryJob?.cancel()
        mdnsDiscoveryJob = serverScope.launch {
            try {
                val localIp = getLocalAddresses().firstOrNull()
                if (localIp == null) {
                    Timber.w("No local IP — can't start mDNS")
                    return@launch
                }

                // Create JmDNS instance bound to our IP
                jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))
                Timber.i("mDNS started on $localIp")

                // ── ADVERTISE ourselves so others can find us ────────
                val serviceName = MDNS_PREFIX + localFingerprint.take(16).replace(":", "")
                val serviceInfo = javax.jmdns.ServiceInfo.create(
                    MDNS_SERVICE_TYPE,
                    serviceName,
                    DEFAULT_PORT,
                    0, // weight
                    0, // priority
                    mapOf("fp" to localFingerprint.take(16))
                )
                jmdns?.registerService(serviceInfo)
                Timber.i("mDNS advertising as '$serviceName' on port $DEFAULT_PORT")

                // ── BROWSE for other devices ────────────────────────
                jmdns?.addServiceListener(
                    MDNS_SERVICE_TYPE,
                    object : javax.jmdns.ServiceListener {
                        override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                            // Request full info for this service
                            jmdns?.requestServiceInfo(MDNS_SERVICE_TYPE, event.name)
                        }

                        override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                            synchronized(this@LanTransportImpl) {
                                mDNSPeers.removeAll { it.deviceId == event.name }
                            }
                            refreshMergedPeers()
                            Timber.d("mDNS service removed: ${event.name}")
                        }

                        override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                            val info = event.info
                            // Skip our own service
                            if (event.name.startsWith(MDNS_PREFIX + localFingerprint.take(16).replace(":", ""))) {
                                return
                            }

                            val addresses = info.inetAddresses
                            if (addresses.isNotEmpty()) {
                                val addr = addresses.first()
                                val ip = addr.hostAddress ?: return
                                val fp = info.getPropertyString("fp") ?: ""

                                val peer = LanPeer(
                                    displayName = event.name.removePrefix(MDNS_PREFIX),
                                    ipAddress = ip,
                                    port = info.port,
                                    discoveryMethod = "mdns",
                                    deviceId = fp.ifBlank { event.name },
                                )

                                synchronized(this@LanTransportImpl) {
                                    mDNSPeers.removeAll { it.ipAddress == ip }
                                    mDNSPeers.add(peer)
                                }
                                refreshMergedPeers()
                                Timber.i("mDNS found peer: $fp @ $ip:${info.port}")
                            }
                        }
                    },
                )
            } catch (e: Exception) {
                Timber.e(e, "mDNS error")
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

    // ═══════════════════════════════════════════════════════════════
    // CONNECTION
    override suspend fun connectDirect(ipAddress: String, port: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                val key = "$ipAddress:$port"
                val existing = activeSockets[key]
                if (existing != null && existing.isConnected && !existing.isClosed) {
                    Timber.d("Already connected to $key")
                    return@withContext null
                }

                activeSockets.remove(key)

                val socket = Socket()
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ipAddress, port), 3000)

                // Send our fingerprint
                sendFingerprint(socket)

                // Read remote fingerprint response
                socket.soTimeout = 3000
                val input = socket.getInputStream()
                val fpBuf = readExact(input, FINGERPRINT_LEN)
                socket.soTimeout = SOCKET_TIMEOUT_MS

                val remoteFp = if (fpBuf != null) {
                    String(fpBuf, Charsets.UTF_8).trimEnd('0')
                } else {
                    ipAddress
                }

                Timber.i("Connected to $ipAddress:$port — remote: $remoteFp")

                _connectionState.value = LanConnectionState.CONNECTED
                activeSockets[key] = socket

                serverScope.launch {
                    readFromConnectedSocket(socket, key, ipAddress, remoteFp)
                }

                remoteFp
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to $ipAddress:$port")
                null
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DATA TRANSFER
    // ═══════════════════════════════════════════════════════════════

    override suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int) {
        withContext(Dispatchers.IO) {
            val key = "$ipAddress:$port"
            val socket = activeSockets[key]

            if (socket != null && socket.isConnected && !socket.isClosed) {
                try {
                    sendWithFraming(socket, data)
                    Timber.d("Sent ${data.size}B to $key (persistent)")
                    return@withContext
                } catch (e: Exception) {
                    Timber.w(e, "Send on existing socket failed — reconnecting")
                    activeSockets.remove(key)
                    runCatching { socket.close() }
                }
            }

            // Fallback: create a short-lived connection
            Socket().use { s ->
                s.soTimeout = SOCKET_TIMEOUT_MS
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(ipAddress, port), 3000)
                sendFingerprint(s)
                sendWithFraming(s, data)
                Timber.d("Sent ${data.size}B to $key (short-lived)")
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
                Timber.w(e, "getLocalAddresses failed")
                emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PIN CODE
    // ═══════════════════════════════════════════════════════════════

    override fun getOrCreatePinCode(): String {
        pinCode?.let { return it }
        val code = String.format("%08d", secureRandom.nextInt(100_000_000))
        pinCode = code
        Timber.i("PIN: $code")
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

                // Register a PIN-specific service name so lookup can find it
                val pinServiceName = "ZG-PIN-$pin"
                val serviceInfo = javax.jmdns.ServiceInfo.create(
                    MDNS_SERVICE_TYPE,
                    pinServiceName,
                    DEFAULT_PORT,
                    0, 0,
                    mapOf("pin" to pin)
                )
                jmdns?.registerService(serviceInfo)
                Timber.i("PIN $pin advertised as '$pinServiceName'")
            } catch (e: Exception) {
                Timber.e(e, "PIN advertise failed")
                throw e
            }
        }
        mdnsAdvertiseJob?.join()
    }

    override suspend fun resolvePinCode(pin: String): LanPeer? {
        return withContext(Dispatchers.IO) {
            var localJmDNS: javax.jmdns.JmDNS? = null
            try {
                val localIp = getLocalAddresses().firstOrNull() ?: return@withContext null
                
                // Use existing jmdns if available, otherwise create a temporary one
                localJmDNS = jmdns ?: javax.jmdns.JmDNS.create(InetAddress.getByName(localIp))

                val targetName = "ZG-PIN-$pin"
                Timber.d("PIN lookup: searching for '$targetName'")

                // First try: list already-resolved services (fast path)
                var services = localJmDNS.list(MDNS_SERVICE_TYPE) ?: emptyArray()
                Timber.d("PIN lookup: ${services.size} cached service(s) on network")
                
                for (svc in services) {
                    Timber.d("  Cached service: ${svc.name}")
                    if (svc.name == targetName || svc.name.contains(pin)) {
                        val info = localJmDNS.getServiceInfo(MDNS_SERVICE_TYPE, svc.name, 2000)
                        val addresses = info?.inetAddresses
                        if (addresses != null && addresses.isNotEmpty()) {
                            val addr = addresses.first()
                            val peer = LanPeer(
                                displayName = "PIN:$pin",
                                ipAddress = addr.hostAddress ?: continue,
                                port = info.port,
                                discoveryMethod = "pin",
                                deviceId = svc.name,
                            )
                            Timber.i("✓ PIN $pin resolved (cached) → ${peer.ipAddress}:${peer.port}")
                            return@withContext peer
                        }
                    }
                }

                // Second try: actively request service resolution (slower, but works for newly advertised services)
                Timber.d("PIN lookup: requesting active resolution for '$targetName'")
                val info = localJmDNS.getServiceInfo(MDNS_SERVICE_TYPE, targetName, 4000)
                if (info != null) {
                    val addresses = info.inetAddresses
                    if (addresses != null && addresses.isNotEmpty()) {
                        val addr = addresses.first()
                        val peer = LanPeer(
                            displayName = "PIN:$pin",
                            ipAddress = addr.hostAddress ?: "0.0.0.0",
                            port = info.port,
                            discoveryMethod = "pin",
                            deviceId = targetName,
                        )
                        Timber.i("✓ PIN $pin resolved (active) → ${peer.ipAddress}:${peer.port}")
                        return@withContext peer
                    }
                }

                Timber.w("PIN $pin not found (${services.size} cached + active query)")
                null
            } catch (e: Exception) {
                Timber.e(e, "PIN resolve failed")
                throw e
            } finally {
                // Only close if we created a temporary one
                if (jmdns == null && localJmDNS != null) {
                    try {
                        localJmDNS.close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE: Framing
    // ═══════════════════════════════════════════════════════════════

    private fun sendWithFraming(socket: Socket, data: ByteArray) {
        val len = data.size
        val header = byteArrayOf(
            ((len shr 24) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
        val output = socket.getOutputStream()
        output.write(header + data)
        output.flush()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) return null
            off += r
        }
        return buf
    }

    private fun readFramedMessage(input: InputStream): ByteArray? {
        val header = readExact(input, LENGTH_FIELD_SIZE) ?: return null
        val len = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
        if (len < 0 || len > MAX_PAYLOAD_SIZE) {
            Timber.w("Invalid payload length: $len")
            return null
        }
        return readExact(input, len)
    }

    // ═══════════════════════════════════════════════════════════════
    // PRIVATE: Connection handling
    // ═══════════════════════════════════════════════════════════════

    private fun sendFingerprint(socket: Socket) {
        val fp = localFingerprint
            .take(FINGERPRINT_LEN)
            .padEnd(FINGERPRINT_LEN, '0')
            .toByteArray(Charsets.UTF_8)
        socket.getOutputStream().write(fp)
        socket.getOutputStream().flush()
    }

    private suspend fun handleIncomingConnection(
        socket: Socket,
        remoteAddr: String,
    ) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = socket.getInputStream()
            val senderIp = socket.inetAddress.hostAddress

            // Read client's fingerprint (they send first)
            val fpBuf = readExact(input, FINGERPRINT_LEN)
            if (fpBuf == null) {
                Timber.w("No fingerprint from $remoteAddr")
                return@withContext
            }
            val fingerprint = String(fpBuf, Charsets.UTF_8).trimEnd('0')
            Timber.i("✓ Incoming: fingerprint=$fingerprint @ $senderIp")

            // Send OUR fingerprint back to the client
            sendFingerprint(socket)

            // Read messages
            while (isActive && !socket.isClosed) {
                val data = readFramedMessage(input) ?: break
                _incomingData.send(LanIncoming(fingerprint, data, senderIp))
                Timber.d("← ${data.size}B from $fingerprint")
            }
        } catch (_: SocketTimeoutException) {
            Timber.d("Idle timeout: $remoteAddr")
        } catch (_: EOFException) {
            Timber.d("Peer disconnected: $remoteAddr")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isActive) Timber.w(e, "Read error: $remoteAddr")
        } finally {
            runCatching { socket.close() }
        }
    }


    private suspend fun readFromConnectedSocket(
        socket: Socket,
        key: String,
        senderIp: String,
        remoteFingerprint: String = senderIp,
    ) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()

            while (isActive && !socket.isClosed) {
                val data = readFramedMessage(input) ?: break
                _incomingData.send(LanIncoming(remoteFingerprint, data, senderIp))
                Timber.d("← ${data.size}B from $remoteFingerprint")
            }
        } catch (_: SocketTimeoutException) {
            Timber.d("Idle timeout: $key")
        } catch (_: EOFException) {
            Timber.d("Peer disconnected: $key")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isActive) Timber.w(e, "Read error: $key")
        } finally {
            activeSockets.remove(key)
            runCatching { socket.close() }
        }
    }
    // ═══════════════════════════════════════════════════════════════
    // PRIVATE: Discovery Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun discoverPeers() {
        wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Timber.d("WiFi Direct scan OK")
            override fun onFailure(reason: Int) = Timber.w("WiFi Direct scan failed: $reason")
        })
    }

    private fun refreshMergedPeers() {
        val wfd: List<LanPeer>
        val mdns: List<LanPeer>
        synchronized(this) {
            wfd = wifiDirectPeers.toList()
            mdns = mDNSPeers.toList()
        }
        // mDNS gives real IP addresses → prefer those.
        // WiFi Direct peers with no IP are still visible (name only) for manual entry.
        val merged = mutableListOf<LanPeer>()
        val seenIps = mutableSetOf<String>()

        // mDNS first (has real IPs)
        for (p in mdns) {
            if (p.ipAddress.isNotBlank() && p.ipAddress !in seenIps) {
                merged.add(p)
                seenIps.add(p.ipAddress)
            }
        }

        // WiFi Direct — only add if we don't already have this IP from mDNS
        for (p in wfd) {
            // WiFi Direct peers may have blank IP (not yet connected)
            // but we add them so user sees device names
            if (p.ipAddress.isBlank() || p.ipAddress !in seenIps) {
                merged.add(p)
                seenIps.add(p.ipAddress)
            }
        }

        _discoveredPeers.value = merged
    }
}
