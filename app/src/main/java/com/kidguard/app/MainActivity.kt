package com.kidguard.app

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import java.text.DateFormat
import java.util.Date

/** Parent-only screen on the kid's phone: link, PIN lock, permission checklist. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var setupPanel: View
    private lateinit var lockPanel: View
    private lateinit var statusPanel: View
    private lateinit var checklist: LinearLayout
    private lateinit var statusTitle: TextView
    private lateinit var statusBody: TextView
    private lateinit var finishButton: Button
    private lateinit var pauseButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        setupPanel = findViewById(R.id.setupPanel)
        lockPanel = findViewById(R.id.lockPanel)
        statusPanel = findViewById(R.id.statusPanel)
        checklist = findViewById(R.id.checklist)
        statusTitle = findViewById(R.id.statusTitle)
        statusBody = findViewById(R.id.statusBody)
        finishButton = findViewById(R.id.finishButton)
        pauseButton = findViewById(R.id.pauseButton)

        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.unlockButton).setOnClickListener { tryUnlock() }
        finishButton.setOnClickListener { finishSetup() }
        pauseButton.setOnClickListener { togglePause() }
        findViewById<Button>(R.id.syncButton).setOnClickListener {
            SyncWorker.runNow(this)
            toast("Sending report…")
        }
        findViewById<Button>(R.id.unlinkButton).setOnClickListener { confirmUnlink() }
        // Only shown before linking: lets you undo the USB setup if you never finish it.
        findViewById<Button>(R.id.removeUnlinkedButton).setOnClickListener {
            Unlink.run(this) {
                toast("Control removed. You can now uninstall Cell Home Kids.")
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from a Settings screen we opened: end the short settings pause.
        if (settingsPauseActive) {
            settingsPauseActive = false
            prefs.localPauseUntil = 0L
        }
        refresh()
    }

    private fun refresh() {
        val linked = prefs.parentUid != null && FirebaseAuth.getInstance().currentUser != null
        when {
            !linked -> {
                show(setupPanel)
                findViewById<View>(R.id.removeUnlinkedButton).visibility =
                    if (DeviceOwner.isOwner(this) || Status.adminOn(this)) View.VISIBLE else View.GONE
            }
            System.currentTimeMillis() > unlockedUntil -> show(lockPanel)
            else -> {
                show(statusPanel)
                renderStatus()
            }
        }
    }

    private fun show(panel: View) {
        listOf(setupPanel, lockPanel, statusPanel).forEach {
            it.visibility = if (it === panel) View.VISIBLE else View.GONE
        }
    }

    // ---------- Setup ----------

    private fun connect() {
        val email = text(R.id.emailInput).trim()
        val password = text(R.id.passwordInput)
        val name = text(R.id.nameInput).trim()
        val pin = text(R.id.pinInput)
        val pin2 = text(R.id.pin2Input)

        val problem = when {
            email.isEmpty() || password.isEmpty() -> "Enter the parent email and password."
            name.isEmpty() -> "Enter your kid's name."
            pin.length !in 4..8 || !pin.all { it.isDigit() } -> "The PIN must be 4 to 8 digits."
            pin != pin2 -> "The two PINs don't match."
            else -> null
        }
        if (problem != null) {
            setupError(problem)
            return
        }
        setupError(null)

        val button = findViewById<Button>(R.id.connectButton)
        button.isEnabled = false
        button.text = "Connecting…"
        FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (!task.isSuccessful || uid == null) {
                    button.isEnabled = true
                    button.text = "Connect this phone"
                    setupError(task.exception?.localizedMessage ?: "Sign-in failed. Check the email and password.")
                    return@addOnCompleteListener
                }
                // A co-parent's account points to the family it joined.
                Fire.db.collection("users").document(uid).get().addOnCompleteListener(this) { userTask ->
                    button.isEnabled = true
                    button.text = "Connect this phone"
                    val family = if (userTask.isSuccessful) userTask.result?.getString("familyUid") else null
                    finishConnect(authUid = uid, familyUid = family ?: uid, name = name, pin = pin)
                }
            }
    }

    private fun finishConnect(authUid: String, familyUid: String, name: String, pin: String) {
        val uid = familyUid
        prefs.authUid = authUid
        prefs.parentUid = uid
        prefs.deviceName = name
        prefs.setPin(pin)
        val now = System.currentTimeMillis()
        prefs.lockHandledAt = now
        prefs.releaseHandledAt = now
        // Start unlocked: don't let an old "lock" command re-lock a freshly linked phone.
        prefs.lockOffAt = now
        prefs.settingsUnlockHandledAt = now

        Fire.deviceRef(uid, prefs.deviceId).set(
            mapOf("name" to name, "linkedAt" to FieldValue.serverTimestamp(), "released" to false),
            SetOptions.merge(),
        )
        val rulesRef = Fire.rulesRef(uid, prefs.deviceId)
        rulesRef.get().addOnSuccessListener { snap ->
            if (!snap.exists()) rulesRef.set(Rules.DEFAULT_FIRESTORE)
        }

        SyncWorker.schedule(this)
        SyncWorker.runNow(this)
        unlockedUntil = System.currentTimeMillis() + UNLOCK_MS
        refresh()
    }

    private fun setupError(msg: String?) {
        val v = findViewById<TextView>(R.id.setupError)
        v.text = msg ?: ""
        v.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    // ---------- PIN lock ----------

    private fun tryUnlock() {
        val now = System.currentTimeMillis()
        val input = findViewById<EditText>(R.id.unlockPinInput)
        if (now < lockoutUntil) {
            toast("Too many wrong tries. Wait a minute.")
            return
        }
        if (prefs.checkPin(input.text.toString())) {
            failedPins = 0
            unlockedUntil = now + UNLOCK_MS
            input.setText("")
            refresh()
        } else {
            failedPins++
            input.setText("")
            if (failedPins >= 5) {
                failedPins = 0
                lockoutUntil = now + 60_000L
                Alerts.send(
                    this, "pin_attempts", "Wrong PIN attempts",
                    "Someone entered the wrong Cell Home Kids PIN 5 times on ${prefs.deviceName}'s phone.",
                    "pin",
                )
            }
            toast("Wrong PIN")
        }
    }

    // ---------- Status / checklist ----------

    private fun renderStatus() {
        checklist.removeAllViews()
        val usageOk = Status.usageAccess(this)
        val adminOk = Status.adminOn(this)
        val batteryOk = Status.batteryOptIgnored(this)
        val a11yOk = Status.accessibilityOn(this)

        addStep("1. Usage access", "Lets Cell Home Kids measure screen time per app.", usageOk, "Grant") {
            openSettings(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        addStep("2. Uninstall protection", "Stops Cell Home Kids being uninstalled.", adminOk, "Turn on") {
            requestAdmin()
        }
        addStep("3. Run in background", "Keeps reports and alerts working when the screen is off.", batteryOk, "Allow") {
            openSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        addStep(
            "4. Blocking service",
            "Turn on \"Cell Home Kids app blocking\". If it's greyed out (Android 13+), tap App info → ⋮ menu → " +
                "Allow restricted settings, then try again.",
            a11yOk, "Turn on",
            extraLabel = "App info",
            extra = {
                openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        ) {
            openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val locationOk = LocationTracker.hasPermission(this)
        addStep(
            "5. Location (optional)",
            "Shows this phone on the map. For live location, set it to \"Allow all the time\" in the next screen.",
            locationOk, "Allow",
        ) {
            requestLocation()
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        addStep(
            "Full control (optional)",
            if (DeviceOwner.isOwner(this)) {
                "On. Cell Home Kids can't be uninstalled, and safe mode, factory reset, new users and date changes are blocked."
            } else {
                "Off. Use the USB setup script from your computer to turn it on (see README). Without it, a " +
                    "determined kid could bypass Cell Home Kids with safe mode or a factory reset."
            },
            DeviceOwner.isOwner(this), null,
        ) {}

        val ready = usageOk && a11yOk
        if (prefs.setupComplete) {
            statusTitle.text = if (ready) "Protection is on" else "Protection needs attention"
            statusBody.text = "Protecting ${prefs.deviceName}. Change rules from the parent dashboard."
            finishButton.visibility = View.GONE
            pauseButton.visibility = View.VISIBLE
        } else {
            statusTitle.text = "Finish setting up"
            statusBody.text = "Do steps 1 and 4 at least (all of them is best), then tap Finish."
            finishButton.visibility = View.VISIBLE
            finishButton.isEnabled = ready
            pauseButton.visibility = View.GONE
        }

        val pausedUntil = prefs.localPauseUntil
        pauseButton.text = if (pausedUntil > System.currentTimeMillis()) {
            "Resume protection (paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))})"
        } else {
            "Pause protection for 15 minutes"
        }

        // Check for a new app version once when the screen is shown (no USB needed to update).
        if (prefs.setupComplete && !updateChecked) {
            updateChecked = true
            Updater.checkAsync(this) { info -> if (info != null) offerUpdate(info) }
        }
    }

    private fun offerUpdate(info: Updater.Info) {
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("A newer version of Cell Home Kids (${info.name}) is ready. Update now? Your settings stay the same.")
            .setPositiveButton("Update now") { _, _ ->
                Updater.downloadAndInstall(this, info) { msg -> toast(msg) }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun addStep(
        title: String,
        desc: String,
        done: Boolean,
        action: String?,
        extraLabel: String? = null,
        extra: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val row = layoutInflater.inflate(R.layout.item_step, checklist, false)
        row.findViewById<TextView>(R.id.stepTitle).text = title
        row.findViewById<TextView>(R.id.stepDesc).text = desc
        row.findViewById<TextView>(R.id.stepState).apply {
            text = if (done) "✓ Done" else if (action == null) "Off" else "Not set"
            setTextColor(ContextCompat.getColor(this@MainActivity, if (done) R.color.ok else R.color.warn))
        }
        row.findViewById<Button>(R.id.stepButton).apply {
            text = action ?: ""
            visibility = if (done || action == null) View.GONE else View.VISIBLE
            setOnClickListener { onClick() }
        }
        row.findViewById<Button>(R.id.stepExtra).apply {
            if (extraLabel != null && extra != null && !done) {
                text = extraLabel
                visibility = View.VISIBLE
                setOnClickListener { extra() }
            } else {
                visibility = View.GONE
            }
        }
        checklist.addView(row)
    }

    /** Opens a system screen. If Settings are locked, pause briefly so the parent can use them. */
    private fun openSettings(intent: Intent) {
        if (prefs.setupComplete) {
            prefs.localPauseUntil = maxOf(prefs.localPauseUntil, System.currentTimeMillis() + 10 * 60_000L)
            settingsPauseActive = true
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                toast("Couldn't open Settings on this phone.")
            }
        }
    }

    private fun requestLocation() {
        val perms = arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        try {
            androidx.core.app.ActivityCompat.requestPermissions(this, perms, 42)
        } catch (e: Exception) {
            // Fall through to the App info screen opened by the caller.
        }
    }

    private fun requestAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents Cell Home Kids from being uninstalled without the parent.",
            )
        openSettings(intent)
    }

    private fun finishSetup() {
        prefs.setupComplete = true
        prefs.localPauseUntil = 0L
        RulesStore.load(this)
        DeviceOwner.enforce(this, RulesStore.rules, blockingServiceOn = Status.accessibilityOn(this))
        SyncWorker.runNow(this)
        toast("Protection is on")
        renderStatus()
    }

    private fun togglePause() {
        val now = System.currentTimeMillis()
        settingsPauseActive = false
        // Resuming (turning protection back ON) is always allowed — it only makes the phone safer.
        if (prefs.localPauseUntil > now) {
            prefs.localPauseUntil = 0L
            renderStatus()
            return
        }
        // Pausing (turning protection OFF) requires the parent PIN every time, so a kid
        // can never pause it even if they reach this screen.
        promptPin("Enter the parent PIN to pause protection") {
            prefs.localPauseUntil = System.currentTimeMillis() + 15 * 60_000L
            renderStatus()
        }
    }

    /** Asks for the parent PIN in a dialog; runs [onOk] only if it's correct. */
    private fun promptPin(message: String, onOk: () -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Parent PIN"
        }
        AlertDialog.Builder(this)
            .setTitle("Parent PIN")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (prefs.checkPin(input.text.toString())) {
                    onOk()
                } else {
                    toast("Wrong PIN")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUnlink() {
        AlertDialog.Builder(this)
            .setTitle("Unlink this phone?")
            .setMessage("Cell Home Kids will stop blocking and reporting, and full control is removed. You can then uninstall it.")
            .setPositiveButton("Unlink") { _, _ -> unlink() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unlink() {
        unlockedUntil = 0L
        Unlink.run(this) {
            toast("Unlinked. You can now uninstall Cell Home Kids.")
            refresh()
        }
    }

    private fun text(id: Int): String = findViewById<EditText>(id).text?.toString() ?: ""

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val UNLOCK_MS = 5 * 60_000L
        private var unlockedUntil = 0L
        private var lockoutUntil = 0L
        private var failedPins = 0
        private var settingsPauseActive = false
        private var updateChecked = false
    }
}
