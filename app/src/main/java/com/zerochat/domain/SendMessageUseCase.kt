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
 *   1. Save message with PENDING status (optimistic UI)
 *   2. Encrypt using peer's session key
 *   3. Send encrypted payload via TransportRouter
 *   4. Update status to SENT (or FAILED)
 *
 * All steps run on [Dispatchers.IO]. The Message is saved BEFORE
 * encryption so that the UI Flow triggers immediately.
 */
@Singleton
class SendMessageUseCase @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    /**
     * Full send flow: create Message entity, encrypt, send, update status.
     *
     * @param peerFingerprint the recipient's identity fingerprint
     * @param plaintext the message text to send
     * @return the final Message entity with its status
     */
    suspend operator fun invoke(
        peerFingerprint: String,
        plaintext: String,
    ): Message = withContext(Dispatchers.IO) {
        val localFingerprint = cryptoEngine.getLocalFingerprint()
        val messageId = Message.createId(localFingerprint)

        val message = Message(
            id = messageId,
            conversationId = peerFingerprint,
            senderFingerprint = localFingerprint,
            plainContent = plaintext,
            content = "",
            contentType = ContentType.TEXT,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.PENDING,
            isOutgoing = true,
            transportMode = transportRouter.currentMode(peerFingerprint),
        )

        // Save with PENDING status — UI sees it immediately via Flow
        messageRepository.saveMessage(message)
        Timber.d("Message saved (PENDING): $messageId")

        // Encrypt and send
        performSend(message, plaintext)
    }

    /**
     * Send a message with a pre-created Message entity (for optimistic UI).
     *
     * The caller creates the Message with PENDING status and shows it
     * before calling this method. We handle encryption, transport, and
     * status updates.
     *
     * @param message the pre-created Message entity
     * @param plaintext the plaintext to encrypt and send
     * @return the updated Message entity
     */
    suspend fun sendOptimistic(
        message: Message,
        plaintext: String,
    ): Message = withContext(Dispatchers.IO) {
        performSend(message, plaintext)
    }

    // ── Core Send Logic ────────────────────────────────────────────

    /**
     * Core send: update status → SENDING → encrypt → transport → SENT/FAILED.
     * Works for both invoke() and sendOptimistic() paths.
     */
    private suspend fun performSend(
        message: Message,
        plaintext: String,
    ): Message {
        // 1. Mark as SENDING
        val sendingMessage = message.copy(status = MessageStatus.SENDING)
        messageRepository.saveMessage(sendingMessage)

        return try {
            // 2. Get or create encryption session
            val sessionId = sessionManager.getOrCreateSession(message.conversationId)

            // 3. Encrypt plaintext → ciphertext (Base64-encoded)
            val ciphertextBase64 = cryptoEngine.encrypt(sessionId, plaintext)
            if (ciphertextBase64 == null) {
                Timber.e("Encryption returned null for session $sessionId")
                return markFailed(sendingMessage, "Encryption returned null")
            }

            // 4. Send: Base64 ciphertext is sent as raw bytes over the transport.
            //    The receiver decrypts: Base64 decode → AES-GCM decrypt → plaintext.
            val encryptedBytes = ciphertextBase64.toByteArray(Charsets.UTF_8)

            transportRouter.send(
                peerFingerprint = message.conversationId,
                encryptedPayload = encryptedBytes,
            )

            // 5. Update to SENT with the ciphertext stored
            val sentMessage = sendingMessage.copy(
                content = ciphertextBase64,
                status = MessageStatus.SENT,
                transportMode = transportRouter.currentMode(message.conversationId),
            )
            messageRepository.saveMessage(sentMessage)
            Timber.d("Message sent: ${message.id} → ${message.conversationId} [${sentMessage.transportMode}]")
            sentMessage

        } catch (e: Exception) {
            Timber.e(e, "Failed to send message ${message.id} to ${message.conversationId}")
            markFailed(sendingMessage, e.message ?: "Unknown error")
        }
    }

    // ── Failure Handling ───────────────────────────────────────────

    /**
     * Mark a message as FAILED, preserving all original fields.
     *
     * The previous version created an incomplete Message with empty fields,
     * which corrupted the database. Now we update the status in-place and
     * return the original entity with status=FAILED for the caller.
     */
    private suspend fun markFailed(
        message: Message,
        reason: String,
    ): Message {
        messageRepository.updateStatus(message.id, MessageStatus.FAILED)
        Timber.w("Message ${message.id} marked FAILED: $reason")
        return message.copy(status = MessageStatus.FAILED)
    }
}
