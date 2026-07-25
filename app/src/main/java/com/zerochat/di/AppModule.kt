package com.zerochat.di

import android.content.Context
import androidx.room.Room
import com.zerochat.crypto.CryptoEngine
import com.zerochat.crypto.AesCryptoEngine
import com.zerochat.data.local.*
import com.zerochat.data.profile.ProfileImageProcessor
import com.zerochat.data.profile.ProfileImageRepositoryImpl
import com.zerochat.data.profile.ProfileImageStorage
import com.zerochat.data.repository.MessageRepositoryImpl
import com.zerochat.data.repository.PeerRepositoryImpl
import com.zerochat.domain.*
import com.zerochat.domain.profile.ProfileImageRepository
import com.zerochat.domain.profile.ProfileImageUseCase
import com.zerochat.domain.profile.ProfileSyncHandler
import com.zerochat.network.lan.LanTransport
import com.zerochat.network.lan.LanTransportImpl
import com.zerochat.network.lan.WifiDirectReceiver
import com.zerochat.network.transport.TransportRouter
import com.zerochat.network.transport.TransportRouterImpl
import com.zerochat.network.wan.WanTransport
import com.zerochat.network.wan.WebRtcTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ═══════════════════════════════════════════════════════════════
    // Database
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ZeroChatDatabase {
        return Room.databaseBuilder(
            context,
            ZeroChatDatabase::class.java,
            ZeroChatDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(db: ZeroChatDatabase): MessageDao = db.messageDao()

    @Provides
    fun providePeerDao(db: ZeroChatDatabase): PeerDao = db.peerDao()

    @Provides
    fun provideUserProfileDao(db: ZeroChatDatabase): UserProfileDao = db.userProfileDao()

    // ═══════════════════════════════════════════════════════════════
    // Repositories
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideMessageRepository(messageDao: MessageDao): MessageRepository {
        return MessageRepositoryImpl(messageDao)
    }

    @Provides
    @Singleton
    fun providePeerRepository(peerDao: PeerDao): PeerRepository {
        return PeerRepositoryImpl(peerDao)
    }

    @Provides
    @Singleton
    fun provideProfileImageRepository(
        userProfileDao: UserProfileDao,
        peerDao: PeerDao,
    ): ProfileImageRepository {
        return ProfileImageRepositoryImpl(userProfileDao, peerDao)
    }

    // ═══════════════════════════════════════════════════════════════
    // Crypto
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideCryptoEngine(@ApplicationContext context: Context): CryptoEngine {
        return AesCryptoEngine(context)
    }

    // ═══════════════════════════════════════════════════════════════
    // Profile Image
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideProfileImageProcessor(
        @ApplicationContext context: Context,
    ): ProfileImageProcessor {
        return ProfileImageProcessor(context)
    }

    @Provides
    @Singleton
    fun provideProfileImageStorage(
        @ApplicationContext context: Context,
    ): ProfileImageStorage {
        return ProfileImageStorage(context)
    }

    @Provides
    @Singleton
    fun provideProfileImageUseCase(
        imageProcessor: ProfileImageProcessor,
        imageStorage: ProfileImageStorage,
        profileRepository: ProfileImageRepository,
    ): ProfileImageUseCase {
        return ProfileImageUseCase(
            imageProcessor = imageProcessor,
            imageStorage = imageStorage,
            profileRepository = profileRepository,
        )
    }

    @Provides
    @Singleton
    fun provideProfileSyncHandler(
        transportRouter: TransportRouter,
        profileRepository: ProfileImageRepository,
        imageStorage: ProfileImageStorage,
        imageProcessor: ProfileImageProcessor,
    ): ProfileSyncHandler {
        return ProfileSyncHandler(
            transportRouter = transportRouter,
            profileRepository = profileRepository,
            imageStorage = imageStorage,
            imageProcessor = imageProcessor,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Domain — Session & Use Cases
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideSessionManager(cryptoEngine: CryptoEngine): SessionManager {
        return SessionManager(cryptoEngine)
    }

    @Provides
    @Singleton
    fun provideSendMessageUseCase(
        cryptoEngine: CryptoEngine,
        messageRepository: MessageRepository,
        sessionManager: SessionManager,
        transportRouter: TransportRouter,
    ): SendMessageUseCase {
        return SendMessageUseCase(
            cryptoEngine = cryptoEngine,
            messageRepository = messageRepository,
            sessionManager = sessionManager,
            transportRouter = transportRouter,
        )
    }

    @Provides
    @Singleton
    fun provideIncomingMessageHandler(
        cryptoEngine: CryptoEngine,
        messageRepository: MessageRepository,
        sessionManager: SessionManager,
        transportRouter: TransportRouter,
    ): IncomingMessageHandler {
        return IncomingMessageHandler(
            cryptoEngine = cryptoEngine,
            messageRepository = messageRepository,
            sessionManager = sessionManager,
            transportRouter = transportRouter,
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // Network — LAN
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideLanTransport(
        @ApplicationContext context: Context,
        wifiDirectReceiver: WifiDirectReceiver,
    ): LanTransport {
        return LanTransportImpl(context, wifiDirectReceiver)
    }

    // ═══════════════════════════════════════════════════════════════
    // Network — WAN
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideWanTransport(@ApplicationContext context: Context): WanTransport {
        return WebRtcTransport(context)
    }

    // ═══════════════════════════════════════════════════════════════
    // Transport Router
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideTransportRouter(
        lanTransport: LanTransport,
        wanTransport: WanTransport,
    ): TransportRouter {
        return TransportRouterImpl(lanTransport, wanTransport)
    }
}
