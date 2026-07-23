package com.zerochat.domain

import com.zerochat.crypto.CryptoEngine
import com.zerochat.crypto.SessionInitiation
import timber.log.Timber

/**
 * Manages encryption sessions with peers.
 *
 * Each peer gets a unique session identified by their fingerprint.
 * Sessions are created lazily: on first message send or receive.
 */
class SessionManager(
    private val cryptoEngine: CryptoEngine,
) {
    // Map: peer fingerprint → session ID
    private val sessions = mutableMapOf<String, String>()

    /**
     * Get the existing session ID for a peer, or create a new one.
     */
    fun getOrCreateSession(peerFingerprint: String): String {
        return sessions[peerFingerprint] ?: createSession(peerFingerprint)
    }

    /**
     * Initiate a new session with a peer.
     * Returns the [SessionInitiation] to send to the peer.
     */
    fun initiateSession(peerFingerprint: String, theirPublicKey: String): SessionInitiation {
        val initiation = cryptoEngine.initiateSession(theirPublicKey)
        sessions[peerFingerprint] = initiation.sessionId
        Timber.d("Session initiated with $peerFingerprint: ${initiation.sessionId}")
        return initiation
    }

    /**
     * Accept an incoming session from a peer.
     */
    fun acceptSession(peerFingerprint: String, initiation: SessionInitiation) {
        val acceptance = cryptoEngine.acceptSession(initiation)
        sessions[peerFingerprint] = initiation.sessionId
        Timber.d("Session accepted from $peerFingerprint: ${initiation.sessionId}")
    }

    private fun createSession(peerFingerprint: String): String {
        // For already-known peers, we initiate
        // In production, this would use stored pre-key bundles
        val initiation = cryptoEngine.initiateSession(peerFingerprint)
        sessions[peerFingerprint] = initiation.sessionId
        return initiation.sessionId
    }

    /**
     * Destroy a session (e.g., on peer disconnect).
     */
    fun destroySession(peerFingerprint: String) {
        sessions.remove(peerFingerprint)?.let { sessionId ->
            cryptoEngine.destroySession(sessionId)
        }
    }

    fun getSessionId(peerFingerprint: String): String? = sessions[peerFingerprint]
}
