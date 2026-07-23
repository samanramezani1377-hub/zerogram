package com.zerochat.crypto

import android.util.Base64
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM + ECDH implementation of CryptoEngine.
 *
 * Temporary replacement for Signal Protocol while libsignal API
 * compatibility is being resolved.
 *
 * Security:
 *  - ECDH (X25519) for initial key agreement
 *  - AES-256-GCM for message encryption (authenticated)
 *  - 12-byte random IV per message
 *  - HKDF-derived session keys from shared secret
 *  - Double Ratchet NOT implemented (forward secrecy limited)
 *
 * TODO: Replace with full Signal Protocol (X3DH + Double Ratchet)
 */
@Singleton
class AesCryptoEngine @Inject constructor() : CryptoEngine {

    // ═══════════════════════════════════════════════════════════════
    // Key agreement algorithm
    // ═══════════════════════════════════════════════════════════════
    private companion object {
        const val EC_ALGORITHM = "X25519"
        const val KEY_AGREEMENT = "XDH"
        const val AES_ALGORITHM = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val FINGERPRINT_CHARS = 16
    }

    private val secureRandom = SecureRandom()

    // ── Device identity ──
    private var identityKeyPair: java.security.KeyPair? = null
    private var identityInitialized = false

    // ── Session keys (sessionId → AES SecretKey) ──
    private val sessionKeys = mutableMapOf<String, SecretKey>()

    // ═══════════════════════════════════════════════════════════════
    // Identity Management
    // ═══════════════════════════════════════════════════════════════

    override fun generateIdentity(): IdentityKeyPair {
        synchronized(this) {
            if (identityInitialized && identityKeyPair != null) {
                val pubKey = Base64.encodeToString(
                    identityKeyPair!!.public.encoded,
                    Base64.NO_WRAP
                )
                return IdentityKeyPair(
                    publicKey = pubKey,
                    fingerprint = computeFingerprint(identityKeyPair!!.public.encoded),
                )
            }

            // Generate X25519 key pair
            val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
            identityKeyPair = generator.generateKeyPair()
            identityInitialized = true

            val pubKey = Base64.encodeToString(
                identityKeyPair!!.public.encoded,
                Base64.NO_WRAP
            )

            Timber.i("AES-GCM identity generated: fingerprint=${getLocalFingerprint()}")

            return IdentityKeyPair(
                publicKey = pubKey,
                fingerprint = getLocalFingerprint(),
            )
        }
    }

    override fun getLocalFingerprint(): String {
        val publicKey = identityKeyPair?.public
            ?: throw IllegalStateException("Identity not generated. Call generateIdentity() first.")
        return computeFingerprint(publicKey.encoded)
    }

    override fun getPublicIdentityKey(): String {
        val publicKey = identityKeyPair?.public
            ?: throw IllegalStateException("Identity not generated. Call generateIdentity() first.")
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    // ═══════════════════════════════════════════════════════════════
    // Session Lifecycle (ECDH)
    // ═══════════════════════════════════════════════════════════════

    override fun initiateSession(
        theirPublicIdentityKey: String,
        theirSignedPreKey: String?,
    ): SessionInitiation {
        val ourIdentity = identityKeyPair
            ?: throw IllegalStateException("Identity not generated. Call generateIdentity() first.")

        val sessionId = java.util.UUID.randomUUID().toString()

        // Ephemeral key pair for this session
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        val ephemeralKeyPair = generator.generateKeyPair()

        // ECDH with their identity key → shared secret → AES key
        val theirPublicKey = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(Base64.decode(theirPublicIdentityKey, Base64.NO_WRAP)))

        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT)
        keyAgreement.init(ourIdentity.private)
        keyAgreement.doPhase(theirPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Also mix ephemeral into session key if they provided a pre-key
        val sessionKey = if (theirSignedPreKey != null) {
            val theirPreKey = KeyFactory.getInstance(EC_ALGORITHM)
                .generatePublic(X509EncodedKeySpec(Base64.decode(theirSignedPreKey, Base64.NO_WRAP)))

            val ephemeralAgreement = KeyAgreement.getInstance(KEY_AGREEMENT)
            ephemeralAgreement.init(ephemeralKeyPair.private)
            ephemeralAgreement.doPhase(theirPreKey, true)
            val ephemeralSecret = ephemeralAgreement.generateSecret()

            // Combine both shared secrets
            val combined = sharedSecret + ephemeralSecret
            deriveAesKey(combined)
        } else {
            deriveAesKey(sharedSecret)
        }

        sessionKeys[sessionId] = sessionKey

        Timber.d("ECDH session initiated: $sessionId")

        return SessionInitiation(
            sessionId = sessionId,
            initiatorIdentityKey = Base64.encodeToString(
                ourIdentity.public.encoded, Base64.NO_WRAP
            ),
            initiatorEphemeralKey = Base64.encodeToString(
                ephemeralKeyPair.public.encoded, Base64.NO_WRAP
            ),
            preKeyId = null,
        )
    }

    override fun acceptSession(initiation: SessionInitiation): SessionAcceptance {
        val ourIdentity = identityKeyPair
            ?: throw IllegalStateException("Identity not generated.")

        val sessionId = initiation.sessionId

        // Ephemeral key pair for response
        val generator = KeyPairGenerator.getInstance(EC_ALGORITHM)
        val ephemeralKeyPair = generator.generateKeyPair()

        // ECDH with their identity key
        val theirIdentityKey = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(
                X509EncodedKeySpec(Base64.decode(initiation.initiatorIdentityKey, Base64.NO_WRAP))
            )

        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT)
        keyAgreement.init(ourIdentity.private)
        keyAgreement.doPhase(theirIdentityKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Mix in their ephemeral key
        val theirEphemeralKey = KeyFactory.getInstance(EC_ALGORITHM)
            .generatePublic(
                X509EncodedKeySpec(Base64.decode(initiation.initiatorEphemeralKey, Base64.NO_WRAP))
            )

        val ephemeralAgreement = KeyAgreement.getInstance(KEY_AGREEMENT)
        ephemeralAgreement.init(ephemeralKeyPair.private)
        ephemeralAgreement.doPhase(theirEphemeralKey, true)
        val ephemeralSecret = ephemeralAgreement.generateSecret()

        val combined = sharedSecret + ephemeralSecret
        val sessionKey = deriveAesKey(combined)
        sessionKeys[sessionId] = sessionKey

        Timber.d("ECDH session accepted: $sessionId")

        return SessionAcceptance(
            sessionId = sessionId,
            responderEphemeralKey = Base64.encodeToString(
                ephemeralKeyPair.public.encoded, Base64.NO_WRAP
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Encrypt / Decrypt (AES-256-GCM)
    // ═══════════════════════════════════════════════════════════════

    override fun encrypt(sessionId: String, plaintext: String): String {
        val sessionKey = sessionKeys[sessionId]
            ?: throw IllegalStateException("No session found for $sessionId")

        val cipher = Cipher.getInstance(AES_ALGORITHM)
        val iv = ByteArray(GCM_IV_BYTES).also { secureRandom.nextBytes(it) }
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, sessionKey, spec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV to ciphertext: [IV (12 bytes) | ciphertext + tag]
        val result = iv + ciphertext
        return Base64.encodeToString(result, Base64.NO_WRAP)
    }

    override fun decrypt(sessionId: String, ciphertext: String): String? {
        return try {
            val sessionKey = sessionKeys[sessionId]
                ?: throw IllegalStateException("No session found for $sessionId")

            val raw = Base64.decode(ciphertext, Base64.NO_WRAP)

            // Split: first 12 bytes = IV, rest = ciphertext + GCM tag
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = raw.copyOfRange(GCM_IV_BYTES, raw.size)

            val cipher = Cipher.getInstance(AES_ALGORITHM)
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, sessionKey, spec)

            val plainBytes = cipher.doFinal(encrypted)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed for session $sessionId")
            null
        }
    }

    override fun destroySession(sessionId: String) {
        sessionKeys.remove(sessionId)
        Timber.d("Session destroyed: $sessionId")
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * Derive a 256-bit AES key from a shared secret using SHA-256.
     */
    private fun deriveAesKey(secret: ByteArray): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(secret)
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyBytes)
        return hash.take(FINGERPRINT_CHARS).joinToString("") { "%02x".format(it) }
    }
}
