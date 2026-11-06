package cz.mares.taxi.user

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

expect object PlatformBridge {
    fun httpRequest(
        url: String,
        method: String,
        headers: kotlin.collections.Map<String, String>,
        body: String?,
        callback: (Boolean, String) -> Unit
    )

    fun loadPref(key: String, default: String = ""): String
    fun savePref(key: String, value: String)
    fun urlEncode(value: String): String
    fun currentTimeMillis(): Long
    fun currentDateIso(): String
    fun formatDateTimeOffset(minutesFromNow: Int): String
    fun areAllPermissionsGranted(): Boolean
    fun requestAllPermissions(callback: (Boolean) -> Unit)
    fun requestLocation(callback: (Double?, Double?) -> Unit)
    fun showSystemNotification(id: String, title: String, body: String, actionUrl: String)
    fun consumePendingNotificationUrl(): String?
    fun clearCacheAndReload(onDone: () -> Unit)
    fun openExternalUrl(url: String)
    fun dialPhone(phone: String)
}

@Composable
expect fun InteractiveTaxiMap(
    isDark: Boolean,
    routePoints: List<Pair<Double, Double>>,
    startPoint: GeoPoint?,
    destPoint: GeoPoint?,
    trackingData: TrackingData?,
    pickMode: Boolean,
    flyToTarget: Pair<Double, Double>?,
    onMapCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
)

@Composable
expect fun EmbeddedWebModal(
    url: String,
    onDetectedToken: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
)
