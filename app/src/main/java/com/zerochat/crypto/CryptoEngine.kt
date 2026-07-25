package com.zerochat.crypto

/**
 * Engine interface for end-to-end message encryption.
 *
 * Implementations can range from simple AES-GCM (for demo/dev) to
 * the full Signal Protocol with X3DH + Double Ratchet (for production).
 *
 * The interface is deliberately minimal: encrypt/decrypt with a sessionId.
 * Session management (key agreement, ratchet steps) is internal to the
 * implementation and not exposed to the rest of the app.
 */
interface CryptoEngine {

    /**
     * Generate a new Curve25519 identity key pair for this device.
     * Called once on first launch. Subsequent calls should return
     * the previously-generated pair.
     *
     * @return the local identity key pair
     */
    suspend fun generateIdentity(): IdentityKeyPair

    /**
     * Get the public identity key (Base64-encoded).
     */
    fun getPublicIdentityKey(): String

    /**
     * Get the local fingerprint — a human-readable hash of the identity key.
     * Format: 12 hex digits (48 bits), e.g. "a1b2 c3d4 e5f6"
     */
    fun getLocalFingerprint(): String

    /**
     * Encrypt plaintext for the session identified by [sessionId].
     *
     * @param sessionId the session with the target peer
     * @param plaintext the message to encrypt
     * @return Base64-encoded ciphertext, or null if encryption fails
     */
    suspend fun encrypt(sessionId: String, plaintext: String): String?

    /**
     * Decrypt ciphertext for the session identified by [sessionId].
     *
     * @param sessionId the session with the sending peer
     * @param ciphertext Base64-encoded ciphertext
     * @return the plaintext, or null if decryption fails
     */
    suspend fun decrypt(sessionId: String, ciphertext: String): String?

    /**
     * Perform key agreement with a peer to establish a session.
     *
     * @param peerFingerprint the peer's identity fingerprint
     * @param peerIdentityKey the peer's public identity key (Base64-encoded)
     * @return a session ID that can be used for encrypt/decrypt
     */
    suspend fun establishSession(peerFingerprint: String, peerIdentityKey: String): String?
}

/**
 * Simple value class holding the local identity key pair.
 */
data class IdentityKeyPair(
    val publicKey: String,
    val fingerprint: String,
    // Private key is intentionally not exposed outside the CryptoEngine
)
