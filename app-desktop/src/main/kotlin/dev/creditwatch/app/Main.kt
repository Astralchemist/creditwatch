package dev.creditwatch.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import androidx.compose.ui.window.PopupProperties
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

private val background @Composable get() = palette.background
private val panel @Composable get() = palette.panel
private val raised @Composable get() = palette.raised
private val muted @Composable get() = palette.muted
private val accent @Composable get() = palette.accent
private val healthy @Composable get() = palette.healthy
private val amber @Composable get() = palette.warning
private val white @Composable get() = palette.text
private val searchShortcut = if (System.getProperty("os.name").startsWith("Mac")) "⌘K" else "Ctrl+K"
private val searchableProviders = listOf(ProviderSearchItem("vast", "Vast.ai", "cloud account gpu credits"))
private enum class DashboardPage { OVERVIEW, PROVIDER }

fun main() = application {
    val http = remember { VastProvider.newHttpClient() }
    val controller = remember {
        MonitoringController(
            when {
                System.getProperty("os.name").startsWith("Mac") -> MacKeychainSecretStore()
                System.getProperty("os.name").startsWith("Windows") -> WindowsCredentialSecretStore()
                System.getProperty("os.name").startsWith("Linux") -> LinuxSecretServiceStore.create()
                else -> null
            },
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
    var searchRequest by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf(DashboardPage.OVERVIEW) }
    var themeMode by remember { mutableStateOf(AppearanceSettings.load()) }
    val setTheme: (ThemeMode) -> Unit = { mode ->
        themeMode = mode
        AppearanceSettings.save(mode)
    }
    val openDashboard = {
        quick = false
        page = DashboardPage.OVERVIEW
        dashboard = true
    }
    val openProvider = {
        quick = false
        page = DashboardPage.PROVIDER
        dashboard = true
    }
    val openSearch = {
        openDashboard()
        searchRequest++
        Unit
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
            onAction = { quick = !quick },
            menu = {
                Item("Show quick view", onClick = { quick = true; dashboard = false })
                Item("Open dashboard", onClick = openDashboard)
                Item("Search providers and actions", onClick = openSearch)
                Item("Refresh now", enabled = state.connected && !state.busy, onClick = controller::refreshNow)
                Separator()
                Item("Theme: ${themeMode.label}", onClick = {
                    setTheme(ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size])
                })
                Separator()
                Item("Quit CreditWatch", onClick = quit)
            },
        )
    }

    Window(
        onCloseRequest = { quick = false }, visible = quick && !closing,
        title = "CreditWatch quick view", icon = CreditWatchIcon,
        undecorated = true, transparent = true, resizable = false, alwaysOnTop = true,
        state = rememberWindowState(width = 360.dp, height = 600.dp),
    ) {
        DisposableEffect(window) {
            val config = window.graphicsConfiguration
            val bounds = config.bounds
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
            window.setLocation(bounds.x + bounds.width - window.width - 16, bounds.y + insets.top + 8)
            onDispose { }
        }
        CreditWatchTheme(themeMode) {
            QuickView(state, openDashboard, openProvider, openSearch, controller::refreshNow, { quick = false },
                themeMode, setTheme)
        }
    }

    Window(
        onCloseRequest = { if (isTraySupported) dashboard = false else quit() },
        visible = dashboard && !closing, title = "CreditWatch", icon = CreditWatchIcon,
        state = rememberWindowState(width = 790.dp, height = 620.dp),
    ) {
        CreditWatchTheme(themeMode) {
            Dashboard(state, controller, closing, page, openDashboard, openProvider,
                { dashboard = false; quick = true }, searchRequest, openSearch, { action ->
                    when (action) {
                        SearchAction.OpenDashboard -> openDashboard()
                        is SearchAction.OpenProvider -> openProvider()
                        SearchAction.Refresh -> controller.refreshNow()
                    }
                }, themeMode, setTheme)
        }
    }
}

private object CreditWatchIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val width = size.minDimension * .095f
        drawCircle(darkPalette.accent, radius = size.minDimension * .40f, style = Stroke(width))
        val points = listOf(
            Offset(size.width * .18f, size.height * .52f), Offset(size.width * .38f, size.height * .52f),
            Offset(size.width * .48f, size.height * .28f), Offset(size.width * .59f, size.height * .70f),
            Offset(size.width * .69f, size.height * .48f), Offset(size.width * .82f, size.height * .48f),
        )
        points.zipWithNext().forEach { (a, b) -> drawLine(darkPalette.accent, a, b, strokeWidth = width) }
    }
}

@Composable
private fun CreditWatchTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val colors = if (dark) darkPalette else lightPalette
    CompositionLocalProvider(LocalCreditWatchPalette provides colors) {
        MaterialTheme(
            colors = if (dark) darkColors(primary = colors.accent, secondary = colors.accent,
                background = colors.background, surface = colors.panel, onPrimary = colors.background,
                onSurface = colors.text, onBackground = colors.text)
            else lightColors(primary = colors.accent, secondary = colors.accent,
                background = colors.background, surface = colors.panel, onPrimary = colors.panel,
                onSurface = colors.text, onBackground = colors.text),
            shapes = Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
            ),
            content = content,
        )
    }
}

@Composable
private fun QuickView(
    state: MonitoringState, onDashboard: () -> Unit, onConnect: () -> Unit, onSearch: () -> Unit,
    onRefresh: () -> Unit, onClose: () -> Unit,
    themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit,
) {
    val isDark = themeMode == ThemeMode.DARK || (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    Column(Modifier.fillMaxSize().background(background, RoundedCornerShape(24.dp))
        .onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown && it.key == Key.K && (it.isMetaPressed || it.isCtrlPressed)) {
                onSearch(); true
            } else false
        }.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Spacer(Modifier.width(10.dp))
            Text("CreditWatch", color = white, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onThemeChange(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK) }) {
                Text(if (isDark) "☀ Light" else "☾ Dark", color = accent, fontSize = 12.sp)
            }
            TextButton(onClick = onClose) { Text("×", color = muted, fontSize = 18.sp) }
        }
        ProviderStatus(state)
        Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(22.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("SAFE RUNWAY")
            Text(state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                color = if (state.stale) amber else white, fontSize = 35.sp, fontWeight = FontWeight.Medium)
            if (state.connected) {
                Text(runwayDetail(state), color = muted, fontSize = 12.sp, lineHeight = 17.sp)
            } else {
                Text("Connect your cloud provider to begin ↗",
                    color = accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onConnect))
            }
            TrendGraph(state.trends.runway, Modifier.fillMaxWidth().height(28.dp))
            TrendCaption(state.trends.runway)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("BALANCE", balanceText(state), "Provider reported", Modifier.weight(1f),
                trend = state.trends.balance)
            Metric("KNOWN BURN", burnText(state), "Per hour", Modifier.weight(1f),
                trend = state.trends.burn)
        }
        Text(statusLine(state), color = if (state.stale) amber else muted, fontSize = 12.sp,
            maxLines = 2, lineHeight = 16.sp)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = if (state.connected) onDashboard else onConnect) {
                Text(if (state.connected) "Dashboard" else "Connect provider")
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
    page: DashboardPage, onOverview: () -> Unit, onProvider: () -> Unit,
    onQuickView: () -> Unit, searchRequest: Int, onSearchRequest: () -> Unit,
    onSearchAction: (SearchAction) -> Unit,
    themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit,
) {
    val entries = remember(state.connected) {
        searchCatalog(searchableProviders, if (state.connected) setOf("vast") else emptySet())
    }
    Column(
        Modifier.fillMaxSize().background(background)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.K && (it.isMetaPressed || it.isCtrlPressed)) {
                    onSearchRequest(); true
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
            ProviderSearchBar(entries, searchRequest, onSearchAction)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProviderStatus(state)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = if (page == DashboardPage.PROVIDER) onOverview else onProvider) {
                Text(if (page == DashboardPage.PROVIDER) "← Overview" else "Provider", color = accent)
            }
            Spacer(Modifier.width(8.dp))
            ThemeToggle(themeMode, onThemeChange)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onQuickView) { Text("Quick view ↗", color = accent) }
        }
        if (page == DashboardPage.PROVIDER) {
            ConnectionView(state, controller, closing, onOverview)
        } else {
            Label("YOUR CREDIT AT A GLANCE")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("BALANCE", balanceText(state), "Provider reported", Modifier.weight(1f),
                    large = true, trend = state.trends.balance)
                Metric("KNOWN BURN", burnText(state), burnDetail(state), Modifier.weight(1f),
                    large = true, trend = state.trends.burn)
                Metric("SAFE RUNWAY", state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                    runwayDetail(state), Modifier.weight(1f), large = true, trend = state.trends.runway)
            }
            Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(22.dp)).padding(18.dp),
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
                Text("Connect your cloud provider to begin ↗", color = accent, fontSize = 14.sp,
                    textDecoration = TextDecoration.Underline, modifier = Modifier.clickable(onClick = onProvider))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = controller::refreshNow, enabled = !state.busy && !closing &&
                        state.status !in setOf(SyncStatus.AUTH_ERROR, SyncStatus.RATE_LIMITED)) {
                        Text(if (state.busy) "Refreshing…" else "Refresh now")
                    }
                    TextButton(onClick = onProvider) { Text("Manage provider", color = muted) }
                }
            }
        }
    }
}

@Composable
private fun ThemeToggle(mode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    TextButton(onClick = { onChange(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
        modifier = Modifier.size(42.dp).semantics {
            contentDescription = if (dark) "Switch to light theme" else "Switch to dark theme"
        }) {
        Text(if (dark) "☾" else "☀", color = accent, fontSize = 20.sp)
    }
}

@Composable
private fun ConnectionView(
    state: MonitoringState, controller: MonitoringController, closing: Boolean, onOverview: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(22.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Connect your cloud provider", color = white, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Text("Vast.ai is the first supported provider. CreditWatch reads your account balance and instances.",
            color = muted, fontSize = 13.sp)
        if (state.connected) {
            ProviderStatus(state)
            Text("Your Vast.ai account is connected.", color = white, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOverview) { Text("View dashboard") }
                TextButton(onClick = { controller.removeAccount() }, enabled = !state.busy && !closing) {
                    Text("Remove account", color = muted)
                }
            }
        } else {
            Text("Create a key with account and instance read permissions. The key is saved in your operating system's secure credential store.",
                color = muted, fontSize = 12.sp)
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Vast.ai API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = !state.busy && !closing && controller.secureStorageAvailable,
                modifier = Modifier.fillMaxWidth())
            Text("Where to find your Vast.ai API key ↗", color = accent, fontSize = 13.sp,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    linkFailed = runCatching { uriHandler.openUri("https://docs.vast.ai/guides/reference/keys") }.isFailure
                })
            if (linkFailed) Text("Open docs.vast.ai/guides/reference/keys in your browser.",
                color = amber, fontSize = 12.sp)
            Text(state.message, color = if (state.status == SyncStatus.AUTH_ERROR) amber else muted, fontSize = 12.sp)
            Button(onClick = { controller.connect(key.trim().toCharArray()); key = "" },
                enabled = !state.busy && !closing && controller.secureStorageAvailable && key.isNotBlank()) {
                Text("Connect account")
            }
        }
    }
}

@Composable
private fun ProviderSearchBar(entries: List<SearchEntry>, requestId: Int, onSelect: (SearchAction) -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val results = remember(entries, query) { searchEntries(entries, query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestId) {
        if (requestId > 0) { focus.requestFocus(); expanded = true }
    }
    Box {
        OutlinedTextField(value = query, onValueChange = { query = it; selected = 0; expanded = true },
            placeholder = { Text("Search providers or actions…") },
            trailingIcon = { TextButton(onClick = { expanded = true }) { Text(searchShortcut, fontSize = 11.sp) } },
            singleLine = true,
            modifier = Modifier.width(330.dp).focusRequester(focus).onFocusChanged {
                if (it.isFocused) expanded = true
            }.onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.Escape -> { expanded = false; true }
                    Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(results.lastIndex); true }
                    Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
                    Key.Enter -> {
                        results.getOrNull(selected)?.let { entry -> onSelect(entry.action) }
                        query = ""; expanded = false; true
                    }
                    else -> false
                }
            })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.width(330.dp), properties = PopupProperties(focusable = false)) {
            if (results.isEmpty()) {
                Text("No results", color = muted, modifier = Modifier.padding(14.dp))
            }
            results.forEachIndexed { index, entry ->
                DropdownMenuItem(onClick = { onSelect(entry.action); query = ""; expanded = false },
                    modifier = Modifier.background(if (index == selected) raised else panel)) {
                    Column {
                        Text(entry.title, color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(entry.description, color = muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun BrandMark() {
    Box(Modifier.size(30.dp).background(accent, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
        Text("◉", color = background, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun ProviderStatus(state: MonitoringState) {
    val live = state.connected && !state.stale && state.status in setOf(SyncStatus.HEALTHY, SyncStatus.DEGRADED)
    Row(Modifier.background(panel, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(if (live) healthy else amber, RoundedCornerShape(50)))
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
    modifier: Modifier = Modifier, large: Boolean = false, trend: CardTrend? = null) {
    Column(modifier.then(if (large) Modifier.height(205.dp) else if (trend != null) Modifier.height(150.dp) else Modifier)
        .background(panel, RoundedCornerShape(22.dp)).padding(large.let { if (it) 17.dp else 15.dp }),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(label)
        Text(value, color = white, fontSize = if (large) 25.sp else 19.sp,
            fontWeight = FontWeight.Medium, maxLines = 1)
        Text(detail, color = muted, fontSize = if (large) 12.sp else 11.sp,
            lineHeight = 16.sp, maxLines = if (large) 2 else 3)
        if (trend != null) {
            Spacer(Modifier.weight(1f))
            TrendGraph(trend, Modifier.fillMaxWidth().height(if (large) 36.dp else 22.dp))
            TrendCaption(trend)
        }
    }
}

@Composable
private fun TrendCaption(trend: CardTrend) {
    val caption = trend.changePercent?.let { percent ->
        val sign = if (percent.signum() > 0) "+" else ""
        "$sign${percent.toPlainString()}% over ${trend.minutes}m"
    } ?: if (trend.points.size >= 2) "Change unavailable" else "Collecting trend"
    Text(caption, color = muted, fontSize = 10.sp, maxLines = 1)
}

@Composable
private fun TrendGraph(trend: CardTrend, modifier: Modifier = Modifier) {
    val lineColor = accent
    val points = remember(trend) { trend.points.mapNotNull { point ->
        point.value.toFloat().takeIf(Float::isFinite)?.let { point.time to it }
    } }
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            Text("—", color = muted, fontSize = 14.sp)
        }
        return
    }
    Canvas(modifier) {
        val minTime = points.first().first.toEpochMilli()
        val span = (points.last().first.toEpochMilli() - minTime).coerceAtLeast(1).toFloat()
        val low = points.minOf { it.second }
        val high = points.maxOf { it.second }
        val spread = (high - low).takeIf { it > 0f } ?: 1f
        val path = GraphicsPath()
        var previousTime: Instant? = null
        points.forEachIndexed { index, (time, value) ->
            val x = (time.toEpochMilli() - minTime) / span * size.width
            val y = if (high == low) size.height / 2f else
                size.height - 4.dp.toPx() - ((value - low) / spread * (size.height - 8.dp.toPx()))
            if (index == 0 || previousTime?.let { java.time.Duration.between(it, time).seconds > 180 } == true)
                path.moveTo(x, y) else path.lineTo(x, y)
            previousTime = time
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        val last = points.last()
        val lastX = (last.first.toEpochMilli() - minTime) / span * size.width
        val lastY = if (high == low) size.height / 2f else
            size.height - 4.dp.toPx() - ((last.second - low) / spread * (size.height - 8.dp.toPx()))
        drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
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
