package cz.mares.taxi.user

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WaterGlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isDark: Boolean,
    brandColor: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isHighlighted: Boolean = false,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    val formBg = if (isDark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val formText = if (isHighlighted) brandColor else if (isDark) brandColor else Color(0xFF000000)
    val formBorder = if (isHighlighted) brandColor else if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = formText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        ),
        cursorBrush = SolidColor(brandColor),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(formBg, RoundedCornerShape(14.dp))
                    .border(1.dp, formBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(10.dp))
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = formText.copy(alpha = 0.48f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    innerTextField()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingIcon()
                }
            }
        },
        modifier = modifier
    )
}

@Composable
fun InboxView(
    isDark: Boolean,
    brandColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color,
    onOpenTracking: (String) -> Unit,
    onOpenView: (String) -> Unit,
    onInitiatePayFromInbox: (String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<InboxMessage>>(emptyList()) }
    var replyTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var customReplyText by remember { mutableStateOf("") }
    var actionStatusMsg by remember { mutableStateOf<String?>(null) }

    fun loadInbox() {
        isLoading = true
        errorMsg = null
        TaxiApi.fetchInboxMessages { res ->
            isLoading = false
            res.onSuccess { messages = it }
                .onFailure { errorMsg = "Chyba připojení k serveru. Zkuste to prosím znovu." }
        }
    }

    LaunchedEffect(Unit) {
        loadInbox()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (actionStatusMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brandColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .border(1.dp, brandColor, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(actionStatusMsg!!, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Načítám zprávy... ⏳", color = textColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
        } else if (errorMsg != null) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(errorMsg!!, color = Color(0xFFFF4C4C), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { loadInbox() },
                    colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Zkusit znovu", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        } else if (messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Schránka je prázdná.", color = textColor.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
            }
        } else {
            messages.forEach { msg ->
                val isUnread = !msg.isRead
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, if (isUnread) brandColor else borderColor, RoundedCornerShape(16.dp))
                        .clickable {
                            if (isUnread) {
                                TaxiApi.markMessageRead(msg.msgId)
                                messages = messages.map { if (it.msgId == msg.msgId) it.copy(isRead = true) else it }
                            }
                            if (msg.trackToken.isNotBlank()) {
                                onOpenTracking(msg.trackToken)
                            } else if (msg.actionUrl.contains("open=orders")) {
                                onOpenView("orders")
                            } else if (msg.actionUrl.contains("open=requests")) {
                                onOpenView("requests")
                            } else if (msg.actionUrl.contains("open=invoices")) {
                                onOpenView("invoices")
                            }
                        }
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(msg.sender, color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                            if (isUnread) {
                                Text(
                                    "NOVÉ",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .background(Color(0xFFFF3B30), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(msg.createdTs, color = textColor.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(msg.title, color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(msg.body, color = textColor.copy(alpha = 0.85f), fontSize = 14.sp)

                    if (msg.isPayRequired && msg.orderId.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onInitiatePayFromInbox(msg.orderId) },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2ECC71)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(2f).height(46.dp)
                            ) {
                                Text("💳 Zaplatit", color = Color.White, fontWeight = FontWeight.Black)
                            }
                            OutlinedButton(
                                onClick = {
                                    TaxiApi.cancelOrder(msg.orderId, "user_cancel") {
                                        actionStatusMsg = "Poptávka byla zrušena."
                                        loadInbox()
                                    }
                                },
                                border = BorderStroke(1.dp, Color(0xFFE74C3C)),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("❌ Zrušit", color = Color(0xFFE74C3C), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (msg.replyToken.isNotBlank() || msg.trackToken.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (msg.replyToken.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { replyTarget = Pair(msg.replyToken, msg.replyType) },
                                    border = BorderStroke(1.dp, borderColor),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = cardBg)
                                ) {
                                    Text("💬 Odpovědět", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            if (msg.trackToken.isNotBlank()) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Sledovat jízdu ➔",
                                    color = brandColor,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable { onOpenTracking(msg.trackToken) }
                                )
                            }
                        }
                    }
                }
            }
        }

        replyTarget?.let { (token, type) ->
            Card(
                backgroundColor = if (isDark) Color(0xFF141418) else Color.White,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, brandColor),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Akce k jízdě", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                    Button(
                        onClick = {
                            TaxiApi.sendClientFeedback(token, type, "msg", mapOf("txt" to "👍 Už jdu ven, počkejte prosím.")) {
                                actionStatusMsg = "✅ Zpráva odeslána řidiči"
                                replyTarget = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("👍 Už jdu ven", color = textColor, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            TaxiApi.sendClientFeedback(token, type, "msg", mapOf("txt" to "⏱️ Zdržím se 5 minut.")) {
                                actionStatusMsg = "✅ Zpráva odeslána řidiči"
                                replyTarget = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = cardBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("⏱️ Zdržím se 5 minut", color = textColor, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            actionStatusMsg = "⏳ Zjišťuji vaši GPS polohu..."
                            PlatformBridge.requestLocation { lat, lon ->
                                if (lat != null && lon != null) {
                                    TaxiApi.sendClientFeedback(
                                        token,
                                        type,
                                        "gps",
                                        mapOf("lat" to lat.toString(), "lon" to lon.toString())
                                    ) {
                                        actionStatusMsg = "✅ Poloha byla upřesněna řidiči"
                                        replyTarget = null
                                    }
                                } else {
                                    actionStatusMsg = "⚠️ Povolte prosím přístup k GPS poloze."
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = brandColor.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("📍 Upřesnit moji polohu", color = brandColor, fontWeight = FontWeight.ExtraBold)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WaterGlassInput(
                            value = customReplyText,
                            onValueChange = { customReplyText = it },
                            placeholder = "Napsat vlastní...",
                            isDark = isDark,
                            brandColor = brandColor,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (customReplyText.isNotBlank()) {
                                    TaxiApi.sendClientFeedback(token, type, "msg", mapOf("txt" to customReplyText.trim())) {
                                        actionStatusMsg = "✅ Zpráva odeslána"
                                        customReplyText = ""
                                        replyTarget = null
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Text("Odeslat", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                    TextButton(
                        onClick = { replyTarget = null },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("Zrušit", color = textColor.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
fun OrdersListView(
    viewType: String,
    userProfile: UserProfile,
    preselectedPayOrderId: String?,
    onClearPreselectedPay: () -> Unit,
    onEditAccountClick: () -> Unit,
    onOpenLegalModal: (String, String) -> Unit,
    brandColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color
) {
    var isLoading by remember { mutableStateOf(true) }
    var allOrders by remember { mutableStateOf<List<OrderItem>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var payConsentOrder by remember { mutableStateOf<OrderItem?>(null) }
    var confirmCancelTarget by remember { mutableStateOf<Pair<OrderItem, String>?>(null) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    fun loadOrders() {
        isLoading = true
        errorMsg = null
        TaxiApi.fetchMyOrders { res ->
            isLoading = false
            res.onSuccess { list ->
                allOrders = list
                if (preselectedPayOrderId != null) {
                    val found = list.firstOrNull { it.orderId == preselectedPayOrderId }
                    if (found != null) {
                        payConsentOrder = found
                    }
                    onClearPreselectedPay()
                }
            }.onFailure {
                errorMsg = "Chyba připojení k databázi."
            }
        }
    }

    LaunchedEffect(viewType) {
        loadOrders()
    }

    if (payConsentOrder != null) {
        val ord = payConsentOrder!!
        var chkTerms by remember { mutableStateOf(false) }
        var chkPrivacy by remember { mutableStateOf(false) }
        var isPreparingPay by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                    .padding(16.dp)
            ) {
                Text("FAKTURAČNÍ ÚDAJE", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text(userProfile.name, color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Black)
                if (userProfile.phone.isNotBlank()) {
                    Text(userProfile.phone, color = textColor.copy(alpha = 0.85f), fontSize = 15.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Zkontrolujte prosím své údaje. Budou uvedeny na faktuře.",
                    color = textColor.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "✏️ Upravit údaje",
                    color = brandColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        payConsentOrder = null
                        onEditAccountClick()
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { chkTerms = !chkTerms }
            ) {
                Checkbox(
                    checked = chkTerms,
                    onCheckedChange = { chkTerms = it },
                    colors = CheckboxDefaults.colors(checkedColor = brandColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Souhlasím s Obchodními podmínkami.",
                    color = textColor,
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        onOpenLegalModal("${TaxiApi.BASE_URL}/taxi/legal/obchodni-podminky.html?pwa=1", "Obchodní podmínky")
                    }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { chkPrivacy = !chkPrivacy }
            ) {
                Checkbox(
                    checked = chkPrivacy,
                    onCheckedChange = { chkPrivacy = it },
                    colors = CheckboxDefaults.colors(checkedColor = brandColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Seznámil/a jsem se se Zásadami ochrany osobních údajů.",
                    color = textColor,
                    fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable {
                        onOpenLegalModal("${TaxiApi.BASE_URL}/taxi/legal/ochrana-osobnich-udaju.html?pwa=1", "Ochrana osobních údajů")
                    }
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { payConsentOrder = null },
                    border = BorderStroke(1.dp, borderColor),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Text("Zpět", color = textColor, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        if (chkTerms && chkPrivacy && !isPreparingPay) {
                            isPreparingPay = true
                            TaxiApi.initPayment(ord.orderId) { res ->
                                isPreparingPay = false
                                res.onSuccess { url ->
                                    payConsentOrder = null
                                    PlatformBridge.openExternalUrl(url)
                                }.onFailure { err ->
                                    statusBanner = "Chyba při inicializaci platby: ${err.message}"
                                    payConsentOrder = null
                                }
                            }
                        }
                    },
                    enabled = chkTerms && chkPrivacy && !isPreparingPay,
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2ECC71)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(2f).height(52.dp)
                ) {
                    Text(
                        if (isPreparingPay) "Připravuji bránu... ⏳" else "Zaplatit (${ord.totalPrice})",
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        return
    }

    val filtered = allOrders.filter { o ->
        if (viewType == "requests") {
            o.status in listOf("NEW", "SENT", "APPROVED", "REJECTED", "CANCELLED")
        } else {
            o.status in listOf("PAID", "COMPLETED", "CANCEL_REQUESTED", "REFUNDED_FULL", "REFUNDED_PARTIAL")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (statusBanner != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brandColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .border(1.dp, brandColor, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(statusBanner!!, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        confirmCancelTarget?.let { (ord, action) ->
            val isRequestCancel = action == "user_request_cancel"
            Card(
                backgroundColor = cardBg,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (isRequestCancel) Color(0xFFE67E22) else Color(0xFFE74C3C)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("⚠️", fontSize = 32.sp)
                    Text(
                        if (isRequestCancel) "Požádat o storno?" else "Zrušit poptávku?",
                        color = textColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                    Text(
                        if (isRequestCancel) {
                            "Opravdu chcete požádat o storno zaplacené jízdy? Váš požadavek předáme dispečinku."
                        } else {
                            "Opravdu chcete zrušit tuto poptávku?"
                        },
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { confirmCancelTarget = null },
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Zpět", color = textColor, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val targetOrd = ord
                                confirmCancelTarget = null
                                TaxiApi.cancelOrder(targetOrd.orderId, action) { res ->
                                    res.onSuccess {
                                        statusBanner = if (isRequestCancel) "Žádost o storno byla odeslána." else "Poptávka byla zrušena."
                                        loadOrders()
                                    }.onFailure { err ->
                                        statusBanner = "Nepodařilo se zrušit: ${err.message}"
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (isRequestCancel) Color(0xFFE67E22) else Color(0xFFE74C3C)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (isRequestCancel) "Odeslat žádost" else "Ano, zrušit",
                                color = Color.White,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Načítám data... ⏳", color = textColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
        } else if (errorMsg != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(errorMsg!!, color = Color(0xFFFF4C4C), fontWeight = FontWeight.Bold)
            }
        } else if (filtered.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Zatím zde nic není.", color = textColor.copy(alpha = 0.5f), fontWeight = FontWeight.Bold)
            }
        } else {
            filtered.forEach { o ->
                var showDetails by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Objednáno: ${o.createdAt}", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
                        Text("ID: ${o.orderIdShort}", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${o.fromName} ➔ ${o.toName}", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)

                    Spacer(Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(borderColor.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                            .clickable { showDetails = !showDetails }
                            .padding(12.dp)
                    ) {
                        Text(
                            if (showDetails) "▲ Skrýt podrobnosti o jízdě" else "ℹ️ Podrobnosti o jízdě",
                            color = textColor.copy(alpha = 0.85f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (showDetails) {
                            Spacer(Modifier.height(8.dp))
                            Text("Vyzvednutí: ${o.pickupTerm}", color = brandColor, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Start: ${o.fromName}", color = textColor, fontSize = 13.sp)
                            o.viaLabels.forEach { v ->
                                Text("↪ Přes: $v", color = textColor.copy(alpha = 0.8f), fontSize = 13.sp)
                            }
                            Text("Cíl: ${o.toName}", color = textColor, fontSize = 13.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Osob: ${if (o.persons > 0) "${o.persons} osob(y)" else "Neurčeno"}",
                                color = textColor.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                            if (o.hasChildren) {
                                Text(
                                    "Děti: Ano (Podsedák: ${o.boosterSeats}x, Autosedačka: ${o.childSeats}x)",
                                    color = textColor.copy(alpha = 0.85f),
                                    fontSize = 12.sp
                                )
                            }
                            if (o.waitMinutes > 0) {
                                Text("Čekání: ${o.waitMinutes} min.", color = textColor.copy(alpha = 0.85f), fontSize = 12.sp)
                            }
                            if (o.escort) {
                                Text("Doprovod vozidla: Ano (Escort)", color = textColor.copy(alpha = 0.85f), fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    val (statusText, statusColor) = when (o.status) {
                        "NEW", "SENT" -> Pair("⏳ Čeká na schválení dispečerem", brandColor)
                        "APPROVED" -> Pair("✅ Schváleno - Čeká na platbu", Color(0xFF2ECC71))
                        "PAID" -> Pair("🚕 Zaplaceno", Color(0xFF2ECC71))
                        "CANCEL_REQUESTED" -> Pair("⚠️ Zpracovává se storno a vrácení peněz", Color(0xFFE67E22))
                        "REJECTED" -> Pair(
                            "❌ Zamítnuto řidičem" + if (o.rejectReason.isNotBlank()) " (${o.rejectReason})" else "",
                            Color(0xFFE74C3C)
                        )
                        "CANCELLED" -> Pair("❌ Zrušeno zákazníkem", Color(0xFFE74C3C))
                        "REFUNDED_FULL", "REFUNDED_PARTIAL" -> Pair("💸 Stornováno / Refundováno", Color(0xFFE74C3C))
                        else -> Pair("🏁 Dokončeno / Uzavřeno", textColor.copy(alpha = 0.6f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(statusText, color = statusColor, fontWeight = FontWeight.Black, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(o.totalPrice, color = textColor, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }

                    if (o.status in listOf("NEW", "SENT")) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { confirmCancelTarget = Pair(o, "user_cancel") },
                            border = BorderStroke(1.dp, Color(0xFFE74C3C)),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("❌ Zrušit poptávku", color = Color(0xFFE74C3C), fontWeight = FontWeight.Black)
                        }
                    } else if (o.status == "APPROVED") {
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { payConsentOrder = o },
                                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2ECC71)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("💳 Zaplatit", color = Color.White, fontWeight = FontWeight.Black)
                            }
                            OutlinedButton(
                                onClick = { confirmCancelTarget = Pair(o, "user_cancel") },
                                border = BorderStroke(1.dp, Color(0xFFE74C3C)),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Text("❌ Zrušit", color = Color(0xFFE74C3C), fontWeight = FontWeight.Black)
                            }
                        }
                    } else if (o.status == "PAID") {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { confirmCancelTarget = Pair(o, "user_request_cancel") },
                            border = BorderStroke(1.dp, Color(0xFFE67E22)),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().height(46.dp)
                        ) {
                            Text("⚠️ Požádat o storno jízdy", color = Color(0xFFE67E22), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InvoicesView(
    brandColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color
) {
    var isLoading by remember { mutableStateOf(true) }
    var invoices by remember { mutableStateOf<List<OrderItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        TaxiApi.fetchMyOrders { res ->
            isLoading = false
            invoices = res.getOrDefault(emptyList()).filter { it.invoiceUrl.isNotBlank() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                Text("Načítám doklady... ⏳", color = textColor.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
            }
        } else if (invoices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📭", fontSize = 42.sp)
                Spacer(Modifier.height(12.dp))
                Text("Žádné doklady", color = textColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                Text("Zatím nemáte žádné vygenerované faktury.", color = textColor.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        } else {
            invoices.forEach { inv ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("📄 ${inv.invoiceNumber}", color = brandColor, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        Text(inv.createdAt, color = textColor.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(inv.totalPrice, color = textColor, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("${inv.fromName} ➔ ${inv.toName}", color = textColor.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            onClick = {
                                val fullUrl = if (inv.invoiceUrl.startsWith("http")) inv.invoiceUrl else "${TaxiApi.BASE_URL}${inv.invoiceUrl}"
                                PlatformBridge.openExternalUrl(fullUrl)
                            },
                            border = BorderStroke(1.dp, borderColor),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(backgroundColor = cardBg)
                        ) {
                            Text("⬇️ Stáhnout doklad", color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountView(
    isDark: Boolean,
    userProfile: UserProfile,
    onProfileReload: () -> Unit,
    onLogout: () -> Unit,
    brandColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color
) {
    var editMode by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf("security") }
    var billingSubTab by remember { mutableStateOf("citizen") }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    var nameInp by remember(userProfile) { mutableStateOf(userProfile.name) }
    var emailInp by remember(userProfile) { mutableStateOf(userProfile.email) }
    var emailOtpVisible by remember { mutableStateOf(false) }
    var emailOtpCode by remember { mutableStateOf("") }

    var phoneInp by remember(userProfile) { mutableStateOf(userProfile.phone) }
    var phoneOtpVisible by remember { mutableStateOf(false) }
    var phoneOtpCode by remember { mutableStateOf("") }

    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }

    var cFn by remember { mutableStateOf("") }
    var cLn by remember { mutableStateOf("") }
    var cStreet by remember { mutableStateOf("") }
    var cNum by remember { mutableStateOf("") }
    var cCity by remember { mutableStateOf("") }
    var cZip by remember { mutableStateOf("") }

    var fIco by remember { mutableStateOf("") }
    var fName by remember { mutableStateOf("") }
    var fDic by remember { mutableStateOf("") }
    var fAddr by remember { mutableStateOf("") }
    var aresMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        TaxiApi.fetchBillingData { b ->
            cFn = b.citizenFirstName
            cLn = b.citizenLastName
            cStreet = b.citizenStreet
            cNum = b.citizenNumber
            cCity = b.citizenCity
            cZip = b.citizenZip
            fIco = b.companyIco
            fName = b.companyName
            fDic = b.companyDic
            fAddr = b.companyAddress
            if (b.preferCompany) billingSubTab = "company"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (statusMsg != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(brandColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                    .border(1.dp, brandColor, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Text(statusMsg!!, color = textColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (!editMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .background(cardBg, CircleShape)
                        .border(1.dp, borderColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("👤", fontSize = 26.sp)
                }
                Column {
                    Text(userProfile.name, color = textColor, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    if (userProfile.phone.isNotBlank()) {
                        Text(userProfile.phone, color = textColor.copy(alpha = 0.7f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    if (userProfile.discountTaxi > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Aktivní sleva: ${userProfile.discountTaxi}%",
                            color = brandColor,
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            modifier = Modifier
                                .background(cardBg, RoundedCornerShape(20.dp))
                                .border(1.dp, borderColor, RoundedCornerShape(20.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Button(
                onClick = { editMode = true },
                colors = ButtonDefaults.buttonColors(backgroundColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text("⚙️ Upravit profil a fakturaci", color = textColor, fontWeight = FontWeight.ExtraBold)
            }

            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(backgroundColor = cardBg),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, borderColor),
                modifier = Modifier.fillMaxWidth().height(54.dp)
            ) {
                Text("🚪 Odhlásit se", color = Color(0xFFE74C3C), fontWeight = FontWeight.ExtraBold)
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { activeTab = "security" },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (activeTab == "security") brandColor else cardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        "🔐 Bezpečnost",
                        color = if (activeTab == "security") Color.Black else textColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
                Button(
                    onClick = { activeTab = "address" },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (activeTab == "address") brandColor else cardBg
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text(
                        "📦 Fakturace",
                        color = if (activeTab == "address") Color.Black else textColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp
                    )
                }
            }

            if (activeTab == "security") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Zobrazované jméno", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    WaterGlassInput(
                        value = nameInp,
                        onValueChange = { nameInp = it },
                        placeholder = "Vaše jméno",
                        isDark = isDark,
                        brandColor = brandColor
                    )
                    Button(
                        onClick = {
                            TaxiApi.securityAction(mapOf("action" to "change_name", "name" to nameInp)) { ok, msg ->
                                statusMsg = msg
                                if (ok) onProfileReload()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("💾 Uložit jméno", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Přihlašovací E-mail", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WaterGlassInput(
                            value = emailInp,
                            onValueChange = { emailInp = it },
                            placeholder = "E-mail",
                            enabled = !emailOtpVisible,
                            isDark = isDark,
                            brandColor = brandColor,
                            keyboardType = KeyboardType.Email,
                            modifier = Modifier.weight(1f)
                        )
                        if (!emailOtpVisible) {
                            Button(
                                onClick = {
                                    TaxiApi.securityAction(mapOf("action" to "email_req", "email" to emailInp)) { ok, msg ->
                                        if (ok) {
                                            emailOtpVisible = true
                                            statusMsg = "Na e-mail byl odeslán 6místný kód."
                                        } else statusMsg = msg
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text("Změnit", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (emailOtpVisible) {
                        WaterGlassInput(
                            value = emailOtpCode,
                            onValueChange = { emailOtpCode = it },
                            placeholder = "6místný kód z e-mailu",
                            isDark = isDark,
                            brandColor = brandColor,
                            keyboardType = KeyboardType.Number
                        )
                        Button(
                            onClick = {
                                TaxiApi.securityAction(mapOf("action" to "email_val", "code" to emailOtpCode)) { ok, msg ->
                                    statusMsg = msg
                                    if (ok) {
                                        emailOtpVisible = false
                                        onProfileReload()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Ověřit a změnit e-mail", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Ověřený Telefon", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        WaterGlassInput(
                            value = phoneInp,
                            onValueChange = { phoneInp = it },
                            placeholder = "+420...",
                            enabled = !phoneOtpVisible,
                            isDark = isDark,
                            brandColor = brandColor,
                            keyboardType = KeyboardType.Phone,
                            modifier = Modifier.weight(1f)
                        )
                        if (!phoneOtpVisible) {
                            Button(
                                onClick = {
                                    TaxiApi.securityAction(mapOf("action" to "phone_req", "phone" to phoneInp)) { ok, msg ->
                                        if (ok) {
                                            phoneOtpVisible = true
                                            statusMsg = "Na telefon byla odeslána SMS s 6místným kódem."
                                        } else statusMsg = msg
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text("Změnit", color = Color.Black, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (phoneOtpVisible) {
                        WaterGlassInput(
                            value = phoneOtpCode,
                            onValueChange = { phoneOtpCode = it },
                            placeholder = "6místný kód ze SMS",
                            isDark = isDark,
                            brandColor = brandColor,
                            keyboardType = KeyboardType.Number
                        )
                        Button(
                            onClick = {
                                TaxiApi.securityAction(mapOf("action" to "phone_val", "code" to phoneOtpCode)) { ok, msg ->
                                    statusMsg = msg
                                    if (ok) {
                                        phoneOtpVisible = false
                                        onProfileReload()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("Ověřit a změnit telefon", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Změna hesla", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                    WaterGlassInput(
                        value = oldPass,
                        onValueChange = { oldPass = it },
                        placeholder = "Současné heslo",
                        isDark = isDark,
                        brandColor = brandColor,
                        isPassword = true
                    )
                    WaterGlassInput(
                        value = newPass,
                        onValueChange = { newPass = it },
                        placeholder = "Nové heslo (min. 8 znaků)",
                        isDark = isDark,
                        brandColor = brandColor,
                        isPassword = true
                    )
                    Button(
                        onClick = {
                            TaxiApi.securityAction(mapOf("action" to "change_password", "old" to oldPass, "new" to newPass)) { ok, msg ->
                                statusMsg = msg
                                if (ok) {
                                    oldPass = ""
                                    newPass = ""
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("💾 Změnit heslo", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { billingSubTab = "citizen" },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (billingSubTab == "citizen") brandColor else Color.Transparent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("👤 Občan", color = if (billingSubTab == "citizen") Color.Black else textColor, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { billingSubTab = "company" },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = if (billingSubTab == "company") brandColor else Color.Transparent
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("🏢 Firma", color = if (billingSubTab == "company") Color.Black else textColor, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (billingSubTab == "citizen") {
                        Text("Fakturační jméno", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        WaterGlassInput(value = cFn, onValueChange = { cFn = it }, placeholder = "Jméno", isDark = isDark, brandColor = brandColor)
                        Text("Fakturační příjmení", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        WaterGlassInput(value = cLn, onValueChange = { cLn = it }, placeholder = "Příjmení", isDark = isDark, brandColor = brandColor)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Ulice", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                WaterGlassInput(value = cStreet, onValueChange = { cStreet = it }, placeholder = "Ulice", isDark = isDark, brandColor = brandColor)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Č.p.", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                WaterGlassInput(value = cNum, onValueChange = { cNum = it }, placeholder = "Č.p.", isDark = isDark, brandColor = brandColor)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Město", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                WaterGlassInput(value = cCity, onValueChange = { cCity = it }, placeholder = "Město", isDark = isDark, brandColor = brandColor)
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("PSČ", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                WaterGlassInput(value = cZip, onValueChange = { cZip = it }, placeholder = "PSČ", isDark = isDark, brandColor = brandColor)
                            }
                        }
                        Button(
                            onClick = {
                                TaxiApi.saveBillingCitizen(cFn, cLn, cStreet, cNum, cZip, cCity) { _, msg ->
                                    statusMsg = msg
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("💾 Uložit adresu občana", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    } else {
                        Text("IČO", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            WaterGlassInput(
                                value = fIco,
                                onValueChange = { fIco = it },
                                placeholder = "8 číslic",
                                isDark = isDark,
                                brandColor = brandColor,
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    aresMsg = "Dotazuji ARES…"
                                    TaxiApi.fetchAres(fIco) { res ->
                                        res.onSuccess { (name, dic, addr) ->
                                            fName = name
                                            fDic = dic
                                            fAddr = addr
                                            aresMsg = "ARES: Data úspěšně načtena."
                                        }.onFailure { err ->
                                            aresMsg = "Chyba ARES: ${err.message}"
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(50.dp)
                            ) {
                                Text("🔎 ARES", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        if (aresMsg != null) {
                            Text(aresMsg!!, color = brandColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("Název firmy", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        WaterGlassInput(value = fName, onValueChange = { fName = it }, placeholder = "Název firmy", isDark = isDark, brandColor = brandColor)
                        Text("DIČ", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        WaterGlassInput(value = fDic, onValueChange = { fDic = it }, placeholder = "CZ...", isDark = isDark, brandColor = brandColor)
                        Text("Fakturační adresa", color = textColor.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                        WaterGlassInput(value = fAddr, onValueChange = { fAddr = it }, placeholder = "Ulice, Město, PSČ", isDark = isDark, brandColor = brandColor)
                        Button(
                            onClick = {
                                TaxiApi.saveBillingCompany(fIco, fName, fDic, fAddr) { _, msg ->
                                    statusMsg = msg
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = brandColor),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                        ) {
                            Text("💾 Uložit firemní údaje", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { editMode = false },
                border = BorderStroke(1.dp, borderColor),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("← Zpět na přehled účtu", color = textColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsView(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onOpenLegalModal: (String, String) -> Unit,
    brandColor: Color,
    textColor: Color,
    cardBg: Color,
    borderColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("VZHLED APLIKACE", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "🤖 Auto", "light" to "☀️ Den", "dark" to "🌙 Noc").forEach { (mode, label) ->
                    val selected = themeMode == mode
                    Button(
                        onClick = { onThemeChange(mode) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (selected) brandColor else Color.Transparent
                        ),
                        border = BorderStroke(1.dp, if (selected) brandColor else borderColor),
                        shape = RoundedCornerShape(10.dp),
                        elevation = null,
                        modifier = Modifier.weight(1f).height(44.dp)
                    ) {
                        Text(
                            label,
                            color = if (selected) Color.Black else textColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cardBg, RoundedCornerShape(16.dp))
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(16.dp)
        ) {
            Text("PRÁVNÍ INFORMACE", color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))

            val legalItems = listOf(
                Triple("🚕 Ceník a provozní doba", "/taxi/legal/cenik-taxi.html", textColor),
                Triple("📄 Obchodní podmínky", "/taxi/legal/obchodni-podminky.html", textColor),
                Triple("🔒 Ochrana osobních údajů", "/taxi/legal/ochrana-osobnich-udaju.html", textColor),
                Triple("🗑️ Žádost o smazání dat", "/taxi/legal/smazani-dat.html", Color(0xFFE74C3C)),
                Triple("ℹ️ O nás", "/taxi/legal/o-nas.html", textColor)
            )

            legalItems.forEachIndexed { idx, (title, path, col) ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLegalModal("${TaxiApi.BASE_URL}$path?pwa=1", title) }
                        .padding(vertical = 12.dp)
                ) {
                    Text(title, color = col, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                if (idx < legalItems.lastIndex) {
                    Divider(color = borderColor)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "© 2026 Created by Pavel Mareš",
                color = textColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
