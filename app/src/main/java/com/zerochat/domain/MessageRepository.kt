package com.zerochat.domain

import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over message persistence.
 *
 * The repository pattern decouples ViewModels and UseCases from
 * the concrete storage mechanism (Room). This makes testing trivial
 * and allows swapping storage implementations without touching business logic.
 */
interface MessageRepository {

    /**
     * Reactive stream of messages for a conversation.
     * The Flow emits the full list whenever the underlying table changes,
     * making it the single source of truth for the UI.
     */
    fun getMessages(conversationId: String): Flow<List<Message>>

    /**
     * Latest message for conversation preview.
     */
    fun getLatestMessage(conversationId: String): Flow<Message?>

    /**
     * Persist a message (insert or update).
     */
    suspend fun saveMessage(message: Message)

    /**
     * Batch insert — used during initial sync.
     */
    suspend fun saveMessages(messages: List<Message>)

    /**
     * Update only the delivery status of a message.
     * This is cheaper than saveMessage() which rewrites the entire row.
     */
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    /**
     * Mark all incoming messages in a conversation as READ.
     */
    suspend fun markConversationRead(conversationId: String)

    /**
     * Returns all messages that failed to send — used by a retry
     * worker (future feature).
     */
    suspend fun getFailedMessages(): List<Message>
}
