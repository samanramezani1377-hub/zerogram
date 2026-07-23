package com.zerochat.data.local

import androidx.room.*
import com.zerochat.data.model.ConnectionStatus
import com.zerochat.data.model.DiscoveryMethod
import com.zerochat.data.model.Peer
import com.zerochat.data.model.PeerIdentity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {

    @Query("SELECT * FROM peers ORDER BY lastSeenAt DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getPeerByFingerprint(fingerprint: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: PeerEntity): Long

    @Delete
    suspend fun deletePeer(peer: PeerEntity)

    @Query("UPDATE peers SET connectionStatus = :status WHERE fingerprint = :fingerprint")
    suspend fun updateConnectionStatus(fingerprint: String, status: String)

    @Query("UPDATE peers SET lastKnownIp = :ip, lastKnownPort = :port, lastSeenAt = :lastSeen WHERE fingerprint = :fingerprint")
    suspend fun updateNetworkInfo(fingerprint: String, ip: String, port: Int, lastSeen: Long)
}

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val fingerprint: String,
    val publicIdentityKey: String,
    val lastKnownIp: String?,
    val lastKnownPort: Int,
    val lastSeenAt: Long,
    val discoveryMethod: String,
    val connectionStatus: String,
)

fun PeerEntity.toDomain(): Peer = Peer(
    id = id,
    identity = PeerIdentity(
        displayName = displayName,
        fingerprint = fingerprint,
        publicIdentityKey = publicIdentityKey,
    ),
    lastKnownIp = lastKnownIp,
    lastKnownPort = lastKnownPort,
    lastSeenAt = lastSeenAt,
    discoveryMethod = try { DiscoveryMethod.valueOf(discoveryMethod) } catch (_: Exception) { DiscoveryMethod.MANUAL },
    connectionStatus = try { ConnectionStatus.valueOf(connectionStatus) } catch (_: Exception) { ConnectionStatus.DISCONNECTED },
)

fun Peer.toEntity(): PeerEntity = PeerEntity(
    id = id,
    displayName = identity.displayName,
    fingerprint = identity.fingerprint,
    publicIdentityKey = identity.publicIdentityKey,
    lastKnownIp = lastKnownIp,
    lastKnownPort = lastKnownPort,
    lastSeenAt = lastSeenAt,
    discoveryMethod = discoveryMethod.name,
    connectionStatus = connectionStatus.name,
)
