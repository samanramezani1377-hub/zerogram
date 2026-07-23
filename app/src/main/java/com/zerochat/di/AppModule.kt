package com.zerochat.di

import android.content.Context
import androidx.room.Room
import com.zerochat.crypto.CryptoEngine
import com.zerochat.crypto.AesCryptoEngine
import com.zerochat.data.local.ZeroChatDatabase
import com.zerochat.data.local.MessageDao
import com.zerochat.data.local.PeerDao
import com.zerochat.data.repository.MessageRepositoryImpl
import com.zerochat.data.repository.PeerRepositoryImpl
import com.zerochat.domain.IncomingMessageHandler
import com.zerochat.domain.MessageRepository
import com.zerochat.domain.PeerRepository
import com.zerochat.domain.SendMessageUseCase
import com.zerochat.domain.SessionManager
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
            "zerochat.db"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(db: ZeroChatDatabase): MessageDao = db.messageDao()

    @Provides
    fun providePeerDao(db: ZeroChatDatabase): PeerDao = db.peerDao()

    // ═══════════════════════════════════════════════════════════════
    // Crypto
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideCryptoEngine(): CryptoEngine {
        return AesCryptoEngine()
    }

    // ═══════════════════════════════════════════════════════════════
    // Network — LAN
    // ═══════════════════════════════════════════════════════════════

    @Provides
    @Singleton
    fun provideWifiDirectReceiver(@ApplicationContext context: Context): WifiDirectReceiver {
        return WifiDirectReceiver(context)
    }

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
        return SendMessageUseCase(cryptoEngine, messageRepository, sessionManager, transportRouter)
    }

    @Provides
    @Singleton
    fun provideIncomingMessageHandler(
        cryptoEngine: CryptoEngine,
        messageRepository: MessageRepository,
        sessionManager: SessionManager,
        transportRouter: TransportRouter,
    ): IncomingMessageHandler {
        return IncomingMessageHandler(cryptoEngine, messageRepository, sessionManager, transportRouter)
    }

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
}
