package com.zerochat.data.profile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure internal storage for profile images.
 *
 * All images are stored in the app's internal directory, never
 * in shared/external storage. No filesystem paths are leaked.
 *
 * Directory: /data/data/com.zerochat/files/profile/
 *
 * Each image gets a UUID-based filename to prevent guessing.
 */
@Singleton
class ProfileImageStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        private const val PROFILE_DIR = "profile"
        private const val FILE_EXTENSION = ".webp"
    }

    private val profileDir: File
        get() = File(context.filesDir, PROFILE_DIR).also { it.mkdirs() }

    /**
     * Save processed image bytes to internal storage.
     * Returns the relative path (e.g., "profile/abc123.webp") for DB storage.
     */
    suspend fun save(bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val filename = "${UUID.randomUUID().toString().replace("-", "")}$FILE_EXTENSION"
        val file = File(profileDir, filename)
        file.writeBytes(bytes)
        Timber.i("Profile image saved: ${file.absolutePath} (${bytes.size}B)")
        "$PROFILE_DIR/$filename"
    }

    /**
     * Load profile image bytes by relative path.
     */
    suspend fun load(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, relativePath)
        if (!file.exists()) {
            Timber.w("Profile image not found: $relativePath")
            return@withContext null
        }
        file.readBytes()
    }

    /**
     * Delete a profile image file.
     */
    suspend fun delete(relativePath: String?): Boolean = withContext(Dispatchers.IO) {
        if (relativePath == null) return@withContext true
        val file = File(context.filesDir, relativePath)
        val deleted = file.delete()
        if (deleted) {
            Timber.i("Profile image deleted: $relativePath")
        }
        deleted
    }

    /**
     * Delete all profile images in the directory.
     */
    suspend fun deleteAll(): Unit = withContext(Dispatchers.IO) {
        profileDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Get total size of profile images directory.
     */
    suspend fun getTotalSize(): Long = withContext(Dispatchers.IO) {
        profileDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
}
