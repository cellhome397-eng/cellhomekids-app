package com.kidguard.app

import android.content.Context
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONArray
import org.json.JSONObject

data class Bedtime(
    val enabled: Boolean = false,
    val start: String = "21:00",
    val end: String = "07:00",
)

/** A place the parent watches: arrive/leave alerts fire when the kid crosses its radius. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Int = 150, // metres
)

/**
 * Rules set by the parent on the dashboard.
 * Firestore shape (parents/{uid}/devices/{id}/config/rules):
 *   blockedApps: [pkg], appLimits: [{pkg, minutes}], dailyLimitMinutes: n,
 *   bedtime: {enabled, start "HH:mm", end "HH:mm"}, lockSettings: bool, pausedUntil: epoch ms
 */
data class Rules(
    val blockedApps: Set<String> = emptySet(),
    val appLimits: Map<String, Int> = emptyMap(),
    val dailyLimitMinutes: Int = 0,
    val bedtime: Bedtime = Bedtime(),
    val lockSettings: Boolean = true,
    val settingsPinOnPhone: Boolean = true,
    val pausedUntil: Long = 0L,
    val places: List<Place> = emptyList(),
) {
    fun toJson(): String {
        val limits = JSONArray()
        appLimits.forEach { (pkg, min) -> limits.put(JSONObject().put("pkg", pkg).put("minutes", min)) }
        val placesArr = JSONArray()
        places.forEach {
            placesArr.put(
                JSONObject().put("id", it.id).put("name", it.name)
                    .put("lat", it.lat).put("lng", it.lng).put("radius", it.radius)
            )
        }
        return JSONObject()
            .put("blockedApps", JSONArray(blockedApps.toList()))
            .put("appLimits", limits)
            .put("dailyLimitMinutes", dailyLimitMinutes)
            .put(
                "bedtime",
                JSONObject().put("enabled", bedtime.enabled).put("start", bedtime.start).put("end", bedtime.end),
            )
            .put("lockSettings", lockSettings)
            .put("settingsPinOnPhone", settingsPinOnPhone)
            .put("pausedUntil", pausedUntil)
            .put("places", placesArr)
            .toString()
    }

    companion object {
        val DEFAULT_FIRESTORE: Map<String, Any> = mapOf(
            "blockedApps" to emptyList<String>(),
            "appLimits" to emptyList<Map<String, Any>>(),
            "dailyLimitMinutes" to 0,
            "bedtime" to mapOf("enabled" to false, "start" to "21:00", "end" to "07:00"),
            "lockSettings" to true,
            "pausedUntil" to 0L,
        )

        fun fromMap(m: Map<String, Any?>?): Rules {
            if (m == null) return Rules()
            val blocked = (m["blockedApps"] as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()
            val limits = HashMap<String, Int>()
            (m["appLimits"] as? List<*>)?.forEach { item ->
                val e = item as? Map<*, *> ?: return@forEach
                val pkg = e["pkg"] as? String ?: return@forEach
                val min = (e["minutes"] as? Number)?.toInt() ?: return@forEach
                if (min > 0) limits[pkg] = min
            }
            val b = m["bedtime"] as? Map<*, *>
            return Rules(
                blockedApps = blocked,
                appLimits = limits,
                dailyLimitMinutes = (m["dailyLimitMinutes"] as? Number)?.toInt() ?: 0,
                bedtime = Bedtime(
                    enabled = b?.get("enabled") as? Boolean ?: false,
                    start = b?.get("start") as? String ?: "21:00",
                    end = b?.get("end") as? String ?: "07:00",
                ),
                lockSettings = m["lockSettings"] as? Boolean ?: true,
                settingsPinOnPhone = m["settingsPinOnPhone"] as? Boolean ?: true,
                pausedUntil = (m["pausedUntil"] as? Number)?.toLong() ?: 0L,
                places = (m["places"] as? List<*>)?.mapNotNull { item ->
                    val e = item as? Map<*, *> ?: return@mapNotNull null
                    val id = e["id"] as? String ?: return@mapNotNull null
                    val lat = (e["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                    val lng = (e["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                    Place(id, e["name"] as? String ?: "Place", lat, lng, (e["radius"] as? Number)?.toInt() ?: 150)
                } ?: emptyList(),
            )
        }

        fun fromJson(s: String?): Rules {
            if (s.isNullOrBlank()) return Rules()
            return try {
                val o = JSONObject(s)
                val blocked = HashSet<String>()
                o.optJSONArray("blockedApps")?.let { a ->
                    for (i in 0 until a.length()) blocked.add(a.getString(i))
                }
                val limits = HashMap<String, Int>()
                o.optJSONArray("appLimits")?.let { a ->
                    for (i in 0 until a.length()) {
                        val e = a.getJSONObject(i)
                        limits[e.getString("pkg")] = e.getInt("minutes")
                    }
                }
                val b = o.optJSONObject("bedtime")
                Rules(
                    blockedApps = blocked,
                    appLimits = limits,
                    dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0),
                    bedtime = Bedtime(
                        enabled = b?.optBoolean("enabled", false) ?: false,
                        start = b?.optString("start", "21:00") ?: "21:00",
                        end = b?.optString("end", "07:00") ?: "07:00",
                    ),
                    lockSettings = o.optBoolean("lockSettings", true),
                    settingsPinOnPhone = o.optBoolean("settingsPinOnPhone", true),
                    pausedUntil = o.optLong("pausedUntil", 0L),
                    places = o.optJSONArray("places")?.let { a ->
                        (0 until a.length()).map { i ->
                            val e = a.getJSONObject(i)
                            Place(e.getString("id"), e.optString("name", "Place"),
                                e.getDouble("lat"), e.getDouble("lng"), e.optInt("radius", 150))
                        }
                    } ?: emptyList(),
                )
            } catch (e: Exception) {
                Rules()
            }
        }
    }
}

/** Keeps the latest rules in memory, live-synced from Firestore and cached on disk. */
object RulesStore {
    @Volatile
    var rules: Rules = Rules()
        private set

    private var registration: ListenerRegistration? = null
    private var commandsRegistration: ListenerRegistration? = null

    fun load(ctx: Context) {
        rules = Rules.fromJson(Prefs(ctx).rulesJson)
    }

    /** Called by the sync worker after it fetched rules itself. */
    fun update(ctx: Context, r: Rules) {
        rules = r
        Prefs(ctx).rulesJson = r.toJson()
    }

    @Synchronized
    fun startListening(ctx: Context) {
        if (registration != null) return
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        val uid = prefs.parentUid ?: return
        registration = Fire.rulesRef(uid, prefs.deviceId).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) update(app, Rules.fromMap(snap.data))
        }
        commandsRegistration = Fire.commandsRef(uid, prefs.deviceId).addSnapshotListener { snap, _ ->
            if (snap != null && snap.exists()) {
                Commands.handle(app, snap.data)
            }
        }
    }

    @Synchronized
    fun stop() {
        registration?.remove()
        registration = null
        commandsRegistration?.remove()
        commandsRegistration = null
    }
}
