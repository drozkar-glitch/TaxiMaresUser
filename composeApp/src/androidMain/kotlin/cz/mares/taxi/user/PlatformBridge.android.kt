package cz.mares.taxi.user

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.util.concurrent.TimeUnit
import kotlin.math.*

actual object PlatformBridge {
    private var appContext: Context? = null
    private var activityRef: WeakReference<MainActivity>? = null
    private var pendingNotifUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val bgPoller = Executors.newSingleThreadScheduledExecutor()
    private var pollerStarted = false
    private const val CHANNEL_ID = "taxi_mares_high_channel"

    @Volatile
    var mapboxToken: String = ""
        private set

    private val tokenLock = Any()

    fun init(activity: MainActivity) {
        appContext = activity.applicationContext
        activityRef = WeakReference(activity)
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {}
        mapboxToken = loadPref("mapbox_token", "")
        ioExecutor.execute {
            ensureMapboxTokenLoaded()
        }
        ensureNotificationChannel()
        startBackgroundInboxPoller()
    }

    fun ensureMapboxTokenLoaded(): String {
        if (mapboxToken.isNotBlank()) return mapboxToken
        synchronized(tokenLock) {
            if (mapboxToken.isNotBlank()) return mapboxToken
            val cached = loadPref("mapbox_token", "")
            if (cached.isNotBlank()) {
                mapboxToken = cached
                return mapboxToken
            }
            var conn: HttpURLConnection? = null
            try {
                val u = URL("${TaxiApi.BASE_URL}/taxi/app/")
                conn = (u.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "TaxiMaresApp/2.0")
                }
                if (conn.responseCode in 200..299) {
                    val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val match = Regex("""window\.MAPBOX_TOKEN\s*=\s*['"]([^'"]+)['"]""").find(html)
                    val extracted = match?.groupValues?.getOrNull(1)?.trim() ?: ""
                    if (extracted.isNotBlank()) {
                        mapboxToken = extracted
                        savePref("mapbox_token", extracted)
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
            return mapboxToken
        }
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

    actual fun areAllPermissionsGranted(): Boolean {
        val act = activityRef?.get() ?: return false
        return act.hasAllNeededPermissions()
    }

    actual fun requestAllPermissions(callback: (Boolean) -> Unit) {
        val act = activityRef?.get()
        if (act == null) {
            callback(false)
            return
        }
        act.requestPermissionsInternal { granted ->
            if (granted) {
                showSystemNotification(
                    id = "perm_ok",
                    title = "🚕 Taxi Mareš",
                    body = "Upozornění na příjezd vozu a schválení jízdy jsou aktivní.",
                    actionUrl = "?open=home"
                )
            }
            callback(granted)
        }
    }

    private fun ensureNotificationChannel() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Taxi Mareš – Objednávky a jízdy",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Okamžitá upozornění na schválení jízdy a příjezd vozu"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 350L, 150L, 350L)
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

            builder.setSmallIcon(R.drawable.ic_launcher)
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

    private fun startBackgroundInboxPoller() {
        if (pollerStarted) return
        pollerStarted = true
        bgPoller.scheduleWithFixedDelay({
            try {
                val tk = loadPref("taxi_api_token")
                if (tk.isNotBlank()) {
                    TaxiApi.fetchInboxMessages { res ->
                        res.onSuccess { msgList ->
                            val unread = msgList.filter { !it.isRead }
                            val seenRaw = loadPref("seen_push_ids", "")
                            val isFirstRun = seenRaw.isEmpty()
                            val seenSet = seenRaw.split(",").filter { it.isNotBlank() }.toMutableSet()
                            var changed = false

                            for (m in unread) {
                                if (m.msgId.isNotBlank() && !seenSet.contains(m.msgId)) {
                                    seenSet.add(m.msgId)
                                    changed = true
                                    if (!isFirstRun || m == unread.firstOrNull()) {
                                        showSystemNotification(
                                            id = m.msgId,
                                            title = m.title.ifBlank { "Taxi Mareš" },
                                            body = m.body,
                                            actionUrl = m.actionUrl.ifBlank { "?open=inbox" }
                                        )
                                    }
                                }
                            }
                            if (changed || isFirstRun) {
                                val saved = if (seenSet.isEmpty()) "init" else seenSet.toList().takeLast(80).joinToString(",")
                                savePref("seen_push_ids", saved)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }, 3L, 6L, TimeUnit.SECONDS)
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
        if (act != null && !act.hasLocationPermission()) {
            act.requestPermissionsInternal { _ ->
                if (act.hasLocationPermission()) {
                    fetchLocationInternal(callback)
                } else {
                    callback(null, null)
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

private val tileBitmapsCache = mutableStateMapOf<String, ImageBitmap>()
private val tileLoadingSet = HashSet<String>()
private val tileExecutor = Executors.newFixedThreadPool(4)
private val tileMainHandler = Handler(Looper.getMainLooper())

private fun loadMapTileAsync(isDark: Boolean, z: Int, x: Int, y: Int) {
    val maxTile = (1 shl z) - 1
    if (x < 0 || x > maxTile || y < 0 || y > maxTile) return
    val modePrefix = if (isDark) "dark" else "light"
    val key = "$modePrefix/$z/$x/$y"
    if (tileBitmapsCache.containsKey(key) || synchronized(tileLoadingSet) { !tileLoadingSet.add(key) }) return

    tileExecutor.execute {
        var conn: HttpURLConnection? = null
        try {
            val mbToken = PlatformBridge.ensureMapboxTokenLoaded()
            val urlStr = if (mbToken.isNotBlank()) {
                val styleId = if (isDark) "navigation-night-v1" else "navigation-day-v1"
                "https://api.mapbox.com/styles/v1/mapbox/$styleId/tiles/256/$z/$x/$y@2x?access_token=$mbToken"
            } else {
                if (isDark) {
                    "https://a.basemaps.cartocdn.com/dark_all/$z/$x/$y@2x.png"
                } else {
                    "https://a.tile.openstreetmap.org/$z/$x/$y.png"
                }
            }
            val u = URL(urlStr)
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "TaxiMaresApp/2.0")
                setRequestProperty("Referer", "${TaxiApi.BASE_URL}/")
            }
            if (conn.responseCode in 200..299) {
                val bytes = conn.inputStream.use { it.readBytes() }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    val img = bmp.asImageBitmap()
                    tileMainHandler.post {
                        tileBitmapsCache[key] = img
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            conn?.disconnect()
            synchronized(tileLoadingSet) { tileLoadingSet.remove(key) }
        }
    }
}

private fun lonToTileX(lon: Double, z: Int): Double {
    return (lon + 180.0) / 360.0 * (1 shl z).toDouble()
}

private fun latToTileY(lat: Double, z: Int): Double {
    val rad = lat.coerceIn(-85.05, 85.05) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z).toDouble()
}

private fun tileXToLon(tileX: Double, z: Int): Double {
    return tileX / (1 shl z).toDouble() * 360.0 - 180.0
}

private fun tileYToLat(tileY: Double, z: Int): Double {
    val n = PI - 2.0 * PI * tileY / (1 shl z).toDouble()
    return 180.0 / PI * atan(0.5 * (exp(n) - exp(-n)))
}

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
    var zoom by remember { mutableStateOf(14) }
    var centerLat by remember { mutableStateOf(50.4222208) }
    var centerLon by remember { mutableStateOf(14.2540608) }

    LaunchedEffect(flyToTarget) {
        if (flyToTarget != null) {
            centerLat = flyToTarget.first
            centerLon = flyToTarget.second
            onMapCenterChanged(centerLat, centerLon)
        }
    }

    LaunchedEffect(routePoints) {
        if (routePoints.size >= 2) {
            val minLat = routePoints.minOf { it.first }
            val maxLat = routePoints.maxOf { it.first }
            val minLon = routePoints.minOf { it.second }
            val maxLon = routePoints.maxOf { it.second }
            centerLat = (minLat + maxLat) / 2.0 - (maxLat - minLat) * 0.18
            centerLon = (minLon + maxLon) / 2.0
            val span = max(maxLat - minLat, maxLon - minLon)
            zoom = when {
                span > 1.2 -> 8
                span > 0.6 -> 9
                span > 0.3 -> 10
                span > 0.15 -> 11
                span > 0.07 -> 12
                span > 0.03 -> 13
                else -> 14
            }
        }
    }

    LaunchedEffect(trackingData?.lat, trackingData?.lon) {
        if (trackingData != null && trackingData.ok && trackingData.lat != 0.0 && trackingData.lon != 0.0) {
            centerLat = trackingData.lat
            centerLon = trackingData.lon
        }
    }

    val modePrefix = if (isDark) "dark" else "light"

    Box(modifier = modifier.background(if (isDark) Color(0xFF121418) else Color(0xFFE5E3DF))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom) {
                    val tilePx = 256.dp.toPx()
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val cTileX = lonToTileX(centerLon, zoom) - (pan.x / tilePx)
                        val cTileY = latToTileY(centerLat, zoom) - (pan.y / tilePx)
                        centerLon = tileXToLon(cTileX, zoom).coerceIn(-179.0, 179.0)
                        centerLat = tileYToLat(cTileY, zoom).coerceIn(-85.0, 85.0)
                        if (gestureZoom > 1.18f && zoom < 18) zoom++
                        else if (gestureZoom < 0.82f && zoom > 6) zoom--
                        onMapCenterChanged(centerLat, centerLon)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val tilePx = 256.dp.toPx()
            val tilePxInt = tilePx.roundToInt()

            val cTileX = lonToTileX(centerLon, zoom)
            val cTileY = latToTileY(centerLat, zoom)

            val halfCols = ceil(w / (2.0 * tilePx)).toInt() + 1
            val halfRows = ceil(h / (2.0 * tilePx)).toInt() + 1

            val minTileX = floor(cTileX).toInt() - halfCols
            val maxTileX = floor(cTileX).toInt() + halfCols
            val minTileY = floor(cTileY).toInt() - halfRows
            val maxTileY = floor(cTileY).toInt() + halfRows

            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val screenX = (w / 2.0 + (tx - cTileX) * tilePx).roundToInt()
                    val screenY = (h / 2.0 + (ty - cTileY) * tilePx).roundToInt()
                    val bmp = tileBitmapsCache["$modePrefix/$zoom/$tx/$ty"]
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(screenX, screenY),
                            dstSize = IntSize(tilePxInt + 1, tilePxInt + 1)
                        )
                    } else {
                        loadMapTileAsync(isDark, zoom, tx, ty)
                    }
                }
            }

            fun toScreen(lat: Double, lon: Double): Offset {
                val px = (w / 2.0 + (lonToTileX(lon, zoom) - cTileX) * tilePx).toFloat()
                val py = (h / 2.0 + (latToTileY(lat, zoom) - cTileY) * tilePx).toFloat()
                return Offset(px, py)
            }

            if (routePoints.size >= 2) {
                val path = Path()
                val first = toScreen(routePoints[0].first, routePoints[0].second)
                path.moveTo(first.x, first.y)
                for (i in 1 until routePoints.size) {
                    val pt = toScreen(routePoints[i].first, routePoints[i].second)
                    path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path = path,
                    color = Color.Black.copy(alpha = 0.85f),
                    style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = Color(0xFFFFCC00),
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            startPoint?.let { sp ->
                val pos = toScreen(sp.lat, sp.lon)
                drawCircle(color = Color.Black, radius = 11.dp.toPx(), center = pos)
                drawCircle(color = Color(0xFF2ECC71), radius = 8.5.dp.toPx(), center = pos)
                drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = pos)
            }

            destPoint?.let { dp ->
                val pos = toScreen(dp.lat, dp.lon)
                drawCircle(color = Color.Black, radius = 11.dp.toPx(), center = pos)
                drawCircle(color = Color(0xFFFFCC00), radius = 8.5.dp.toPx(), center = pos)
                drawCircle(color = Color.Black, radius = 3.5.dp.toPx(), center = pos)
            }

            trackingData?.takeIf { it.ok && it.lat != 0.0 && it.lon != 0.0 }?.let { td ->
                if (td.targetLat != 0.0 && td.targetLon != 0.0) {
                    val tPos = toScreen(td.targetLat, td.targetLon)
                    drawCircle(color = Color.Black, radius = 10.dp.toPx(), center = tPos)
                    drawCircle(color = Color(0xFFFFCC00), radius = 7.dp.toPx(), center = tPos)
                }
                val carPos = toScreen(td.lat, td.lon)
                drawCircle(color = Color.Black.copy(alpha = 0.65f), radius = 14.dp.toPx(), center = carPos)
                drawCircle(color = Color(0xFFFFCC00), radius = 11.dp.toPx(), center = carPos)
                drawCircle(color = Color(0xFF141418), radius = 5.5.dp.toPx(), center = carPos)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 64.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { if (zoom < 18) zoom++ },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xD9141418), CircleShape)
            ) {
                Text("＋", color = Color(0xFFFFCC00), fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            IconButton(
                onClick = { if (zoom > 6) zoom-- },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color(0xD9141418), CircleShape)
            ) {
                Text("－", color = Color(0xFFFFCC00), fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
        }
    }
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
