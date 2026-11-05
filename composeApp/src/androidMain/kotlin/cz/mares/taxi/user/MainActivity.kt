package cz.mares.taxi.user

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var pendingLocationPermissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fine = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val cb = pendingLocationPermissionCallback
        pendingLocationPermissionCallback = null
        cb?.invoke(fine || coarse)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlatformBridge.init(this)
        handleNotificationIntent(intent)
        enableEdgeToEdge()

        setContent {
            App()
        }

        // Požádáme o oprávnění pro GPS polohu a Notifikace ihned po vykreslení okna
        window.decorView.postDelayed({
            requestStartupPermissions()
        }, 700L)
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

    fun requestStartupPermissions() {
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
        if (missing.isNotEmpty()) {
            try {
                permissionLauncher.launch(missing.toTypedArray())
            } catch (_: Exception) {}
        }
    }

    fun ensureLocationPermission(callback: (Boolean) -> Unit) {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            callback(true)
            return
        }

        pendingLocationPermissionCallback = callback
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        try {
            permissionLauncher.launch(perms.toTypedArray())
        } catch (_: Exception) {
            pendingLocationPermissionCallback = null
            callback(false)
        }
    }
}
