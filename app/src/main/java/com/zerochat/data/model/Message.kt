package com.zerochat.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a single message in a conversation.
 *
 * Design decisions (inspired by Signal Android's message model):
 * - id is a client-generated unique string (fingerprint_timestamp_random)
 *   rather than auto-increment. This avoids ID collisions in P2P scenarios
 *   where two devices may insert the same logical message.
 * - conversationId maps to the peer's identity fingerprint.
 * - plainContent is the human-readable representation; content may hold
 *   serialized structured data in future versions.
 * - status tracks the delivery lifecycle: PENDING → SENDING → SENT → DELIVERED → READ
 * - FAILED is a terminal state for messages that couldn't be delivered.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversation_id", "timestamp"]),
        Index(value = ["conversation_id", "status"]),
    ]
)
data class Message(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    @ColumnInfo(name = "sender_fingerprint")
    val senderFingerprint: String,

    /** Encrypted payload (Base64-encoded) — null for outgoing before encryption */
    @ColumnInfo(name = "content")
    val content: String = "",

    /** Human-readable plaintext for UI display */
    @ColumnInfo(name = "plain_content")
    val plainContent: String = "",

    @ColumnInfo(name = "content_type")
    val contentType: ContentType = ContentType.TEXT,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "status")
    val status: MessageStatus = MessageStatus.PENDING,

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = true,

    @ColumnInfo(name = "transport_mode")
    val transportMode: TransportMode = TransportMode.UNKNOWN,
) {
    companion object {
        /**
         * Creates a unique message ID.
         * Format: {localFingerprintShort}_{timestamp}_{randomHex}
         */
        fun createId(localFingerprint: String): String {
            val short = localFingerprint.take(8)
            val random = kotlin.random.Random.nextInt(0x1000, 0xFFFF).toString(16)
            return "${short}_${System.currentTimeMillis()}_${random}"
        }
    }
}

enum class ContentType {
    TEXT,
    IMAGE,
    FILE,
    AUDIO,
}

enum class MessageStatus {
    /** Just created, not yet sent to transport */
    PENDING,
    /** Handed off to transport layer, awaiting confirmation */
    SENDING,
    /** Transport layer confirmed receipt by remote peer */
    SENT,
    /** Remote peer acknowledged delivery */
    DELIVERED,
    /** Remote user has read the message */
    READ,
    /** Terminal failure — will not be retried automatically */
    FAILED,
}

enum class TransportMode {
    /** No transport determined yet */
    UNKNOWN,
    /** Local network (WiFi Direct / mDNS) */
    LAN,
    /** Internet (WebRTC) */
    WAN,
}
