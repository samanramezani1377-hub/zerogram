package com.zerochat

import android.app.Application
import com.zerochat.domain.IncomingMessageHandler
import com.zerochat.network.transport.TransportRouter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * ZeroChat Application — initializes global components.
 */
@HiltAndroidApp
class ZeroChatApp : Application() {

    @Inject lateinit var transportRouter: TransportRouter
    @Inject lateinit var incomingMessageHandler: IncomingMessageHandler

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("ZeroChat v${BuildConfig.VERSION_NAME} starting up")

        // Start network services
        appScope.launch {
            try {
                transportRouter.start()
                Timber.i("TransportRouter started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start TransportRouter")
            }
        }

        // Start listening for incoming messages
        appScope.launch {
            try {
                incomingMessageHandler.startListening()
                Timber.i("IncomingMessageHandler started")
            } catch (e: Exception) {
                Timber.e(e, "Failed to start IncomingMessageHandler")
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.launch {
            transportRouter.stop()
        }
    }
}
