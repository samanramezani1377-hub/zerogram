package com.zerochat.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.zerochat.data.model.Message
import com.zerochat.data.model.Peer

/**
 * Room database for ZeroChat.
 *
 * Single database with two tables:
 * - messages: all sent and received messages
 * - peers: known peer identities and connection info
 *
 * @see <a href="https://developer.android.com/training/data-storage/room">Room Persistence Library</a>
 */
@Database(
    entities = [
        Message::class,
        Peer::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ZeroChatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun peerDao(): PeerDao

    companion object {
        const val DATABASE_NAME = "zerochat.db"
    }
}
