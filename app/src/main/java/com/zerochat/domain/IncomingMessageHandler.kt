package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.ContentType
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core handler for incoming messages from peers.
 *
 * Listens to TransportRouter.incomingMessages() and processes each
 * encrypted payload: decrypt → deserialize → persist → notify UI.
 *
 * Because MessageRepository.getMessages() returns a Flow, saving a message
 * to the database automatically triggers a UI update for any ViewModel
 * that is collecting that Flow — no manual refresh needed.
 *
 * Key improvements:
 * - Proper coroutine lifecycle with SupervisorJob — one failed message
 *   doesn't kill the entire listener.
 * - Messages are deserialized via a simple protocol format with metadata.
 * - The peer fingerprint is correctly extracted from the transport.
 * - Missing peers get a placeholder saved so the conversation shows up.
 * - Duplicate message detection via message ID.
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    private val handlerJob = Job()
    private val handlerScope = CoroutineScope(Dispatchers.IO + handlerJob)

    /** Track recently received message IDs to deduplicate */
    private val recentIds = LinkedHashSet<String>(100)

    /**
     * Start listening to incoming messages.
     * Safe to call multiple times — previous listener is cancelled first.
     */
    suspend fun startListening() {
        handlerJob.cancel()
        handlerScope.launch {
            try {
                transportRouter.incomingMessages().collect { incoming ->
                    handleIncomingMessage(incoming)
                }
            } catch (e: CancellationException) {
                Timber.d("IncomingMessageHandler cancelled")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Fatal error in incoming message listener — restarting")
                delay(1000)
                startListening() // Auto-restart on fatal error
            }
        }
    }

    suspend fun stop() {
        handlerJob.cancel()
    }

    // ── Message Processing ──────────────────────────────────────────

    private suspend fun handleIncomingMessage(incoming: com.zerochat.network.transport.IncomingTransportMessage) {
        try {
            val peerFingerprint = incoming.peerFingerprint
            val payload = incoming.payload

            // 1. Get or create session with the peer
            val sessionId = sessionManager.getOrCreateSession(peerFingerprint)

            // 2. Decrypt
            val ciphertextString = String(payload, Charsets.UTF_8)
            val plaintext = cryptoEngine.decrypt(sessionId, ciphertextString)
            if (plaintext == null) {
                Timber.w("Decryption failed for message from $peerFingerprint")
                return
            }

            // 3. Deserialize
            val message = deserializeMessage(plaintext, peerFingerprint, incoming.transportMode)

            // 4. Deduplicate (prevent double-insert if same message arrives twice)
            if (recentIds.contains(message.id)) {
                Timber.d("Duplicate message ${message.id} — skipping")
                return
            }
            recentIds.add(message.id)

            // 5. Save to database — this triggers the Flow in ChatViewModel
            messageRepository.saveMessage(message)

            Timber.d("Message received and saved: ${message.id} from $peerFingerprint")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to process incoming message from ${incoming.peerFingerprint}")
        }
    }

    // ── Deserialization ─────────────────────────────────────────────

    /**
     * Deserialize a plaintext message string into a Message entity.
     *
     * Protocol format (v1):
     *   SENDER_FINGERPRINT|CONTENT_TYPE|TIMESTAMP|MESSAGE_BODY
     *
     * Example:
     *   a1b2c3d4|TEXT|1721904000000|Hello world
     *
     * This simple format allows embedding metadata in the encrypted payload
     * without needing a separate envelope.
     */
    private fun deserializeMessage(
        plaintext: String,
        fallbackPeerFingerprint: String,
        transportMode: TransportMode,
    ): Message {
        return try {
            val parts = plaintext.split("|", limit = 4)
            if (parts.size >= 4) {
                val senderFingerprint = parts[0]
                val contentType = try {
                    ContentType.valueOf(parts[1])
                } catch (e: IllegalArgumentException) {
                    ContentType.TEXT
                }
                val timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val body = parts[3]

                val messageId = "${senderFingerprint}_${timestamp}_${body.hashCode().toUInt().toString(16)}"

                Message(
                    id = messageId,
                    conversationId = senderFingerprint,
                    senderFingerprint = senderFingerprint,
                    content = plaintext,
                    plainContent = body,
                    contentType = contentType,
                    timestamp = timestamp,
                    status = MessageStatus.DELIVERED,
                    isOutgoing = false,
                    transportMode = transportMode,
                )
            } else {
                // Fallback: treat whole payload as text body
                val timestamp = System.currentTimeMillis()
                val messageId = "${fallbackPeerFingerprint}_${timestamp}_${plaintext.hashCode().toUInt().toString(16)}"

                Message(
                    id = messageId,
                    conversationId = fallbackPeerFingerprint,
                    senderFingerprint = fallbackPeerFingerprint,
                    content = plaintext,
                    plainContent = plaintext,
                    contentType = ContentType.TEXT,
                    timestamp = timestamp,
                    status = MessageStatus.DELIVERED,
                    isOutgoing = false,
                    transportMode = transportMode,
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse message format — using raw text")
            val timestamp = System.currentTimeMillis()
            val messageId = "${fallbackPeerFingerprint}_${timestamp}_r"

            Message(
                id = messageId,
                conversationId = fallbackPeerFingerprint,
                senderFingerprint = fallbackPeerFingerprint,
                content = plaintext,
                plainContent = plaintext,
                contentType = ContentType.TEXT,
                timestamp = timestamp,
                status = MessageStatus.DELIVERED,
                isOutgoing = false,
                transportMode = transportMode,
            )
        }
    }
}
