package com.zerochat.domain

import com.zerochat.data.model.Peer
import com.zerochat.data.model.PeerIdentity
import com.zerochat.network.transport.DiscoveredPeer
import kotlinx.coroutines.flow.Flow

/**
 * Repository for peer contacts and identity management.
 */
interface PeerRepository {

    /** Get all saved contacts */
    fun getContacts(): Flow<List<Peer>>

    /** Add a new contact */
    suspend fun addContact(peer: Peer)

    /** Remove a contact */
    suspend fun removeContact(peerId: Long)

    /** Get a peer by fingerprint */
    suspend fun getPeerByFingerprint(fingerprint: String): Peer?

    /** Update peer's connection status */
    suspend fun updateConnectionStatus(fingerprint: String, status: com.zerochat.data.model.ConnectionStatus)

    /** Save our own identity */
    suspend fun saveMydentity(identity: PeerIdentity)

    /** Get our own identity */
    suspend fun getMydentity(): PeerIdentity?

    /** Convert a discovered LAN peer to a Peer contact */
    fun discoveredPeerToContact(discovered: DiscoveredPeer): Peer
}

/**
 * Repository for chat messages. (Simplified — in production uses Room DB)
 */
interface MessageRepository {

    /** Save a message to local DB */
    suspend fun saveMessage(message: com.zerochat.data.model.Message)

    /** Get all messages for a conversation */
    fun getMessages(conversationId: String): Flow<List<com.zerochat.data.model.Message>>

    /** Update message delivery status */
    suspend fun updateMessageStatus(messageId: String, status: com.zerochat.data.model.MessageStatus)

    /** Mark all messages in a conversation as read */
    suspend fun markConversationRead(conversationId: String)
}
