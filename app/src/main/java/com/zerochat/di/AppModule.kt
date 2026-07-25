package com.zerochat.di

import android.content.Context
import com.zerochat.crypto.AesCryptoEngine
import com.zerochat.crypto.CryptoEngine
import com.zerochat.data.local.*
import com.zerochat.data.profile.ProfileImageProcessor
import com.zerochat.data.profile.ProfileImageRepositoryImpl
import com.zerochat.data.profile.ProfileImageStorage
import com.zerochat.data.repository.MessageRepositoryImpl
import com.zerochat.data.repository.PeerRepositoryImpl
import com.zerochat.domain.PeerRepository
import com.zerochat.domain.*
import com.zerochat.domain.profile.ProfileImageRepository
import com.zerochat.domain.profile.ProfileImageUseCase
import com.zerochat.domain.profile.ProfileSyncHandler
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.lan.LanTransportImpl
import com.zerochat.network.lan.WifiDirectReceiver
import com.zerochat.network.transport.TransportRouter
import com.zerochat.network.transport.TransportRouterImpl
import com.zerochat.network.signaling.SignalingClient
import com.zerochat.network.signaling.WanSignalingManager
import com.zerochat.domain.ConnectionRequestUseCase
import com.zerochat.domain.ConnectionRequestRepository
import com.zerochat.domain.BlockedPeerRepository
import com.zerochat.data.repository.ConnectionRequestRepositoryImpl
import com.zerochat.data.repository.BlockedPeerRepositoryImpl
import com.zerochat.network.wan.WanTransport
import com.zerochat.network.wan.WebRtcTransport
import com.zerochat.ui.discovery.DiscoveryViewModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideCryptoEngine(impl: AesCryptoEngine): CryptoEngine = impl

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZeroChatDatabase =
        androidx.room.Room.databaseBuilder(
            context, ZeroChatDatabase::class.java, ZeroChatDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideMessageDao(db: ZeroChatDatabase): MessageDao = db.messageDao()
    @Provides fun providePeerDao(db: ZeroChatDatabase): PeerDao = db.peerDao()
    @Provides fun provideUserProfileDao(db: ZeroChatDatabase): UserProfileDao = db.userProfileDao()
    @Provides fun provideConnectionRequestDao(db: ZeroChatDatabase): ConnectionRequestDao = db.connectionRequestDao()
    @Provides fun provideBlockedPeerDao(db: ZeroChatDatabase): BlockedPeerDao = db.blockedPeerDao()

    @Provides @Singleton
    fun provideMessageRepository(dao: MessageDao): MessageRepository =
        MessageRepositoryImpl(dao)

    @Provides @Singleton
    fun providePeerRepository(dao: PeerDao): PeerRepository =
        PeerRepositoryImpl(dao)

    @Provides @Singleton
    fun provideProfileImageRepository(
        userProfileDao: UserProfileDao,
        peerDao: PeerDao,
    ): ProfileImageRepository = ProfileImageRepositoryImpl(userProfileDao, peerDao)

    @Provides @Singleton
    fun provideProfileImageProcessor(
        @ApplicationContext context: Context,
    ): ProfileImageProcessor = ProfileImageProcessor(context)

    @Provides @Singleton
    fun provideProfileImageStorage(
        @ApplicationContext context: Context,
    ): ProfileImageStorage = ProfileImageStorage(context)

    @Provides @Singleton
    fun provideProfileImageUseCase(
        processor: ProfileImageProcessor,
        storage: ProfileImageStorage,
        repository: ProfileImageRepository,
    ): ProfileImageUseCase = ProfileImageUseCase(processor, storage, repository)

    @Provides @Singleton
    fun provideWifiDirectReceiver(@ApplicationContext context: Context): WifiDirectReceiver = WifiDirectReceiver(context)

    @Provides @Singleton
    fun provideLanTransport(
        @ApplicationContext context: Context,
        wifiDirectReceiver: WifiDirectReceiver,
    ): LanTransport = LanTransportImpl(context, wifiDirectReceiver)

    @Provides @Singleton
    fun provideWanTransport(
        @ApplicationContext context: Context,
    ): WanTransport = WebRtcTransport(context)

    @Provides @Singleton
    fun provideTransportRouter(
        lanTransport: LanTransport,
        wanTransport: WanTransport,
        peerRepository: PeerRepository,
    ): TransportRouter = TransportRouterImpl(lanTransport, wanTransport, peerRepository)

    @Provides @Singleton
    fun provideSessionManager(
        cryptoEngine: CryptoEngine,
    ): SessionManager = SessionManager(cryptoEngine)

    @Provides @Singleton
    fun provideSendMessageUseCase(
        cryptoEngine: CryptoEngine,
        messageRepository: MessageRepository,
        sessionManager: SessionManager,
        transportRouter: TransportRouter,
    ): SendMessageUseCase = SendMessageUseCase(
        cryptoEngine, messageRepository, sessionManager, transportRouter
    )

    @Provides @Singleton
    fun provideIncomingMessageHandler(
        cryptoEngine: CryptoEngine,
        messageRepository: MessageRepository,
        sessionManager: SessionManager,
        transportRouter: TransportRouter,
        connectionRequestUseCase: ConnectionRequestUseCase,
    ): IncomingMessageHandler = IncomingMessageHandler(
        cryptoEngine, messageRepository, sessionManager, transportRouter, connectionRequestUseCase
    )

    @Provides @Singleton
    fun provideProfileSyncHandler(
        transportRouter: TransportRouter,
        profileRepository: ProfileImageRepository,
        imageStorage: ProfileImageStorage,
        imageProcessor: ProfileImageProcessor,
    ): ProfileSyncHandler = ProfileSyncHandler(
        transportRouter, profileRepository, imageStorage, imageProcessor
    )

    // ── Signaling (WAN via PIN) ─────────────────────────────────

    @Provides @Singleton
    fun provideSignalingClient(): SignalingClient = SignalingClient()

    @Provides @Singleton
    fun provideWanSignalingManager(
        signalingClient: SignalingClient,
        transportRouter: TransportRouter,
    ): WanSignalingManager = WanSignalingManager(signalingClient, transportRouter)

    @Provides @Singleton
    fun provideDiscoveryViewModel(
        lanTransport: LanTransport,
        transportRouter: TransportRouter,
        peerRepository: PeerRepository,
        wanSignalingManager: WanSignalingManager,
        connectionRequestUseCase: ConnectionRequestUseCase,
    ): DiscoveryViewModel = DiscoveryViewModel(
        lanTransport, transportRouter, peerRepository, wanSignalingManager, connectionRequestUseCase
    )

    // ── Connection Requests ────────────────────────────────────────

    @Provides @Singleton
    fun provideConnectionRequestRepository(
        dao: ConnectionRequestDao,
    ): ConnectionRequestRepository = ConnectionRequestRepositoryImpl(dao)

    @Provides @Singleton
    fun provideBlockedPeerRepository(
        dao: BlockedPeerDao,
    ): BlockedPeerRepository = BlockedPeerRepositoryImpl(dao)

    @Provides @Singleton
    fun provideConnectionRequestUseCase(
        requestRepo: ConnectionRequestRepository,
        blockedRepo: BlockedPeerRepository,
        peerRepo: PeerRepository,
        transportRouter: TransportRouter,
        lanTransport: LanTransport,
        cryptoEngine: CryptoEngine,
    ): ConnectionRequestUseCase = ConnectionRequestUseCase(
        requestRepo, blockedRepo, peerRepo, transportRouter, lanTransport, cryptoEngine
    )
}
