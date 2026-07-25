package com.zerochat.domain.profile

import android.net.Uri
import com.zerochat.data.profile.ProfileImageProcessor
import com.zerochat.data.profile.ProfileImageStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileImageUseCase @Inject constructor(
    private val imageProcessor: ProfileImageProcessor,
    private val imageStorage: ProfileImageStorage,
    private val profileRepository: ProfileImageRepository,
) {

    /**
     * Process and save a profile image from a URI.
     *
     * @param fingerprint the local user's identity fingerprint
     * @param imageUri the content URI from the Photo Picker
     * @return Result.success(imageHash) or Result.failure(exception)
     */
    suspend operator fun invoke(
        fingerprint: String,
        imageUri: Uri,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // 1. Process image (single-stream read into memory, then decode)
            val processed = imageProcessor.processFromUri(imageUri)

            // 2. Save to internal storage
            val imagePath = imageStorage.save(processed.bytes)

            // 3. Generate unique image ID
            val imageId = UUID.randomUUID().toString()

            // 4. Update database
            profileRepository.setLocalProfileImage(
                fingerprint = fingerprint,
                imageId = imageId,
                imagePath = imagePath,
                imageHash = processed.hash,
            )

            Timber.i(
                "Profile image set: ${processed.width}x${processed.height}, " +
                        "${processed.sizeBytes}B, hash=${processed.hash}"
            )
            Result.success(processed.hash)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set profile image")
            // Pass the original exception so the ViewModel gets the real message
            Result.failure(e)
        }
    }

    suspend fun removeImage(fingerprint: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val profile = profileRepository.getLocalProfileOnce(fingerprint)
                profile?.profileImagePath?.let { imageStorage.delete(it) }
                profileRepository.removeLocalProfileImage(fingerprint)
                Timber.i("Profile image removed for $fingerprint")
                Result.success(Unit)
            } catch (e: Exception) {
                Timber.e(e, "Failed to remove profile image")
                Result.failure(e)
            }
        }

    suspend fun loadImage(imagePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            imageStorage.load(imagePath)
        }
}
