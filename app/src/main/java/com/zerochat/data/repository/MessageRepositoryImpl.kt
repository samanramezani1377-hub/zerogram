package com.zerochat.data.repository

import com.zerochat.data.local.MessageDao
import com.zerochat.data.model.Message
import com.zerochat.data.model.MessageStatus
import com.zerochat.domain.MessageRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
) : MessageRepository {

    override fun getMessages(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
    }

    override fun getLatestMessage(conversationId: String): Flow<Message?> {
        return messageDao.getLatestMessage(conversationId)
    }

    override suspend fun saveMessage(message: Message) {
        messageDao.insertMessage(message)
    }

    override suspend fun saveMessages(messages: List<Message>) {
        messageDao.insertMessages(messages)
    }

    override suspend fun updateStatus(messageId: String, status: MessageStatus) {
        messageDao.updateStatus(messageId, status)
    }

    override suspend fun markConversationRead(conversationId: String) {
        messageDao.markConversationRead(conversationId)
    }

    override suspend fun getFailedMessages(): List<Message> {
        return messageDao.getMessagesWithStatus(MessageStatus.FAILED)
    }
}
