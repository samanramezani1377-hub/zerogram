package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.ContentType
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full send-message pipeline:
 *
 *   1. Create Message entity with PENDING status (for optimistic UI)
 *   2. Save to local database (so UI shows it immediately)
 *   3. Encrypt with peer's session key
 *   4. Send via TransportRouter
 *   5. Update status to SENT (or FAILED)
 *
 * All steps run on [Dispatchers.IO]. The Message entity is saved BEFORE
 * encryption and transport so that the UI Flow triggers immediately.
 */
@Singleton
class SendMessageUseCase @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    /**
     * Send a text message to a peer.
     *
     * @param peerFingerprint the recipient's identity fingerprint
     * @param plaintext the message text to send
     * @return the saved Message entity with its final status
     */
    suspend operator fun invoke(
        peerFingerprint: String,
        plaintext: String,
    ): Message = withContext(Dispatchers.IO) {
        // 1. Create message entity with PENDING status
        val localFingerprint = cryptoEngine.getLocalFingerprint()
        val messageId = Message.createId(localFingerprint)

        val message = Message(
            id = messageId,
            conversationId = peerFingerprint,
            senderFingerprint = localFingerprint,
            plainContent = plaintext,
            content = "", // Will be filled after encryption
            contentType = ContentType.TEXT,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
            isOutgoing = true,
            transportMode = transportRouter.currentMode(peerFingerprint),
        )

        // 2. Save with PENDING status — UI sees it immediately via Flow
        messageRepository.saveMessage(message)
        Timber.d("Message saved (PENDING): $messageId")

        // 3. Update to SENDING status
        val sendingMessage = message.copy(status = MessageStatus.SENDING)
        messageRepository.saveMessage(sendingMessage)

        try {
            // 4. Get or create encryption session
            val sessionId = sessionManager.getOrCreateSession(peerFingerprint)

            // 5. Encrypt
            val ciphertext = cryptoEngine.encrypt(sessionId, plaintext)
            if (ciphertext == null) {
                Timber.e("Encryption returned null for session $sessionId")
                return@withContext markFailed(messageId, "Encryption failed")
            }

            // 6. Send via transport
            transportRouter.send(
                peerFingerprint = peerFingerprint,
                encryptedPayload = ciphertext.toByteArray(Charsets.UTF_8),
            )

            // 7. Update to SENT
            val sentMessage = sendingMessage.copy(
                content = ciphertext,
                status = MessageStatus.SENT,
                transportMode = transportRouter.currentMode(peerFingerprint),
            )
            messageRepository.saveMessage(sentMessage)
            Timber.d("Message sent: $messageId → $peerFingerprint [${sentMessage.transportMode}]")
            sentMessage

        } catch (e: Exception) {
            Timber.e(e, "Failed to send message $messageId to $peerFingerprint")
            markFailed(messageId, e.message ?: "Unknown error")
        }
    }

    /**
     * Send a message with a pre-created Message entity (for optimistic UI).
     *
     * The caller creates the Message with PENDING status and shows it in the UI
     * before calling this method. This method handles encryption, transport,
     * and status updates.
     */
    suspend fun sendOptimistic(
        message: Message,
        plaintext: String,
    ): Message = withContext(Dispatchers.IO) {
        val sendingMessage = message.copy(status = MessageStatus.SENDING)
        messageRepository.saveMessage(sendingMessage)

        try {
            val sessionId = sessionManager.getOrCreateSession(message.conversationId)
            val ciphertext = cryptoEngine.encrypt(sessionId, plaintext)
            if (ciphertext == null) {
                return@withContext markFailed(message.id, "Encryption failed")
            }

            transportRouter.send(
                peerFingerprint = message.conversationId,
                encryptedPayload = ciphertext.toByteArray(Charsets.UTF_8),
            )

            val sentMessage = sendingMessage.copy(
                content = ciphertext,
                status = MessageStatus.SENT,
                transportMode = transportRouter.currentMode(message.conversationId),
            )
            messageRepository.saveMessage(sentMessage)
            Timber.d("Message sent (optimistic): ${message.id}")
            sentMessage

        } catch (e: Exception) {
            Timber.e(e, "Failed to send message ${message.id}")
            markFailed(message.id, e.message ?: "Unknown error")
        }
    }

    // ── Private Helpers ─────────────────────────────────────────────

    private suspend fun markFailed(messageId: String, reason: String): Message {
        // Read current message to preserve its data, update only status
        // Since we don't have a direct read-by-id, we create a minimal update
        return try {
            // Update status in DB
            messageRepository.updateStatus(messageId, MessageStatus.FAILED)
            Timber.w("Message $messageId marked FAILED: $reason")
            // Return a minimal representation — the Flow will re-emit the full entity
            Message(
                id = messageId,
                conversationId = "",
                senderFingerprint = "",
                status = MessageStatus.FAILED,
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to mark message $messageId as FAILED")
            Message(
                id = messageId,
                conversationId = "",
                senderFingerprint = "",
                status = MessageStatus.FAILED,
            )
        }
    }
}
