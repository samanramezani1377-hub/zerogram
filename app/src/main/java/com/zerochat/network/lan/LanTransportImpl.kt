package com.zerochat.network.lan

import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.zerochat.data.model.Peer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanTransportImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wifiDirectReceiver: WifiDirectReceiver,
) : LanTransport {

    // ── WiFi Direct ──
    private val wifiP2pManager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private var wifiP2pChannel: WifiP2pManager.Channel? = null

    // ── TCP Server ──
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private val serverJob = Job()
    private val serverScope = CoroutineScope(Dispatchers.IO + serverJob)

    // ── State flows ──
    private val _discoveredPeers = MutableStateFlow<List<LanPeer>>(emptyList())
    override fun discoveredPeers(): StateFlow<List<LanPeer>> = _discoveredPeers.asStateFlow()

    private val _connectionState = MutableStateFlow(LanConnectionState.DISCONNECTED)
    override fun connectionState(): StateFlow<LanConnectionState> = _connectionState.asStateFlow()

    private val _incomingData = Channel<ByteArray>(Channel.BUFFERED)
    override fun incomingData(): Flow<ByteArray> = _incomingData.receiveAsFlow()

    // ── mDNS ──
    private var jmdns: javax.jmdns.JmDNS? = null
    private val mDNSPeers = mutableListOf<LanPeer>()

    // ── Discovery jobs ──
    private var discoveryJob: Job? = null
    private var mdnsDiscoveryJob: Job? = null

    // ── Server Lifecycle ─────────────────────────────────────────

    override fun startListening() = startListening(Peer.DEFAULT_PORT)

    private suspend fun startListening(port: Int) {
        withContext(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"))
                _connectionState.value = LanConnectionState.CONNECTED
                Timber.i("TCP server listening on port $port")

                serverScope.launch {
                    while (isActive) {
                        try {
                            val socket = serverSocket?.accept() ?: continue
                            clientSocket = socket
                            outputStream = socket.getOutputStream()
                            inputStream = socket.getInputStream()
                            _connectionState.value = LanConnectionState.CONNECTED
                            Timber.i("Client connected: ${socket.inetAddress.hostAddress}")

                            socket.use { s ->
                                val buffer = ByteArray(65536)
                                var bytesRead: Int
                                while (s.getInputStream().read(buffer).also { bytesRead = it } != -1) {
                                    val data = buffer.copyOf(bytesRead)
                                    _incomingData.send(data)
                                }
                            }
                        } catch (e: IOException) {
                            if (isActive) Timber.w(e, "Accept/read error")
                        } finally {
                            _connectionState.value = LanConnectionState.CONNECTED
                        }
                    }
                }
            } catch (e: IOException) {
                Timber.e(e, "Failed to start TCP server")
                _connectionState.value = LanConnectionState.DISCONNECTED
            }
        }
    }

    override fun stopListening() {
        serverJob.cancel()
        runCatching {
            clientSocket?.close()
            serverSocket?.close()
            outputStream?.close()
            inputStream?.close()
        }
        _connectionState.value = LanConnectionState.DISCONNECTED
        Timber.i("LAN server stopped")
    }

    // ── WiFi Direct Discovery ──────────────────────────────────

    override fun startWiFiDirectDiscovery() {
        val manager = wifiP2pManager ?: run {
            Timber.e("WiFi P2P not available on this device")
            return
        }

        wifiP2pChannel = manager.initialize(context, context.mainLooper, null)
        wifiP2pChannel?.let { channel ->
            wifiDirectReceiver.initialize(manager, channel)
        }

        context.registerReceiver(
            wifiDirectReceiver,
            WifiDirectReceiver.createIntentFilter(),
            Context.RECEIVER_NOT_EXPORTED
        )

        wifiDirectReceiver.onPeersChanged = {
            wifiP2pChannel?.let { ch ->
                manager.requestPeers(ch) { peerList ->
                    val peers = peerList?.deviceList?.mapNotNull { mapWifiP2pDevice(it) } ?: emptyList()
                    Timber.d("WiFi Direct peers: ${peers.size}")
                    updateDiscoveredPeers(peers)
                }
            }
        }

        wifiDirectReceiver.onConnectionChanged = { connected ->
            if (connected) {
                wifiP2pChannel?.let { ch ->
                    manager.requestConnectionInfo(ch) { info: WifiP2pInfo ->
                        if (info.groupOwnerAddress != null) {
                            Timber.i("Group owner: ${info.groupOwnerAddress.hostAddress}")
                            if (!info.isGroupOwner) {
                                discoveryJob?.cancel()
                                serverScope.launch {
                                    connectDirect(
                                        info.groupOwnerAddress.hostAddress ?: return@launch,
                                        Peer.DEFAULT_PORT
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        _connectionState.value = LanConnectionState.CONNECTED

        manager.discoverPeers(wifiP2pChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Timber.i("WiFi Direct discovery started")
            }
            override fun onFailure(reason: Int) {
                Timber.e("WiFi Direct discovery failed: $reason")
            }
        })
    }

    override fun stopWiFiDirectDiscovery() {
        runCatching {
            wifiP2pChannel?.let { wifiP2pManager?.stopPeerDiscovery(it, null) }
            context.unregisterReceiver(wifiDirectReceiver)
        }
    }

    // ── mDNS Discovery ─────────────────────────────────────────

    override fun startMdnsDiscovery() {
        mdnsDiscoveryJob = serverScope.launch {
            try {
                val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val localAddress = getLocalIpAddress()
                jmdns = javax.jmdns.JmDNS.create(InetAddress.getByName(localAddress))
                Timber.i("mDNS started on $localAddress")

                val serviceInfo = javax.jmdns.ServiceInfo.create(
                    "_zerochat._tcp.local.",
                    "ZeroChat-${android.os.Build.MODEL}",
                    Peer.DEFAULT_PORT,
                    "ZeroChat P2P Messenger"
                )
                jmdns?.registerService(serviceInfo)

                jmdns?.addServiceListener("_zerochat._tcp.local.", object : javax.jmdns.ServiceListener {
                    override fun serviceAdded(event: javax.jmdns.ServiceEvent) {
                        Timber.d("mDNS service added: ${event.name}")
                        jmdns?.requestServiceInfo("_zerochat._tcp.local.", event.name)
                    }
                    override fun serviceRemoved(event: javax.jmdns.ServiceEvent) {
                        Timber.d("mDNS service removed: ${event.name}")
                        mDNSPeers.removeAll { it.deviceId == event.name }
                        refreshPeerList()
                    }
                    override fun serviceResolved(event: javax.jmdns.ServiceEvent) {
                        val addresses = event.info.inetAddresses
                        if (addresses.isNotEmpty()) {
                            val addr = addresses.first()
                            val peer = LanPeer(
                                displayName = event.name,
                                ipAddress = addr.hostAddress ?: return,
                                port = event.info.port,
                                discoveryMethod = "mdns",
                                deviceId = event.name,
                            )
                            mDNSPeers.removeAll { it.deviceId == event.name }
                            mDNSPeers.add(peer)
                            refreshPeerList()
                            Timber.i("mDNS peer resolved: ${peer.displayName} @ ${peer.ipAddress}")
                        }
                    }
                })
            } catch (e: Exception) {
                Timber.e(e, "Failed to start mDNS discovery")
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

    // ── Direct Connection ──────────────────────────────────────

    override suspend fun connectDirect(ipAddress: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _connectionState.value = LanConnectionState.CONNECTING
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, port), 5000)
                clientSocket = socket
                outputStream = socket.getOutputStream()
                inputStream = socket.getInputStream()
                _connectionState.value = LanConnectionState.CONNECTED
                Timber.i("Connected to $ipAddress:$port")

                serverScope.launch {
                    socket.use { s ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        try {
                            while (s.getInputStream().read(buffer).also { bytesRead = it } != -1) {
                                val data = buffer.copyOf(bytesRead)
                                _incomingData.send(data)
                            }
                        } catch (e: IOException) {
                            Timber.w(e, "Read error from $ipAddress")
                        } finally {
                            _connectionState.value = LanConnectionState.CONNECTED
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to $ipAddress:$port")
                _connectionState.value = LanConnectionState.DISCONNECTED
                false
            }
        }
    }

    // ── Data transfer ───────────────────────────────────────────

    override suspend fun sendData(data: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                outputStream?.write(data)
                outputStream?.flush()
                Timber.d("Sent ${data.size} bytes via LAN")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send data")
                throw e
            }
        }
    }

    override suspend fun sendDataTo(data: ByteArray, ipAddress: String, port: Int) {
        withContext(Dispatchers.IO) {
            try {
                if (clientSocket?.inetAddress?.hostAddress == ipAddress && clientSocket?.isConnected == true) {
                    outputStream?.write(data)
                    outputStream?.flush()
                } else {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(ipAddress, port), 5000)
                        socket.getOutputStream().write(data)
                        socket.getOutputStream().flush()
                    }
                }
                Timber.d("Sent ${data.size} bytes to $ipAddress:$port")
            } catch (e: Exception) {
                Timber.e(e, "Failed to send data to $ipAddress:$port")
                throw e
            }
        }
    }

    // ── Utility ─────────────────────────────────────────────────

    override suspend fun getLocalAddresses(): List<String> {
        return withContext(Dispatchers.IO) {
            val addresses = mutableListOf<String>()
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
                    if (networkInterface.isUp && !networkInterface.isLoopback) {
                        networkInterface.inetAddresses.toList()
                            .filterIsInstance<Inet4Address>()
                            .filter { !it.isLoopbackAddress }
                            .forEach { addresses.add(it.hostAddress ?: "") }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get local addresses")
            }
            addresses
        }
    }

    // ── Private helpers ────────────────────────────────────────

    private fun mapWifiP2pDevice(device: WifiP2pDevice): LanPeer? {
        return LanPeer(
            displayName = device.deviceName,
            ipAddress = "", // Will be filled after connection
            deviceId = device.deviceAddress,
            discoveryMethod = "wifi_direct",
        )
    }

    private fun getLocalIpAddress(): String {
        return runCatching {
            getLocalAddresses().firstOrNull()
                ?: InetAddress.getLocalHost()?.hostAddress
                ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
    }

    private fun updateDiscoveredPeers(newPeers: List<LanPeer>) {
        _discoveredPeers.value = (newPeers + mDNSPeers).distinctBy { it.deviceId + it.ipAddress }
    }

    private fun refreshPeerList() {
        updateDiscoveredPeers(
            _discoveredPeers.value.filter { it.discoveryMethod == "wifi_direct" }
        )
    }

    // ── WiFi Direct connection helper ──────────────────────────

    suspend fun connectViaWifiDirect(device: WifiP2pDevice): Boolean {
        return withContext(Dispatchers.Main) {
            val config = WifiP2pConfig().apply {
                deviceAddress = device.deviceAddress
                groupOwnerIntent = 0
            }
            wifiP2pChannel?.let { channel ->
                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Timber.i("WiFi Direct connection initiated to ${device.deviceName}")
                    }
                    override fun onFailure(reason: Int) {
                        Timber.e("WiFi Direct connection failed: $reason")
                    }
                })
            }
            true
        }
    }
}
