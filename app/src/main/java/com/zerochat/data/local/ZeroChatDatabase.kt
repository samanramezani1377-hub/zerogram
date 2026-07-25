package com.zerochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zerochat.data.model.Message
import com.zerochat.data.model.Peer
import com.zerochat.data.model.UserProfile

@Database(
    entities = [
        Message::class,
        Peer::class,
        UserProfile::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ZeroChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
    abstract fun userProfileDao(): UserProfileDao

    companion object {
        const val DATABASE_NAME = "zerochat.db"
    }
}
