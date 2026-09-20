package dev.creditwatch.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import dev.creditwatch.domain.CurrencyCode
import dev.creditwatch.engine.RunwayResult
import dev.creditwatch.persistence.SqliteMonitoringHistory
import dev.creditwatch.vast.VastProvider
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency

private val background = Color(0xFF0B1220)
private val panel = Color(0xFF152233)
private val raised = Color(0xFF21344A)
private val muted = Color(0xFF9AABC0)
private val accent = Color(0xFF78E3D0)
private val amber = Color(0xFFFFCA82)
private val white = Color(0xFFF3F8FB)
private val searchShortcut = if (System.getProperty("os.name").startsWith("Mac")) "⌘K" else "Ctrl+K"
private val searchableProviders = listOf(ProviderSearchItem("vast", "Vast.ai", "cloud account gpu credits"))

fun main() = application {
    val http = remember { VastProvider.newHttpClient() }
    val controller = remember {
        MonitoringController(
            if (System.getProperty("os.name").startsWith("Mac")) MacKeychainSecretStore() else null,
            SqliteMonitoringHistory(historyPath()),
            { key -> VastProvider(http, key) },
        )
    }
    val state by controller.state.collectAsState()
    val trayState = rememberTrayState()
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf(isTraySupported) }
    var dashboard by remember { mutableStateOf(!isTraySupported) }
    var searching by remember { mutableStateOf(false) }
    val openDashboard = {
        quick = false
        searching = false
        dashboard = true
    }
    val quit = {
        if (!closing) {
            closing = true
            scope.launch { try { controller.stop() } finally { http.close(); exitApplication() } }
        }
    }
    LaunchedEffect(controller) { controller.start() }
    LaunchedEffect(controller, trayState) {
        controller.alertEvents.collect { event ->
            if (isTraySupported) {
                trayState.sendNotification(Notification(
                    "CreditWatch — low runway",
                    if (event.runway == RunwayResult.BalanceDepleted) "Balance depleted."
                    else "Safe runway is below ${event.thresholdHours}h (${formatRunway(event.runway)}). Based on known costs.",
                    if (event.thresholdHours <= 1) Notification.Type.Error else Notification.Type.Warning,
                ))
            }
        }
    }

    if (isTraySupported) {
        Tray(
            state = trayState,
            icon = CreditWatchIcon,
            tooltip = "CreditWatch",
            onAction = { quick = !quick; searching = false },
            menu = {
                Item("Show quick view", onClick = { quick = true; dashboard = false })
                Item("Open dashboard", onClick = openDashboard)
                Item("Search providers and actions", onClick = { searching = true })
                Item("Refresh now", enabled = state.connected && !state.busy, onClick = controller::refreshNow)
                Separator()
                Item("Quit CreditWatch", onClick = quit)
            },
        )
    }

    Window(
        onCloseRequest = { quick = false }, visible = quick && !closing,
        title = "CreditWatch quick view", icon = CreditWatchIcon,
        undecorated = true, resizable = false, alwaysOnTop = true,
        state = rememberWindowState(width = 360.dp, height = 460.dp),
    ) {
        DisposableEffect(window) {
            val config = window.graphicsConfiguration
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            window.setLocation(bounds.x + bounds.width - window.width - 16, bounds.y + insets.top + 8)
            onDispose { }
        }
        CreditWatchTheme {
            QuickView(state, openDashboard, { searching = true }, controller::refreshNow, { quick = false })
        }
    }

    Window(
        onCloseRequest = { if (isTraySupported) dashboard = false else quit() },
        visible = dashboard && !closing, title = "CreditWatch", icon = CreditWatchIcon,
        state = rememberWindowState(width = 790.dp, height = 620.dp),
    ) {
        CreditWatchTheme {
            Dashboard(state, controller, closing, { searching = true }, { dashboard = false; quick = true })
        }
    }

    if (searching && !closing) {
        Window(
            onCloseRequest = { searching = false }, title = "Search CreditWatch",
            icon = CreditWatchIcon, undecorated = true, alwaysOnTop = true, resizable = false,
            state = rememberWindowState(width = 520.dp, height = 350.dp),
        ) {
            CreditWatchTheme {
                SearchView(searchCatalog(searchableProviders, if (state.connected) setOf("vast") else emptySet()),
                    { searching = false }) { action ->
                    when (action) {
                        SearchAction.OpenDashboard -> openDashboard()
                        is SearchAction.OpenProvider -> openDashboard()
                        SearchAction.Refresh -> { controller.refreshNow(); searching = false }
                    }
                }
            }
        }
    }
}

private object CreditWatchIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val width = size.minDimension * .095f
        drawCircle(accent, radius = size.minDimension * .40f, style = Stroke(width))
        val points = listOf(
            Offset(size.width * .18f, size.height * .52f), Offset(size.width * .38f, size.height * .52f),
            Offset(size.width * .48f, size.height * .28f), Offset(size.width * .59f, size.height * .70f),
            Offset(size.width * .69f, size.height * .48f), Offset(size.width * .82f, size.height * .48f),
        )
        points.zipWithNext().forEach { (a, b) -> drawLine(accent, a, b, strokeWidth = width) }
    }
}

@Composable
private fun CreditWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = darkColors(primary = accent, secondary = accent, background = background,
            surface = panel, onPrimary = background, onSurface = white, onBackground = white),
        content = content,
    )
}

@Composable
private fun QuickView(
    state: MonitoringState, onDashboard: () -> Unit, onSearch: () -> Unit,
    onRefresh: () -> Unit, onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(background)
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.K && (it.isMetaPressed || it.isCtrlPressed)) {
                onSearch(); true
            } else false
        }.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(10.dp))
            Text("CreditWatch", color = white, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close", color = muted, fontSize = 12.sp) }
        }
        ProviderStatus(state)
        Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(18.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("SAFE RUNWAY")
            Text(state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                color = if (state.stale) amber else white, fontSize = 35.sp, fontWeight = FontWeight.Medium)
            Text(runwayDetail(state), color = muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("BALANCE", balanceText(state), "Provider reported", Modifier.weight(1f))
            Metric("KNOWN BURN", burnText(state), "Per hour", Modifier.weight(1f))
        }
        Text(statusLine(state), color = if (state.stale) amber else muted, fontSize = 12.sp,
            maxLines = 2, lineHeight = 16.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onDashboard) {
                Text(if (state.connected) "Dashboard" else "Connect Vast.ai")
            }
            OutlinedButton(onClick = onSearch) { Text("Search  $searchShortcut", color = white) }
            TextButton(onClick = onRefresh, enabled = state.connected && !state.busy) {
                Text("↻", fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun Dashboard(
    state: MonitoringState, controller: MonitoringController, closing: Boolean,
    onSearch: () -> Unit, onQuickView: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(background)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.K && (it.isMetaPressed || it.isCtrlPressed)) {
                    onSearch(); true
                } else false
            }
            .verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(12.dp))
            Column {
                Text("CreditWatch", color = white, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Cloud credit monitor", color = muted, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSearch) { Text("⌕  Search providers and actions   $searchShortcut", color = white) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProviderStatus(state)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onQuickView) { Text("Quick view ↗", color = accent) }
        }
        Label("YOUR CREDIT AT A GLANCE")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("BALANCE", balanceText(state), "Provider reported", Modifier.weight(1f), large = true)
            Metric("KNOWN BURN", burnText(state), burnDetail(state), Modifier.weight(1f), large = true)
            Metric("SAFE RUNWAY", state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                runwayDetail(state), Modifier.weight(1f), large = true)
        }
        Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("MONITORING")
            Text(if (closing) "Closing…" else state.message, color = white, fontSize = 14.sp)
            Text(statusLine(state), color = if (state.stale) amber else muted, fontSize = 12.sp)
            state.summary?.let { summary ->
                Text("Raw runway: ${formatRunway(summary.rawRunway)}  •  Known costs only", color = muted, fontSize = 12.sp)
                Text(summary.average?.let {
                    "Last-hour average: ${formatMoney(it.rate.amountPerHour, summary.sample.balance.currency, 4)}/hr, " +
                        "${it.coverage.toMinutes()} minutes observed"
                } ?: "More history will improve the safe-runway estimate.", color = muted, fontSize = 12.sp)
            }
            Text("Safe runway uses the higher of current burn and the one-hour average, plus a 10% buffer.",
                color = muted, fontSize = 12.sp)
        }
        if (!state.connected) {
            Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(16.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Connect Vast.ai", color = white, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("Use a key limited to account and instance reads. On macOS, it is stored in Keychain.",
                    color = muted, fontSize = 12.sp)
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Vast.ai API key") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !state.busy && !closing && controller.secureStorageAvailable,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { controller.connect(key.trim().toCharArray()); key = "" },
                    enabled = !state.busy && !closing && controller.secureStorageAvailable && key.isNotBlank()) {
                    Text("Connect account")
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = controller::refreshNow, enabled = !state.busy && !closing &&
                    state.status !in setOf(SyncStatus.AUTH_ERROR, SyncStatus.RATE_LIMITED)) {
                    Text(if (state.busy) "Refreshing…" else "Refresh now")
                }
                TextButton(onClick = { controller.removeAccount() }, enabled = !state.busy && !closing) {
                    Text("Remove account", color = muted)
                }
            }
        }
    }
}

@Composable
private fun SearchView(entries: List<SearchEntry>, onDismiss: () -> Unit, onSelect: (SearchAction) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val results = remember(entries, query) { searchEntries(entries, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(background)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Escape -> { onDismiss(); true }
                    Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(results.lastIndex); true }
                    Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                    Key.Enter -> { results.getOrNull(selected)?.let { entry -> onSelect(entry.action) }; true }
                    else -> false
                }
            }.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(value = query, onValueChange = { query = it; selected = 0 },
            placeholder = { Text("Search providers or actions…") },
            singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        Label("PROVIDERS & ACTIONS")
        if (results.isEmpty()) Text("No results", color = muted, modifier = Modifier.padding(16.dp))
        results.forEachIndexed { index, entry ->
            Row(Modifier.fillMaxWidth().background(if (index == selected) raised else panel, RoundedCornerShape(10.dp))
                .clickable { onSelect(entry.action) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(entry.title, color = white, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(entry.description, color = muted, fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(entry.category, color = accent, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("↑ ↓ navigate    ↵ select    esc close", color = muted, fontSize = 11.sp)
    }
}

@Composable private fun BrandMark() {
    Box(Modifier.size(30.dp).background(accent, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Text("◉", color = background, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ProviderStatus(state: MonitoringState) {
    val live = state.connected && !state.stale && state.status in setOf(SyncStatus.HEALTHY, SyncStatus.DEGRADED)
    Row(Modifier.background(panel, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (live) accent else amber, RoundedCornerShape(50)))
        Spacer(Modifier.width(8.dp))
        Text(if (!state.connected) "Vast.ai  •  Not connected"
            else "Vast.ai  •  ${if (state.stale) "Stale" else if (state.busy) "Syncing" else "Monitoring"}",
            color = white, fontSize = 12.sp)
    }
}

@Composable private fun Label(text: String) {
    Text(text, color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

@Composable private fun Metric(label: String, value: String, detail: String,
    modifier: Modifier = Modifier, large: Boolean = false) {
    Column(modifier.then(if (large) Modifier.height(155.dp) else Modifier)
        .background(panel, RoundedCornerShape(15.dp)).padding(large.let { if (it) 17.dp else 15.dp }),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        Text(value, color = white, fontSize = if (large) 25.sp else 19.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
        Text(detail, color = muted, fontSize = if (large) 12.sp else 11.sp,
            lineHeight = 16.sp, maxLines = 3)
    }
}

private fun balanceText(state: MonitoringState) =
    state.summary?.sample?.balance?.let { formatMoney(it.amount, it.currency, 2) } ?: "—"

private fun burnText(state: MonitoringState) =
    state.summary?.sample?.let { "~${formatMoney(it.knownRate.amountPerHour, it.balance.currency, 4)}/hr" } ?: "—"

private fun burnDetail(state: MonitoringState) = state.summary?.sample?.let {
    if (it.unknownCosts.isEmpty()) "All listed costs included"
    else "Excludes ${it.unknownCosts.joinToString { cost -> cost.name.lowercase() }}"
} ?: "Waiting for provider data"

private fun runwayDetail(state: MonitoringState): String = when (state.summary?.safeRunway) {
    RunwayResult.Unavailable -> "Waiting for complete, recent compute and storage prices"
    RunwayResult.NoBurn -> "No active known burn detected"
    RunwayResult.BalanceDepleted -> "Balance depleted"
    is RunwayResult.Available -> if (state.stale) "Saved estimate • refresh for current data"
        else "Planning estimate based on known costs"
    null -> "Connect a provider to start monitoring"
}

private fun statusLine(state: MonitoringState): String =
    listOfNotNull(
        state.activeRunwayThresholdHours?.let { "Runway warning: below ${it}h" },
        if (state.stale && state.summary != null) "Stale data" else null,
        state.summary?.sample?.balanceObservedAt?.let { "Balance updated ${formatTime(it)}" },
        state.nextSyncAt?.let { "Next sync ${formatTime(it)}" }).joinToString("  •  ").ifBlank { state.message }

private fun formatMoney(amount: BigDecimal, code: CurrencyCode, decimals: Int) =
    NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(code.value)
        minimumFractionDigits = 2
        maximumFractionDigits = decimals
        roundingMode = RoundingMode.HALF_UP
    }.format(amount)

private fun formatRunway(result: RunwayResult): String = when (result) {
    is RunwayResult.Available -> "~${result.duration.toHours()}h ${result.duration.toMinutesPart()}m"
    RunwayResult.NoBurn -> "—"
    RunwayResult.BalanceDepleted -> "0m"
    RunwayResult.Unavailable -> "Unavailable"
}

private fun formatTime(instant: Instant) =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(instant)

private fun historyPath(): Path {
    val home = System.getProperty("user.home")
    val root = when {
        System.getProperty("os.name").startsWith("Mac") -> Path.of(home, "Library", "Application Support", "CreditWatch")
        System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home, "CreditWatch")
        else -> Path.of(System.getenv("XDG_DATA_HOME") ?: "${home}/.local/share", "creditwatch")
    }
    return root.resolve("creditwatch.db")
}
