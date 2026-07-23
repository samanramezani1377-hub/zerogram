package com.zerochat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String = "",
    val conversationId: String,
    val senderFingerprint: String,
    val content: String,
    val plainContent: String = "",
    val contentType: ContentType = ContentType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENDING,
    val isOutgoing: Boolean = true,
)

enum class ContentType { TEXT, IMAGE, FILE }
enum class MessageStatus { SENDING, SENT, DELIVERED, READ, FAILED }
