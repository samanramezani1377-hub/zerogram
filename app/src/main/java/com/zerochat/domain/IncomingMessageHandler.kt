package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.ContentType
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.data.model.TransportMode
import com.zerochat.network.transport.IncomingTransportMessage
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core handler for incoming messages from peers.
 *
 * Listens to TransportRouter.incomingMessages() and processes each
 * encrypted payload: decrypt → deserialize → persist → notify UI.
 *
 * Key improvements over the previous version:
 * - Thread-safe with Mutex on internal state
 * - LRU-based recentIds cache that auto-evicts old entries (no memory leak)
 * - Proper CancellationException handling
 * - startListening() is idempotent
 * - Correct WAN peer fingerprint resolution
 */
@Singleton
class IncomingMessageHandler @Inject constructor(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    private val stateMutex = Mutex()
    private var handlerJob: Job? = null

    /**
     * LRU set for message deduplication.
     * Automatically removes the eldest entry when size exceeds [MAX_RECENT_IDS].
     */
    private val recentIds = Collections.synchronizedMap(object :
        LinkedHashMap<String, Boolean>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>): Boolean {
            return size > MAX_RECENT_IDS
        }
    })

    private companion object {
        const val MAX_RECENT_IDS = 500
        const val RESTART_DELAY_MS = 2000L
    }

    /**
     * Start listening to incoming messages.
     *
     * Safe to call multiple times — the previous listener is cancelled
     * and restarted. All state access is protected by [stateMutex].
     */
    suspend fun startListening() {
        stateMutex.lock()
        val oldJob = handlerJob
        handlerJob = null
        stateMutex.unlock()

        // Cancel old job outside the lock to avoid deadlock
        oldJob?.cancelAndJoin()

        stateMutex.lock()
        val newJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                transportRouter.incomingMessages().collect { incoming ->
                    handleIncomingMessage(incoming)
                }
            } catch (e: CancellationException) {
                Timber.d("IncomingMessageHandler listener cancelled")
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Fatal error in incoming message listener — restarting...")
                delay(RESTART_DELAY_MS)
                startListening() // Auto-restart
            }
        }
        handlerJob = newJob
        stateMutex.unlock()
    }

    /**
     * Stop the listener gracefully.
     */
    suspend fun stop() {
        stateMutex.lock()
        val job = handlerJob
        handlerJob = null
        stateMutex.unlock()

        job?.cancelAndJoin()
    }

    // ── Message Processing ─────────────────────────────────────────

    private suspend fun handleIncomingMessage(
        incoming: IncomingTransportMessage,
    ) {
        try {
            val peerFingerprint = incoming.peerFingerprint
            val payload = incoming.payload

            // 1. Get or create session with the peer
            val sessionId = try {
                sessionManager.getOrCreateSession(peerFingerprint)
            } catch (e: Exception) {
                Timber.w(e, "Failed to get session for $peerFingerprint")
                return
            }

            // 2. Decrypt: payload is Base64 ciphertext from SendMessageUseCase
            val ciphertextBase64 = String(payload, Charsets.UTF_8)
            val plaintext = cryptoEngine.decrypt(sessionId, ciphertextBase64)
            if (plaintext == null) {
                Timber.w("Decryption failed for message from $peerFingerprint")
                return
            }

            // 3. Deserialize
            val message = deserializeMessage(
                plaintext,
                peerFingerprint,
                incoming.transportMode,
            )

            // 4. Deduplicate (prevent double-insert if same message arrives twice)
            if (!recentIds.containsKey(message.id)) {
                recentIds[message.id] = true
            } else {
                Timber.d("Duplicate message ${message.id} — skipping")
                return
            }

            // 5. Save to database — triggers Flow in ChatViewModel
            messageRepository.saveMessage(message)

            Timber.d("Message received and saved: ${message.id} from $peerFingerprint")

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Failed to process incoming message from ${incoming.peerFingerprint}")
        }
    }

    // ── Deserialization ────────────────────────────────────────────

    /**
     * Deserialize a plaintext message string into a Message entity.
     *
     * Protocol format (v1):
     *   SENDER_FINGERPRINT|CONTENT_TYPE|TIMESTAMP|MESSAGE_BODY
     *
     * This format allows embedding metadata in the encrypted payload
     * without needing a separate envelope layer.
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
                val contentType = runCatching {
                    ContentType.valueOf(parts[1])
                }.getOrDefault(ContentType.TEXT)
                val timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                val body = parts[3]

                val messageId = buildMessageId(senderFingerprint, timestamp, body)

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
                // Fallback: treat entire payload as text body
                val timestamp = System.currentTimeMillis()
                val messageId = buildMessageId(
                    fallbackPeerFingerprint,
                    timestamp,
                    plaintext,
                )

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

            Message(
                id = buildMessageId(fallbackPeerFingerprint, timestamp, "raw"),
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

    private fun buildMessageId(
        sender: String,
        timestamp: Long,
        body: String,
    ): String {
        val bodyHash = body.hashCode().toUInt().toString(16).take(8)
        return "${sender.take(12)}_${timestamp}_$bodyHash"
    }
}
