package cz.mares.taxi.user

import kotlin.math.*

typealias JsonObj = kotlin.collections.Map<String, Any?>

data class UserProfile(
    val id: String = "",
    val name: String = "Zákazník",
    val email: String = "",
    val phone: String = "",
    val discountTaxi: Int = 0
)

data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val label: String
)

data class SuggestItem(
    val lat: Double,
    val lon: Double,
    val listLabel: String,
    val inputLabel: String
)

data class CenikConfig(
    val perKm: Double = 35.0,
    val approachPerKm: Double = 35.0,
    val waitPerMin: Double = 10.0,
    val startKc: Double = 60.0,
    val escortMult: Double = 1.5,
    val roundTo: Int = 10,
    val roundMode: String = "ceil"
)

data class PricingResult(
    val kmAB: Double = 0.0,
    val totalKc: Int = 0,
    val startFee: Int = 60,
    val waitKc: Int = 0,
    val pristavneKm: Double = 0.0,
    val pristavneKc: Int = 0,
    val carsCount: Int = 1,
    val ridesCount: Int = 1,
    val discountPercent: Int = 0,
    val discountKc: Int = 0,
    val durationMin: Int = 0,
    val routePoints: List<Pair<Double, Double>> = emptyList()
)

data class DraftOrder(
    val routeType: String,
    val term: String,
    val start: GeoPoint,
    val dest: GeoPoint,
    val via: List<GeoPoint>,
    val persons: Int,
    val waitMinutes: Int,
    val hasChildren: Boolean,
    val boosterSeats: Int,
    val childSeats: Int,
    val escort: Boolean,
    val vehiclesMode: String,
    val flightNumber: String,
    val flightDate: String,
    val pricing: PricingResult
)

data class OrderItem(
    val orderId: String,
    val orderIdShort: String,
    val status: String,
    val fromName: String,
    val toName: String,
    val totalPrice: String,
    val createdAt: String,
    val pickupTerm: String,
    val viaLabels: List<String>,
    val persons: Int,
    val hasChildren: Boolean,
    val boosterSeats: Int,
    val childSeats: Int,
    val waitMinutes: Int,
    val escort: Boolean,
    val vehiclesMode: String,
    val rejectReason: String,
    val invoiceUrl: String,
    val invoiceNumber: String
)

data class InboxMessage(
    val msgId: String,
    val sender: String,
    val title: String,
    val body: String,
    val actionUrl: String,
    val isRead: Boolean,
    val createdTs: String,
    val orderId: String,
    val trackToken: String,
    val replyToken: String,
    val replyType: String,
    val isPayRequired: Boolean
)

data class BillingData(
    val citizenFirstName: String = "",
    val citizenLastName: String = "",
    val citizenStreet: String = "",
    val citizenNumber: String = "",
    val citizenZip: String = "",
    val citizenCity: String = "",
    val companyIco: String = "",
    val companyName: String = "",
    val companyDic: String = "",
    val companyAddress: String = "",
    val preferCompany: Boolean = false
)

data class TrackingData(
    val ok: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val targetLat: Double = 0.0,
    val targetLon: Double = 0.0,
    val carColor: String = "#ffcc00",
    val carType: String = "sedan",
    val etaText: String = "--:--",
    val routePoints: List<Pair<Double, Double>> = emptyList()
)

object CzechVocativeHelper {
    private val specialNames = mapOf(
        "petr" to "Petře", "pavel" to "Pavle", "marek" to "Marku", "david" to "Davide",
        "filip" to "Filipe", "jakub" to "Jakube", "jan" to "Honzo", "lukáš" to "Lukáši",
        "tomáš" to "Tomáši", "martin" to "Martine", "michal" to "Michale", "ondřej" to "Ondřeji",
        "vojtěch" to "Vojtěchu", "matěj" to "Matěji", "jiří" to "Jiří", "josef" to "Josefe",
        "karel" to "Karle", "milan" to "Milane", "václav" to "Václave", "adam" to "Adame",
        "roman" to "Romane", "radek" to "Radku", "miloš" to "Miloši", "patrik" to "Patriku",
        "richard" to "Richarde", "dominik" to "Dominiku", "vladimír" to "Vladimíre",
        "eva" to "Evo", "jana" to "Jano", "marie" to "Marie", "hana" to "Hano",
        "lenka" to "Lenko", "kateřina" to "Katko", "lucie" to "Lucko", "petra" to "Petro",
        "zuzana" to "Zuzko", "jitka" to "Jitko", "martina" to "Martino", "kristýna" to "Kristýno",
        "terezka" to "Terezko", "tereza" to "Terezo", "radu" to "Radu"
    )

    fun greetingFor(fullName: String?): String {
        val raw = fullName?.trim()?.split(" ")?.firstOrNull()?.trim() ?: ""
        if (raw.isEmpty() || raw.contains("@") || raw.all { it.isDigit() || it == '+' } ||
            raw.lowercase() == "zákazník" || raw.lowercase() == "zakaznik"
        ) {
            return "Kam to bude?"
        }
        val n = raw.lowercase()
        val vocative = specialNames[n] ?: run {
            val last = n.last()
            val last2 = if (n.length >= 2) n.substring(n.length - 2) else ""
            val v = when {
                last2 == "el" -> n.dropLast(2) + "le"
                last2 == "ek" -> n.dropLast(2) + "ku"
                last == 'a' -> n.dropLast(1) + "o"
                last in listOf('š', 'j', 'č', 'ř', 'c', 'z') -> n + "i"
                last !in listOf('e', 'i', 'o', 'u', 'y', 'á', 'é', 'í', 'ý') -> n + "e"
                else -> n
            }
            v.replaceFirstChar { it.uppercase() }
        }
        return "Kam to bude, $vocative?"
    }
}

object SimpleJson {
    fun parse(text: String): Any? {
        val parser = Parser(text.trim())
        return parser.parseValue()
    }

    fun parseObj(text: String): JsonObj {
        val res = parse(text)
        @Suppress("UNCHECKED_CAST")
        return (res as? JsonObj) ?: emptyMap()
    }

    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> value.toString()
        is String -> "\"" + escape(value) + "\""
        is kotlin.collections.Map<*, *> -> {
            value.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"" + escape(k.toString()) + "\":" + encode(v)
            }
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { encode(it) }
        else -> "\"" + escape(value.toString()) + "\""
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var pos = 0

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= s.length) return null
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> if (c == '-' || c.isDigit()) parseNumber() else null
            }
        }

        private fun parseObject(): JsonObj {
            val map = LinkedHashMap<String, Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (pos < s.length) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') break
                val key = parseString()
                skipWhitespace()
                if (pos < s.length && s[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == '}') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return map
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (pos < s.length) {
                list.add(parseValue())
                skipWhitespace()
                if (pos < s.length && s[pos] == ',') {
                    pos++
                } else if (pos < s.length && s[pos] == ']') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return list
        }

        private fun parseString(): String {
            pos++
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos++]
                if (c == '"') break
                if (c == '\\' && pos < s.length) {
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 <= s.length) {
                                val hex = s.substring(pos, pos + 4)
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun parseBoolean(): Boolean {
            return if (s.startsWith("true", pos)) {
                pos += 4
                true
            } else {
                pos += 5
                false
            }
        }

        private fun parseNull(): Any? {
            pos += 4
            return null
        }

        private fun parseNumber(): Number {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in listOf('-', '+', '.', 'e', 'E'))) {
                pos++
            }
            val numStr = s.substring(start, pos)
            return if (numStr.contains('.') || numStr.contains('e', ignoreCase = true)) {
                numStr.toDoubleOrNull() ?: 0.0
            } else {
                numStr.toLongOrNull() ?: 0L
            }
        }
    }
}

fun JsonObj.str(key: String, default: String = ""): String =
    this[key]?.toString()?.takeIf { it != "null" } ?: default

fun JsonObj.int(key: String, default: Int = 0): Int =
    when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toDoubleOrNull()?.toInt() ?: default
        else -> default
    }

fun JsonObj.dbl(key: String, default: Double = 0.0): Double =
    when (val v = this[key]) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: default
        else -> default
    }

fun JsonObj.bool(key: String, default: Boolean = false): Boolean =
    when (val v = this[key]) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v.equals("true", ignoreCase = true) || v == "1"
        else -> default
    }

@Suppress("UNCHECKED_CAST")
fun JsonObj.obj(key: String): JsonObj? = this[key] as? JsonObj

@Suppress("UNCHECKED_CAST")
fun JsonObj.arr(key: String): List<Any?> = (this[key] as? List<Any?>) ?: emptyList()
