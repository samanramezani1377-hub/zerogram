package com.zerochat.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.ContentType
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.domain.IncomingMessageHandler
import com.zerochat.domain.MessageRepository
import com.zerochat.domain.SendMessageUseCase
import com.zerochat.domain.SessionManager
import com.zerochat.network.transport.TransportRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class ChatUiState(
    val peerName: String = "Unknown",
    val messages: List<Message> = emptyList(),
    val transportMode: TransportMode = TransportMode.UNKNOWN,
    val isConnected: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageRepository: MessageRepository,
    private val cryptoEngine: CryptoEngine,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
    private val incomingMessageHandler: IncomingMessageHandler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var peerFingerprint: String = ""
    private var messageCollectionJob: Job? = null

    fun initialize(peerFingerprint: String) {
        if (this.peerFingerprint == peerFingerprint &&
            messageCollectionJob?.isActive == true
        ) {
            return // Already initialized for this peer
        }

        this.peerFingerprint = peerFingerprint

        // Cancel previous collection
        messageCollectionJob?.cancel()

        // Ensure a session exists
        viewModelScope.launch {
            sessionManager.getOrCreateSession(peerFingerprint)
        }

        // Reactive message collection from database
        messageCollectionJob = viewModelScope.launch {
            try {
                messageRepository.getMessages(peerFingerprint).collect { messages ->
                    _uiState.update {
                        it.copy(
                            messages = messages,
                            isLoading = false,
                            peerName = formatPeerName(peerFingerprint),
                            transportMode = transportRouter.currentMode(peerFingerprint),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error collecting messages for $peerFingerprint")
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load messages")
                }
            }
        }

        // Start incoming message processing
        viewModelScope.launch {
            incomingMessageHandler.startListening()
        }

        // Observe transport mode
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(transportMode = transportRouter.currentMode(peerFingerprint))
                }
            } catch (_: Exception) {}
        }
    }

    fun sendMessage(text: String) {
        if (peerFingerprint.isBlank()) {
            _uiState.update { it.copy(error = "No peer selected") }
            return
        }

        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        viewModelScope.launch {
            try {
                // 1. Create optimistic message
                val localFingerprint = cryptoEngine.getLocalFingerprint()
                val message = Message(
                    id = Message.createId(localFingerprint),
                    conversationId = peerFingerprint,
                    senderFingerprint = localFingerprint,
                    plainContent = trimmed,
                    content = "",
                    contentType = ContentType.TEXT,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.PENDING,
                    isOutgoing = true,
                    transportMode = transportRouter.currentMode(peerFingerprint),
                )

                // 2. Show immediately in UI
                messageRepository.saveMessage(message)
                Timber.d("Optimistic message saved: ${message.id}")

                // 3. Encrypt and send
                val result = sendMessageUseCase.sendOptimistic(message, trimmed)

                // 4. Update error state based on result
                when (result.status) {
                    MessageStatus.FAILED -> {
                        _uiState.update {
                            it.copy(error = "Message failed to send — tap to retry")
                        }
                    }
                    MessageStatus.SENT -> {
                        _uiState.update { it.copy(error = null) }
                    }
                    else -> {
                        // PENDING or SENDING — still in progress
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending message to $peerFingerprint")
                _uiState.update { it.copy(error = "Failed to send: ${e.message}") }
            }
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            try {
                // Find the failed message
                val failedMessage = _uiState.value.messages.find { it.id == messageId }
                if (failedMessage == null) {
                    Timber.w("Cannot retry — message $messageId not found in UI state")
                    return@launch
                }

                // Reset to PENDING so it moves up in the UI, then try to send
                messageRepository.updateStatus(messageId, MessageStatus.PENDING)

                val result = sendMessageUseCase.sendOptimistic(
                    failedMessage.copy(status = MessageStatus.PENDING),
                    failedMessage.plainContent,
                )

                _uiState.update {
                    it.copy(
                        error = when (result.status) {
                            MessageStatus.FAILED -> "Retry failed — tap to retry"
                            else -> null
                        },
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Retry failed for message $messageId")
                _uiState.update { it.copy(error = "Retry failed: ${e.message}") }
            }
        }
    }

    fun sendMedia(uriString: String) {
        if (peerFingerprint.isBlank()) return

        viewModelScope.launch {
            try {
                val fileName = uriString.substringAfterLast("/")
                val displayText = "\uD83D\uDCCE $fileName"
                sendMessage(displayText)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = "Failed to send media: ${e.message}")
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        messageCollectionJob?.cancel()
    }

    private fun formatPeerName(fingerprint: String): String {
        // Show first 8 + last 4 chars of fingerprint
        return if (fingerprint.length >= 12) {
            "${fingerprint.take(8)}…${fingerprint.takeLast(4)}"
        } else {
            fingerprint
        }
    }
}
