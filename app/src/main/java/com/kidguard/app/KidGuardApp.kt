package com.kidguard.app

import android.app.Application

class KidGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseConfig.ensureInit(this)
        val prefs = Prefs(this)
        if (prefs.parentUid != null) {
            SyncWorker.schedule(this)
            RulesStore.load(this)
            DeviceOwner.enforce(this, RulesStore.rules, blockingServiceOn = Status.accessibilityOn(this))
        }
    }
}
