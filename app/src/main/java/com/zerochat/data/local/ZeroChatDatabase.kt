package com.zerochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zerochat.data.model.Message
import com.zerochat.data.model.Peer
import com.zerochat.data.model.UserProfile
import com.zerochat.data.model.ConnectionRequest
import com.zerochat.data.model.BlockedPeer

@Database(
    entities = [
        Message::class,
        Peer::class,
        UserProfile::class,
        ConnectionRequest::class,
        BlockedPeer::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ZeroChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun connectionRequestDao(): ConnectionRequestDao
    abstract fun blockedPeerDao(): BlockedPeerDao

    companion object {
        const val DATABASE_NAME = "zerochat.db"
    }
}
