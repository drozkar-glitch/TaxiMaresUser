package cz.mares.taxi.user

import kotlin.math.*

object TaxiApi {
    const val BASE_URL = "https://606420002.com"
    private const val PROXY_PATH = "/taxi/admin/modules/pricing/assets/tiles.php"
    private const val ROUDNICE_BASE_LAT = 50.4222208
    private const val ROUDNICE_BASE_LON = 14.2540608

    private var cachedCenik: CenikConfig? = null

    fun getToken(): String = PlatformBridge.loadPref("taxi_api_token")

    fun setToken(token: String) {
        PlatformBridge.savePref("taxi_api_token", token)
    }

    private fun buildUrl(path: String, params: kotlin.collections.Map<String, String> = emptyMap()): String {
        val base = if (path.startsWith("http")) path else "$BASE_URL$path"
        val allParams = LinkedHashMap<String, String>()
        allParams.putAll(params)
        val token = getToken()
        if (token.isNotBlank() && !allParams.containsKey("_pwa_auth")) {
            allParams["_pwa_auth"] = token
        }
        if (allParams.isEmpty()) return base
        val sep = if (base.contains("?")) "&" else "?"
        val query = allParams.entries.joinToString("&") { (k, v) ->
            "${PlatformBridge.urlEncode(k)}=${PlatformBridge.urlEncode(v)}"
        }
        return "$base$sep$query"
    }

    private fun authHeaders(isJsonPost: Boolean = false): kotlin.collections.Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map["X-Requested-With"] = "XMLHttpRequest"
        map["Accept-Language"] = "cs"
        if (isJsonPost) {
            map["Content-Type"] = "application/json; charset=utf-8"
        }
        val token = getToken()
        if (token.isNotBlank()) {
            map["Authorization"] = "Bearer $token"
        }
        return map
    }

    fun getJson(path: String, params: kotlin.collections.Map<String, String> = emptyMap(), callback: (Result<JsonObj>) -> Unit) {
        val url = buildUrl(path, params)
        PlatformBridge.httpRequest(url, "GET", authHeaders(false), null) { ok, body ->
            if (!ok || body.isBlank()) {
                callback(Result.failure(Exception("Chyba spojení se serverem")))
            } else {
                try {
                    callback(Result.success(SimpleJson.parseObj(body)))
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            }
        }
    }

    fun postJson(path: String, payload: JsonObj, callback: (Result<JsonObj>) -> Unit) {
        val url = buildUrl(path)
        val bodyStr = SimpleJson.encode(payload)
        PlatformBridge.httpRequest(url, "POST", authHeaders(true), bodyStr) { ok, body ->
            if (!ok && body.isBlank()) {
                callback(Result.failure(Exception("Chyba spojení se serverem")))
            } else {
                try {
                    callback(Result.success(SimpleJson.parseObj(body)))
                } catch (e: Exception) {
                    callback(Result.failure(e))
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // 1. PŘIHLÁŠENÍ A PROFIL
    // ------------------------------------------------------------------------
    fun login(email: String, pass: String, callback: (Boolean, String) -> Unit) {
        val payload: JsonObj = mapOf(
            "action" to "login",
            "email" to email.trim(),
            "password" to pass
        )
        postJson("/taxi/app/api/auth_engine.php", payload) { res ->
            res.onSuccess { obj ->
                if (obj.bool("ok")) {
                    val token = obj.str("token")
                    val uid = obj.str("user_id")
                    if (token.isNotBlank()) {
                        setToken(token)
                        if (uid.isNotBlank()) PlatformBridge.savePref("taxi_user_id", uid)
                        callback(true, "Přihlášeno")
                    } else {
                        callback(false, "Chyba při generování tokenu.")
                    }
                } else {
                    callback(false, obj.str("error", "Nesprávný e-mail nebo heslo."))
                }
            }.onFailure {
                callback(false, "Nepodařilo se spojit se serverem.")
            }
        }
    }

    fun logout(callback: () -> Unit) {
        val token = getToken()
        val payload: JsonObj = mapOf("action" to "logout", "token" to token)
        postJson("/taxi/app/api/auth_engine.php", payload) {
            setToken("")
            PlatformBridge.savePref("taxi_user_id", "")
            callback()
        }
    }

    fun fetchUserProfile(callback: (UserProfile?) -> Unit) {
        getJson("/taxi/api/user.php", mapOf("_t" to PlatformBridge.currentTimeMillis().toString())) { res ->
            res.onSuccess { obj ->
                if (obj.containsKey("error") && obj.str("error").isNotBlank()) {
                    callback(null)
                } else {
                    val id = obj.str("id", PlatformBridge.loadPref("taxi_user_id"))
                    if (id.isNotBlank()) PlatformBridge.savePref("taxi_user_id", id)
                    val discount = obj.int("discount_taxi", obj.obj("discount")?.int("taxi_percent", 0) ?: 0)
                    val profile = UserProfile(
                        id = id,
                        name = obj.str("name", "Zákazník"),
                        email = obj.str("email", ""),
                        phone = obj.str("phone", ""),
                        discountTaxi = discount
                    )
                    callback(profile)
                }
            }.onFailure {
                callback(null)
            }
        }
    }

    // ------------------------------------------------------------------------
    // 2. NAŠEPTÁVAČ ADRES A REVERZNÍ GEOCODING (MAPY.CZ PŘES TILES.PHP)
    // ------------------------------------------------------------------------
    fun suggestAddresses(query: String, callback: (List<SuggestItem>) -> Unit) {
        val q = query.trim()
        if (q.length < 2) {
            callback(emptyList())
            return
        }
        val params = mapOf(
            "mode" to "suggest",
            "query" to q,
            "lang" to "cs",
            "limit" to "12",
            "type" to "regional.address,poi",
            "locality" to "-10,35,40,70",
            "preferNear" to "50.4333,14.3833,40000"
        )
        getJson(PROXY_PATH, params) { res ->
            res.onSuccess { obj ->
                val items = obj.arr("items")
                val out = ArrayList<SuggestItem>()
                for (raw in items) {
                    @Suppress("UNCHECKED_CAST")
                    val it = (raw as? JsonObj) ?: continue
                    val pos = it.obj("position") ?: it.obj("positionWgs84") ?: it
                    val lat = pos.dbl("lat", Double.NaN)
                    val lon = pos.dbl("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) continue

                    val typeStr = it.str("type")
                    val city = pickCity(it)
                    val zip = pickZip(it)
                    val region = pickRegion(it)
                    val country = pickCountry(it)
                    val street = streetWithHN(it).ifBlank { it.str("name").ifBlank { it.str("title") } }

                    if (typeStr.startsWith("regional")) {
                        val listLbl = withStateOnce(listOf(street, city, zip, region, country))
                        val insLbl = withStateOnce(
                            listOf(
                                street,
                                city,
                                if (zip.isNotBlank()) "$zip $city" else city,
                                region,
                                country
                            )
                        )
                        out.add(SuggestItem(lat, lon, listLbl, insLbl))
                    } else {
                        val name = it.str("name").ifBlank { it.str("title") }
                        val display = withStateOnce(listOf(name, city, zip, region, country))
                        out.add(SuggestItem(lat, lon, display, display))
                    }
                }
                callback(out)
            }.onFailure {
                callback(emptyList())
            }
        }
    }

    fun reverseGeocode(lat: Double, lon: Double, callback: (String) -> Unit) {
        val params = mapOf(
            "mode" to "rgeocode",
            "lat" to lat.toString(),
            "lon" to lon.toString(),
            "lang" to "cs",
            "limit" to "1",
            "locality" to "cz"
        )
        getJson(PROXY_PATH, params) { res ->
            res.onSuccess { obj ->
                val first = obj.arr("items").firstOrNull() as? JsonObj
                if (first != null) {
                    val city = pickCity(first)
                    val zip = pickZip(first)
                    val region = pickRegion(first)
                    val country = pickCountry(first)
                    val street = streetWithHN(first).ifBlank { first.str("name").ifBlank { first.str("title") } }
                    val formatted = withStateOnce(
                        listOf(
                            street,
                            city,
                            if (zip.isNotBlank()) "$zip $city" else city,
                            region,
                            country
                        )
                    )
                    if (formatted.isNotBlank()) {
                        callback(formatted)
                        return@onSuccess
                    }
                }
                callback("GPS: ${round5(lat)}, ${round5(lon)}")
            }.onFailure {
                callback("GPS: ${round5(lat)}, ${round5(lon)}")
            }
        }
    }

    private fun round5(v: Double): String {
        val factor = 100000.0
        return (round(v * factor) / factor).toString()
    }

    private fun withStateOnce(parts: List<String>): String {
        val filtered = parts.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return filtered.joinToString(", ")
    }

    private fun parseLoc(loc: String): Pair<String, String> {
        val regex = Regex("""\b(\d{3})\s?(\d{2})\b\s*(.*)$""")
        val m = regex.find(loc)
        return if (m != null) {
            Pair("${m.groupValues[1]} ${m.groupValues[2]}", m.groupValues[3].trim())
        } else {
            Pair("", loc.trim())
        }
    }

    private fun pickZip(it: JsonObj): String {
        val direct = it.str("postalCode")
        if (direct.isNotBlank()) return direct
        val addrZip = it.obj("address")?.str("postcode")?.ifBlank { it.obj("address")?.str("postalCode") ?: "" } ?: ""
        if (addrZip.isNotBlank()) return addrZip
        val loc = it.str("location")
        return if (loc.isNotBlank()) parseLoc(loc).first else ""
    }

    private fun pickCity(it: JsonObj): String {
        val m = it.str("municipality").ifBlank { it.str("municipalityName") }.ifBlank { it.str("city") }.ifBlank { it.str("town") }
        if (m.isNotBlank()) return m
        val addr = it.obj("address")
        if (addr != null) {
            val am = addr.str("municipality").ifBlank { addr.str("city") }.ifBlank { addr.str("town") }
            if (am.isNotBlank()) return am
        }
        val reg = it.obj("regionalStructure")?.str("municipality") ?: ""
        if (reg.isNotBlank()) return reg
        val loc = it.str("location")
        return if (loc.isNotBlank()) parseLoc(loc).second else ""
    }

    private fun pickRegion(it: JsonObj): String {
        val reg = it.obj("regionalStructure")
        val r = reg?.str("region")?.ifBlank { reg.str("regionName") } ?: ""
        if (r.isNotBlank()) return r
        return it.obj("address")?.str("region") ?: ""
    }

    private fun pickCountry(it: JsonObj): String {
        return it.obj("regionalStructure")?.str("country") ?: ""
    }

    private fun streetWithHN(it: JsonObj): String {
        val name = it.str("name").ifBlank { it.str("title") }
        val hn = it.str("houseNumber").ifBlank { it.obj("address")?.str("houseNumber") ?: "" }
        if (hn.isBlank()) return name
        val hasNumber = Regex("""\b\d+[A-Za-z]?\b""").containsMatchIn(name)
        return if (hasNumber) name else if (name.isNotBlank()) "$name $hn" else hn
    }

    // ------------------------------------------------------------------------
    // 3. TRASOVÁNÍ A CENÍKOVÝ MOTOR (1:1 S PRICING.JS)
    // ------------------------------------------------------------------------
    fun fetchNearestDriverEta(lat: Double, lon: Double, callback: (Int?) -> Unit) {
        val params = mapOf(
            "lat" to lat.toString(),
            "lon" to lon.toString(),
            "_t" to PlatformBridge.currentTimeMillis().toString()
        )
        getJson("/taxi/admin/modules/drivers/api_nearest.php", params) { res ->
            res.onSuccess { obj ->
                if (obj.str("status") == "success" && obj.int("eta_minutes", 0) > 0) {
                    callback(obj.int("eta_minutes") + 10)
                } else {
                    callback(null)
                }
            }.onFailure {
                callback(null)
            }
        }
    }

    private fun ensureCenik(callback: (CenikConfig) -> Unit) {
        cachedCenik?.let {
            callback(it)
            return
        }
        getJson(PROXY_PATH, mapOf("mode" to "cenik")) { res ->
            val cfg = res.getOrNull()?.obj("taxi")
            val rates = cfg?.obj("rates")
            val mults = cfg?.obj("multipliers")
            val rules = cfg?.obj("rounding")?.obj("rules")
            val loaded = CenikConfig(
                perKm = rates?.dbl("per_km", 35.0) ?: 35.0,
                approachPerKm = rates?.dbl("approach_per_km", 35.0) ?: 35.0,
                waitPerMin = rates?.dbl("wait_per_min", 10.0) ?: 10.0,
                startKc = rates?.dbl("start_kc", 60.0) ?: 60.0,
                escortMult = mults?.dbl("escort", 1.5) ?: 1.5,
                roundTo = rules?.int("to", 10) ?: 10,
                roundMode = rules?.str("mode", "ceil") ?: "ceil"
            )
            cachedCenik = loaded
            callback(loaded)
        }
    }

    fun calculateRouteAndPrice(
        waypoints: List<GeoPoint>,
        routeType: String,
        persons: Int,
        waitMinutes: Int,
        hasChildren: Boolean,
        boosterSeats: Int,
        childSeats: Int,
        vehiclesMode: String,
        escort: Boolean,
        userDiscountPercent: Int,
        callback: (Result<PricingResult>) -> Unit
    ) {
        if (waypoints.size < 2) {
            callback(Result.failure(Exception("Zadejte start i cíl.")))
            return
        }
        ensureCenik { cenik ->
            val s = waypoints.first()
            val e = waypoints.last()
            val params = LinkedHashMap<String, String>()
            params["mode"] = "route"
            params["start"] = "${s.lon},${s.lat}"
            params["end"] = "${e.lon},${e.lat}"
            params["routeType"] = routeType
            if (waypoints.size > 2) {
                val vias = waypoints.subList(1, waypoints.size - 1)
                params["waypoints"] = vias.joinToString(";") { "${it.lon},${it.lat}" }
            }
            params["_t"] = PlatformBridge.currentTimeMillis().toString()

            getJson(PROXY_PATH, params) { routeRes ->
                routeRes.onFailure { err ->
                    callback(Result.failure(err))
                }.onSuccess { gj ->
                    val points = extractCoordinates(gj)
                    val distMeters = extractDistanceMeters(gj)
                    val durSec = extractDurationSeconds(gj)
                    val distanceKm = distMeters / 1000.0
                    val durationMin = round(durSec / 60.0).toInt()

                    // Kontrola, zda trasa prochází Roudnicí nad Labem (vzdálenost bodu od středu Roudnice < 3.6 km nebo název v adrese)
                    val touchesRoudnice = isPointInRoudnice(s.lat, s.lon, s.label) ||
                        isPointInRoudnice(e.lat, e.lon, e.label) ||
                        points.any { (lat, lon) -> haversineMeters(lat, lon, ROUDNICE_BASE_LAT, ROUDNICE_BASE_LON) <= 3600.0 }

                    val finalizeCalculation = { pristavneKm: Double, pristavneKc: Int ->
                        val waitKc = if (waitMinutes > 0) (waitMinutes * cenik.waitPerMin).roundToInt() else 0
                        val startFee = cenik.startKc.roundToInt()
                        val baseRideNoEscortNoPristavne = distanceKm * cenik.perKm + waitKc + startFee

                        val tripsForPersons = max(1, ceil(max(0, persons) / 4.0).toInt())
                        val tripsForChildSeats = if (hasChildren) max(0, childSeats) else 0
                        val tripsForBoosters = if (hasChildren) ceil(max(0, boosterSeats) / 2.0).toInt() else 0
                        val totalTripsNeeded = max(max(tripsForPersons, tripsForChildSeats), max(tripsForBoosters, 1))

                        fun scenarioForCars(carsWanted: Int): Triple<Int, Int, Double> {
                            val c = carsWanted.coerceIn(1, 3)
                            val usedCars = min(c, totalTripsNeeded)
                            val sub = baseRideNoEscortNoPristavne * totalTripsNeeded + pristavneKc * usedCars
                            return Triple(usedCars, totalTripsNeeded, sub)
                        }

                        val chosen = if (vehiclesMode in listOf("1", "2", "3")) {
                            scenarioForCars(vehiclesMode.toInt())
                        } else {
                            (1..3).map { scenarioForCars(it) }.minByOrNull { it.third } ?: scenarioForCars(1)
                        }

                        var subtotal = chosen.third
                        if (escort && cenik.escortMult > 0) {
                            subtotal *= cenik.escortMult
                        }

                        var discountKc = 0
                        if (userDiscountPercent > 0) {
                            discountKc = (subtotal * (userDiscountPercent / 100.0)).roundToInt().coerceIn(0, subtotal.roundToInt())
                            subtotal -= discountKc
                        }

                        val step = max(1, cenik.roundTo)
                        val totalKc = when (cenik.roundMode.lowercase()) {
                            "floor" -> (floor(subtotal / step) * step).toInt()
                            "round" -> (round(subtotal / step) * step).toInt()
                            else -> (ceil(subtotal / step) * step).toInt()
                        }

                        callback(
                            Result.success(
                                PricingResult(
                                    kmAB = distanceKm,
                                    totalKc = totalKc,
                                    startFee = startFee,
                                    waitKc = waitKc,
                                    pristavneKm = pristavneKm,
                                    pristavneKc = pristavneKc,
                                    carsCount = chosen.first,
                                    ridesCount = chosen.second,
                                    discountPercent = userDiscountPercent,
                                    discountKc = discountKc,
                                    durationMin = durationMin,
                                    routePoints = points
                                )
                            )
                        )
                    }

                    if (touchesRoudnice || points.isEmpty()) {
                        finalizeCalculation(0.0, 0)
                    } else {
                        // Najdeme nejbližší bod trasy k základně Špindlerova 673 a spočítáme přístavné
                        val closestPt = points.minByOrNull { (lat, lon) ->
                            haversineMeters(ROUDNICE_BASE_LAT, ROUDNICE_BASE_LON, lat, lon)
                        } ?: Pair(s.lat, s.lon)

                        val appParams = mapOf(
                            "mode" to "route",
                            "start" to "$ROUDNICE_BASE_LON,$ROUDNICE_BASE_LAT",
                            "end" to "${closestPt.second},${closestPt.first}",
                            "routeType" to "car_short",
                            "format" to "geojson"
                        )
                        getJson(PROXY_PATH, appParams) { appRes ->
                            val appKm = appRes.getOrNull()?.let { extractDistanceMeters(it) / 1000.0 }
                                ?: (haversineMeters(ROUDNICE_BASE_LAT, ROUDNICE_BASE_LON, closestPt.first, closestPt.second) / 1000.0)
                            val roundedKm = round(appKm * 100.0) / 100.0
                            val rawKc = max(0.0, roundedKm * cenik.approachPerKm - 50.0).roundToInt()
                            finalizeCalculation(roundedKm, rawKc)
                        }
                    }
                }
            }
        }
    }

    private fun isPointInRoudnice(lat: Double, lon: Double, label: String): Boolean {
        val low = label.lowercase()
        if (low.contains("roudnice nad labem") || low.contains("roudnice n. l") || low.contains("413 01")) {
            return true
        }
        return haversineMeters(lat, lon, ROUDNICE_BASE_LAT, ROUDNICE_BASE_LON) <= 3200.0
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = (lat2 - lat1) * PI / 180.0
        val dLon = (lon2 - lon1) * PI / 180.0
        val a = sin(dLat / 2).pow(2) + cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    private fun extractCoordinates(gj: JsonObj): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        fun walk(node: Any?) {
            if (node is List<*>) {
                if (node.size == 2 && node[0] is Number && node[1] is Number) {
                    val lon = (node[0] as Number).toDouble()
                    val lat = (node[1] as Number).toDouble()
                    out.add(Pair(lat, lon))
                } else {
                    node.forEach { walk(it) }
                }
            }
        }
        val coords = gj.obj("geometry")?.obj("geometry")?.get("coordinates")
            ?: gj.obj("geometry")?.get("coordinates")
            ?: (gj.arr("features").firstOrNull() as? JsonObj)?.obj("geometry")?.get("coordinates")
            ?: gj.obj("route")?.obj("geometry")?.get("coordinates")
        walk(coords)
        return out
    }

    private fun extractDistanceMeters(gj: JsonObj): Double {
        return gj.obj("properties")?.obj("summary")?.dbl("distance", -1.0)?.takeIf { it >= 0 }
            ?: ((gj.arr("features").firstOrNull() as? JsonObj)?.obj("properties")?.obj("summary")?.dbl("distance", -1.0)?.takeIf { it >= 0 })
            ?: ((gj.arr("routes").firstOrNull() as? JsonObj)?.dbl("distance", -1.0)?.takeIf { it >= 0 })
            ?: gj.obj("route")?.dbl("distance", -1.0)?.takeIf { it >= 0 }
            ?: gj.dbl("length", 0.0)
    }

    private fun extractDurationSeconds(gj: JsonObj): Double {
        return gj.obj("properties")?.obj("summary")?.dbl("duration", -1.0)?.takeIf { it >= 0 }
            ?: ((gj.arr("features").firstOrNull() as? JsonObj)?.obj("properties")?.obj("summary")?.dbl("duration", -1.0)?.takeIf { it >= 0 })
            ?: ((gj.arr("routes").firstOrNull() as? JsonObj)?.dbl("duration", -1.0)?.takeIf { it >= 0 })
            ?: gj.obj("route")?.dbl("duration", -1.0)?.takeIf { it >= 0 }
            ?: gj.dbl("duration", 0.0)
    }

    // ------------------------------------------------------------------------
    // 4. ODESLÁNÍ POPTÁVKY, SEZNAM JÍZD, STORNO A PLATBA
    // ------------------------------------------------------------------------
    fun submitOrder(draft: DraftOrder, callback: (Result<JsonObj>) -> Unit) {
        val parts = mutableListOf(draft.start.label)
        draft.via.forEach { parts.add(it.label) }
        parts.add(draft.dest.label)
        val routeLabel = parts.joinToString(" ➔ ")

        val optionsMap = LinkedHashMap<String, Any?>()
        optionsMap["escort"] = draft.escort
        optionsMap["waitMinutes"] = draft.waitMinutes
        optionsMap["persons"] = draft.persons
        optionsMap["vehiclesMode"] = draft.vehiclesMode
        optionsMap["hasChildren"] = draft.hasChildren
        optionsMap["boosterSeats"] = draft.boosterSeats
        optionsMap["childSeats"] = draft.childSeats
        if (draft.flightNumber.isNotBlank()) {
            optionsMap["flight_number"] = draft.flightNumber.uppercase().replace(" ", "")
            optionsMap["flight_date"] = draft.flightDate
        }

        val pricingMap: JsonObj = mapOf(
            "kmAB" to draft.pricing.kmAB,
            "totalKc" to draft.pricing.totalKc,
            "startFee" to draft.pricing.startFee,
            "waitKc" to draft.pricing.waitKc,
            "pristavneKm" to draft.pricing.pristavneKm,
            "pristavneKc" to draft.pricing.pristavneKc,
            "carsCount" to draft.pricing.carsCount,
            "ridesCount" to draft.pricing.ridesCount,
            "discountPercent" to draft.pricing.discountPercent,
            "discountKc" to draft.pricing.discountKc,
            "etaApproachMin" to 0,
            "etaABMin" to draft.pricing.durationMin
        )

        val routePtsList = draft.pricing.routePoints.map { listOf(it.first, it.second) }
        val routeMap: JsonObj = mapOf(
            "km" to draft.pricing.kmAB,
            "seconds" to (draft.pricing.durationMin * 60),
            "points" to routePtsList
        )

        val payload: JsonObj = mapOf(
            "source" to "calc_v1",
            "service" to "taxi",
            "variant_key" to if (draft.routeType == "car_short") "short" else "traffic",
            "routeType" to draft.routeType,
            "term" to draft.term,
            "start" to mapOf("label" to draft.start.label, "lat" to draft.start.lat, "lon" to draft.start.lon),
            "dest" to mapOf("label" to draft.dest.label, "lat" to draft.dest.lat, "lon" to draft.dest.lon),
            "via" to draft.via.map { mapOf("label" to it.label, "lat" to it.lat, "lon" to it.lon) },
            "routeLabel" to routeLabel,
            "options" to optionsMap,
            "pricing" to pricingMap,
            "route" to routeMap,
            "pristavnePath" to emptyList<Any>()
        )

        postJson("/taxi/admin/modules/orders/api/create_from_calc.php", payload) { res ->
            res.onSuccess { obj ->
                if (obj.bool("ok", true) && !obj.containsKey("error")) {
                    callback(Result.success(obj))
                } else {
                    callback(Result.failure(Exception(obj.str("error", obj.str("message", "Zamítnuto serverem")))))
                }
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    fun fetchMyOrders(callback: (Result<List<OrderItem>>) -> Unit) {
        getJson("/taxi/app/api/get_my_orders.php", mapOf("_t" to PlatformBridge.currentTimeMillis().toString())) { res ->
            res.onSuccess { obj ->
                if (!obj.bool("ok")) {
                    callback(Result.failure(Exception(obj.str("error", "Chyba načítání objednávek"))))
                    return@onSuccess
                }
                val list = ArrayList<OrderItem>()
                for (raw in obj.arr("orders")) {
                    val o = (raw as? JsonObj) ?: continue
                    val detailsRaw = o["details"]
                    val detailsObj: JsonObj = when (detailsRaw) {
                        is String -> SimpleJson.parseObj(detailsRaw)
                        is kotlin.collections.Map<*, *> -> {
                            @Suppress("UNCHECKED_CAST")
                            detailsRaw as JsonObj
                        }
                        else -> emptyMap()
                    }
                    val opts = detailsObj.obj("options") ?: emptyMap()
                    val invObj = detailsObj.obj("invoicing") ?: o.obj("invoicing") ?: emptyMap()
                    val orderId = o.str("order_id")
                    val shortId = orderId.split("_").getOrNull(1) ?: orderId

                    val vias = detailsObj.arr("via").mapNotNull { (it as? JsonObj)?.str("label") }.filter { it.isNotBlank() }
                    val fromName = o.str("from_name").ifBlank { detailsObj.obj("start")?.str("label") ?: "Start" }
                    val toName = o.str("to_name").ifBlank { detailsObj.obj("dest")?.str("label") ?: "Cíl" }
                    val priceVal = o.str("total_price").ifBlank { detailsObj.obj("pricing")?.str("totalKc") ?: "" }
                    val pdfUrl = o.str("invoice_url").ifBlank { invObj.str("pdf_url") }
                    val invNum = invObj.str("invoice_number", "Daňový doklad")

                    list.add(
                        OrderItem(
                            orderId = orderId,
                            orderIdShort = shortId,
                            status = o.str("status", "NEW").uppercase(),
                            fromName = fromName,
                            toName = toName,
                            totalPrice = if (priceVal.isNotBlank()) "$priceVal Kč" else "—",
                            createdAt = o.str("created_at"),
                            pickupTerm = detailsObj.str("term").ifBlank { "Co nejdříve (ASAP)" },
                            viaLabels = vias,
                            persons = opts.int("persons", 0),
                            hasChildren = opts.bool("hasChildren"),
                            boosterSeats = opts.int("boosterSeats"),
                            childSeats = opts.int("childSeats"),
                            waitMinutes = opts.int("waitMinutes"),
                            escort = opts.bool("escort"),
                            vehiclesMode = opts.str("vehiclesMode", "auto"),
                            rejectReason = o.str("reject_reason").ifBlank { detailsObj.str("reject_reason") },
                            invoiceUrl = pdfUrl,
                            invoiceNumber = invNum
                        )
                    )
                }
                callback(Result.success(list))
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    fun cancelOrder(orderId: String, cancelAction: String, callback: (Result<String>) -> Unit) {
        val payload: JsonObj = mapOf(
            "order_id" to orderId,
            "cancel_action" to cancelAction
        )
        postJson("/taxi/app/api/cancel_order.php", payload) { res ->
            res.onSuccess { obj ->
                if (obj.str("status") == "success" || obj.bool("ok")) {
                    callback(Result.success(obj.str("new_status", "CANCELLED")))
                } else {
                    callback(Result.failure(Exception(obj.str("message", "Server zamítl storno."))))
                }
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    fun initPayment(orderId: String, callback: (Result<String>) -> Unit) {
        val returnUrl = "$BASE_URL/taxi/app/pay_done.html"
        val payload: JsonObj = mapOf(
            "order_id" to orderId,
            "return_url" to returnUrl
        )
        postJson("/taxi/admin/modules/pay/api_init_pwa.php", payload) { res ->
            res.onSuccess { obj ->
                val checkoutUrl = obj.str("checkout_url")
                if (obj.bool("ok") && checkoutUrl.isNotBlank()) {
                    callback(Result.success(checkoutUrl))
                } else {
                    callback(Result.failure(Exception(obj.str("error", "Chyba při inicializaci platby"))))
                }
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    // ------------------------------------------------------------------------
    // 5. POŠTA (INBOX) A RYCHLÉ ODPOVĚDI ŘIDIČI
    // ------------------------------------------------------------------------
    fun fetchUnreadCount(callback: (Int) -> Unit) {
        getJson("/taxi/app/api/get_unread_count.php", mapOf("_t" to PlatformBridge.currentTimeMillis().toString())) { res ->
            val count = res.getOrNull()?.let { it.int("unread", it.int("count", 0)) } ?: 0
            callback(count)
        }
    }

    fun fetchInboxMessages(callback: (Result<List<InboxMessage>>) -> Unit) {
        getJson("/taxi/app/api/get_inbox.php", mapOf("_t" to PlatformBridge.currentTimeMillis().toString())) { res ->
            res.onSuccess { obj ->
                if (!obj.bool("ok")) {
                    callback(Result.failure(Exception("Chyba načítání zpráv")))
                    return@onSuccess
                }
                val list = ArrayList<InboxMessage>()
                for (raw in obj.arr("messages")) {
                    val m = (raw as? JsonObj) ?: continue
                    val linkUrl = m.str("action_url")
                    val orderId = Regex("""[?&](?:oid|order_id|id)=([^&]+)""").find(linkUrl)?.groupValues?.getOrNull(1) ?: ""
                    val trackMatch = Regex("""[?&]track=([^&]+)""").find(linkUrl)?.groupValues?.getOrNull(1) ?: ""
                    val pMatch = Regex("""[?&]p=([^&]+)""").find(linkUrl)?.groupValues?.getOrNull(1) ?: ""

                    val replyType = when {
                        pMatch.isNotBlank() -> "p"
                        orderId.isNotBlank() -> "oid"
                        else -> ""
                    }
                    val replyToken = pMatch.ifBlank { orderId }

                    list.add(
                        InboxMessage(
                            msgId = m.str("msg_id"),
                            sender = m.str("sender", "Dispečink"),
                            title = m.str("title", "Zpráva"),
                            body = m.str("body"),
                            actionUrl = linkUrl,
                            isRead = m.bool("is_read"),
                            createdTs = m.str("created_ts"),
                            orderId = orderId,
                            trackToken = trackMatch.ifBlank { pMatch },
                            replyToken = replyToken,
                            replyType = replyType,
                            isPayRequired = linkUrl.contains("pay=1")
                        )
                    )
                }
                callback(Result.success(list))
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    fun markMessageRead(msgId: String) {
        postJson("/taxi/app/api/mark_read.php", mapOf("msg_id" to msgId)) {}
    }

    fun sendClientFeedback(
        token: String,
        type: String,
        action: String,
        extra: kotlin.collections.Map<String, String>,
        callback: (Boolean) -> Unit
    ) {
        val params = LinkedHashMap<String, String>()
        params["action"] = action
        params["_t"] = PlatformBridge.currentTimeMillis().toString()
        if (type == "oid") params["oid"] = token else params["p"] = token
        params.putAll(extra)
        getJson("/taxi/client_feedback.php", params) { res ->
            callback(res.getOrNull()?.bool("ok") == true)
        }
    }

    // ------------------------------------------------------------------------
    // 6. ŽIVÉ SLEDOVÁNÍ VOZU (TRACKING)
    // ------------------------------------------------------------------------
    fun fetchTrackingData(token: String, callback: (TrackingData) -> Unit) {
        val params = LinkedHashMap<String, String>()
        params["ajax"] = "1"
        params["_t"] = PlatformBridge.currentTimeMillis().toString()
        if (token.contains("-")) params["p"] = token else params["oid"] = token

        getJson("/taxi/track.php", params) { res ->
            val obj = res.getOrNull()
            if (obj == null || !obj.bool("ok")) {
                callback(TrackingData(ok = false))
                return@getJson
            }
            val lat = obj.dbl("lat", 0.0)
            val lon = obj.dbl("lon", 0.0)
            val tLat = obj.dbl("target_lat", 0.0)
            val tLon = obj.dbl("target_lon", 0.0)
            val carColor = obj.str("car_color", "#ffcc00")
            val carType = obj.str("car_type", "sedan")

            if (lat != 0.0 && lon != 0.0 && tLat != 0.0 && tLon != 0.0) {
                val dist = haversineMeters(lat, lon, tLat, tLon)
                if (dist < 50.0) {
                    callback(
                        TrackingData(
                            ok = true,
                            lat = lat,
                            lon = lon,
                            targetLat = tLat,
                            targetLon = tLon,
                            carColor = carColor,
                            carType = carType,
                            etaText = "Na místě!"
                        )
                    )
                    return@getJson
                }
                val rParams = mapOf(
                    "mode" to "route",
                    "start" to "$lon,$lat",
                    "end" to "$tLon,$tLat",
                    "routeType" to "car_short",
                    "format" to "geojson"
                )
                getJson(PROXY_PATH, rParams) { rRes ->
                    val gj = rRes.getOrNull()
                    val pts = if (gj != null) extractCoordinates(gj) else emptyList()
                    val sec = if (gj != null) extractDurationSeconds(gj) else 0.0
                    val etaStr = if (sec > 0) "${ceil(sec / 60.0).toInt()} min" else "--:--"
                    callback(
                        TrackingData(
                            ok = true,
                            lat = lat,
                            lon = lon,
                            targetLat = tLat,
                            targetLon = tLon,
                            carColor = carColor,
                            carType = carType,
                            etaText = etaStr,
                            routePoints = pts
                        )
                    )
                }
            } else {
                callback(
                    TrackingData(
                        ok = true,
                        lat = lat,
                        lon = lon,
                        targetLat = tLat,
                        targetLon = tLon,
                        carColor = carColor,
                        carType = carType,
                        etaText = "--:--"
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // 7. MŮJ ÚČET, BEZPEČNOST, FAKTURACE A ARES
    // ------------------------------------------------------------------------
    fun fetchBillingData(callback: (BillingData) -> Unit) {
        getJson("/taxi/admin/modules/account/api/get.php", mapOf("pwa" to "1")) { res ->
            val b = res.getOrNull() ?: emptyMap()
            val cit = b.obj("citizen")
            val addr = cit?.obj("address_obj")
            val comp = b.obj("company")
            callback(
                BillingData(
                    citizenFirstName = cit?.str("first_name") ?: "",
                    citizenLastName = cit?.str("last_name") ?: "",
                    citizenStreet = addr?.str("street") ?: "",
                    citizenNumber = addr?.str("number") ?: "",
                    citizenZip = addr?.str("zip") ?: "",
                    citizenCity = addr?.str("city") ?: "",
                    companyIco = comp?.str("ico") ?: "",
                    companyName = comp?.str("name") ?: "",
                    companyDic = comp?.str("dic") ?: "",
                    companyAddress = comp?.str("address") ?: "",
                    preferCompany = comp != null && comp.str("ico").isNotBlank()
                )
            )
        }
    }

    fun saveBillingCitizen(
        firstName: String,
        lastName: String,
        street: String,
        number: String,
        zip: String,
        city: String,
        callback: (Boolean, String) -> Unit
    ) {
        val payload: JsonObj = mapOf(
            "type" to "citizen",
            "first_name" to firstName.trim(),
            "last_name" to lastName.trim(),
            "address_obj" to mapOf(
                "street" to street.trim(),
                "number" to number.trim(),
                "zip" to zip.trim(),
                "city" to city.trim(),
                "country" to "Česko"
            )
        )
        postJson("/taxi/admin/modules/account/api/save.php?pwa=1", payload) { res ->
            res.onSuccess { obj ->
                if (obj.containsKey("error") && obj.str("error").isNotBlank()) {
                    callback(false, obj.str("error"))
                } else {
                    callback(true, "Fakturační adresa občana uložena.")
                }
            }.onFailure {
                callback(false, it.message ?: "Chyba uložení")
            }
        }
    }

    fun saveBillingCompany(
        ico: String,
        name: String,
        dic: String,
        address: String,
        callback: (Boolean, String) -> Unit
    ) {
        val payload: JsonObj = mapOf(
            "type" to "company",
            "ico" to ico.filter { it.isDigit() },
            "name" to name.trim(),
            "dic" to dic.trim(),
            "address" to address.trim()
        )
        postJson("/taxi/admin/modules/account/api/save.php?pwa=1", payload) { res ->
            res.onSuccess { obj ->
                if (obj.containsKey("error") && obj.str("error").isNotBlank()) {
                    callback(false, obj.str("error"))
                } else {
                    callback(true, "Firemní fakturační údaje uloženy.")
                }
            }.onFailure {
                callback(false, it.message ?: "Chyba uložení")
            }
        }
    }

    fun fetchAres(ico: String, callback: (Result<Triple<String, String, String>>) -> Unit) {
        val cleanIco = ico.filter { it.isDigit() }
        if (cleanIco.length != 8) {
            callback(Result.failure(Exception("Zadejte platné IČO (8 číslic).")))
            return
        }
        getJson("/taxi/admin/modules/clients/api/ares.php", mapOf("ico" to cleanIco, "pwa" to "1")) { res ->
            res.onSuccess { obj ->
                if (obj.containsKey("error") && obj.str("error").isNotBlank()) {
                    callback(Result.failure(Exception(obj.str("error"))))
                } else {
                    callback(
                        Result.success(
                            Triple(
                                obj.str("company_name"),
                                obj.str("dic").uppercase(),
                                obj.str("address_text")
                            )
                        )
                    )
                }
            }.onFailure {
                callback(Result.failure(it))
            }
        }
    }

    fun securityAction(payload: JsonObj, callback: (Boolean, String) -> Unit) {
        postJson("/taxi/admin/modules/account/api/security.php?pwa=1", payload) { res ->
            res.onSuccess { obj ->
                val err = obj.str("error").ifBlank { obj.str("msg") }
                if (err.isNotBlank() && !obj.bool("ok", true)) {
                    callback(false, err)
                } else {
                    callback(true, "Změna byla úspěšně provedena.")
                }
            }.onFailure {
                callback(false, it.message ?: "Chyba serveru")
            }
        }
    }

    fun registerPushToken(fcmToken: String, provider: String = "FCM") {
        if (fcmToken.isBlank() || getToken().isBlank()) return
        val uid = PlatformBridge.loadPref("taxi_user_id")
        val payload: JsonObj = mapOf(
            "action" to "save_token",
            "fcm_token" to fcmToken,
            "app_type" to "user_pwa",
            "provider" to provider,
            "user_id" to uid
        )
        postJson("/taxi/admin/modules/push/api.php", payload) {}
    }
}
