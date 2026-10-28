package com.kidguard.app

import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore

/** Firestore paths. Everything lives under parents/{parentUid}/... */
object Fire {
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    fun deviceRef(uid: String, deviceId: String): DocumentReference =
        db.collection("parents").document(uid).collection("devices").document(deviceId)

    fun rulesRef(uid: String, deviceId: String): DocumentReference =
        deviceRef(uid, deviceId).collection("config").document("rules")

    fun commandsRef(uid: String, deviceId: String): DocumentReference =
        deviceRef(uid, deviceId).collection("config").document("commands")

    fun usageRef(uid: String, deviceId: String, date: String): DocumentReference =
        deviceRef(uid, deviceId).collection("usage").document(date)

    fun alertsCol(uid: String): CollectionReference =
        db.collection("parents").document(uid).collection("alerts")
}
