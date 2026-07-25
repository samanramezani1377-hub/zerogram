package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profile WHERE fingerprint = :fingerprint")
    suspend fun getProfile(fingerprint: String): UserProfile?

    @Query("SELECT * FROM user_profile WHERE fingerprint = :fingerprint")
    fun getProfileFlow(fingerprint: String): Flow<UserProfile?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: UserProfile)

    @Query("""
        UPDATE user_profile 
        SET profile_image_id = :imageId,
            profile_image_path = :imagePath,
            profile_image_hash = :imageHash,
            updated_at = :updatedAt
        WHERE fingerprint = :fingerprint
    """)
    suspend fun updateProfileImage(
        fingerprint: String,
        imageId: String?,
        imagePath: String?,
        imageHash: String?,
        updatedAt: Long,
    )

    @Query("""
        UPDATE user_profile
        SET profile_image_id = NULL,
            profile_image_path = NULL,
            profile_image_hash = NULL,
            updated_at = :updatedAt
        WHERE fingerprint = :fingerprint
    """)
    suspend fun removeProfileImage(fingerprint: String, updatedAt: Long)

    @Query("DELETE FROM user_profile WHERE fingerprint = :fingerprint")
    suspend fun delete(fingerprint: String)
}
