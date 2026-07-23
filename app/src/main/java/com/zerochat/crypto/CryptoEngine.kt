package com.zerochat.crypto

interface CryptoEngine {
    fun generateIdentity(): IdentityKeyPair
    fun initiateSession(theirPublicIdentityKey: String, theirSignedPreKey: String? = null): SessionInitiation
    fun acceptSession(initiation: SessionInitiation): SessionAcceptance
    fun encrypt(sessionId: String, plaintext: String): String
    fun decrypt(sessionId: String, ciphertext: String): String?
    fun destroySession(sessionId: String)
    fun getLocalFingerprint(): String
    fun getPublicIdentityKey(): String
}

data class IdentityKeyPair(val publicKey: String, val fingerprint: String)
data class SessionInitiation(val sessionId: String, val initiatorIdentityKey: String, val initiatorEphemeralKey: String, val preKeyId: Int? = null)
data class SessionAcceptance(val sessionId: String, val responderEphemeralKey: String)

