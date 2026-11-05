package cz.mares.taxi.user

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var pendingPermCallback: ((Boolean) -> Unit)? = null
    private var lastRequestTime = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val allOk = hasAllNeededPermissions()
        val elapsed = System.currentTimeMillis() - lastRequestTime
        // Pokud systém dialog okamžitě zamítl (< 250 ms) a oprávnění stále chybí, otevřeme nastavení aplikace
        if (!allOk && elapsed < 250L) {
            openAppPermissionSettings()
        }
        val cb = pendingPermCallback
        pendingPermCallback = null
        cb?.invoke(allOk)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformBridge.init(this)
        handleNotificationIntent(intent)
        enableEdgeToEdge()

        setContent {
            App()
        }

        window.decorView.postDelayed({
            if (!hasAllNeededPermissions()) {
                requestPermissionsInternal(null)
            }
        }, 800L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onDestroy() {
        PlatformBridge.detachActivity(this)
        super.onDestroy()
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val actionUrl = intent?.getStringExtra("taxi_action_url")
        if (!actionUrl.isNullOrBlank()) {
            PlatformBridge.setPendingNotificationUrl(actionUrl)
            intent.removeExtra("taxi_action_url")
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasAllNeededPermissions(): Boolean {
        return hasLocationPermission() && hasNotificationPermission()
    }

    fun requestPermissionsInternal(callback: ((Boolean) -> Unit)?) {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback?.invoke(true)
            return
        }
        pendingPermCallback = callback
        lastRequestTime = System.currentTimeMillis()
        try {
            permissionLauncher.launch(missing.toTypedArray())
        } catch (_: Exception) {
            pendingPermCallback = null
            callback?.invoke(false)
        }
    }

    private fun openAppPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {}
    }
}
