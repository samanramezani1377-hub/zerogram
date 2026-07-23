package com.zerochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        PeerEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ZeroChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao
}
