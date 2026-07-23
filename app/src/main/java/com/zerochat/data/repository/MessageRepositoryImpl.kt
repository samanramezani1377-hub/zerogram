package com.zerochat.data.repository

import com.zerochat.data.local.MessageDao
import com.zerochat.data.local.toDomain
import com.zerochat.data.local.toEntity
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.domain.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : MessageRepository {

    override suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
    }

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun updateMessageStatus(messageId: String, status: MessageStatus) {
        messageDao.updateStatus(messageId, status.name)
    }

    override suspend fun markConversationRead(conversationId: String) {
        messageDao.markConversationRead(conversationId)
    }
}
