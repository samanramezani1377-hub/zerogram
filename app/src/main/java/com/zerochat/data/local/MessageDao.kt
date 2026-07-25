package com.zerochat.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the messages table.
 *
 * Key design choices:
 * - All read methods return Flow for reactive UI updates.
 *   When a new message is inserted or a status changes, the Flow
 *   automatically emits the updated list — no manual refresh needed.
 * - Write methods are suspend functions (no Flow return) to ensure
 *   they run on a background dispatcher.
 * - ON CONFLICT REPLACE allows idempotent inserts — useful when
 *   two peers may generate the same logical message.
 */
@Dao
interface MessageDao {

    // ── Read (reactive) ───────────────────────────────────────────

    /**
     * Returns all messages for a conversation, newest first.
     * The Flow auto-updates the UI when messages change.
     */
    @Query("""
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp ASC
    """)
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>

    /**
     * Returns the most recent message for each conversation.
     * Used for the conversation list screen.
     */
    @Query("""
        SELECT * FROM messages
        WHERE conversation_id = :conversationId
        ORDER BY timestamp DESC
        LIMIT 1
    """)
    fun getLatestMessage(conversationId: String): Flow<Message?>

    /**
     * Returns messages with a specific status — useful for
     * a retry queue of FAILED messages.
     */
    @Query("""
        SELECT * FROM messages
        WHERE status = :status
        ORDER BY timestamp ASC
    """)
    suspend fun getMessagesWithStatus(status: MessageStatus): List<Message>

    // ── Write ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<Message>)

    @Update
    suspend fun updateMessage(message: Message)

    /**
     * Update only the status field of a message.
     * More efficient than updating the entire row.
     */
    @Query("""
        UPDATE messages
        SET status = :status
        WHERE id = :messageId
    """)
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    /**
     * Mark all messages in a conversation as read.
     */
    @Query("""
        UPDATE messages
        SET status = 'READ'
        WHERE conversation_id = :conversationId
          AND is_outgoing = 0
          AND status != 'READ'
    """)
    suspend fun markConversationRead(conversationId: String)

    // ── Delete ────────────────────────────────────────────────────

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}
