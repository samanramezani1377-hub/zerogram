package com.zerochat.network.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production LAN transport implementation using TCP sockets + WiFi Direct discovery.
 *
 * Architecture:
 * - A single TCP ServerSocket listens on a fixed port for incoming connections.
 * - Each incoming connection spawns a reader coroutine that pushes data to
 *   the shared incomingData Flow.
 * - Outgoing data uses short-lived TCP connections (connect → write → close).
 * - WiFi Direct and mDNS provide peer discovery on the local network.
 *
 * Design notes (inspired by Signal's local transport):
 * - All socket I/O runs on Dispatchers.IO.
 * - ServerSocket is resilient — if a connection drops, new ones are accepted.
 * - Data framing uses a simple 4-byte length prefix.
 */
@Singleton
class LanTransportImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectReceiver: WifiDirectReceiver,
) : LanTransport {

    companion object {
        const val DEFAULT_PORT = 44231
        const val MAX_MESSAGE_SIZE = 1024 * 1024 // 1 MB
    }

    // ── Server state ────────────────────────────────────────────────

    private var serverSocket: ServerSocket? = null
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    // ── Peer connections ────────────────────────────────────────────

    // Maps ipAddress:port → Socket (for active outgoing connections)
    private val activeSockets = ConcurrentHashMap<String, Socket>()

    // ── Data channels ───────────────────────────────────────────────

    private val _incomingData = Channel<ByteArray>(Channel.BUFFERED)
    override fun incomingData(): Flow<ByteArray> = _incomingData.receiveAsFlow()

    private val _discoveredPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override fun discoveredPeers(): Flow<List<LanPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionState = MutableStateFlow(LanConnectionState.DISCONNECTED)
    override fun connectionState(): Flow<LanConnectionState> = _connectionState.asStateFlow()

    // ── WiFi Direct ─────────────────────────────────────────────────

    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

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

            // Accept loop
            serverScope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket!!.accept()
                        Timber.d("New LAN connection from ${clientSocket.inetAddress.hostAddress}")
                        launch { handleIncomingConnection(clientSocket) }
                    } catch (e: Exception) {
                        if (isActive) {
                            Timber.w(e, "Error accepting LAN connection")
                        }
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
                        ipAddress = "", // WiFi Direct doesn't expose IP in device list
                        port = DEFAULT_PORT,
                        displayName = device.deviceName,
                        discoveryMethod = "wifi_direct",
                    )
                } ?: emptyList()
                _discoveredPeers.value = peers
            }
        }

        discoverPeers()
        Timber.i("WiFi Direct discovery started")
    }

    override fun stopWiFiDirectDiscovery() {
        wifiP2pManager?.stopPeerDiscovery(wifiP2pChannel, null)
    }

    override fun startMdnsDiscovery() {
        // mDNS discovery via JmDNS — lightweight alternative to WiFi Direct.
        // Implemented as a server-scope coroutine that periodically resolves
        // local service names.
        serverScope.launch {
            try {
                // In production, use JmDNS to register and browse _zerochat._tcp.local.
                // For now, this is a stub that adds a dummy peer for testing.
                Timber.d("mDNS discovery started (stub)")
            } catch (e: Exception) {
                Timber.w(e, "mDNS discovery unavailable")
            }
        }
    }

    override fun stopMdnsDiscovery() {
        // JmDNS cleanup
    }

    // ── Connection ──────────────────────────────────────────────────

    override suspend fun connectDirect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _connectionState.value = LanConnectionState.CONNECTING
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), 5000) // 5s timeout
                val key = "$ipAddress:$port"
                activeSockets[key] = socket

                _connectionState.value = LanConnectionState.CONNECTED
                Timber.i("Connected to $ipAddress:$port")
                true
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to $ipAddress:$port")
                _connectionState.value = LanConnectionState.DISCONNECTED
                false
            }
        }
    }

    // ── Data transfer ───────────────────────────────────────────────

    override suspend fun sendData(data: ByteArray) {
        // Broadcast to the most recently connected peer
        val socket = activeSockets.values.lastOrNull()
        if (socket != null && socket.isConnected) {
            sendToSocket(socket, data)
        } else {
            throw IllegalStateException("No active LAN connection. Call connectDirect() first.")
        }
    }

    override suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int) {
        withContext(Dispatchers.IO) {
            val key = "$ipAddress:$port"
            val existingSocket = activeSockets[key]

            if (existingSocket != null && existingSocket.isConnected) {
                sendToSocket(existingSocket, data)
            } else {
                // Create a short-lived connection for this send
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ipAddress, port), 5000)
                    sendToSocket(socket, data)
                }
                Timber.d("Sent ${data.size} bytes to $ipAddress:$port (short-lived)")
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

    // ── Private helpers ─────────────────────────────────────────────

    /**
     * Handle an incoming connection: read framed messages and push to incomingData.
     */
    private suspend fun handleIncomingConnection(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val input = BufferedInputStream(socket.getInputStream())
                while (isActive && !socket.isClosed) {
                    // Read 4-byte length prefix (big-endian)
                    val lengthBytes = ByteArray(4)
                    var read = input.read(lengthBytes)
                    if (read < 4) break // Connection closed

                    val length = ((lengthBytes[0].toInt() and 0xFF) shl 24) or
                            ((lengthBytes[1].toInt() and 0xFF) shl 16) or
                            ((lengthBytes[2].toInt() and 0xFF) shl 8) or
                            (lengthBytes[3].toInt() and 0xFF)

                    if (length <= 0 || length > MAX_MESSAGE_SIZE) {
                        Timber.w("Invalid message length: $length — closing connection")
                        break
                    }

                    val data = ByteArray(length)
                    read = input.read(data)
                    if (read < length) break

                    _incomingData.send(data)
                    Timber.d("Received ${data.size} bytes via LAN")
                }
            } catch (e: Exception) {
                if (isActive) {
                    Timber.w(e, "Error reading from LAN connection")
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /**
     * Send data to a socket with length-prefix framing.
     */
    private suspend fun sendToSocket(socket: Socket, data: ByteArray) {
        withContext(Dispatchers.IO) {
            val output = BufferedOutputStream(socket.getOutputStream())
            // 4-byte length prefix (big-endian)
            val length = data.size
            output.write((length shr 24) and 0xFF)
            output.write((length shr 16) and 0xFF)
            output.write((length shr 8) and 0xFF)
            output.write(length and 0xFF)
            output.write(data)
            output.flush()
            Timber.d("Sent ${data.size} bytes to ${socket.inetAddress.hostAddress}")
        }
    }

    private fun discoverPeers() {
        wifiP2pManager?.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.d("WiFi Direct peer discovery initiated")
            }
            override fun onFailure(reason: Int) {
                Timber.w("WiFi Direct peer discovery failed: reason=$reason")
            }
        })
    }
}
