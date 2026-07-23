package com.zerochat.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PeerIdentity(
    val displayName: String,
    val fingerprint: String,
    val publicIdentityKey: String,
    val createdAt: Long = System.currentTimeMillis()
)
