package com.zerochat.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for blocked peers.
 *
 * Blocked peers cannot send us connection requests or messages.
 * They remain blocked until the user explicitly unblocks them.
 */
@Entity(tableName = "blocked_peers")
data class BlockedPeer(
    @PrimaryKey
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,                  // peer's identity fingerprint

    @ColumnInfo(name = "display_name")
    val displayName: String = "",             // peer's display name

    @ColumnInfo(name = "blocked_at")
    val blockedAt: Long = System.currentTimeMillis(),  // when they were blocked

    @ColumnInfo(name = "reason")
    val reason: String = "",                  // optional reason (e.g. "spam")
)
