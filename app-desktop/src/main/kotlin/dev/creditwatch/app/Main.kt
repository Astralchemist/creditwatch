package dev.creditwatch.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path as GraphicsPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import dev.creditwatch.domain.CurrencyCode
import dev.creditwatch.domain.CloudInstance
import dev.creditwatch.domain.InstanceState
import dev.creditwatch.engine.RunwayResult
import dev.creditwatch.persistence.SqliteMonitoringHistory
import dev.creditwatch.vast.VastProvider
import kotlinx.coroutines.launch
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
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

/** The popover is the whole product; settings slides in over it rather than opening a window. */
private enum class Pane { RUNWAY, SETTINGS }

/** Mirrors [MonitoringController.refreshNow]: during a backoff the request is dropped, so don't offer it. */
private val MonitoringState.canRefresh: Boolean get() = connected && !busy && !backingOff

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
    // Without a tray the popover is the application window, so it starts open and closing it quits.
    var open by remember { mutableStateOf(!isTraySupported) }
    var pane by remember { mutableStateOf(Pane.RUNWAY) }
    var trayAnchor by remember { mutableStateOf<Point?>(null) }
    var themeMode by remember { mutableStateOf(AppearanceSettings.load()) }
    val setTheme: (ThemeMode) -> Unit = { mode ->
        themeMode = mode
        AppearanceSettings.save(mode)
    }
    val showFromTray = {
        trayAnchor = MouseInfo.getPointerInfo()?.location
        pane = Pane.RUNWAY
        open = true
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
            onAction = { if (open) open = false else showFromTray() },
            menu = {
                Item("Show CreditWatch", onClick = showFromTray)
                Item("Refresh now", enabled = state.canRefresh, onClick = controller::refreshNow)
                Item("Settings", onClick = { showFromTray(); pane = Pane.SETTINGS })
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
        onCloseRequest = { if (isTraySupported) open = false else quit() },
        visible = open && !closing,
        title = "CreditWatch", icon = CreditWatchIcon,
        undecorated = isTraySupported, transparent = isTraySupported,
        resizable = false, alwaysOnTop = isTraySupported,
        state = rememberWindowState(width = 360.dp, height = 470.dp),
    ) {
        LaunchedEffect(open, trayAnchor, window) {
            if (open && isTraySupported) {
                positionPopover(window, trayAnchor)
                window.toFront()
                window.requestFocus()
            }
        }
        CreditWatchTheme(themeMode) {
            Popover(state, controller, closing, pane, { pane = it }, { open = false },
                themeMode, setTheme, quit)
        }
    }
}

private fun positionPopover(window: java.awt.Window, anchor: Point?) {
    val config = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration }
        .firstOrNull { anchor != null && it.bounds.contains(anchor) }
        ?: window.graphicsConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val left = bounds.x + insets.left + 8
    val right = (bounds.x + bounds.width - insets.right - window.width - 8).coerceAtLeast(left)
    val top = bounds.y + insets.top + 8
    val bottom = (bounds.y + bounds.height - insets.bottom - window.height - 8).coerceAtLeast(top)
    val x = (anchor?.x?.minus(window.width / 2) ?: right).coerceIn(left, right)
    val y = when {
        anchor == null || anchor.y <= top + 48 -> top
        else -> (anchor.y + 12).coerceIn(top, bottom)
    }
    window.setLocation(x, y)
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
private fun Popover(
    state: MonitoringState, controller: MonitoringController, closing: Boolean,
    pane: Pane, onPane: (Pane) -> Unit, onClose: () -> Unit,
    themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit, onQuit: () -> Unit,
) {
    // Floating over the menu bar it needs its own rounded edge; as a plain window it must not.
    val shape: Shape = if (isTraySupported) RoundedCornerShape(16.dp) else RectangleShape
    Column(Modifier.fillMaxSize().background(background, shape)
        .then(if (isTraySupported) Modifier.border(1.dp, muted.copy(alpha = .22f), shape) else Modifier)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        when {
            pane == Pane.SETTINGS ->
                SettingsPane(state, controller, closing, { onPane(Pane.RUNWAY) }, themeMode, onThemeChange, onQuit)
            !state.connected && !state.busy ->
                ConnectPane(state, controller, closing, { onPane(Pane.SETTINGS) }, onClose)
            else ->
                RunwayPane(state, controller, closing, { onPane(Pane.SETTINGS) }, onClose)
        }
    }
}

/** Status dot, provider, state, and the two chrome actions. One line, no branding. */
@Composable
private fun PaneHeader(state: MonitoringState, onSettings: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(statusTone(state), CircleShape))
        Spacer(Modifier.width(9.dp))
        Text("Vast.ai", color = white, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(statusLabel(state), color = statusTone(state), fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.weight(1f))
        IconAction("⋯", "Settings", onSettings)
        IconAction("×", "Close", onClose)
    }
}

@Composable
private fun ColumnScope.RunwayPane(
    state: MonitoringState, controller: MonitoringController, closing: Boolean,
    onSettings: () -> Unit, onClose: () -> Unit,
) {
    val running = state.instances.filter { it.state == InstanceState.RUNNING }
    PaneHeader(state, onSettings, onClose)

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {

        Spacer(Modifier.height(0.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("SAFE RUNWAY")
                Spacer(Modifier.weight(1f))
                state.activeRunwayThresholdHours?.let {
                    Text("below ${it}h", color = amber, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
            Text(state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                color = if (state.stale && state.summary != null) amber else white,
                fontSize = 50.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(runwaySubline(state), color = muted, fontSize = 11.sp, lineHeight = 15.sp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCell("BALANCE", balanceText(state), state.trends.balance, healthy, Modifier.weight(1f))
            MetricCell("KNOWN BURN", burnCompact(state), state.trends.burn, accent, Modifier.weight(1f))
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("INSTANCES")
                Spacer(Modifier.weight(1f))
                Text(if (state.connected && state.instances.isNotEmpty()) "${running.size} running" else "—",
                    color = muted, fontSize = 11.sp)
            }
            if (running.isEmpty()) {
                Text(if (state.connected && state.summary != null && !state.stale) "No running instances"
                    else "Waiting for instance data",
                    color = muted, fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp))
                        .padding(horizontal = 11.dp, vertical = 10.dp))
            } else running.forEach { InstanceRow(it) }
        }
    }

    HairLine()
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(footerText(state, closing), color = if (state.stale) amber else muted,
            fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, modifier = Modifier.weight(1f))
        IconAction("↻", "Refresh now", controller::refreshNow, enabled = state.canRefresh && !closing)
    }
}

@Composable
private fun ColumnScope.ConnectPane(
    state: MonitoringState, controller: MonitoringController, closing: Boolean,
    onSettings: () -> Unit, onClose: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var linkFailed by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    PaneHeader(state, onSettings, onClose)

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.height(2.dp))
        Text("Connect Vast.ai", color = white, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("CreditWatch reads your balance and instances. It cannot start, stop or destroy anything.",
            color = muted, fontSize = 11.sp, lineHeight = 16.sp)
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text("Vast.ai API key", fontSize = 12.sp) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            enabled = !state.busy && !closing && controller.secureStorageAvailable,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Where to find your key ↗", color = accent, fontSize = 11.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable {
                linkFailed = runCatching {
                    uriHandler.openUri("https://docs.vast.ai/guides/reference/keys")
                }.isFailure
            })
        if (linkFailed) {
            Text("Open docs.vast.ai/guides/reference/keys in your browser.", color = amber, fontSize = 11.sp)
        }
        Text(state.message, color = if (state.status == SyncStatus.AUTH_ERROR) amber else muted,
            fontSize = 11.sp, lineHeight = 15.sp)
    }

    HairLine()
    Row(Modifier.fillMaxWidth().padding(top = 11.dp)) {
        Button(onClick = { if (controller.connect(key.trim().toCharArray()) != null) key = "" },
            enabled = !state.busy && !closing && controller.secureStorageAvailable && key.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) {
            Text("Connect account", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ColumnScope.SettingsPane(
    state: MonitoringState, controller: MonitoringController, closing: Boolean,
    onBack: () -> Unit, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit, onQuit: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconAction("‹", "Back to runway", onBack)
        Spacer(Modifier.width(4.dp))
        Text("Settings", color = white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(2.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("ACCOUNT")
            Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(statusTone(state), CircleShape))
                    Spacer(Modifier.width(9.dp))
                    Text(if (state.connected) "Vast.ai connected" else "Not connected",
                        color = white, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Text("Your key is held in the operating system's secure store. Removing the account also " +
                    "attempts to clear local monitoring history and alerts.",
                    color = muted, fontSize = 11.sp, lineHeight = 16.sp)
                if (state.connected) {
                    TextButton(onClick = { controller.removeAccount() },
                        enabled = !state.busy && !closing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("Remove account", color = amber, fontSize = 12.sp)
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Caption("ALERTS")
            ALERT_THRESHOLD_HOURS.forEach { hours ->
                val tripped = state.activeRunwayThresholdHours == hours
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Safe runway below ${hours}h", color = if (tripped) amber else white, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    if (tripped) Text("alerted", color = amber, fontSize = 11.sp)
                }
            }
            Text("Thresholds are fixed in this release.", color = muted, fontSize = 10.sp)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption("APPEARANCE")
            Row(Modifier.background(panel, RoundedCornerShape(9.dp)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ThemeMode.entries.forEach { entry ->
                    val selected = entry == themeMode
                    Box(Modifier.background(if (selected) raised else Color.Transparent, RoundedCornerShape(7.dp))
                        .clickable { onThemeChange(entry) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)) {
                        Text(entry.label, color = if (selected) white else muted, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("REFRESH EVERY")
            Spacer(Modifier.weight(1f))
            Text("60s", color = white, fontSize = 12.sp)
        }
    }

    HairLine()
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("CreditWatch ${appVersion()}", color = muted, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onQuit, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
            Text("Quit", color = muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, trend: CardTrend, tone: Color, modifier: Modifier) {
    Column(modifier.background(panel, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Caption(label)
        Text(value, color = white, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        TrendGraph(trend, Modifier.fillMaxWidth().height(14.dp), tone)
    }
}

@Composable
private fun InstanceRow(instance: CloudInstance) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp))
        .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(healthy, CircleShape))
        Spacer(Modifier.width(9.dp))
        Text(instance.id.value, color = white, fontSize = 12.sp, maxLines = 1)
        instance.label?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, color = muted, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Text(instance.computeRate?.let { "${formatMoney(it.amountPerHour, it.currency, 3)}/hr" } ?: "rate unknown",
            color = muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable private fun Caption(text: String) {
    Text(text, color = muted, fontSize = 10.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp)
}

@Composable private fun HairLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(muted.copy(alpha = .14f)))
}

@Composable
private fun IconAction(glyph: String, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(32.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)) {
        Text(glyph, color = muted.copy(alpha = if (enabled) 1f else .35f), fontSize = 17.sp)
    }
}

@Composable
private fun TrendGraph(trend: CardTrend, modifier: Modifier = Modifier, color: Color? = null) {
    val lineColor = color ?: accent
    val points = remember(trend) { trend.points.mapNotNull { point ->
        point.value.toFloat().takeIf(Float::isFinite)?.let { point.time to it }
    } }
    if (points.size < 2) {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            Text("—", color = muted, fontSize = 12.sp)
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
                size.height - 3.dp.toPx() - ((value - low) / spread * (size.height - 6.dp.toPx()))
            if (index == 0 || previousTime?.let { java.time.Duration.between(it, time).seconds > 180 } == true)
                path.moveTo(x, y) else path.lineTo(x, y)
            previousTime = time
        }
        drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))
        val last = points.last()
        val lastX = (last.first.toEpochMilli() - minTime) / span * size.width
        val lastY = if (high == low) size.height / 2f else
            size.height - 3.dp.toPx() - ((last.second - low) / spread * (size.height - 6.dp.toPx()))
        drawCircle(lineColor, radius = 2.dp.toPx(), center = Offset(lastX, lastY))
    }
}

/** The thresholds [dev.creditwatch.engine.RunwayAlertRule] fires on, longest first for display. */
private val ALERT_THRESHOLD_HOURS = listOf(12, 6, 1)

private fun statusLabel(state: MonitoringState): String = when {
    state.status == SyncStatus.CONNECTING -> "Connecting"
    state.status == SyncStatus.AUTH_ERROR -> "Key rejected"
    !state.connected && state.busy -> "Loading"
    !state.connected -> "Not connected"
    state.status == SyncStatus.SYNCING -> "Syncing"
    state.status == SyncStatus.RATE_LIMITED -> "Rate limited"
    state.status == SyncStatus.OFFLINE -> "Offline"
    state.status == SyncStatus.DEGRADED -> "Partial data"
    state.stale -> "Saved data"
    else -> "Monitoring"
}

@Composable
private fun statusTone(state: MonitoringState): Color = when {
    state.status in setOf(SyncStatus.AUTH_ERROR, SyncStatus.OFFLINE, SyncStatus.RATE_LIMITED) -> amber
    state.connected && (state.stale || state.status == SyncStatus.DEGRADED) -> amber
    state.connected && state.status in setOf(SyncStatus.HEALTHY, SyncStatus.SYNCING) -> healthy
    else -> muted
}

private fun balanceText(state: MonitoringState) =
    state.summary?.sample?.balance?.let { formatMoney(it.amount, it.currency, 2) } ?: "—"

private fun burnCompact(state: MonitoringState) = state.summary?.sample?.let {
    "${formatMoney(it.knownRate.amountPerHour, it.balance.currency, 3)}/hr"
} ?: "—"

/** The headline is the safe figure, so the subline has to say what makes it lower than the raw one. */
private fun runwaySubline(state: MonitoringState): String {
    val summary = state.summary ?: return "Connect Vast.ai to start monitoring"
    return when (summary.safeRunway) {
        RunwayResult.Unavailable -> "Waiting for complete, recent compute and storage prices"
        RunwayResult.NoBurn -> "No active known burn detected"
        RunwayResult.BalanceDepleted -> "Credit exhausted at this burn rate"
        is RunwayResult.Available ->
            if (state.stale) "Saved estimate • refresh for current data"
            else "Raw ${formatRunway(summary.rawRunway)} • includes a 10% safety buffer"
    }
}

private fun footerText(state: MonitoringState, closing: Boolean): String = when {
    closing -> "Closing…"
    state.busy -> "Refreshing…"
    state.backingOff || !state.connected -> state.message
    state.nextSyncAt != null -> "Next sync ${formatClock(state.nextSyncAt)}"
    else -> state.message
}

private fun formatMoney(amount: BigDecimal, code: CurrencyCode, decimals: Int) =
    NumberFormat.getCurrencyInstance().apply {
        currency = Currency.getInstance(code.value)
        minimumFractionDigits = 2
        maximumFractionDigits = decimals
        roundingMode = RoundingMode.HALF_UP
    }.format(amount)

private fun formatRunway(result: RunwayResult): String = when (result) {
    is RunwayResult.Available -> "${result.duration.toHours()}h ${result.duration.toMinutesPart()}m"
    RunwayResult.NoBurn -> "—"
    RunwayResult.BalanceDepleted -> "0m"
    RunwayResult.Unavailable -> "Unavailable"
}

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private fun formatClock(instant: Instant) = clockFormat.format(instant)

private fun appVersion(): String =
    Main::class.java.`package`?.implementationVersion ?: "0.1.0"

private object Main

private fun historyPath(): Path {
    val home = System.getProperty("user.home")
    val root = when {
        System.getProperty("os.name").startsWith("Mac") -> Path.of(home, "Library", "Application Support", "CreditWatch")
        System.getProperty("os.name").startsWith("Windows") -> Path.of(System.getenv("LOCALAPPDATA") ?: home, "CreditWatch")
        else -> Path.of(System.getenv("XDG_DATA_HOME") ?: "${home}/.local/share", "creditwatch")
    }
    return root.resolve("creditwatch.db")
}
