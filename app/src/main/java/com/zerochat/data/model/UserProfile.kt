package com.zerochat.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local user profile for the device owner.
 *
 * Unlike [Peer] which represents remote peers, UserProfile stores the
 * local device owner's display preferences. Only one row exists
 * (fingerprint = local identity fingerprint).
 *
 * Peer profile images are stored on the [Peer] entity — this table
 * is exclusively for the local user's own profile.
 */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey
    @ColumnInfo(name = "fingerprint")
    val fingerprint: String,

    @ColumnInfo(name = "display_name")
    val displayName: String = "",

    @ColumnInfo(name = "profile_image_id")
    val profileImageId: String? = null,

    /** Relative path within internal storage, e.g. "profile/abc123.webp" */
    @ColumnInfo(name = "profile_image_path")
    val profileImagePath: String? = null,

    /** SHA-256 hex hash of the profile image file */
    @ColumnInfo(name = "profile_image_hash")
    val profileImageHash: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)
