package com.zerochat.domain

import android.util.Base64
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.Message
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.flow.collect
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core handler for incoming messages from peers
 * Listens to TransportRouter and processes encrypted messages
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    /**
     * Start listening to incoming messages from the transport router
     */
    suspend fun startListening() {
        try {
            transportRouter.incomingMessages().collect { incomingMessage ->
                handleIncomingMessage(incomingMessage.peerFingerprint, incomingMessage.payload)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error listening to incoming messages")
        }
    }

    /**
     * Process an incoming encrypted message from a peer
     */
    private suspend fun handleIncomingMessage(
        peerFingerprint: String,
        encryptedPayload: ByteArray
    ) {
        try {
            // 1. Get or create session with the peer
            val sessionId = sessionManager.getOrCreateSession(peerFingerprint)

            // 2. Convert ByteArray to Base64 String
            val ciphertextString = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP)

            // 3. Decrypt the message
            val plaintext = cryptoEngine.decrypt(sessionId, ciphertextString)

            // 4. Deserialize the message
            if (plaintext != null) {
                val message = deserializeMessage(plaintext, peerFingerprint)

                // 5. Save to local database
                messageRepository.saveMessage(message)

                Timber.d("Message received from $peerFingerprint: ${message.content}")
            } else {
                Timber.w("Failed to decrypt message from $peerFingerprint")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process incoming message from $peerFingerprint")
        }
    }

    /**
     * Deserialize message from string
     */
    private fun deserializeMessage(
        plaintext: String,
        peerFingerprint: String
    ): Message {
        return Message(
            id = "${peerFingerprint}_${System.currentTimeMillis()}",
            conversationId = peerFingerprint,
            senderFingerprint = peerFingerprint,
            content = plaintext,
            plainContent = plaintext,
            contentType = com.zerochat.data.model.ContentType.TEXT,
            timestamp = System.currentTimeMillis(),
            status = com.zerochat.data.model.MessageStatus.DELIVERED,
            isOutgoing = false,
        )
    }
}
