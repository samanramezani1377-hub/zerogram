package com.zerochat.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.data.model.Peer
import com.zerochat.data.model.TransportMode
import com.zerochat.domain.PeerRepository
import com.zerochat.network.lan.LanPeer
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.transport.TransportRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

data class DiscoveryUiState(
    val peers: List<LanPeer> = emptyList(),
    val connectedPeerFingerprint: String? = null,
    val isDiscovering: Boolean = true,
    val error: String? = null,
    val myPinCode: String = "",
    val isAdvertisingPin: Boolean = false,
    val lookupPin: String = "",
    val isLookingUp: Boolean = false,
    val resolvedPeer: LanPeer? = null,
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val lanTransport: LanTransport,
    private val transportRouter: TransportRouter,
    private val peerRepository: PeerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    init {
        startDiscovery()
        observeDiscoveredPeers()
        generatePinCode()
    }

    fun startDiscovery() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isDiscovering = true, error = null) }
            try {
                lanTransport.stopWiFiDirectDiscovery()
                lanTransport.startWiFiDirectDiscovery()
                lanTransport.stopMdnsDiscovery()
                lanTransport.startMdnsDiscovery()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isDiscovering = false) }
            }
        }
    }

    fun connectToPeer(peer: LanPeer) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ip = peer.ipAddress.ifBlank {
                    _uiState.update { it.copy(error = "No IP address for this peer. Try again.") }
                    return@launch
                }
                val port = if (peer.port > 0) peer.port else com.zerochat.network.lan.LanTransportImpl.DEFAULT_PORT

                val actualFingerprint = transportRouter.connectLan(ip, port, resolveFingerprint(peer))

                savePeer(actualFingerprint, peer, ip, port)
                _uiState.update { it.copy(error = null) }
                _uiState.update { it.copy(connectedPeerFingerprint = actualFingerprint, error = null) }
                Timber.i("Connected: $actualFingerprint ($ip:$port)")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Connection failed") }
            }
        }
    }

    fun connectManually(peerIdOrIp: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ipPattern = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
                if (ipPattern.matches(peerIdOrIp)) {
                    transportRouter.connectLan(
                        peerIdOrIp,
                        Peer.DEFAULT_PORT,
                        peerIdOrIp,
                    )
                } else {
                    _uiState.update {
                        it.copy(error = "Enter a valid IP address like 192.168.1.5")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Connection failed")
                }
            }
        }
    }

    private fun generatePinCode() {
        val pin = lanTransport.getOrCreatePinCode()
        _uiState.update { it.copy(myPinCode = pin, isAdvertisingPin = true) }
    }

    fun startPinAdvertising() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                lanTransport.advertisePinCode()
                _uiState.update { it.copy(isAdvertisingPin = true, error = null) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun lookupPinCode(pin: String) {
        if (pin.length != 8 || !pin.all { it.isDigit() }) {
            _uiState.update { it.copy(error = "PIN must be exactly 8 digits") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLookingUp = true, error = null, resolvedPeer = null) }
            try {
                val peer = lanTransport.resolvePinCode(pin)
                if (peer != null) {
                    _uiState.update {
                        it.copy(isLookingUp = false, resolvedPeer = peer, lookupPin = pin)
                    }
                    connectToPeer(peer)
                } else {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            error = "No device with PIN $pin.\nBoth devices must be on same WiFi."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLookingUp = false, error = e.message) }
            }
        }
    }

    fun updateLookupPin(pin: String) {
        _uiState.update {
            it.copy(lookupPin = pin.take(8).filter { it.isDigit() }, resolvedPeer = null)
        }
    }

    fun resolveFingerprint(peer: LanPeer): String {
        val deviceId = peer.deviceId.trim()
        if (deviceId.isNotEmpty() &&
            !deviceId.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))
        ) {
            return deviceId
        }
        return peer.ipAddress.ifBlank { "peer_${System.currentTimeMillis()}" }
    }

    private suspend fun savePeer(
        fingerprint: String,
        peer: LanPeer,
        ip: String,
        port: Int,
    ) = withContext(Dispatchers.IO) {
        try {
            val existing = peerRepository.getPeer(fingerprint)
            if (existing == null) {
                peerRepository.savePeer(
                    Peer(
                        fingerprint = fingerprint,
                        displayName = peer.displayName.ifBlank { ip },
                        ipAddress = ip,
                        port = port,
                        preferredTransport = TransportMode.LAN,
                        lastSeen = System.currentTimeMillis(),
                    )
                )
            } else {
                peerRepository.updateConnectionInfo(
                    fingerprint, ip, TransportMode.LAN, System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to save peer")
        }
    }

    private fun observeDiscoveredPeers() {
        viewModelScope.launch {
            lanTransport.discoveredPeers().collect { peers ->
                _uiState.update { it.copy(peers = peers, isDiscovering = false) }
            }
        }
    }
}
