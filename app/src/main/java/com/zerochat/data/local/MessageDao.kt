package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE conversationId = :conversationId AND isOutgoing = 0")
    suspend fun markConversationRead(conversationId: String, status: String = MessageStatus.READ.name)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderFingerprint: String,
    val content: String,       // Encrypted
    val plainContent: String,  // Decrypted (for local display)
    val contentType: String,
    val timestamp: Long,
    val status: String,
    val isOutgoing: Boolean,
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderFingerprint = senderFingerprint,
    content = content,
    plainContent = plainContent,
    contentType = when (contentType) {
        "IMAGE" -> com.zerochat.data.model.ContentType.IMAGE
        "FILE" -> com.zerochat.data.model.ContentType.FILE
        else -> com.zerochat.data.model.ContentType.TEXT
    },
    timestamp = timestamp,
    status = try { MessageStatus.valueOf(status) } catch (_: Exception) { MessageStatus.SENT },
    isOutgoing = isOutgoing,
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderFingerprint = senderFingerprint,
    content = content,
    plainContent = plainContent,
    contentType = contentType.name,
    timestamp = timestamp,
    status = status.name,
    isOutgoing = isOutgoing,
)
