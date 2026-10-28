package com.kidguard.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/**
 * Firebase config for the Cell Home Kids project, set in code instead of a
 * google-services.json file — so the kid app builds with nothing extra to add.
 * These values identify the project; they are not secrets.
 */
object FirebaseConfig {
    fun ensureInit(ctx: Context) {
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId("cell-home-kids")
            .setApplicationId("1:357385499837:web:4fcb0f3e320d42e14773d4")
            .setApiKey("AIzaSyAPG-bUYVithFYmfp-8G95vZiEyss8L5FY")
            .setGcmSenderId("357385499837")
            .setStorageBucket("cell-home-kids.firebasestorage.app")
            .build()
        FirebaseApp.initializeApp(ctx, options)
    }
}
