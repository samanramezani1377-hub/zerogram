package com.zerochat.domain.profile

import com.zerochat.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over profile image operations.
 *
 * Decouples UI from the concrete storage implementation.
 * Works for both local profile and peer profile images.
 */
interface ProfileImageRepository {

    // ── Local Profile ──────────────────────────────────────────────

    /** Reactive stream of the local user's profile */
    fun getLocalProfile(fingerprint: String): Flow<UserProfile?>

    /** Set/update the local user's profile image */
    suspend fun setLocalProfileImage(
        fingerprint: String,
        imageId: String,
        imagePath: String,
        imageHash: String,
    )

    /** Remove the local user's profile image */
    suspend fun removeLocalProfileImage(fingerprint: String)

    /** Update local user's display name */
    suspend fun updateDisplayName(fingerprint: String, displayName: String)

    /** Get the local profile entity */
    suspend fun getLocalProfileOnce(fingerprint: String): UserProfile?

    // ── Peer Profile ───────────────────────────────────────────────

    /** Update a peer's profile image metadata */
    suspend fun updatePeerProfileImage(
        peerFingerprint: String,
        imageId: String?,
        imagePath: String?,
        imageHash: String?,
        updatedAt: Long,
    )
}
