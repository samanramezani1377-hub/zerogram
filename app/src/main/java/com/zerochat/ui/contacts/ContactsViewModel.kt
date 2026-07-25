package com.zerochat.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.Peer
import com.zerochat.domain.PeerRepository
import com.zerochat.network.lan.LanTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ContactsUiState(
    val contacts: List<Peer> = emptyList(),
    val myId: String = "Initializing...",
    val myIp: String = "",
    val isLoading: Boolean = true,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val lanTransport: LanTransport,
    private val peerRepository: PeerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        initializeIdentity()
        loadLocalIp()
        observeContacts()
    }

    private fun initializeIdentity() {
        viewModelScope.launch {
            try {
                cryptoEngine.generateIdentity()
                val fingerprint = cryptoEngine.getLocalFingerprint()
                val publicKey = cryptoEngine.getPublicIdentityKey()

                _uiState.update {
                    it.copy(
                        myId = "ZC:$fingerprint",
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize identity")
                _uiState.update {
                    it.copy(
                        myId = "ZC:${cryptoEngine.getLocalFingerprint()}",
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun loadLocalIp() {
        viewModelScope.launch {
            val addresses = lanTransport.getLocalAddresses()
            val ip = addresses.firstOrNull() ?: "Not connected"
            _uiState.update { it.copy(myIp = ip) }
        }
    }

    /**
     * Observe contacts from the database via PeerRepository.
     *
     * Previously the contact list was never populated because
     * ContactsViewModel didn't read from PeerRepository. Now it
     * reactively observes all saved peers.
     */
    private fun observeContacts() {
        viewModelScope.launch {
            try {
                peerRepository.getAllPeers().collect { peers ->
                    _uiState.update { it.copy(contacts = peers) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to observe contacts")
            }
        }
    }
}
