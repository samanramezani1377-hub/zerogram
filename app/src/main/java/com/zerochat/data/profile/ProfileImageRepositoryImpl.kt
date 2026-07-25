package com.zerochat.data.profile

import com.zerochat.data.local.PeerDao
import com.zerochat.data.local.UserProfileDao
import com.zerochat.data.model.UserProfile
import com.zerochat.domain.profile.ProfileImageRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileImageRepositoryImpl @Inject constructor(
    private val userProfileDao: UserProfileDao,
    private val peerDao: PeerDao,
) : ProfileImageRepository {

    override fun getLocalProfile(fingerprint: String): Flow<UserProfile?> =
        userProfileDao.getProfileFlow(fingerprint)

    override suspend fun getLocalProfileOnce(fingerprint: String): UserProfile? =
        userProfileDao.getProfile(fingerprint)

    override suspend fun setLocalProfileImage(
        fingerprint: String,
        imageId: String,
        imagePath: String,
        imageHash: String,
    ) {
        val now = System.currentTimeMillis()

        // Ensure a profile row exists
        val existing = userProfileDao.getProfile(fingerprint)
        if (existing == null) {
            val profile = UserProfile(
                fingerprint = fingerprint,
                profileImageId = imageId,
                profileImagePath = imagePath,
                profileImageHash = imageHash,
                updatedAt = now,
            )
            userProfileDao.insertOrUpdate(profile)
        } else {
            userProfileDao.updateProfileImage(
                fingerprint = fingerprint,
                imageId = imageId,
                imagePath = imagePath,
                imageHash = imageHash,
                updatedAt = now,
            )
        }
        Timber.i("Local profile image set: hash=$imageHash")
    }

    override suspend fun removeLocalProfileImage(fingerprint: String) {
        userProfileDao.removeProfileImage(fingerprint, System.currentTimeMillis())
        Timber.i("Local profile image removed")
    }

    override suspend fun updateDisplayName(fingerprint: String, displayName: String) {
        val existing = userProfileDao.getProfile(fingerprint)
        if (existing != null) {
            userProfileDao.insertOrUpdate(
                existing.copy(
                    displayName = displayName,
                    updatedAt = System.currentTimeMillis()
                )
            )
        } else {
            userProfileDao.insertOrUpdate(
                UserProfile(
                    fingerprint = fingerprint,
                    displayName = displayName,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    override suspend fun updatePeerProfileImage(
        peerFingerprint: String,
        imageId: String?,
        imagePath: String?,
        imageHash: String?,
        updatedAt: Long,
    ) {
        peerDao.updatePeerProfileImage(
            fingerprint = peerFingerprint,
            imageId = imageId,
            imagePath = imagePath,
            imageHash = imageHash,
            updatedAt = updatedAt,
        )
        Timber.i("Peer $peerFingerprint profile image updated: hash=$imageHash")
    }
}
