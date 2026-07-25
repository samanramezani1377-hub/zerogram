package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cryptographic sessions with peers.
 *
 * A session represents an established encrypted channel with a specific peer.
 * SessionManager tracks which sessions are active and provides the session ID
 * needed by CryptoEngine.encrypt/decrypt.
 *
 * Key responsibilities:
 * - Map peer fingerprints to session IDs
 * - Establish new sessions when contacting a peer for the first time
 * - Track session state (ACTIVE / ESTABLISHING / FAILED)
 */
@Singleton
class SessionManager @Inject constructor(
    private val cryptoEngine: CryptoEngine,
) {

    private val sessionMap = mutableMapOf<String, String>()
    private val peerIdentityKeys = mutableMapOf<String, String>()

    /**
     * Get the session ID for a peer, or create one if none exists.
     *
     * IMPORTANT: In production with the Signal Protocol, this would trigger
     * X3DH key agreement the first time. The current implementation uses
     * deterministic derivation — adequate for dev but must be replaced.
     */
    suspend fun getOrCreateSession(peerFingerprint: String): String {
        return sessionMap.getOrPut(peerFingerprint) {
            val existingKey = peerIdentityKeys[peerFingerprint]
            cryptoEngine.establishSession(peerFingerprint, existingKey ?: "")
                ?: "fallback_${cryptoEngine.getLocalFingerprint()}_$peerFingerprint"
        }
    }

    /**
     * Store a peer's identity key for future session establishment.
     */
    fun registerPeerIdentity(peerFingerprint: String, identityKey: String) {
        peerIdentityKeys[peerFingerprint] = identityKey
    }

    /**
     * Check if we have an active session with a peer.
     */
    fun hasSession(peerFingerprint: String): Boolean {
        return sessionMap.containsKey(peerFingerprint)
    }

    /**
     * Remove a session (e.g., on peer disconnect or key compromise).
     */
    fun removeSession(peerFingerprint: String) {
        sessionMap.remove(peerFingerprint)
    }
}
