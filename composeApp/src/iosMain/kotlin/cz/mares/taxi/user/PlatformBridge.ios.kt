@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package cz.mares.taxi.user

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocationManager
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.*

actual object PlatformBridge {
    private val taxiLocationMgr = CLLocationManager()
    private var pendingUrl: String? = null
    private var iosPermsAsked = false

    actual fun httpRequest(
        url: String,
        method: String,
        headers: kotlin.collections.Map<String, String>,
        body: String?,
        callback: (Boolean, String) -> Unit
    ) {
        val nsUrl = NSURL.URLWithString(url)
        if (nsUrl == null) {
            dispatch_async(dispatch_get_main_queue()) { callback(false, "") }
            return
        }
        val req = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod(method)
            setTimeoutInterval(15.0)
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
            if (body != null && method == "POST") {
                val nsBody = NSString.create(string = body)
                setHTTPBody(nsBody.dataUsingEncoding(NSUTF8StringEncoding))
            }
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
            val httpResp = response as? NSHTTPURLResponse
            val status = httpResp?.statusCode?.toInt() ?: 0
            val isOk = (error == null) && (status in 200..299)
            val textOut = if (data != null) {
                NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString() ?: ""
            } else {
                ""
            }
            dispatch_async(dispatch_get_main_queue()) {
                callback(isOk, textOut)
            }
        }
        task.resume()
    }

    actual fun loadPref(key: String, default: String): String {
        return NSUserDefaults.standardUserDefaults.stringForKey("taxi_mares_$key") ?: default
    }

    actual fun savePref(key: String, value: String) {
        NSUserDefaults.standardUserDefaults.setObject(value, forKey = "taxi_mares_$key")
        NSUserDefaults.standardUserDefaults.synchronize()
    }

    actual fun urlEncode(value: String): String {
        val allowed = NSCharacterSet.URLQueryAllowedCharacterSet.mutableCopy() as NSMutableCharacterSet
        allowed.removeCharactersInString("&=?+/")
        return NSString.create(string = value)
            .stringByAddingPercentEncodingWithAllowedCharacters(allowed) ?: value
    }

    actual fun currentTimeMillis(): Long {
        return (NSDate().timeIntervalSince1970 * 1000.0).toLong()
    }

    actual fun currentDateIso(): String {
        val fmt = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd" }
        return fmt.stringFromDate(NSDate())
    }

    actual fun formatDateTimeOffset(minutesFromNow: Int): String {
        val targetDate = NSDate.dateWithTimeIntervalSinceNow(minutesFromNow * 60.0)
        val fmt = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd'T'HH:mm" }
        return fmt.stringFromDate(targetDate)
    }

    actual fun areAllPermissionsGranted(): Boolean = iosPermsAsked

    actual fun requestAllPermissions(callback: (Boolean) -> Unit) {
        iosPermsAsked = true
        taxiLocationMgr.requestWhenInUseAuthorization()
        taxiLocationMgr.startUpdatingLocation()
        callback(true)
    }

    actual fun requestLocation(callback: (Double?, Double?) -> Unit) {
        iosPermsAsked = true
        taxiLocationMgr.requestWhenInUseAuthorization()
        taxiLocationMgr.startUpdatingLocation()
        val loc = taxiLocationMgr.location
        if (loc != null) {
            val coords = loc.coordinate.useContents { Pair(latitude, longitude) }
            callback(coords.first, coords.second)
        } else {
            callback(50.4222208, 14.2540608)
        }
    }

    actual fun showSystemNotification(id: String, title: String, body: String, actionUrl: String) {
        pendingUrl = actionUrl
    }

    actual fun consumePendingNotificationUrl(): String? {
        val u = pendingUrl
        pendingUrl = null
        return u
    }

    actual fun openExternalUrl(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(
            nsUrl,
            options = emptyMap<Any?, Any>(),
            completionHandler = null
        )
    }

    actual fun dialPhone(phone: String) {
        val clean = if (phone.startsWith("tel:")) phone else "tel:${phone.replace(" ", "")}"
        openExternalUrl(clean)
    }
}

private fun iosLonToX(lon: Double, z: Int): Double =
    (lon + 180.0) / 360.0 * (1 shl z).toDouble()

private fun iosLatToY(lat: Double, z: Int): Double {
    val rad = lat.coerceIn(-85.05, 85.05) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0 * (1 shl z).toDouble()
}

private fun iosXToLon(tileX: Double, z: Int): Double =
    tileX / (1 shl z).toDouble() * 360.0 - 180.0

private fun iosYToLat(tileY: Double, z: Int): Double {
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

    val bgCol = if (isDark) Color(0xFF111318) else Color(0xFFE9E6DF)
    val gridCol = if (isDark) Color(0xFF1F2430) else Color(0xFFD5D1C8)

    Box(modifier = modifier.background(bgCol)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(zoom) {
                    val tilePx = 256.dp.toPx()
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        val cx = iosLonToX(centerLon, zoom) - (pan.x / tilePx)
                        val cy = iosLatToY(centerLat, zoom) - (pan.y / tilePx)
                        centerLon = iosXToLon(cx, zoom).coerceIn(-179.0, 179.0)
                        centerLat = iosYToLat(cy, zoom).coerceIn(-85.0, 85.0)
                        if (gestureZoom > 1.18f && zoom < 18) zoom++
                        else if (gestureZoom < 0.82f && zoom > 6) zoom--
                        onMapCenterChanged(centerLat, centerLon)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val tilePx = 256.dp.toPx()
            val cTileX = iosLonToX(centerLon, zoom)
            val cTileY = iosLatToY(centerLat, zoom)

            val minX = floor(cTileX - (w / (2.0 * tilePx)) - 1).toInt()
            val maxX = ceil(cTileX + (w / (2.0 * tilePx)) + 1).toInt()
            val minY = floor(cTileY - (h / (2.0 * tilePx)) - 1).toInt()
            val maxY = ceil(cTileY + (h / (2.0 * tilePx)) + 1).toInt()

            for (tx in minX..maxX) {
                val sx = (w / 2.0 + (tx - cTileX) * tilePx).toFloat()
                drawLine(gridCol, Offset(sx, 0f), Offset(sx, h), strokeWidth = 1f)
            }
            for (ty in minY..maxY) {
                val sy = (h / 2.0 + (ty - cTileY) * tilePx).toFloat()
                drawLine(gridCol, Offset(0f, sy), Offset(w, sy), strokeWidth = 1f)
            }

            fun toScreen(lat: Double, lon: Double): Offset {
                val px = (w / 2.0 + (iosLonToX(lon, zoom) - cTileX) * tilePx).toFloat()
                val py = (h / 2.0 + (iosLatToY(lat, zoom) - cTileY) * tilePx).toFloat()
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
                modifier = Modifier.size(42.dp).background(Color(0xD9141418), CircleShape)
            ) {
                Text("＋", color = Color(0xFFFFCC00), fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            IconButton(
                onClick = { if (zoom > 6) zoom-- },
                modifier = Modifier.size(42.dp).background(Color(0xD9141418), CircleShape)
            ) {
                Text("－", color = Color(0xFFFFCC00), fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
        }
    }
}

@Composable
actual fun EmbeddedWebModal(
    url: String,
    onDetectedToken: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Pro otevření této stránky v prohlížeči Safari klepněte na tlačítko níže:",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 15.sp
            )
            Button(
                onClick = {
                    PlatformBridge.openExternalUrl(url)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFFFFCC00)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Otevřít v prohlížeči", color = Color.Black, fontWeight = FontWeight.Black)
            }
        }
    }
}
