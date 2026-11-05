package cz.mares.taxi.user

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

actual object PlatformBridge {
    private var appContext: Context? = null
    private var activityRef: WeakReference<MainActivity>? = null
    private var pendingNotifUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private const val CHANNEL_ID = "taxi_mares_notifications"

    fun init(activity: MainActivity) {
        appContext = activity.applicationContext
        activityRef = WeakReference(activity)
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {}
        ensureNotificationChannel()
    }

    fun detachActivity(activity: MainActivity) {
        if (activityRef?.get() == activity) {
            activityRef = null
        }
    }

    fun setPendingNotificationUrl(url: String) {
        pendingNotifUrl = url
    }

    actual fun consumePendingNotificationUrl(): String? {
        val u = pendingNotifUrl
        pendingNotifUrl = null
        return u
    }

    private fun ensureNotificationChannel() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Taxi Mareš – Objednávky a příjezd vozu",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Upozornění na schválení jízdy, zprávy z dispečinku a příjezd vozu"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 300L, 120L, 300L)
            }
            nm.createNotificationChannel(channel)
        }
    }

    actual fun showSystemNotification(id: String, title: String, body: String, actionUrl: String) {
        val ctx = appContext ?: return
        ensureNotificationChannel()
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val intent = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("taxi_action_url", actionUrl)
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val notifId = id.hashCode()
            val pendingIntent = PendingIntent.getActivity(ctx, notifId, intent, pendingFlags)

            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(ctx, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }

            builder.setSmallIcon(ctx.applicationInfo.icon)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")
                builder.setPriority(Notification.PRIORITY_HIGH)
                @Suppress("DEPRECATION")
                builder.setDefaults(Notification.DEFAULT_ALL)
            }

            nm.notify(notifId, builder.build())
        } catch (_: Exception) {}
    }

    actual fun httpRequest(
        url: String,
        method: String,
        headers: kotlin.collections.Map<String, String>,
        body: String?,
        callback: (Boolean, String) -> Unit
    ) {
        ioExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                val u = URL(url)
                conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 12000
                    readTimeout = 15000
                    doInput = true
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
                    if (!cookie.isNullOrBlank()) {
                        setRequestProperty("Cookie", cookie)
                    }
                    if (body != null && method == "POST") {
                        doOutput = true
                        outputStream.use { os ->
                            os.write(body.toByteArray(Charsets.UTF_8))
                        }
                    }
                }

                val code = conn.responseCode
                val setCookies = conn.headerFields["Set-Cookie"]
                if (setCookies != null) {
                    try {
                        val cm = CookieManager.getInstance()
                        setCookies.forEach { c -> cm.setCookie(url, c) }
                        cm.flush()
                    } catch (_: Exception) {}
                }

                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: ""

                val ok = code in 200..299
                mainHandler.post { callback(ok, responseText) }
            } catch (_: Exception) {
                mainHandler.post { callback(false, "") }
            } finally {
                conn?.disconnect()
            }
        }
    }

    actual fun loadPref(key: String, default: String): String {
        val ctx = appContext ?: return default
        val prefs = ctx.getSharedPreferences("taxi_mares_prefs", Context.MODE_PRIVATE)
        return prefs.getString(key, default) ?: default
    }

    actual fun savePref(key: String, value: String) {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences("taxi_mares_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(key, value).apply()
    }

    actual fun urlEncode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    actual fun currentTimeMillis(): Long = System.currentTimeMillis()

    actual fun currentDateIso(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    actual fun formatDateTimeOffset(minutesFromNow: Int): String {
        val target = Date(System.currentTimeMillis() + minutesFromNow * 60_000L)
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).format(target)
    }

    @SuppressLint("MissingPermission")
    actual fun requestLocation(callback: (Double?, Double?) -> Unit) {
        val act = activityRef?.get()
        if (act != null) {
            act.ensureLocationPermission { granted ->
                if (!granted) {
                    callback(null, null)
                } else {
                    fetchLocationInternal(callback)
                }
            }
        } else {
            fetchLocationInternal(callback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocationInternal(callback: (Double?, Double?) -> Unit) {
        val ctx = appContext
        if (ctx == null) {
            callback(null, null)
            return
        }
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsLoc = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null
            val netLoc = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null

            val bestLast = when {
                gpsLoc != null && netLoc != null -> if (gpsLoc.time >= netLoc.time) gpsLoc else netLoc
                gpsLoc != null -> gpsLoc
                else -> netLoc
            }

            if (bestLast != null && (System.currentTimeMillis() - bestLast.time) < 90_000L) {
                callback(bestLast.latitude, bestLast.longitude)
                return
            }

            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }

            if (provider == null) {
                callback(bestLast?.latitude, bestLast?.longitude)
                return
            }

            var answered = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!answered) {
                        answered = true
                        lm.removeUpdates(this)
                        mainHandler.post { callback(location.latitude, location.longitude) }
                    }
                }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            mainHandler.postDelayed({
                if (!answered) {
                    answered = true
                    lm.removeUpdates(listener)
                    callback(bestLast?.latitude, bestLast?.longitude)
                }
            }, 6000L)
        } catch (_: Exception) {
            callback(null, null)
        }
    }

    actual fun openExternalUrl(url: String) {
        val ctx = appContext ?: return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }

    actual fun dialPhone(phone: String) {
        val ctx = appContext ?: return
        try {
            val clean = if (phone.startsWith("tel:")) phone else "tel:$phone"
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(clean)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {}
    }
}

private class AndroidLeafletBridge(
    private val onCenterChanged: (Double, Double) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapMoved(lat: Double, lon: Double) {
        handler.post {
            onCenterChanged(lat, lon)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun InteractiveTaxiMap(
    isDark: Boolean,
    routePoints: List<Pair<Double, Double>>,
    startPoint: GeoPoint?,
    destPoint: GeoPoint?,
    trackingData: TrackingData?,
    pickMode: Boolean,
    flyToTarget: Pair<Double, Double>?,
    onMapCenterChanged: (Double, Double) -> Unit,
    modifier: Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    val statePayloadJson = remember(isDark, routePoints, startPoint, destPoint, trackingData, flyToTarget) {
        val ptsJson = routePoints.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
        val startJson = if (startPoint != null) "[${startPoint.lat},${startPoint.lon}]" else "null"
        val destJson = if (destPoint != null) "[${destPoint.lat},${destPoint.lon}]" else "null"
        val flyJson = if (flyToTarget != null) "[${flyToTarget.first},${flyToTarget.second}]" else "null"
        val trackJson = if (trackingData != null && trackingData.ok && trackingData.lat != 0.0) {
            """{"lat":${trackingData.lat},"lon":${trackingData.lon},"tLat":${trackingData.targetLat},"tLon":${trackingData.targetLon},"color":"${trackingData.carColor}","type":"${trackingData.carType}"}"""
        } else "null"
        """{"isDark":$isDark,"route":$ptsJson,"start":$startJson,"dest":$destJson,"fly":$flyJson,"track":$trackJson}"""
    }

    LaunchedEffect(statePayloadJson, isMapReady) {
        if (isMapReady) {
            webViewRef?.evaluateJavascript("if(window.syncTaxiMap){window.syncTaxiMap($statePayloadJson);}", null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                setBackgroundColor(android.graphics.Color.parseColor("#0A0A0D"))
                addJavascriptInterface(AndroidLeafletBridge(onMapCenterChanged), "AndroidBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        isMapReady = true
                        view?.evaluateJavascript("if(window.syncTaxiMap){window.syncTaxiMap($statePayloadJson);}", null)
                    }
                }

                val html = """
                    <!DOCTYPE html>
                    <html data-theme="${if (isDark) "dark" else "light"}">
                    <head>
                        <meta charset="utf-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
                        <style>
                            * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                            html, body, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0a0a0d; overflow: hidden; }
                            html[data-theme="dark"] .leaflet-tile-container { filter: brightness(85%) contrast(105%); }
                            svg path.leaflet-interactive {
                                stroke: #ffcc00 !important;
                                stroke-width: 5px !important;
                                stroke-opacity: 1 !important;
                                stroke-linecap: round !important;
                                stroke-linejoin: round !important;
                                filter: drop-shadow(1px 1px 2px rgba(0,0,0,0.85)) drop-shadow(-1px -1px 2px rgba(0,0,0,0.85)) !important;
                            }
                        </style>
                    </head>
                    <body>
                        <div id="map"></div>
                        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
                        <script>
                            var map = L.map('map', { zoomControl: false, attributionControl: false, zoomSnap: 0 }).setView([50.4222208, 14.2540608], 14);
                            var currentDark = null;
                            var tileLayer = null;
                            var routeLayer = null;
                            var carMarker = null;
                            var targetMarker = null;
                            var lastFlyKey = "";
                            var lastRouteKey = "";

                            var taxiIcon = L.icon({
                                iconUrl: 'https://606420002.com/taxi/app/assets/images/taxi-point.png',
                                iconSize: [34, 34],
                                iconAnchor: [17, 34]
                            });

                            function updateThemeTiles(isDark) {
                                if (currentDark === isDark && tileLayer) return;
                                currentDark = isDark;
                                document.documentElement.setAttribute('data-theme', isDark ? 'dark' : 'light');
                                if (tileLayer) map.removeLayer(tileLayer);
                                var url = isDark
                                    ? 'https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png'
                                    : 'https://606420002.com/taxi/admin/modules/pricing/assets/tiles.php?mode=tiles&set=basic&tileSize=256&z={z}&x={x}&y={y}&lang=cs';
                                tileLayer = L.tileLayer(url, { maxZoom: 19 }).addTo(map);
                            }

                            map.on('moveend', function() {
                                var c = map.getCenter();
                                if (window.AndroidBridge && window.AndroidBridge.onMapMoved) {
                                    window.AndroidBridge.onMapMoved(c.lat, c.lng);
                                }
                            });

                            function buildCarSvg(carColor) {
                                return '<svg viewBox="0 0 40 80" width="22" height="44" style="overflow:visible;"><rect x="2" y="2" width="36" height="76" rx="8" fill="rgba(0,0,0,0.35)"/><rect x="4" y="4" width="32" height="72" rx="8" fill="' + carColor + '" stroke="#111" stroke-width="1.5"/><path d="M 6 24 L 34 24 L 32 32 L 8 32 Z" fill="#1a1a1a"/><rect x="8" y="32" width="24" height="26" rx="2" fill="' + carColor + '" stroke="#222" stroke-width="0.6"/><rect x="14" y="38" width="12" height="4" rx="1" fill="#ffcc00" stroke="#111" stroke-width="0.5"/></svg>';
                            }

                            window.syncTaxiMap = function(state) {
                                if (!state) return;
                                updateThemeTiles(!!state.isDark);

                                if (state.fly && state.fly.length === 2) {
                                    var fk = state.fly[0] + ',' + state.fly[1];
                                    if (fk !== lastFlyKey) {
                                        lastFlyKey = fk;
                                        map.setView(state.fly, 16, { animate: true });
                                    }
                                }

                                var rk = JSON.stringify({ r: state.route, s: state.start, d: state.dest });
                                if (rk !== lastRouteKey) {
                                    lastRouteKey = rk;
                                    if (routeLayer) {
                                        map.removeLayer(routeLayer);
                                        routeLayer = null;
                                    }
                                    var items = [];
                                    var poly = null;
                                    if (state.route && state.route.length >= 2) {
                                        poly = L.polyline(state.route);
                                        items.push(poly);
                                    }
                                    if (state.start && state.start.length === 2) {
                                        items.push(L.marker(state.start, { icon: taxiIcon }));
                                    }
                                    if (state.dest && state.dest.length === 2) {
                                        items.push(L.marker(state.dest, { icon: taxiIcon }));
                                    }
                                    if (items.length > 0) {
                                        routeLayer = L.layerGroup(items).addTo(map);
                                        if (poly) {
                                            map.fitBounds(poly.getBounds(), { paddingBottomRight: [20, 260], paddingTopLeft: [20, 60], maxZoom: 15 });
                                        }
                                    }
                                }

                                if (state.track && state.track.lat) {
                                    var pos = [state.track.lat, state.track.lon];
                                    if (!carMarker) {
                                        var cIcon = L.divIcon({
                                            className: 'live-car',
                                            html: buildCarSvg(state.track.color || '#ffcc00'),
                                            iconSize: [22, 44],
                                            iconAnchor: [11, 22]
                                        });
                                        carMarker = L.marker(pos, { icon: cIcon }).addTo(map);
                                    } else {
                                        carMarker.setLatLng(pos);
                                    }
                                    if (state.track.tLat && state.track.tLon) {
                                        var tPos = [state.track.tLat, state.track.tLon];
                                        if (!targetMarker) {
                                            targetMarker = L.marker(tPos, { icon: taxiIcon }).addTo(map);
                                        } else {
                                            targetMarker.setLatLng(tPos);
                                        }
                                    }
                                } else {
                                    if (carMarker) { map.removeLayer(carMarker); carMarker = null; }
                                    if (targetMarker) { map.removeLayer(targetMarker); targetMarker = null; }
                                }
                            };
                        </script>
                    </body>
                    </html>
                """.trimIndent()

                loadDataWithBaseURL(TaxiApi.BASE_URL, html, "text/html", "UTF-8", null)
                webViewRef = this
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun EmbeddedWebModal(
    url: String,
    onDetectedToken: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString() ?: return false
                        if (u.startsWith("tel:")) {
                            PlatformBridge.dialPhone(u)
                            return true
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                        super.onPageFinished(view, loadedUrl)
                        view?.evaluateJavascript(
                            "(function(){ return localStorage.getItem('taxi_api_token') || ''; })();"
                        ) { res ->
                            val clean = res?.trim()?.removeSurrounding("\"") ?: ""
                            if (clean.isNotBlank() && clean != "null") {
                                onDetectedToken(clean)
                            }
                        }
                    }
                }
                loadUrl(url)
            }
        }
    )
}
