package com.zerochat.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Processes profile images for ZeroGram.
 *
 * Handles:
 * - Resizing to max 1024×1024
 * - JPEG/WebP compression with quality presets
 * - EXIF metadata stripping (GPS, camera info, etc.)
 * - SHA-256 hash generation
 * - Memory-safe progressive decoding
 *
 * All operations run on Dispatchers.IO.
 */
@Singleton
class ProfileImageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val MAX_DIMENSION = 1024
        const val MAX_FILE_SIZE_BYTES = 2 * 1024 * 1024L // 2 MB
        const val JPEG_QUALITY = 82
        const val WEBP_QUALITY = 80

        val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )
    }

    data class ProcessedImage(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val mimeType: String, // "image/webp" (we convert everything to WebP)
        val hash: String,     // SHA-256 hex
        val sizeBytes: Int,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ProcessedImage
            return bytes.contentEquals(other.bytes) &&
                    width == other.width &&
                    height == other.height &&
                    mimeType == other.mimeType &&
                    hash == other.hash &&
                    sizeBytes == other.sizeBytes
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + mimeType.hashCode()
            result = 31 * result + hash.hashCode()
            result = 31 * result + sizeBytes
            return result
        }
    }

    /**
     * Load, decode, resize, strip EXIF, compress, and hash an image from a URI.
     *
     * @param uri Content URI from Photo Picker or file picker
     * @return [ProcessedImage] with compressed bytes and metadata
     * @throws IllegalArgumentException if the image is invalid or too large
     */
    suspend fun processFromUri(uri: Uri): ProcessedImage =
        withContext(Dispatchers.IO) {
            val mimeType = context.contentResolver.getType(uri)
                ?: throw IllegalArgumentException("Unknown image type")

            if (mimeType !in SUPPORTED_MIME_TYPES) {
                throw IllegalArgumentException(
                    "Unsupported image type: $mimeType. Supported: ${SUPPORTED_MIME_TYPES.joinToString()}"
                )
            }

            // Step 1: Decode with bounds-only first to get dimensions
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IllegalStateException("Cannot open image stream")

            // Step 2: Calculate sample size for memory-efficient down-scaling
            options.apply {
                inJustDecodeBounds = false
                inSampleSize = calculateSampleSize(
                    options.outWidth, options.outHeight
                )
                inPreferredConfig = Bitmap.Config.RGB_565 // 2 bytes/pixel instead of 4
                inMutable = false
            }

            var bitmap: Bitmap? = null
            try {
                // Step 3: Decode with sample size
                context.contentResolver.openInputStream(uri)?.use { input ->
                    bitmap = BitmapFactory.decodeStream(input, null, options)
                }

                val decoded = bitmap
                    ?: throw IllegalStateException("Image decoding returned null")

                // Step 4: Resize to max dimensions if still too large
                val resized = resizeIfNeeded(decoded, MAX_DIMENSION)

                // Step 5: Strip EXIF by re-encoding — this naturally drops all metadata
                val outputStream = ByteArrayOutputStream()
                val rotation = getExifRotation(uri)

                val finalBitmap = if (rotation != 0f) {
                    val matrix = Matrix().apply { postRotate(rotation) }
                    Bitmap.createBitmap(
                        resized, 0, 0, resized.width, resized.height, matrix, true
                    )
                } else {
                    resized
                }

                // Step 6: Compress to WebP (smaller files, good quality, universal support)
                finalBitmap.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, outputStream)
                val bytes = outputStream.toByteArray()

                // Step 7: Validate final size
                if (bytes.size > MAX_FILE_SIZE_BYTES) {
                    // Re-compress with lower quality
                    outputStream.reset()
                    finalBitmap.compress(Bitmap.CompressFormat.WEBP, 60, outputStream)
                    val smaller = outputStream.toByteArray()
                    if (smaller.size > MAX_FILE_SIZE_BYTES) {
                        throw IllegalArgumentException(
                            "Image too large even after compression: ${bytes.size} bytes"
                        )
                    }
                    val hash = computeHash(smaller)
                    return@withContext ProcessedImage(
                        bytes = smaller,
                        width = finalBitmap.width,
                        height = finalBitmap.height,
                        mimeType = "image/webp",
                        hash = hash,
                        sizeBytes = smaller.size,
                    )
                }

                val hash = computeHash(bytes)
                Timber.i(
                    "Image processed: ${finalBitmap.width}x${finalBitmap.height}, " +
                            "${bytes.size}B, hash=$hash"
                )

                ProcessedImage(
                    bytes = bytes,
                    width = finalBitmap.width,
                    height = finalBitmap.height,
                    mimeType = "image/webp",
                    hash = hash,
                    sizeBytes = bytes.size,
                )
            } finally {
                bitmap?.recycle()
            }
        }

    /**
     * Validate and hash a received image from a peer.
     * Returns true if the image matches the expected hash.
     */
    fun validateImage(bytes: ByteArray, expectedHash: String): Boolean {
        val actualHash = computeHash(bytes)
        val matches = actualHash == expectedHash
        if (!matches) {
            Timber.w("Image hash mismatch: expected=$expectedHash, actual=$actualHash")
        }
        return matches
    }

    /**
     * Check if image bytes represent a valid image.
     */
    fun isValidImage(bytes: ByteArray): Boolean {
        if (bytes.size > MAX_FILE_SIZE_BYTES) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    /**
     * Extract MIME type from raw bytes.
     */
    fun detectMimeType(bytes: ByteArray): String? {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() -> "image/png"
            bytes.size >= 4 && bytes[0] == 0x52.toByte() &&
                    bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
                    bytes[3] == 0x46.toByte() -> "image/webp"
            else -> null
        }
    }

    // ── Private ─────────────────────────────────────────────────────

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var w = width
        var h = height
        while (w / 2 > MAX_DIMENSION || h / 2 > MAX_DIMENSION) {
            w /= 2
            h /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun resizeIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap

        val ratio = minOf(maxDim.toFloat() / w, maxDim.toFloat() / h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Read EXIF rotation and return the degrees to rotate.
     * Returns 0 if no rotation needed.
     */
    private fun getExifRotation(uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            Timber.w(e, "Failed to read EXIF rotation")
            0f
        }
    }

    private fun computeHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
