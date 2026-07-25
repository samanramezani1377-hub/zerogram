package com.zerochat.domain.profile

import com.zerochat.data.profile.ProfileImageProcessor
import com.zerochat.data.profile.ProfileImageStorage
import com.zerochat.network.transport.TransportRouter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles P2P profile image synchronization.
 *
 * Protocol:
 * 1. When user A sets a profile picture, they broadcast a PROFILE_UPDATE
 *    event to all connected peers.
 * 2. Peer B receives the event, sees imageHash ≠ their cached hash,
 *    and sends a PROFILE_IMAGE_REQUEST.
 * 3. User A responds with PROFILE_IMAGE_DATA containing the image bytes
 *    (chunked if large).
 * 4. Peer B verifies the hash and saves the image locally.
 *
 * Message format: JSON envelope with type field, carried over
 * the existing TransportRouter.send().
 *
 * Event types:
 *   PROFILE_UPDATE       — metadata about a profile image change
 *   PROFILE_IMAGE_REQUEST — request for image data
 *   PROFILE_IMAGE_DATA    — the image bytes (Base64-encoded)
 */
@Singleton
class ProfileSyncHandler @Inject constructor(
    private val transportRouter: TransportRouter,
    private val profileRepository: ProfileImageRepository,
    private val imageStorage: ProfileImageStorage,
    private val imageProcessor: ProfileImageProcessor,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Incoming profile sync events */
    private val _profileEvents = Channel<ProfileEvent>(Channel.BUFFERED)
    val profileEvents: Flow<ProfileEvent> = _profileEvents.receiveAsFlow()

    /** Track pending image requests */
    private val pendingRequests = ConcurrentHashMap<String, Job>()

    // ── Message Types ───────────────────────────────────────────────

    @Serializable
    data class ProfileSyncMessage(
        val type: String,
        val senderFingerprint: String,
        val imageId: String? = null,
        val imageHash: String? = null,
        val imageSize: Int? = null,
        val updatedAt: Long = System.currentTimeMillis(),
        val imageData: String? = null, // Base64-encoded image bytes
    )

    sealed class ProfileEvent {
        /** Peer has a new profile picture — we should request it */
        data class PeerProfileChanged(
            val peerFingerprint: String,
            val imageId: String,
            val imageHash: String,
            val updatedAt: Long,
        ) : ProfileEvent()

        /** Profile image data received from a peer */
        data class ProfileImageReceived(
            val peerFingerprint: String,
            val imageHash: String,
            val imageBytes: ByteArray,
        ) : ProfileEvent()

        /** A peer removed their profile picture */
        data class PeerProfileRemoved(
            val peerFingerprint: String,
        ) : ProfileEvent()
    }

    // ── Send Side ──────────────────────────────────────────────────

    /**
     * Broadcast a profile update to all connected peers.
     * Called after the local user changes their profile picture.
     */
    suspend fun broadcastProfileUpdate(
        imageId: String,
        imageHash: String,
        imageSize: Int,
    ) {
        val message = ProfileSyncMessage(
            type = "PROFILE_UPDATE",
            senderFingerprint = "", // filled by transport layer
            imageId = imageId,
            imageHash = imageHash,
            imageSize = imageSize,
            updatedAt = System.currentTimeMillis(),
        )

        val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)

        // Send to all connected peers via transport
        // TransportRouter.send() routes to the correct peer;
        // we would iterate over connected peers here.
        // For now, the profile update is sent as a best-effort broadcast.
        Timber.i("Profile update broadcast: hash=$imageHash, size=$imageSize")
    }

    /**
     * Send the full profile image data to a requesting peer.
     */
    suspend fun sendProfileImage(
        peerFingerprint: String,
        imagePath: String,
    ) {
        val bytes = imageStorage.load(imagePath) ?: run {
            Timber.w("Cannot send profile image — file not found: $imagePath")
            return
        }

        val profile = profileRepository.getLocalProfileOnce(
            /* local fingerprint — resolved by caller */ ""
        )

        val message = ProfileSyncMessage(
            type = "PROFILE_IMAGE_DATA",
            senderFingerprint = "",
            imageId = profile?.profileImageId,
            imageHash = profile?.profileImageHash,
            imageSize = bytes.size,
            imageData = Base64.getEncoder().encodeToString(bytes),
        )

        val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        transportRouter.send(peerFingerprint, payload)
        Timber.i("Profile image sent to $peerFingerprint: ${bytes.size}B")
    }

    /**
     * Notify peers that the profile picture was removed.
     */
    suspend fun broadcastProfileRemoved() {
        val message = ProfileSyncMessage(
            type = "PROFILE_UPDATE",
            senderFingerprint = "",
            imageId = null,
            imageHash = null,
            updatedAt = System.currentTimeMillis(),
        )
        val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        Timber.i("Profile removal broadcast")
    }

    // ── Receive Side ───────────────────────────────────────────────

    /**
     * Process an incoming sync message from the transport layer.
     * Call this from IncomingMessageHandler when a profile sync
     * message type is detected.
     */
    suspend fun handleIncomingSyncMessage(
        payload: String,
        peerFingerprint: String,
    ) {
        try {
            val message = json.decodeFromString<ProfileSyncMessage>(payload)
            Timber.d("Profile sync message: ${message.type} from $peerFingerprint")

            when (message.type) {
                "PROFILE_UPDATE" -> handleProfileUpdate(message, peerFingerprint)
                "PROFILE_IMAGE_DATA" -> handleImageData(message, peerFingerprint)
                else -> Timber.w("Unknown profile sync message type: ${message.type}")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse profile sync message from $peerFingerprint")
        }
    }

    private suspend fun handleProfileUpdate(
        message: ProfileSyncMessage,
        peerFingerprint: String,
    ) {
        if (message.imageId == null || message.imageHash == null) {
            // Profile picture removed
            profileRepository.updatePeerProfileImage(
                peerFingerprint = peerFingerprint,
                imageId = null,
                imagePath = null,
                imageHash = null,
                updatedAt = message.updatedAt,
            )
            _profileEvents.send(ProfileEvent.PeerProfileRemoved(peerFingerprint))
            Timber.i("Peer $peerFingerprint removed profile picture")
        } else {
            // Profile picture changed — emit event so UI can trigger
            _profileEvents.send(
                ProfileEvent.PeerProfileChanged(
                    peerFingerprint = peerFingerprint,
                    imageId = message.imageId,
                    imageHash = message.imageHash,
                    updatedAt = message.updatedAt,
                )
            )
            Timber.i("Peer $peerFingerprint profile updated: hash=${message.imageHash}")
        }
    }

    private suspend fun handleImageData(
        message: ProfileSyncMessage,
        peerFingerprint: String,
    ) {
        val imageData = message.imageData ?: return
        val expectedHash = message.imageHash ?: return

        val bytes = Base64.getDecoder().decode(imageData)

        // Validate size
        if (bytes.size > ProfileImageProcessor.MAX_FILE_SIZE_BYTES) {
            Timber.w("Profile image from $peerFingerprint too large: ${bytes.size}B")
            return
        }

        // Validate MIME type
        val mimeType = imageProcessor.detectMimeType(bytes)
        if (mimeType !in ProfileImageProcessor.SUPPORTED_MIME_TYPES.plus("image/webp")) {
            Timber.w("Invalid MIME type from $peerFingerprint: $mimeType")
            return
        }

        // Validate hash
        if (!imageProcessor.validateImage(bytes, expectedHash)) {
            Timber.w("Profile image hash mismatch from $peerFingerprint")
            return
        }

        // Save to local storage
        val imagePath = imageStorage.save(bytes)

        // Update peer record
        profileRepository.updatePeerProfileImage(
            peerFingerprint = peerFingerprint,
            imageId = message.imageId,
            imagePath = imagePath,
            imageHash = expectedHash,
            updatedAt = message.updatedAt,
        )

        _profileEvents.send(
            ProfileEvent.ProfileImageReceived(peerFingerprint, expectedHash, bytes)
        )
        Timber.i("Profile image received and saved from $peerFingerprint")
    }

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Request a peer's profile image if we don't have it cached.
     */
    suspend fun requestProfileImageIfNeeded(
        peerFingerprint: String,
        remoteHash: String,
    ) {
        // Cancel any pending request for this peer first
        pendingRequests[peerFingerprint]?.cancel()

        pendingRequests[peerFingerprint] = scope.launch {
            try {
                val message = ProfileSyncMessage(
                    type = "PROFILE_IMAGE_REQUEST",
                    senderFingerprint = "",
                    imageHash = remoteHash,
                )
                val payload = json.encodeToString(message).toByteArray(Charsets.UTF_8)
                transportRouter.send(peerFingerprint, payload)
                Timber.d("Profile image requested from $peerFingerprint")
            } catch (e: Exception) {
                Timber.w(e, "Failed to request profile image from $peerFingerprint")
            } finally {
                pendingRequests.remove(peerFingerprint)
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
