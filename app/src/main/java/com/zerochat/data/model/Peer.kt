package com.zerochat.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a known peer (contact / discovered device).
 *
 * In ZeroGram there are no accounts — identity is a cryptographic key pair.
 * A Peer is any device we've exchanged identity keys with.
 *
 * Profile image fields store the peer's profile picture metadata so
 * we can display it without re-requesting from the peer each time.
 * The actual image is stored in local cache, referenced by profileImagePath.
 */
@Entity(
    tableName = "peers",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["last_seen"]),
    ]
)
data class Peer(
    @PrimaryKey
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "display_name")
    val displayName: String = "Unknown",

    /** Last known IP address (LAN mode) */
    @ColumnInfo(name = "ip_address")
    val ipAddress: String = "",

    /** Last known port */
    @ColumnInfo(name = "port")
    val port: Int = DEFAULT_PORT,

    /** Preferred transport mode based on last successful connection */
    @ColumnInfo(name = "preferred_transport")
    val preferredTransport: TransportMode = TransportMode.UNKNOWN,

    /** Whether we have an active Signal Protocol session with this peer */
    @ColumnInfo(name = "has_session")
    val hasSession: Boolean = false,

    /** Unix timestamp of last seen (connected) time */
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long = 0L,

    /** User-verified safety number (out-of-band verification) */
    @ColumnInfo(name = "is_verified")
    val isVerified: Boolean = false,

    /** Public identity key (Base64-encoded X25519) */
    @ColumnInfo(name = "identity_key")
    val identityKey: String = "",

    // ── Profile Picture Metadata ────────────────────────────────

    /** Profile image ID from the peer — null if no profile picture */
    @ColumnInfo(name = "profile_image_id")
    val profileImageId: String? = null,

    /** Local cache path for peer's profile image */
    @ColumnInfo(name = "profile_image_path")
    val profileImagePath: String? = null,

    /** SHA-256 hash of the peer's profile image (for integrity) */
    @ColumnInfo(name = "profile_image_hash")
    val profileImageHash: String? = null,

    /** Timestamp when the profile image was last updated by the peer */
    @ColumnInfo(name = "profile_updated_at")
    val profileUpdatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_PORT = 44231
    }
}
