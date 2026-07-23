package com.zerochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.domain.MessageRepository
import com.zerochat.domain.SendMessageUseCase
import com.zerochat.domain.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val peerName: String = "Unknown",
    val messages: List<Message> = emptyList(),
    val transportMode: TransportMode = TransportMode.LAN,
    val isConnected: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageRepository: MessageRepository,
    private val cryptoEngine: CryptoEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var peerFingerprint: String = ""

    fun initialize(peerFingerprint: String) {
        this.peerFingerprint = peerFingerprint
        loadMessages()
        _uiState.update { it.copy(peerName = peerFingerprint.take(12)) }
    }

    fun sendMessage(text: String) {
        viewModelScope.launch {
            try {
                val message = sendMessageUseCase(peerFingerprint, text)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send: ${e.message}") }
            }
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            messageRepository.getMessages(peerFingerprint).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }
}
