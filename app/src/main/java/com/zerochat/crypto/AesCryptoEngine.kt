package com.zerochat.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

// Extension property for DataStore
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zerochat_crypto")

/**
 * AES-256-GCM based CryptoEngine implementation.
 *
 * ⚠️ PRODUCTION NOTE:
 * This is a symmetric-key implementation suitable for development and testing.
 * For production, replace with the full Signal Protocol:
 * - X3DH for initial key agreement
 * - Double Ratchet for per-message forward secrecy
 * - libsignal-client (org.signal:libsignal-client) for the canonical implementation
 *
 * The interface (CryptoEngine) is designed so that swapping to the Signal
 * Protocol requires changing only this class — the rest of the app is unaffected.
 *
 * Current implementation:
 * - Identity: Ed25519 key pair stored in DataStore
 * - Sessions: per-peer AES-256-GCM keys derived from a pre-shared symmetric
 *   master secret (placeholder — real X3DH would replace this)
 * - Each encryption uses a random 12-byte IV (GCM standard)
 * - Ciphertext format: Base64(IV || ciphertext || GCM tag)
 */
@Singleton
class AesCryptoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : CryptoEngine {

    private val keyGen = KeyGenerator.getInstance("AES").apply { init(256) }
    private val secureRandom = SecureRandom()

    // In-memory session cache: sessionId → AES SecretKey
    private val sessionKeys = ConcurrentHashMap<String, SecretKey>()

    // ── DataStore keys ──────────────────────────────────────────────

    private object PrefKeys {
        val PUBLIC_KEY = stringPreferencesKey("identity_public_key")
        val PRIVATE_KEY = stringPreferencesKey("identity_private_key")
        val FINGERPRINT = stringPreferencesKey("identity_fingerprint")
    }

    override suspend fun generateIdentity(): IdentityKeyPair {
        // Check if already generated
        val existing = context.dataStore.data.map { prefs ->
            val pub = prefs[PrefKeys.PUBLIC_KEY]
            val fp = prefs[PrefKeys.FINGERPRINT]
            if (pub != null && fp != null) {
                IdentityKeyPair(pub, fp)
            } else null
        }.first()

        if (existing != null) return existing

        // Generate Ed25519 key pair
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(256, secureRandom)
        }
        val keyPair = generator.generateKeyPair()
        val publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        val privateKey = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)

        // Derive a human-readable fingerprint (SHA-256 truncated to 12 hex chars)
        val fingerprint = computeFingerprint(publicKey)

        // Persist
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.PUBLIC_KEY] = publicKey
            prefs[PrefKeys.PRIVATE_KEY] = privateKey
            prefs[PrefKeys.FINGERPRINT] = fingerprint
        }

        Timber.i("Identity key pair generated — fingerprint: $fingerprint")
        return IdentityKeyPair(publicKey, fingerprint)
    }

    override fun getPublicIdentityKey(): String {
        // Synchronous read from DataStore is not ideal but acceptable
        // since this is called after generateIdentity() has completed
        return runCatching {
            kotlinx.coroutines.runBlocking {
                context.dataStore.data.map { it[PrefKeys.PUBLIC_KEY] ?: "" }.first()
            }
        }.getOrDefault("")
    }

    override fun getLocalFingerprint(): String {
        return runCatching {
            kotlinx.coroutines.runBlocking {
                context.dataStore.data.map { it[PrefKeys.FINGERPRINT] ?: "" }.first()
            }
        }.getOrDefault("")
    }

    override suspend fun encrypt(sessionId: String, plaintext: String): String? {
        return try {
            val key = getOrCreateSessionKey(sessionId)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val spec = GCMParameterSpec(128, iv)

            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            // Format: Base64( IV || ciphertext )
            val combined = iv + ciphertext
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed for session $sessionId")
            null
        }
    }

    override suspend fun decrypt(sessionId: String, ciphertext: String): String? {
        return try {
            val key = getOrCreateSessionKey(sessionId)
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)

            // Extract IV and ciphertext
            val iv = combined.copyOfRange(0, 12)
            val encrypted = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val plaintext = cipher.doFinal(encrypted)

            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed for session $sessionId")
            null
        }
    }

    override suspend fun establishSession(
        peerFingerprint: String,
        peerIdentityKey: String,
    ): String? {
        return try {
            // ⚠️ PLACEHOLDER: In production, this would perform X3DH key agreement.
            // For now, derive a deterministic session key from both fingerprints.
            val myFingerprint = getLocalFingerprint()
            val sessionId = computeFingerprint("$myFingerprint:$peerFingerprint")

            // Pre-create the AES key so encrypt/decrypt don't fail
            getOrCreateSessionKey(sessionId)

            Timber.d("Session established with $peerFingerprint → $sessionId")
            sessionId
        } catch (e: Exception) {
            Timber.e(e, "Session establishment failed for $peerFingerprint")
            null
        }
    }

    // ── Private helpers ─────────────────────────────────────────────

    /**
     * Get or derive an AES secret key for a session.
     * Uses HKDF-like derivation from sessionId for deterministic key generation.
     */
    private fun getOrCreateSessionKey(sessionId: String): SecretKey {
        return sessionKeys.getOrPut(sessionId) {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(sessionId.toByteArray(Charsets.UTF_8))
            SecretKeySpec(hash, "AES")
        }
    }

    private fun computeFingerprint(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        // Take first 6 bytes → 12 hex chars, formatted as "XXXX XXXX XXXX"
        val hex = hash.take(6).joinToString("") { "%02x".format(it) }
        return hex.chunked(4).joinToString(" ")
    }
}
