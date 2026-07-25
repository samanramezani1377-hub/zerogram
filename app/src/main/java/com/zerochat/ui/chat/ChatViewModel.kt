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

/**
 * UI state for the ChatScreen.
 *
 * Messages are a reactive Flow from the database — whenever
 * a message is saved (sent or received), the Flow emits the
 * updated list and the UI recomposes automatically.
 */
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
    private var incomingListenerJob: Job? = null

    /**
     * Initialize the chat screen for a specific peer.
     *
     * This subscribes to two Flows:
     * 1. messageRepository.getMessages() — reactive list of all messages
     *    in this conversation. Updates automatically when messages are
     *    saved (both outgoing and incoming).
     * 2. transportRouter.incomingMessages() — raw incoming data that's
     *    already handled by IncomingMessageHandler, but we watch it
     *    to update the transport mode indicator.
     */
    fun initialize(peerFingerprint: String) {
        if (this.peerFingerprint == peerFingerprint && messageCollectionJob?.isActive == true) {
            return // Already initialized for this peer
        }

        this.peerFingerprint = peerFingerprint

        // Ensure a session exists
        viewModelScope.launch {
            sessionManager.getOrCreateSession(peerFingerprint)
        }

        // Cancel previous collection
        messageCollectionJob?.cancel()

        // Reactive message collection
        messageCollectionJob = viewModelScope.launch {
            try {
                messageRepository.getMessages(peerFingerprint).collect { messages ->
                    _uiState.update {
                        it.copy(
                            messages = messages,
                            isLoading = false,
                            peerName = peerFingerprint.take(12),
                            transportMode = transportRouter.currentMode(peerFingerprint),
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error collecting messages for $peerFingerprint")
                _uiState.update { it.copy(isLoading = false, error = "Failed to load messages") }
            }
        }

        // Observe transport mode changes
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(transportMode = transportRouter.currentMode(peerFingerprint))
                }
            } catch (_: Exception) {}
        }

        // Start incoming message processing if not already running
        viewModelScope.launch {
            incomingMessageHandler.startListening()
        }
    }

    /**
     * Send a text message with optimistic UI update.
     *
     * The message is saved to the database with PENDING status BEFORE
     * encryption and transport. This means:
     * 1. User taps Send
     * 2. Message appears in the chat immediately (PENDING)
     * 3. Encryption + transport happens in background
     * 4. Status updates to SENDING → SENT (or FAILED)
     *
     * At each step, messageRepository.saveMessage() triggers the Flow,
     * which updates the UI automatically — no manual list management.
     */
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

                // 3. Encrypt and send (status updated by SendMessageUseCase)
                val result = sendMessageUseCase.sendOptimistic(message, trimmed)

                // 4. If failed, surface error to UI
                if (result.status == MessageStatus.FAILED) {
                    _uiState.update {
                        it.copy(error = "Message failed to send — tap to retry")
                    }
                } else {
                    // Clear any previous error
                    _uiState.update { it.copy(error = null) }
                }

            } catch (e: Exception) {
                Timber.e(e, "Error sending message to $peerFingerprint")
                _uiState.update { it.copy(error = "Failed to send: ${e.message}") }
            }
        }
    }

    /**
     * Retry sending a failed message.
     */
    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            try {
                // Find the message in current state
                val failedMessage = _uiState.value.messages.find { it.id == messageId }
                if (failedMessage != null) {
                    sendMessageUseCase.sendOptimistic(failedMessage, failedMessage.plainContent)
                    _uiState.update { it.copy(error = null) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Retry failed for message $messageId")
                _uiState.update { it.copy(error = "Retry failed: ${e.message}") }
            }
        }
    }

    /**
     * Send a media attachment.
     */
    fun sendMedia(uriString: String) {
        if (peerFingerprint.isBlank()) return

        viewModelScope.launch {
            try {
                val fileName = uriString.substringAfterLast("/")
                val displayText = "📎 $fileName"
                sendMessage(displayText)
                Timber.d("Media shared: $fileName")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send media: ${e.message}") }
            }
        }
    }

    /**
     * Clear error message from UI.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        messageCollectionJob?.cancel()
        incomingListenerJob?.cancel()
    }
}
