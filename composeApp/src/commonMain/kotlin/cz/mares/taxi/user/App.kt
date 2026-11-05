package cz.mares.taxi.user

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class SheetStop {
    MIN, MID, MAX
}

@Composable
fun App() {
    var token by remember { mutableStateOf(PlatformBridge.loadPref("taxi_api_token")) }
    var themePref by remember { mutableStateOf(PlatformBridge.loadPref("taxi_theme", "dark")) }
    val isDark = themePref != "light"

    // WATER GLASS BAREVNÁ PALETA PŘESNĚ PODLE STYLE.CSS
    val bgColor = if (isDark) Color(0xFF0A0A0D) else Color(0xFFF5F6F8)
    val sheetBg = if (isDark) Color(0xD90F0F12) else Color(0xE6FFFFFF)
    val cardBg = if (isDark) Color(0x18FFFFFF) else Color(0x0D000000)
    val solidCardBg = if (isDark) Color(0xFF141418) else Color(0xFFFFFFFF)
    val textColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)
    val borderColor = if (isDark) Color(0x2EFFFFFF) else Color(0x26000000)
    val brandColor = if (isDark) Color(0xFFFFCC00) else Color(0xFF2ECC71)
    val brandTextColor = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)

    var userProfile by remember { mutableStateOf(UserProfile()) }
    var unreadCount by remember { mutableStateOf(0) }
    var activeView by remember { mutableStateOf("home") }
    var sheetStop by remember { mutableStateOf(SheetStop.MID) }
    var barTitle by remember { mutableStateOf("Kam to bude?") }
    var permissionsGranted by remember { mutableStateOf(PlatformBridge.areAllPermissionsGranted()) }

    var routeType by remember { mutableStateOf("car_fast_traffic") }
    var startPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var via1Point by remember { mutableStateOf<GeoPoint?>(null) }
    var via2Point by remember { mutableStateOf<GeoPoint?>(null) }
    var destPoint by remember { mutableStateOf<GeoPoint?>(null) }
    var showVia1 by remember { mutableStateOf(false) }
    var showVia2 by remember { mutableStateOf(false) }

    var rideTerm by remember { mutableStateOf("") }
    var minDriverWaitMin by remember { mutableStateOf(15) }
    var personsStr by remember { mutableStateOf("1") }
    var waitMinStr by remember { mutableStateOf("0") }
    var hasChildren by remember { mutableStateOf(false) }
    var boosterSeatsStr by remember { mutableStateOf("0") }
    var childSeatsStr by remember { mutableStateOf("0") }
    var escort by remember { mutableStateOf(false) }
    var vehiclesMode by remember { mutableStateOf("auto") }
    var flightNumber by remember { mutableStateOf("") }
    var flightDate by remember { mutableStateOf(PlatformBridge.currentDateIso()) }

    var isCalculatingRoute by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var pricingResult by remember { mutableStateOf<PricingResult?>(null) }
    var draftOrder by remember { mutableStateOf<DraftOrder?>(null) }

    var mapPickTargetField by remember { mutableStateOf<String?>(null) }
    var mapCenterCoords by remember { mutableStateOf(Pair(50.4222208, 14.2540608)) }
    var flyToCoords by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var activeTrackingToken by remember { mutableStateOf<String?>(null) }
    var trackingData by remember { mutableStateOf<TrackingData?>(null) }

    var preselectedPayOrderId by remember { mutableStateOf<String?>(null) }
    var webModalUrl by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun handleDeepLinkAction(url: String) {
        if (url.isBlank()) return
        val trackMatch = Regex("""[?&](?:track|p)=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
        val openMatch = Regex("""[?&]open=([^&]+)""").find(url)?.groupValues?.getOrNull(1)
        val oidMatch = Regex("""[?&](?:oid|order_id|id)=([^&]+)""").find(url)?.groupValues?.getOrNull(1)

        if (!trackMatch.isNullOrBlank()) {
            activeTrackingToken = trackMatch
            activeView = "home"
            sheetStop = SheetStop.MIN
        } else if (url.contains("pay=1") && !oidMatch.isNullOrBlank()) {
            preselectedPayOrderId = oidMatch
            activeView = "requests"
            barTitle = "Potvrzení platby"
            sheetStop = SheetStop.MAX
        } else if (!openMatch.isNullOrBlank()) {
            if (openMatch == "home") {
                activeView = "home"
                sheetStop = SheetStop.MID
                barTitle = CzechVocativeHelper.greetingFor(userProfile.name)
            } else {
                activeView = openMatch
                barTitle = when (openMatch) {
                    "orders" -> "🚕 Moje objednávky"
                    "requests" -> "⏳ Moje poptávky"
                    "invoices" -> "Moje doklady"
                    "inbox" -> "Doručená pošta"
                    else -> "Taxi Mareš"
                }
                sheetStop = SheetStop.MAX
            }
        } else {
            activeView = "inbox"
            barTitle = "Doručená pošta"
            sheetStop = SheetStop.MAX
        }
    }

    // Pravidelná kontrola stavu oprávnění, prokliků z notifikací a počtu nepřečtených zpráv
    LaunchedEffect(token) {
        if (token.isNotBlank()) {
            TaxiApi.fetchUserProfile { prof ->
                if (prof != null) {
                    userProfile = prof
                    if (activeView == "home" && activeTrackingToken == null) {
                        barTitle = CzechVocativeHelper.greetingFor(prof.name)
                    }
                }
            }
        }
        while (true) {
            permissionsGranted = PlatformBridge.areAllPermissionsGranted()
            PlatformBridge.consumePendingNotificationUrl()?.let { clickedUrl ->
                handleDeepLinkAction(clickedUrl)
            }
            if (token.isNotBlank()) {
                TaxiApi.fetchUnreadCount { unreadCount = it }
            }
            delay(3000L)
        }
    }

    // Pravidelná obnova živého sledování vozu (každé 3 sekundy)
    LaunchedEffect(activeTrackingToken) {
        while (activeTrackingToken != null) {
            val tk = activeTrackingToken ?: break
            TaxiApi.fetchTrackingData(tk) { data ->
                if (!data.ok) {
                    activeTrackingToken = null
                    trackingData = null
                    barTitle = CzechVocativeHelper.greetingFor(userProfile.name)
                    sheetStop = SheetStop.MID
                } else {
                    trackingData = data
                    barTitle = if (data.etaText == "Na místě!") {
                        "📍 Vůz je na místě!"
                    } else {
                        "🚕 Váš vůz dorazí za ${data.etaText}"
                    }
                }
            }
            delay(3000L)
        }
    }

    fun triggerRouteRecalculation() {
        val s = startPoint
        val d = destPoint
        if (s != null && d != null) {
            sheetStop = SheetStop.MAX
            isCalculatingRoute = true
            routeError = null
            val pts = mutableListOf(s)
            via1Point?.let { pts.add(it) }
            via2Point?.let { pts.add(it) }
            pts.add(d)

            TaxiApi.calculateRouteAndPrice(
                waypoints = pts,
                routeType = routeType,
                persons = personsStr.toIntOrNull() ?: 1,
                waitMinutes = waitMinStr.toIntOrNull() ?: 0,
                hasChildren = hasChildren,
                boosterSeats = boosterSeatsStr.toIntOrNull() ?: 0,
                childSeats = childSeatsStr.toIntOrNull() ?: 0,
                vehiclesMode = vehiclesMode,
                escort = escort,
                userDiscountPercent = userProfile.discountTaxi
            ) { res ->
                isCalculatingRoute = false
                res.onSuccess { pr ->
                    pricingResult = pr
                    barTitle = "Odhad: ${pr.totalKc} Kč"
                }.onFailure {
                    routeError = "Trasování selhalo. Zkuste prosím body upravit."
                }
            }
        } else {
            pricingResult = null
            routeError = null
            if (activeView == "order") {
                sheetStop = SheetStop.MID
            }
        }
    }

    fun closeToHome() {
        activeView = "home"
        sheetStop = SheetStop.MID
        mapPickTargetField = null
        barTitle = CzechVocativeHelper.greetingFor(userProfile.name)
        TaxiApi.fetchUnreadCount { unreadCount = it }
    }

    MaterialTheme(
        colors = if (isDark) {
            darkColors(primary = brandColor, background = bgColor, surface = solidCardBg)
        } else {
            lightColors(primary = brandColor, background = bgColor, surface = solidCardBg)
        }
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            val screenHeight = maxHeight

            if (token.isBlank()) {
                NativeLoginScreen(
                    isDark = isDark,
                    brandColor = brandColor,
                    brandTextColor = brandTextColor,
                    textColor = textColor,
                    cardBg = solidCardBg,
                    borderColor = borderColor,
                    onLoginSuccess = { newToken ->
                        token = newToken
                    },
                    onOpenAuthWebModal = { url, title ->
                        webModalUrl = Pair(url, title)
                    }
                )
            } else {
                // 1. INTERAKTIVNÍ MAPA NA POZADÍ
                InteractiveTaxiMap(
                    isDark = isDark,
                    routePoints = trackingData?.routePoints?.takeIf { it.isNotEmpty() }
                        ?: pricingResult?.routePoints
                        ?: emptyList(),
                    startPoint = startPoint,
                    destPoint = destPoint,
                    trackingData = trackingData,
                    pickMode = mapPickTargetField != null,
                    flyToTarget = flyToCoords,
                    onMapCenterChanged = { lat, lon ->
                        mapCenterCoords = Pair(lat, lon)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // 2. HORNÍ LIŠTA PRO POVOLENÍ NOTIFIKACÍ A POLOHY (dokud nejsou povolené)
                if (!permissionsGranted) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xE6141418))
                            .border(1.dp, brandColor, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "🔔 Povolte upozornění na jízdu a GPS polohu",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                PlatformBridge.requestAllPermissions { granted ->
                                    permissionsGranted = granted
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Povolit", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                        }
                    }
                }

                // 3. UBER-STYLE PLOVOUCÍ ŠPENDLÍK PŘI VÝBĚRU Z MAPY
                if (mapPickTargetField != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.align(Alignment.Center).offset(y = (-20).dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFFFFCC00), CircleShape)
                                    .border(3.dp, Color.Black, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🚕", fontSize = 20.sp)
                            }
                            Box(
                                modifier = Modifier
                                    .size(width = 14.dp, height = 6.dp)
                                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            )
                        }

                        Card(
                            backgroundColor = sheetBg,
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, borderColor),
                            elevation = 0.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "POSUŇTE MAPU POD ŠPENDLÍK",
                                    color = textColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                Button(
                                    onClick = {
                                        val targetField = mapPickTargetField
                                        val (lat, lon) = mapCenterCoords
                                        mapPickTargetField = null
                                        TaxiApi.reverseGeocode(lat, lon) { label ->
                                            val pt = GeoPoint(lat, lon, label)
                                            when (targetField) {
                                                "start" -> {
                                                    startPoint = pt
                                                    TaxiApi.fetchNearestDriverEta(lat, lon) { wait ->
                                                        if (wait != null) {
                                                            minDriverWaitMin = wait
                                                            if (destPoint == null) {
                                                                barTitle = "🚕 Vůz u vás může být za $wait min"
                                                            }
                                                        }
                                                    }
                                                }
                                                "via1" -> via1Point = pt
                                                "via2" -> via2Point = pt
                                                "dest" -> destPoint = pt
                                            }
                                            triggerRouteRecalculation()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(54.dp)
                                ) {
                                    Text("Potvrdit tuto polohu", color = brandTextColor, fontWeight = FontWeight.Black, fontSize = 17.sp)
                                }
                                TextButton(onClick = { mapPickTargetField = null }) {
                                    Text("Zrušit výběr z mapy", color = textColor.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }

                // 4. SMART GLASS SHEET (POLOPRŮHLEDNÝ SKLENĚNÝ PANEL NAD MAPOU)
                if (mapPickTargetField == null) {
                    val targetSheetHeight = when {
                        activeTrackingToken != null && activeView == "home" -> 86.dp
                        sheetStop == SheetStop.MIN -> 86.dp
                        sheetStop == SheetStop.MID -> (screenHeight * 0.52f).coerceAtLeast(330.dp)
                        else -> screenHeight * 0.90f
                    }
                    val animatedHeight by animateDpAsState(
                        targetValue = targetSheetHeight,
                        animationSpec = tween(durationMillis = 280)
                    )

                    var dragAccum by remember { mutableStateOf(0f) }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(animatedHeight)
                            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                            .background(sheetBg)
                            .border(1.dp, borderColor, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    ) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(78.dp)
                                    .pointerInput(sheetStop) {
                                        detectVerticalDragGestures(
                                            onDragStart = { dragAccum = 0f },
                                            onVerticalDrag = { change, dragAmount ->
                                                change.consume()
                                                dragAccum += dragAmount
                                            },
                                            onDragEnd = {
                                                if (dragAccum < -40f) {
                                                    sheetStop = if (sheetStop == SheetStop.MIN) SheetStop.MID else SheetStop.MAX
                                                } else if (dragAccum > 40f) {
                                                    sheetStop = if (sheetStop == SheetStop.MAX) SheetStop.MID else SheetStop.MIN
                                                }
                                                dragAccum = 0f
                                            }
                                        )
                                    }
                                    .clickable {
                                        sheetStop = when (sheetStop) {
                                            SheetStop.MIN -> SheetStop.MID
                                            SheetStop.MID -> SheetStop.MAX
                                            SheetStop.MAX -> SheetStop.MID
                                        }
                                    }
                                    .padding(horizontal = 20.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 12.dp)
                                        .size(width = 40.dp, height = 5.dp)
                                        .background(textColor.copy(alpha = 0.25f), RoundedCornerShape(3.dp))
                                )

                                Text(
                                    text = barTitle,
                                    color = brandColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(top = 10.dp)
                                )

                                if (activeView != "home" || activeTrackingToken != null) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .size(32.dp)
                                            .background(cardBg, CircleShape)
                                            .border(1.dp, borderColor, CircleShape)
                                            .clickable {
                                                if (activeTrackingToken != null && activeView == "home") {
                                                    activeTrackingToken = null
                                                    trackingData = null
                                                }
                                                closeToHome()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("✕", color = brandColor, fontWeight = FontWeight.Black, fontSize = 14.sp)
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp)
                            ) {
                                when (activeView) {
                                    "home" -> {
                                        HomeMenuGrid(
                                            unreadCount = unreadCount,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor,
                                            onSelectView = { view, title, stop ->
                                                activeView = view
                                                barTitle = title
                                                sheetStop = stop
                                            }
                                        )
                                    }

                                    "order" -> {
                                        TwoPhaseOrderCalculatorView(
                                            isDark = isDark,
                                            routeType = routeType,
                                            onRouteTypeChange = {
                                                routeType = it
                                                triggerRouteRecalculation()
                                            },
                                            startPoint = startPoint,
                                            onStartPointChange = { pt ->
                                                startPoint = pt
                                                if (pt != null) {
                                                    flyToCoords = Pair(pt.lat, pt.lon)
                                                    TaxiApi.fetchNearestDriverEta(pt.lat, pt.lon) { wait ->
                                                        if (wait != null) {
                                                            minDriverWaitMin = wait
                                                            if (destPoint == null) {
                                                                barTitle = "🚕 Vůz u vás může být za $wait min"
                                                            }
                                                        } else if (destPoint == null) {
                                                            barTitle = "⚠️ Vozy jsou momentálně vytížené"
                                                        }
                                                    }
                                                }
                                                triggerRouteRecalculation()
                                            },
                                            via1Point = via1Point,
                                            onVia1PointChange = {
                                                via1Point = it
                                                triggerRouteRecalculation()
                                            },
                                            via2Point = via2Point,
                                            onVia2PointChange = {
                                                via2Point = it
                                                triggerRouteRecalculation()
                                            },
                                            destPoint = destPoint,
                                            onDestPointChange = { pt ->
                                                destPoint = pt
                                                if (pt != null) {
                                                    flyToCoords = Pair(pt.lat, pt.lon)
                                                }
                                                triggerRouteRecalculation()
                                            },
                                            showVia1 = showVia1,
                                            onShowVia1Change = { showVia1 = it },
                                            showVia2 = showVia2,
                                            onShowVia2Change = { showVia2 = it },
                                            onPickFromMapRequest = { fieldKey ->
                                                mapPickTargetField = fieldKey
                                            },
                                            onTypingExpandSheet = {
                                                sheetStop = SheetStop.MAX
                                            },
                                            flightNumber = flightNumber,
                                            onFlightNumberChange = { flightNumber = it },
                                            flightDate = flightDate,
                                            onFlightDateChange = { flightDate = it },
                                            rideTerm = rideTerm,
                                            onRideTermChange = { rideTerm = it },
                                            onAsapClick = {
                                                rideTerm = PlatformBridge.formatDateTimeOffset(minDriverWaitMin)
                                            },
                                            personsStr = personsStr,
                                            onPersonsChange = {
                                                personsStr = it
                                                triggerRouteRecalculation()
                                            },
                                            waitMinStr = waitMinStr,
                                            onWaitMinChange = {
                                                waitMinStr = it
                                                triggerRouteRecalculation()
                                            },
                                            hasChildren = hasChildren,
                                            onHasChildrenChange = {
                                                hasChildren = it
                                                triggerRouteRecalculation()
                                            },
                                            boosterSeatsStr = boosterSeatsStr,
                                            onBoosterSeatsChange = {
                                                boosterSeatsStr = it
                                                triggerRouteRecalculation()
                                            },
                                            childSeatsStr = childSeatsStr,
                                            onChildSeatsChange = {
                                                childSeatsStr = it
                                                triggerRouteRecalculation()
                                            },
                                            escort = escort,
                                            onEscortChange = {
                                                escort = it
                                                triggerRouteRecalculation()
                                            },
                                            vehiclesMode = vehiclesMode,
                                            onVehiclesModeChange = {
                                                vehiclesMode = it
                                                triggerRouteRecalculation()
                                            },
                                            isCalculating = isCalculatingRoute,
                                            routeError = routeError,
                                            pricingResult = pricingResult,
                                            brandColor = brandColor,
                                            brandTextColor = brandTextColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            solidCardBg = solidCardBg,
                                            borderColor = borderColor,
                                            onProceedToReview = {
                                                val s = startPoint
                                                val d = destPoint
                                                val pr = pricingResult
                                                if (s != null && d != null && pr != null) {
                                                    val vias = mutableListOf<GeoPoint>()
                                                    via1Point?.let { vias.add(it) }
                                                    via2Point?.let { vias.add(it) }
                                                    draftOrder = DraftOrder(
                                                        routeType = routeType,
                                                        term = rideTerm.ifBlank { PlatformBridge.formatDateTimeOffset(minDriverWaitMin) },
                                                        start = s,
                                                        dest = d,
                                                        via = vias,
                                                        persons = personsStr.toIntOrNull() ?: 1,
                                                        waitMinutes = waitMinStr.toIntOrNull() ?: 0,
                                                        hasChildren = hasChildren,
                                                        boosterSeats = boosterSeatsStr.toIntOrNull() ?: 0,
                                                        childSeats = childSeatsStr.toIntOrNull() ?: 0,
                                                        escort = escort,
                                                        vehiclesMode = vehiclesMode,
                                                        flightNumber = flightNumber,
                                                        flightDate = flightDate,
                                                        pricing = pr
                                                    )
                                                    activeView = "order-review"
                                                    barTitle = "Souhrn poptávky"
                                                    sheetStop = SheetStop.MAX
                                                }
                                            }
                                        )
                                    }

                                    "order-review" -> {
                                        OrderReviewView(
                                            draft = draftOrder,
                                            brandColor = brandColor,
                                            brandTextColor = brandTextColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor,
                                            onBackToEdit = {
                                                activeView = "order"
                                                barTitle = pricingResult?.let { "Odhad: ${it.totalKc} Kč" } ?: "Nová objednávka"
                                                sheetStop = SheetStop.MAX
                                            },
                                            onOrderSubmitted = {
                                                startPoint = null
                                                via1Point = null
                                                via2Point = null
                                                destPoint = null
                                                showVia1 = false
                                                showVia2 = false
                                                pricingResult = null
                                                draftOrder = null
                                                closeToHome()
                                            }
                                        )
                                    }

                                    "inbox" -> {
                                        InboxView(
                                            isDark = isDark,
                                            brandColor = brandColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor,
                                            onOpenTracking = { tk ->
                                                activeTrackingToken = tk
                                                activeView = "home"
                                                sheetStop = SheetStop.MIN
                                            },
                                            onOpenView = { targetView ->
                                                activeView = targetView
                                                barTitle = when (targetView) {
                                                    "orders" -> "🚕 Moje objednávky"
                                                    "requests" -> "⏳ Moje poptávky"
                                                    "invoices" -> "Moje doklady"
                                                    else -> "Taxi Mareš"
                                                }
                                                sheetStop = SheetStop.MAX
                                            },
                                            onInitiatePayFromInbox = { oid ->
                                                preselectedPayOrderId = oid
                                                activeView = "requests"
                                                barTitle = "Potvrzení platby"
                                                sheetStop = SheetStop.MAX
                                            }
                                        )
                                    }

                                    "requests", "orders" -> {
                                        OrdersListView(
                                            viewType = activeView,
                                            userProfile = userProfile,
                                            preselectedPayOrderId = preselectedPayOrderId,
                                            onClearPreselectedPay = { preselectedPayOrderId = null },
                                            onEditAccountClick = {
                                                activeView = "account"
                                                barTitle = "👤 Můj účet"
                                                sheetStop = SheetStop.MAX
                                            },
                                            onOpenLegalModal = { url, title ->
                                                webModalUrl = Pair(url, title)
                                            },
                                            brandColor = brandColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor
                                        )
                                    }

                                    "invoices" -> {
                                        InvoicesView(
                                            brandColor = brandColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor
                                        )
                                    }

                                    "account" -> {
                                        AccountView(
                                            isDark = isDark,
                                            userProfile = userProfile,
                                            onProfileReload = {
                                                TaxiApi.fetchUserProfile { prof ->
                                                    if (prof != null) userProfile = prof
                                                }
                                            },
                                            onLogout = {
                                                TaxiApi.logout {
                                                    token = ""
                                                }
                                            },
                                            brandColor = brandColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor
                                        )
                                    }

                                    "settings" -> {
                                        SettingsView(
                                            themeMode = themePref,
                                            onThemeChange = { newMode ->
                                                themePref = newMode
                                                PlatformBridge.savePref("taxi_theme", newMode)
                                            },
                                            onOpenLegalModal = { url, title ->
                                                webModalUrl = Pair(url, title)
                                            },
                                            brandColor = brandColor,
                                            textColor = textColor,
                                            cardBg = cardBg,
                                            borderColor = borderColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            webModalUrl?.let { (url, title) ->
                Surface(
                    color = bgColor,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(solidCardBg)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(title, color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Button(
                                onClick = { webModalUrl = null },
                                colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Zavřít", color = brandTextColor, fontWeight = FontWeight.Bold)
                            }
                        }
                        EmbeddedWebModal(
                            url = url,
                            onDetectedToken = { newTk ->
                                if (newTk.isNotBlank()) {
                                    TaxiApi.setToken(newTk)
                                    token = newTk
                                    webModalUrl = null
                                }
                            },
                            onClose = { webModalUrl = null },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NativeLoginScreen(
    isDark: Boolean,
    brandColor: Color,
    brandTextColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color,
    onLoginSuccess: (String) -> Unit,
    onOpenAuthWebModal: (String, String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            backgroundColor = cardBg,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, borderColor),
            elevation = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("🚕", fontSize = 44.sp)
                Text("TAXI MAREŠ", color = brandColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(
                    "Přihlaste se ke svému zákaznickému účtu",
                    color = textColor.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )

                if (errorMsg != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x33FF4C4C), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFFF4C4C), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Text(errorMsg!!, color = Color(0xFFFF4C4C), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("E-MAIL", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    WaterGlassInput(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "E-mailová adresa",
                        isDark = isDark,
                        brandColor = brandColor,
                        keyboardType = KeyboardType.Email
                    )
                }

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("HESLO", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    WaterGlassInput(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Heslo (••••••••)",
                        isDark = isDark,
                        brandColor = brandColor,
                        isPassword = true,
                        keyboardType = KeyboardType.Password
                    )
                }

                Button(
                    onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            errorMsg = "Vyplňte prosím e-mail i heslo."
                            return@Button
                        }
                        isLoading = true
                        errorMsg = null
                        TaxiApi.login(email, password) { ok, msg ->
                            isLoading = false
                            if (ok) {
                                onLoginSuccess(TaxiApi.getToken())
                            } else {
                                errorMsg = msg
                            }
                        }
                    },
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(
                        if (isLoading) "Přihlašuji… ⏳" else "Přihlásit se",
                        color = brandTextColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onOpenAuthWebModal("${TaxiApi.BASE_URL}/taxi/auth/register.php?app=pwa", "Nová registrace")
                        },
                        border = BorderStroke(1.dp, borderColor),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Nová registrace", color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            onOpenAuthWebModal("${TaxiApi.BASE_URL}/taxi/auth/forgot.php?app=pwa", "Obnova hesla")
                        },
                        border = BorderStroke(1.dp, borderColor),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) {
                        Text("Zapomenuté heslo?", color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "© 2026 Created by Pavel Mareš",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun HomeMenuGrid(
    unreadCount: Int,
    textColor: Color,
    cardBg: Color,
    borderColor: Color,
    onSelectView: (String, String, SheetStop) -> Unit
) {
    val rows = listOf(
        listOf(
            Triple("inbox", "📬", "Pošta"),
            Triple("order", "📍", "Kam to bude")
        ),
        listOf(
            Triple("requests", "⏳", "Poptávky"),
            Triple("orders", "🚕", "Objednávky")
        ),
        listOf(
            Triple("account", "👤", "Můj účet"),
            Triple("settings", "⚙️", "Nastavení")
        ),
        listOf(
            Triple("invoices", "📄", "Doklady"),
            Triple("dispatch", "📞", "Dispečink")
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { (key, icon, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(cardBg)
                            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
                            .clickable {
                                when (key) {
                                    "dispatch" -> PlatformBridge.dialPhone("+420606420002")
                                    "order" -> onSelectView("order", "Nová objednávka", SheetStop.MID)
                                    "inbox" -> onSelectView("inbox", "Doručená pošta", SheetStop.MAX)
                                    "requests" -> onSelectView("requests", "⏳ Moje poptávky", SheetStop.MAX)
                                    "orders" -> onSelectView("orders", "🚕 Moje objednávky", SheetStop.MAX)
                                    "account" -> onSelectView("account", "👤 Můj účet", SheetStop.MAX)
                                    "settings" -> onSelectView("settings", "⚙️ Nastavení", SheetStop.MAX)
                                    "invoices" -> onSelectView("invoices", "Moje doklady", SheetStop.MAX)
                                }
                            }
                            .padding(vertical = 18.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box {
                                Text(icon, fontSize = 28.sp)
                                if (key == "inbox" && unreadCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 6.dp, y = (-2).dp)
                                            .size(12.dp)
                                            .background(Color(0xFFE74C3C), CircleShape)
                                            .border(2.dp, Color.Black, CircleShape)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(label, color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "© 2026 Created by Pavel Mareš",
            color = textColor.copy(alpha = 0.45f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun TwoPhaseOrderCalculatorView(
    isDark: Boolean,
    routeType: String,
    onRouteTypeChange: (String) -> Unit,
    startPoint: GeoPoint?,
    onStartPointChange: (GeoPoint?) -> Unit,
    via1Point: GeoPoint?,
    onVia1PointChange: (GeoPoint?) -> Unit,
    via2Point: GeoPoint?,
    onVia2PointChange: (GeoPoint?) -> Unit,
    destPoint: GeoPoint?,
    onDestPointChange: (GeoPoint?) -> Unit,
    showVia1: Boolean,
    onShowVia1Change: (Boolean) -> Unit,
    showVia2: Boolean,
    onShowVia2Change: (Boolean) -> Unit,
    onPickFromMapRequest: (String) -> Unit,
    onTypingExpandSheet: () -> Unit,
    flightNumber: String,
    onFlightNumberChange: (String) -> Unit,
    flightDate: String,
    onFlightDateChange: (String) -> Unit,
    rideTerm: String,
    onRideTermChange: (String) -> Unit,
    onAsapClick: () -> Unit,
    personsStr: String,
    onPersonsChange: (String) -> Unit,
    waitMinStr: String,
    onWaitMinChange: (String) -> Unit,
    hasChildren: Boolean,
    onHasChildrenChange: (Boolean) -> Unit,
    boosterSeatsStr: String,
    onBoosterSeatsChange: (String) -> Unit,
    childSeatsStr: String,
    onChildSeatsChange: (String) -> Unit,
    escort: Boolean,
    onEscortChange: (Boolean) -> Unit,
    vehiclesMode: String,
    onVehiclesModeChange: (String) -> Unit,
    isCalculating: Boolean,
    routeError: String?,
    pricingResult: PricingResult?,
    brandColor: Color,
    brandTextColor: Color,
    textColor: Color,
    cardBg: Color,
    solidCardBg: Color,
    borderColor: Color,
    onProceedToReview: () -> Unit
) {
    val isPhaseTwoUnlocked = startPoint != null && destPoint != null

    val isAirportPickup = remember(startPoint) {
        val low = startPoint?.label?.lowercase() ?: ""
        low.contains("terminál 1") || low.contains("terminál 2") || low.contains("letiště václava havla")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(
                "car_fast_traffic" to "Rychlá (Provoz)",
                "car_short" to "Nejkratší"
            ).forEach { (typeKey, label) ->
                val active = routeType == typeKey
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) brandColor else Color.Transparent)
                        .clickable { onRouteTypeChange(typeKey) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (active) brandTextColor else textColor.copy(alpha = 0.65f),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        NativeAddressField(
            isDark = isDark,
            icon = "🟢",
            placeholder = "Odkud pojedete? *",
            point = startPoint,
            onPointSelected = onStartPointChange,
            onPickFromMap = { onPickFromMapRequest("start") },
            onTypingStart = onTypingExpandSheet,
            brandColor = brandColor,
            textColor = textColor,
            solidCardBg = solidCardBg,
            borderColor = borderColor
        )

        AnimatedVisibility(visible = isAirportPickup) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brandColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                    .border(1.dp, brandColor, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("PŘILÉTÁTE DO PRAHY?", color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                Text(
                    "Zadejte údaje o letu, přistavíme vůz přesně na čas.",
                    color = textColor.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WaterGlassInput(
                        value = flightNumber,
                        onValueChange = { onFlightNumberChange(it.uppercase()) },
                        placeholder = "Let (QS1234)",
                        isDark = isDark,
                        brandColor = brandColor,
                        modifier = Modifier.weight(1f)
                    )
                    WaterGlassInput(
                        value = flightDate,
                        onValueChange = onFlightDateChange,
                        placeholder = "RRRR-MM-DD",
                        isDark = isDark,
                        brandColor = brandColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (showVia1) {
            NativeAddressField(
                isDark = isDark,
                icon = "⚪",
                placeholder = "Mezizastávka 1",
                point = via1Point,
                onPointSelected = { pt ->
                    onVia1PointChange(pt)
                    if (pt == null) onShowVia1Change(false)
                },
                onPickFromMap = { onPickFromMapRequest("via1") },
                onTypingStart = onTypingExpandSheet,
                brandColor = brandColor,
                textColor = textColor,
                solidCardBg = solidCardBg,
                borderColor = borderColor
            )
        }

        if (showVia2) {
            NativeAddressField(
                isDark = isDark,
                icon = "⚪",
                placeholder = "Mezizastávka 2",
                point = via2Point,
                onPointSelected = { pt ->
                    onVia2PointChange(pt)
                    if (pt == null) onShowVia2Change(false)
                },
                onPickFromMap = { onPickFromMapRequest("via2") },
                onTypingStart = onTypingExpandSheet,
                brandColor = brandColor,
                textColor = textColor,
                solidCardBg = solidCardBg,
                borderColor = borderColor
            )
        }

        if (!showVia1 || !showVia2) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        if (!showVia1) onShowVia1Change(true)
                        else if (!showVia2) onShowVia2Change(true)
                    },
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = cardBg)
                ) {
                    Text("＋ Přidat zastávku", color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                }
            }
        }

        NativeAddressField(
            isDark = isDark,
            icon = "🏁",
            placeholder = "Kam to bude? *",
            point = destPoint,
            onPointSelected = onDestPointChange,
            onPickFromMap = { onPickFromMapRequest("dest") },
            onTypingStart = onTypingExpandSheet,
            brandColor = brandColor,
            textColor = textColor,
            solidCardBg = solidCardBg,
            borderColor = borderColor
        )

        AnimatedVisibility(visible = isPhaseTwoUnlocked) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "TERMÍN ODJEZDU / PŘISTAVENÍ",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WaterGlassInput(
                                value = rideTerm,
                                onValueChange = onRideTermChange,
                                placeholder = "Co nejdříve (ASAP)",
                                isDark = isDark,
                                brandColor = brandColor,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = onAsapClick,
                                colors = ButtonDefaults.buttonColors(backgroundColor = cardBg),
                                border = BorderStroke(1.dp, borderColor),
                                shape = RoundedCornerShape(12.dp),
                                elevation = null,
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text("Co nejdřív", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("POČET OSOB", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            WaterGlassInput(
                                value = personsStr,
                                onValueChange = { onPersonsChange(it.filter { c -> c.isDigit() }) },
                                placeholder = "Např. 4",
                                isDark = isDark,
                                brandColor = brandColor,
                                keyboardType = KeyboardType.Number
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("ČEKÁNÍ (MIN)", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            WaterGlassInput(
                                value = waitMinStr,
                                onValueChange = { onWaitMinChange(it.filter { c -> c.isDigit() }) },
                                placeholder = "0",
                                isDark = isDark,
                                brandColor = brandColor,
                                keyboardType = KeyboardType.Number
                            )
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onHasChildrenChange(!hasChildren) }
                        ) {
                            Checkbox(
                                checked = hasChildren,
                                onCheckedChange = onHasChildrenChange,
                                colors = CheckboxDefaults.colors(checkedColor = brandColor)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Z toho děti (do 150cm / 36kg)", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }

                        if (hasChildren) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("PODSEDÁK", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    WaterGlassInput(
                                        value = boosterSeatsStr,
                                        onValueChange = { onBoosterSeatsChange(it.filter { c -> c.isDigit() }) },
                                        placeholder = "0",
                                        isDark = isDark,
                                        brandColor = brandColor,
                                        keyboardType = KeyboardType.Number
                                    )
                                }
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("AUTOSEDAČKA", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                    WaterGlassInput(
                                        value = childSeatsStr,
                                        onValueChange = { onChildSeatsChange(it.filter { c -> c.isDigit() }) },
                                        placeholder = "0",
                                        isDark = isDark,
                                        brandColor = brandColor,
                                        keyboardType = KeyboardType.Number
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onEscortChange(!escort) }
                    ) {
                        Checkbox(
                            checked = escort,
                            onCheckedChange = onEscortChange,
                            colors = CheckboxDefaults.colors(checkedColor = brandColor)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Odvoz vozu (Escort)", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("VOZIDLA", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "auto" to "Auto",
                                "1" to "1 vůz",
                                "2" to "2 vozy",
                                "3" to "3 vozy"
                            ).forEach { (vMode, vLabel) ->
                                val active = vehiclesMode == vMode
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) brandColor else Color.Transparent)
                                        .border(1.dp, if (active) brandColor else borderColor, RoundedCornerShape(8.dp))
                                        .clickable { onVehiclesModeChange(vMode) }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        vLabel,
                                        color = if (active) brandTextColor else textColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (isCalculating) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("POČÍTÁM TRASU A CENU...", color = textColor.copy(alpha = 0.6f), fontWeight = FontWeight.ExtraBold)
                    }
                } else if (routeError != null) {
                    Text(
                        routeError,
                        color = Color(0xFFFF4C4C),
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (pricingResult != null) {
                    Divider(color = borderColor)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("ODHADOVANÁ CENA", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${pricingResult.totalKc} Kč", color = textColor, fontSize = 32.sp, fontWeight = FontWeight.Black)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("VZDÁLENOST", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                            val kmRounded = (kotlin.math.round(pricingResult.kmAB * 10.0) / 10.0)
                            Text("$kmRounded km", color = textColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }

                    Button(
                        onClick = onProceedToReview,
                        colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().height(58.dp)
                    ) {
                        Text("🚕 Poptat jízdu", color = brandTextColor, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun NativeAddressField(
    isDark: Boolean,
    icon: String,
    placeholder: String,
    point: GeoPoint?,
    onPointSelected: (GeoPoint?) -> Unit,
    onPickFromMap: () -> Unit,
    onTypingStart: () -> Unit,
    brandColor: Color,
    textColor: Color,
    solidCardBg: Color,
    borderColor: Color
) {
    var text by remember(point) { mutableStateOf(point?.label ?: "") }
    var showActionMenu by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<SuggestItem>>(emptyList()) }
    var isLocating by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        WaterGlassInput(
            value = if (isLocating) "Hledám vás..." else text,
            onValueChange = { newVal ->
                text = newVal
                if (point != null) {
                    onPointSelected(null)
                }
                if (newVal.trim().isEmpty()) {
                    suggestions = emptyList()
                    showActionMenu = true
                } else {
                    showActionMenu = false
                    onTypingStart()
                    TaxiApi.suggestAddresses(newVal) { items ->
                        suggestions = items
                    }
                }
            },
            placeholder = placeholder,
            isDark = isDark,
            brandColor = brandColor,
            isHighlighted = point != null,
            leadingIcon = { Text(icon, fontSize = 16.sp) },
            trailingIcon = {
                if (text.isNotEmpty() || point != null) {
                    Text(
                        "✕",
                        color = Color(0xFFE74C3C),
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .clickable {
                                text = ""
                                suggestions = emptyList()
                                onPointSelected(null)
                                showActionMenu = true
                            }
                            .padding(horizontal = 4.dp)
                    )
                } else {
                    Text(
                        if (showActionMenu) "▲" else "📍",
                        color = brandColor,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable { showActionMenu = !showActionMenu }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        )

        if (showActionMenu && text.isEmpty() && point == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(solidCardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showActionMenu = false
                            isLocating = true
                            PlatformBridge.requestLocation { lat, lon ->
                                if (lat != null && lon != null) {
                                    TaxiApi.reverseGeocode(lat, lon) { lbl ->
                                        isLocating = false
                                        text = lbl
                                        onPointSelected(GeoPoint(lat, lon, lbl))
                                    }
                                } else {
                                    isLocating = false
                                }
                            }
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("📍", fontSize = 18.sp)
                    Text("Moje poloha", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
                Divider(color = borderColor)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showActionMenu = false
                            onPickFromMap()
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("🗺️", fontSize = 18.sp)
                    Text("Vybrat z mapy", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                }
            }
        }

        if (suggestions.isNotEmpty() && point == null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .background(solidCardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, brandColor, RoundedCornerShape(14.dp))
            ) {
                suggestions.take(6).forEachIndexed { index, item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                text = item.inputLabel
                                suggestions = emptyList()
                                showActionMenu = false
                                onPointSelected(GeoPoint(item.lat, item.lon, item.inputLabel))
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                    ) {
                        Text(item.listLabel, color = textColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    if (index < minOf(suggestions.size, 6) - 1) {
                        Divider(color = borderColor)
                    }
                }
            }
        }
    }
}

@Composable
fun OrderReviewView(
    draft: DraftOrder?,
    brandColor: Color,
    brandTextColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color,
    onBackToEdit: () -> Unit,
    onOrderSubmitted: () -> Unit
) {
    if (draft == null) {
        onBackToEdit()
        return
    }

    var isSubmitting by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    var showSuccessToast by remember { mutableStateOf(false) }

    if (showSuccessToast) {
        LaunchedEffect(Unit) {
            delay(1800L)
            onOrderSubmitted()
        }
        Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("✅", fontSize = 48.sp)
                Text(
                    "Poptávka byla úspěšně odeslána!",
                    color = Color(0xFF2ECC71),
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("TRASA ODVOZU", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🟢", fontSize = 14.sp)
                Text(draft.start.label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            draft.via.forEach { v ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("⚪", fontSize = 12.sp)
                    Text(v.label, color = textColor.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("🏁", fontSize = 14.sp)
                Text(draft.dest.label, color = textColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text("ODJEZD", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(draft.term.replace("T", " "), color = brandColor, fontWeight = FontWeight.Black, fontSize = 15.sp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(16.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Text("ORIENTAČNÍ CENA", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text("${draft.pricing.totalKc} Kč", color = brandColor, fontWeight = FontWeight.Black, fontSize = 20.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("DETAILY SPECIFIKACE", color = textColor.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
            Text("👥 Osoby: ${draft.persons}", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (draft.waitMinutes > 0) Text("⏱️ Čekání: ${draft.waitMinutes} min", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (draft.escort) Text("🚗 Odvoz vozu (Escort)", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (draft.boosterSeats > 0) Text("💺 Podsedák: ${draft.boosterSeats}x", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (draft.childSeats > 0) Text("👶 Sedačka: ${draft.childSeats}x", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (draft.flightNumber.isNotBlank()) Text("✈️ Let: ${draft.flightNumber}", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brandColor.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .border(1.dp, brandColor, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("ℹ️ CO SE STANE DÁL?", color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Poptávku nyní odešleme dispečinku. Jakmile ji řidič potvrdí, vyzveme vás v aplikaci k online platbě kartou pro definitivní dokončení objednávky.",
                color = textColor.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (submitError != null) {
            Text(submitError!!, color = Color(0xFFFF4C4C), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }

        Button(
            onClick = {
                if (!isSubmitting) {
                    isSubmitting = true
                    submitError = null
                    TaxiApi.submitOrder(draft) { res ->
                        isSubmitting = false
                        res.onSuccess {
                            showSuccessToast = true
                        }.onFailure { err ->
                            submitError = "CHYBA ODESLÁNÍ: ${err.message}"
                        }
                    }
                }
            },
            enabled = !isSubmitting,
            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text(
                if (isSubmitting) "Odesílám... ⏳" else "🚕 Odeslat poptávku",
                color = brandTextColor,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
        }

        OutlinedButton(
            onClick = onBackToEdit,
            enabled = !isSubmitting,
            border = BorderStroke(1.dp, borderColor),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("✏️ Zpět k úpravám", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
    }
}
