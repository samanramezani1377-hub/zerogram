package com.zerochat.ui.discovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val lanTransport: LanTransport,
    private val transportRouter: TransportRouter,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiscoveryUiState())
    val uiState: StateFlow<DiscoveryUiState> = _uiState.asStateFlow()

    init {
        startDiscovery()
        observeDiscoveredPeers()
    }

    fun startDiscovery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDiscovering = true, error = null) }
            try {
                lanTransport.startWiFiDirectDiscovery()
                lanTransport.startMdnsDiscovery()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isDiscovering = false) }
            }
        }
    }

    fun connectToPeer(peer: LanPeer) {
        viewModelScope.launch {
            try {
                // Register the peer with TransportRouter so it knows about this connection
                val fingerprint = peer.deviceId.ifBlank { peer.ipAddress }
                transportRouter.connectLan(peer.ipAddress, peer.port, fingerprint)
                Timber.i("Connected to peer $fingerprint via LAN")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Connection failed: ${e.message}") }
            }
        }
    }

    fun connectManually(peerIdOrIp: String) {
        viewModelScope.launch {
            try {
                if (peerIdOrIp.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    val port = com.zerochat.data.model.Peer.DEFAULT_PORT
                    transportRouter.connectLan(peerIdOrIp, port, peerIdOrIp)
                    Timber.i("Manual connection to $peerIdOrIp")
                } else {
                    _uiState.update { it.copy(error = "Peer ID resolution not yet implemented") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Connection failed: ${e.message}") }
            }
        }
    }

    private fun observeDiscoveredPeers() {
        viewModelScope.launch {
            lanTransport.discoveredPeers().collect { peers ->
                _uiState.update {
                    it.copy(
                        peers = peers,
                        isDiscovering = false,
                    )
                }
            }
        }
    }
}
