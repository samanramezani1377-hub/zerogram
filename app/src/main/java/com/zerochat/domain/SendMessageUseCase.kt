package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.model.*
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Core chat use case — handles the full lifecycle of a secure message:
 *  encrypt → route → send → track delivery
 */
class SendMessageUseCase(
    private val cryptoEngine: CryptoEngine,
    private val messageRepository: MessageRepository,
    private val sessionManager: SessionManager,
    private val transportRouter: TransportRouter,
) {

    /**
     * Send a text message to a peer.
     *
     * @param peerFingerprint Recipient fingerprint
     * @param plaintext The message text
     * @return The created Message with delivery status
     */
    suspend operator fun invoke(
        peerFingerprint: String,
        plaintext: String,
    ): Message {
        // 1. Get or create session with peer
        val sessionId = sessionManager.getOrCreateSession(peerFingerprint)

        // 2. Encrypt the message
        val ciphertext = cryptoEngine.encrypt(sessionId, plaintext)

        // 3. Create message record
        val message = Message(
            id = "${cryptoEngine.getLocalFingerprint()}_${System.currentTimeMillis()}",
            conversationId = peerFingerprint,
            senderFingerprint = cryptoEngine.getLocalFingerprint(),
            content = ciphertext,
            plainContent = plaintext,
            contentType = ContentType.TEXT,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            isOutgoing = true,
        )

        // 4. Persist to local DB
        messageRepository.saveMessage(message)

        // 5. Send over the best available transport
        return try {
            val payload = serializeMessage(message)
            transportRouter.send(peerFingerprint, payload)

            // 6. Update status
            messageRepository.updateMessageStatus(message.id, MessageStatus.SENT)
            message.copy(status = MessageStatus.SENT)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send message to $peerFingerprint")
            messageRepository.updateMessageStatus(message.id, MessageStatus.FAILED)
            message.copy(status = MessageStatus.FAILED)
        }
    }

    /**
     * Serialize a message to bytes for transport.
     * Format: [4 bytes: message ID length][message ID UTF-8][4 bytes: sender length][sender UTF-8][4 bytes: content length][content]
     */
    private fun serializeMessage(message: Message): ByteArray {
        val idBytes = message.id.toByteArray(Charsets.UTF_8)
        val contentBytes = message.content.toByteArray(Charsets.UTF_8)
        val senderBytes = message.senderFingerprint.toByteArray(Charsets.UTF_8)

        // Simple TLV-like format
        val buffer = ByteArray(4 + idBytes.size + 4 + senderBytes.size + 4 + contentBytes.size)
        var pos = 0

        // Message ID
        buffer.writeInt(pos, idBytes.size); pos += 4
        System.arraycopy(idBytes, 0, buffer, pos, idBytes.size); pos += idBytes.size

        // Sender fingerprint
        buffer.writeInt(pos, senderBytes.size); pos += 4
        System.arraycopy(senderBytes, 0, buffer, pos, senderBytes.size); pos += senderBytes.size

        // Content
        buffer.writeInt(pos, contentBytes.size); pos += 4
        System.arraycopy(contentBytes, 0, buffer, pos, contentBytes.size)

        return buffer
    }

    companion object {
        /**
         * Deserialize a message from transport bytes.
         */
        fun deserializeMessage(bytes: ByteArray): Pair<String, String>? {
            try {
                var pos = 0
                // Read ID
                val idLen = bytes.readInt(pos); pos += 4
                val id = String(bytes, pos, idLen, Charsets.UTF_8); pos += idLen

                // Read sender fingerprint
                val senderLen = bytes.readInt(pos); pos += 4
                val sender = String(bytes, pos, senderLen, Charsets.UTF_8); pos += senderLen

                // Read content
                val contentLen = bytes.readInt(pos); pos += 4
                val content = String(bytes, pos, contentLen, Charsets.UTF_8)

                return Pair(sender, content)
            } catch (e: Exception) {
                Timber.e(e, "Failed to deserialize message")
                return null
            }
        }
    }
}

// ByteArray helpers
private fun ByteArray.writeInt(offset: Int, value: Int) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun ByteArray.readInt(offset: Int): Int {
    return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
}
