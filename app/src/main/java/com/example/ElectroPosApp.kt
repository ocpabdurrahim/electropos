package com.example

import android.app.Application
import com.example.update.AppUpdater
import com.example.update.UpdatePrefs
import com.example.update.UpdateWorker
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ElectroPosApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val updatePrefs by lazy { UpdatePrefs(this) }
    val updater by lazy { AppUpdater(this, updatePrefs, appScope) }

    override fun onCreate() {
        super.onCreate()
        // JVM unit tests (Robolectric) start this class too; they have no WorkManager or network
        if (android.os.Build.FINGERPRINT == "robolectric") return
        configureFirebase()
        appScope.launch {
            updater.cleanup()
            if (updatePrefs.autoUpdate) {
                UpdateWorker.schedule(this@ElectroPosApp)
                updater.checkAndDownload()
            }
        }
    }

    private fun configureFirebase() {
        // google-services.json is missing in some local builds; Firebase is then simply not used.
        if (com.google.firebase.FirebaseApp.initializeApp(this) == null) return
        val host = BuildConfig.FIREBASE_EMULATOR_HOST
        if (host.isNotEmpty()) {
            Firebase.auth.useEmulator(host, 9099)
            Firebase.firestore.useEmulator(host, 8085)
        }
        // Room is the shop's data on this phone; Firestore only carries changes in and out.
        Firebase.firestore.firestoreSettings = firestoreSettings {
            setLocalCacheSettings(memoryCacheSettings {})
        }
    }
}
