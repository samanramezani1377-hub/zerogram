package com.zerochat.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for incoming/outgoing connection requests.
 *
 * When device A wants to connect to device B, A sends a connection request.
 * B sees it in their request inbox and can Accept / Reject / Block.
 */
@Entity(tableName = "connection_requests")
data class ConnectionRequest(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "id")
    val id: String,                          // unique request ID

    @ColumnInfo(name = "sender_fingerprint")
    val senderFingerprint: String,           // fingerprint of who sent the request

    @ColumnInfo(name = "sender_display_name")
    val senderDisplayName: String = "",      // display name of sender

    @ColumnInfo(name = "sender_ip")
    val senderIp: String = "",               // sender's IP (for LAN accept)

    @ColumnInfo(name = "sender_port")
    val senderPort: Int = 44231,             // sender's port

    @ColumnInfo(name = "pin")
    val pin: String? = null,                 // PIN if this was a PIN-based request

    @ColumnInfo(name = "status")
    val status: RequestStatus = RequestStatus.PENDING,  // pending / accepted / rejected

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),   // when the request was created

    @ColumnInfo(name = "is_outgoing")
    val isOutgoing: Boolean = false,         // true if WE sent this request

    @ColumnInfo(name = "message")
    val message: String = "",                // optional greeting message
)

enum class RequestStatus {
    PENDING,      // waiting for peer response
    ACCEPTED,     // peer accepted
    REJECTED,     // peer declined
    BLOCKED,      // peer chose to block
    EXPIRED,      // timed out
}
