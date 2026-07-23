package com.zerochat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * ZeroChat Application — initializes global components.
 */
@HiltAndroidApp
class ZeroChatApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("ZeroChat v${BuildConfig.VERSION_NAME} starting up")
    }
}
