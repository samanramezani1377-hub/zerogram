package com.zerochat.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.Peer
import com.zerochat.domain.MessageRepository
import com.zerochat.domain.PeerRepository
import com.zerochat.domain.profile.ProfileImageRepository
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
    val myProfileImagePath: String? = null,
    val isLoading: Boolean = true,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val lanTransport: LanTransport,
    private val peerRepository: PeerRepository,
    private val profileImageRepository: ProfileImageRepository,
    private val messageRepository: MessageRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactsUiState())
    val uiState: StateFlow<ContactsUiState> = _uiState.asStateFlow()

    init {
        initializeIdentity()
        loadLocalIp()
        observeContacts()
        observeLocalProfile()
    }

    private fun initializeIdentity() {
        viewModelScope.launch {
            try {
                cryptoEngine.generateIdentity()
                val fingerprint = cryptoEngine.getLocalFingerprint()
                _uiState.update { it.copy(myId = "ZC:$fingerprint", isLoading = false) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize identity")
                _uiState.update { it.copy(myId = "ZC:${cryptoEngine.getLocalFingerprint()}", isLoading = false) }
            }
        }
    }

    private fun loadLocalIp() {
        viewModelScope.launch {
            val addresses = lanTransport.getLocalAddresses()
            _uiState.update { it.copy(myIp = addresses.firstOrNull() ?: "Not connected") }
        }
    }

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

    private fun observeLocalProfile() {
        viewModelScope.launch {
            val fp = cryptoEngine.getLocalFingerprint()
            profileImageRepository.getLocalProfile(fp).collect { profile ->
                _uiState.update { it.copy(myProfileImagePath = profile?.profileImagePath) }
            }
        }
    }

    fun deletePeerAndMessages(fingerprint: String) {
        viewModelScope.launch {
            try {
                // Delete all messages in this conversation
                messageRepository.deleteConversation(fingerprint)
                // Delete the peer from contacts
                peerRepository.deletePeer(fingerprint)
                Timber.i("Deleted peer and messages: $fingerprint")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete peer $fingerprint")
            }
        }
    }
}
