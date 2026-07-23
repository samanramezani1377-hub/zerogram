package com.zerochat.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.crypto.IdentityKeyPair
import com.zerochat.data.model.Peer
import com.zerochat.network.lan.LanTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        initializeIdentity()
        loadLocalIp()
    }

    private fun initializeIdentity() {
        viewModelScope.launch {
            val identity = try {
                cryptoEngine.generateIdentity()
            } catch (e: Exception) {
                IdentityKeyPair(
                    publicKey = cryptoEngine.getPublicIdentityKey(),
                    fingerprint = cryptoEngine.getLocalFingerprint(),
                )
            }
            _uiState.update {
                it.copy(
                    myId = "ZC:${identity.fingerprint}",
                    isLoading = false,
                )
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
}
