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
import com.zerochat.domain.PeerRepository
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
    val peerProfileImagePath: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageRepository: MessageRepository,
    private val cryptoEngine: CryptoEngine,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
    private val incomingMessageHandler: IncomingMessageHandler,
    private val peerRepository: PeerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var peerFingerprint: String = ""
    private var localFingerprint: String = ""
    private var messageCollectionJob: Job? = null
    private var peerFlowJob: Job? = null

    fun initialize(peerFingerprint: String) {
        if (this.peerFingerprint == peerFingerprint &&
            messageCollectionJob?.isActive == true
        ) return

        this.peerFingerprint = peerFingerprint
        this.localFingerprint = cryptoEngine.getLocalFingerprint()

        messageCollectionJob?.cancel()
        peerFlowJob?.cancel()

        viewModelScope.launch { sessionManager.getOrCreateSession(peerFingerprint) }
        viewModelScope.launch { incomingMessageHandler.startListening() }

        messageCollectionJob = viewModelScope.launch {
            try {
                messageRepository.getMessages(peerFingerprint).collect { messages ->
                    val relevant = messages.filter { msg ->
                        msg.conversationId == peerFingerprint ||
                        msg.senderFingerprint == peerFingerprint
                    }.sortedBy { it.timestamp }

                    _uiState.update {
                        it.copy(
                            messages = relevant,
                            isLoading = false,
                            peerName = resolvePeerName(),
                            transportMode = transportRouter.currentMode(peerFingerprint),
                            isConnected = transportRouter.currentMode(peerFingerprint) != TransportMode.UNKNOWN,
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error collecting messages")
                _uiState.update { it.copy(isLoading = false, error = "Failed to load messages") }
            }
        }

        peerFlowJob = viewModelScope.launch {
            peerRepository.getPeerFlow(peerFingerprint).collect { peer ->
                _uiState.update {
                    it.copy(
                        peerProfileImagePath = peer?.profileImagePath,
                        peerName = peer?.displayName?.ifBlank { null }
                            ?: formatPeerName(peerFingerprint),
                    )
                }
            }
        }

        viewModelScope.launch {
            try {
                val mode = transportRouter.currentMode(peerFingerprint)
                _uiState.update { it.copy(transportMode = mode, isConnected = mode != TransportMode.UNKNOWN) }
            } catch (_: Exception) {}
        }
    }

    fun sendMessage(text: String) {
        if (peerFingerprint.isBlank() || text.isBlank()) return
        viewModelScope.launch {
            try {
                val message = Message(
                    id = Message.createId(localFingerprint),
                    conversationId = peerFingerprint,
                    senderFingerprint = localFingerprint,
                    plainContent = text,
                    content = "",
                    contentType = ContentType.TEXT,
                    timestamp = System.currentTimeMillis(),
                    status = MessageStatus.PENDING,
                    isOutgoing = true,
                    transportMode = transportRouter.currentMode(peerFingerprint),
                )
                messageRepository.saveMessage(message)
                val result = sendMessageUseCase.sendOptimistic(message, text)
                _uiState.update {
                    it.copy(error = if (result.status == MessageStatus.FAILED)
                        "Not connected. Connect first, then try again." else null)
                }
            } catch (e: Exception) {
                Timber.e(e, "Send failed")
                _uiState.update { it.copy(error = "Failed: " + (e.message ?: "unknown")) }
            }
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            val failed = _uiState.value.messages.find { it.id == messageId } ?: return@launch
            messageRepository.updateStatus(messageId, MessageStatus.PENDING)
            val result = sendMessageUseCase.sendOptimistic(
                failed.copy(status = MessageStatus.PENDING), failed.plainContent
            )
            _uiState.update {
                it.copy(error = if (result.status == MessageStatus.FAILED) "Retry failed" else null)
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                messageRepository.deleteMessage(messageId)
                Timber.d("Message deleted: $messageId")
            } catch (e: Exception) {
                Timber.e(e, "Delete failed")
                _uiState.update { it.copy(error = "Failed to delete message") }
            }
        }
    }

    fun sendMedia(uriString: String) {
        if (peerFingerprint.isBlank()) return
        viewModelScope.launch {
            try {
                val fileName = uriString.substringAfterLast("/")
                sendMessage("file: $fileName")
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to send media") }
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }

    override fun onCleared() {
        super.onCleared()
        messageCollectionJob?.cancel()
        peerFlowJob?.cancel()
    }

    private fun resolvePeerName() =
        _uiState.value.peerName.let {
            if (it == "Unknown") formatPeerName(peerFingerprint) else it
        }

    private fun formatPeerName(fp: String) =
        if (fp.length >= 12) fp.take(8) + "..." + fp.takeLast(4) else fp
}
