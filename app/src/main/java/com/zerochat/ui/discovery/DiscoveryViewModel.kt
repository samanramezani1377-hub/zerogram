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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DiscoveryUiState(
    val peers: List<LanPeer> = emptyList(),
    val isDiscovering: Boolean = true,
    val error: String? = null,

    // ── PIN code ──────────────────────────────────────────────────
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

    // ── WiFi / mDNS Discovery ────────────────────────────────────

    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, error = null) }
            try {
                lanTransport.startWiFiDirectDiscovery()
                lanTransport.startMdnsDiscovery()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message, isDiscovering = false)
                }
            }
        }
    }

    fun connectToPeer(peer: LanPeer) {
        viewModelScope.launch {
            try {
                val fingerprint = resolveFingerprint(peer)
                savePeer(fingerprint, peer)
                transportRouter.connectLan(peer.ipAddress, peer.port, fingerprint)
                Timber.i("Connected to peer $fingerprint via LAN")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Connection failed: ${e.message}")
                }
            }
        }
    }

    fun connectManually(peerIdOrIp: String) {
        viewModelScope.launch {
            try {
                val ipPattern = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")
                if (ipPattern.matches(peerIdOrIp)) {
                    transportRouter.connectLan(
                        peerIdOrIp,
                        Peer.DEFAULT_PORT,
                        peerIdOrIp,
                    )
                    Timber.i("Manual connection to $peerIdOrIp")
                } else {
                    _uiState.update {
                        it.copy(error = "Peer ID resolution not yet implemented")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Connection failed: ${e.message}")
                }
            }
        }
    }

    // ── PIN Code ─────────────────────────────────────────────────

    private fun generatePinCode() {
        val pin = lanTransport.getOrCreatePinCode()
        _uiState.update { it.copy(myPinCode = pin, isAdvertisingPin = true) }
    }

    fun startPinAdvertising() {
        viewModelScope.launch {
            try {
                lanTransport.advertisePinCode()
                _uiState.update { it.copy(isAdvertisingPin = true, error = null) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to advertise: ${e.message}")
                }
            }
        }
    }

    fun lookupPinCode(pin: String) {
        if (pin.length != 8 || !pin.all { it.isDigit() }) {
            _uiState.update {
                it.copy(error = "PIN must be exactly 8 digits")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLookingUp = true, error = null, resolvedPeer = null)
            }

            try {
                val peer = lanTransport.resolvePinCode(pin)
                if (peer != null) {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            resolvedPeer = peer,
                            lookupPin = pin,
                        )
                    }
                    connectToPeer(peer)
                } else {
                    _uiState.update {
                        it.copy(
                            isLookingUp = false,
                            error = "No device found with PIN $pin. " +
                                    "Make sure both devices are on the same WiFi network.",
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLookingUp = false,
                        error = "PIN lookup failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun updateLookupPin(pin: String) {
        _uiState.update {
            it.copy(
                lookupPin = pin.take(8).filter { it.isDigit() },
                resolvedPeer = null,
            )
        }
    }

    /**
     * Resolve a stable fingerprint from a LanPeer.
     *
     * Priority:
     *  1. deviceId if it looks like a valid fingerprint
     *  2. IP address as fallback
     *  3. "lan_peer" as last resort
     */
    fun resolveFingerprint(peer: LanPeer): String {
        // If deviceId is a non-empty, non-MAC address, use it
        val deviceId = peer.deviceId.trim()
        if (deviceId.isNotEmpty() &&
            !deviceId.matches(Regex("[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}"))
        ) {
            return deviceId
        }
        // Fallback to IP
        return peer.ipAddress.ifBlank { "lan_peer" }
    }

    // ── Private ──────────────────────────────────────────────────

    private suspend fun savePeer(fingerprint: String, peer: LanPeer) {
        try {
            val existing = peerRepository.getPeer(fingerprint)
            if (existing == null) {
                val newPeer = Peer(
                    fingerprint = fingerprint,
                    displayName = peer.displayName.ifBlank { peer.ipAddress },
                    ipAddress = peer.ipAddress,
                    port = peer.port,
                    preferredTransport = TransportMode.LAN,
                    lastSeen = System.currentTimeMillis(),
                )
                peerRepository.savePeer(newPeer)
                Timber.i("Peer saved to contacts: $fingerprint")
            } else {
                peerRepository.updateConnectionInfo(
                    fingerprint = fingerprint,
                    ipAddress = peer.ipAddress,
                    transport = TransportMode.LAN,
                    timestamp = System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to save peer $fingerprint")
        }
    }

    private fun observeDiscoveredPeers() {
        viewModelScope.launch {
            lanTransport.discoveredPeers().collect { peers ->
                _uiState.update {
                    it.copy(peers = peers, isDiscovering = false)
                }
            }
        }
    }
}
