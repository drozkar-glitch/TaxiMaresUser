package cz.mares.taxi.user

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

actual object PlatformBridge {
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()

    fun init(context: Context) {
        appContext = context.applicationContext
        try {
            CookieManager.getInstance().setAcceptCookie(true)
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
            } catch (e: Exception) {
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
        val ctx = appContext
        if (ctx == null) {
            callback(null, null)
            return
        }
        val fineGranted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
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

            if (bestLast != null && (System.currentTimeMillis() - bestLast.time) < 120_000L) {
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

private fun loadMapTileAsync(z: Int, x: Int, y: Int) {
    val maxTile = (1 shl z) - 1
    if (x < 0 || x > maxTile || y < 0 || y > maxTile) return
    val key = "$z/$x/$y"
    if (tileBitmapsCache.containsKey(key) || synchronized(tileLoadingSet) { !tileLoadingSet.add(key) }) return

    tileExecutor.execute {
        var conn: HttpURLConnection? = null
        try {
            val urlStr = "${TaxiApi.BASE_URL}/taxi/admin/modules/pricing/assets/tiles.php?mode=tiles&set=basic&tileSize=256&z=$z&x=$x&y=$y&lang=cs"
            val u = URL(urlStr)
            conn = (u.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 7000
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

    LaunchedEffect(trackingData?.lat, trackingData?.lon) {
        if (trackingData != null && trackingData.ok && trackingData.lat != 0.0 && trackingData.lon != 0.0) {
            centerLat = trackingData.lat
            centerLon = trackingData.lon
        }
    }

    val darkColorFilter = remember(isDark) {
        if (isDark) {
            val matrix = ColorMatrix(
                floatArrayOf(
                    0.75f, 0f, 0f, 0f, -20f,
                    0f, 0.75f, 0f, 0f, -20f,
                    0f, 0f, 0.82f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            ColorFilter.colorMatrix(matrix)
        } else null
    }

    Box(modifier = modifier.background(if (isDark) Color(0xFF101216) else Color(0xFFE5E3DF))) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val cTileX = lonToTileX(centerLon, zoom) - (pan.x / 256.0)
                        val cTileY = latToTileY(centerLat, zoom) - (pan.y / 256.0)
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
            val cTileX = lonToTileX(centerLon, zoom)
            val cTileY = latToTileY(centerLat, zoom)

            val minTileX = floor(cTileX - (w / 512.0) - 1).toInt()
            val maxTileX = ceil(cTileX + (w / 512.0) + 1).toInt()
            val minTileY = floor(cTileY - (h / 512.0) - 1).toInt()
            val maxTileY = ceil(cTileY + (h / 512.0) + 1).toInt()

            for (tx in minTileX..maxTileX) {
                for (ty in minTileY..maxTileY) {
                    val screenX = (w / 2.0 + (tx - cTileX) * 256.0).roundToInt()
                    val screenY = (h / 2.0 + (ty - cTileY) * 256.0).roundToInt()
                    val bmp = tileBitmapsCache["$zoom/$tx/$ty"]
                    if (bmp != null) {
                        drawImage(
                            image = bmp,
                            dstOffset = IntOffset(screenX, screenY),
                            dstSize = IntSize(256, 256),
                            colorFilter = darkColorFilter
                        )
                    } else {
                        loadMapTileAsync(zoom, tx, ty)
                    }
                }
            }

            fun toScreen(lat: Double, lon: Double): Offset {
                val px = (w / 2.0 + (lonToTileX(lon, zoom) - cTileX) * 256.0).toFloat()
                val py = (h / 2.0 + (latToTileY(lat, zoom) - cTileY) * 256.0).toFloat()
                return Offset(px, py)
            }

            // Vykreslení trasy (#FFCC00 s černým lemem jako ve Water Glass CSS)
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
                    color = Color.Black.copy(alpha = 0.75f),
                    style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = path,
                    color = Color(0xFFFFCC00),
                    style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Špendlík Start
            startPoint?.let { sp ->
                val pos = toScreen(sp.lat, sp.lon)
                drawCircle(color = Color.Black, radius = 18f, center = pos)
                drawCircle(color = Color(0xFF2ECC71), radius = 14f, center = pos)
                drawCircle(color = Color.White, radius = 6f, center = pos)
            }

            // Špendlík Cíl
            destPoint?.let { dp ->
                val pos = toScreen(dp.lat, dp.lon)
                drawCircle(color = Color.Black, radius = 18f, center = pos)
                drawCircle(color = Color(0xFFFFCC00), radius = 14f, center = pos)
                drawCircle(color = Color.Black, radius = 6f, center = pos)
            }

            // Živý vůz při sledování
            trackingData?.takeIf { it.ok && it.lat != 0.0 && it.lon != 0.0 }?.let { td ->
                if (td.targetLat != 0.0 && td.targetLon != 0.0) {
                    val tPos = toScreen(td.targetLat, td.targetLon)
                    drawCircle(color = Color.Black, radius = 16f, center = tPos)
                    drawCircle(color = Color(0xFFFFCC00), radius = 12f, center = tPos)
                }
                val carPos = toScreen(td.lat, td.lon)
                rotate(degrees = 0f, pivot = carPos) {
                    drawCircle(color = Color.Black.copy(alpha = 0.6f), radius = 24f, center = carPos)
                    drawCircle(color = Color(0xFFFFCC00), radius = 19f, center = carPos)
                    drawCircle(color = Color(0xFF141418), radius = 10f, center = carPos)
                }
            }
        }

        // Ovládání zoomu v pravém horním rohu mapy
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { if (zoom < 18) zoom++ },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xCC1B1B22), CircleShape)
            ) {
                Text("＋", color = Color(0xFFFFCC00), fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            IconButton(
                onClick = { if (zoom > 6) zoom-- },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xCC1B1B22), CircleShape)
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
