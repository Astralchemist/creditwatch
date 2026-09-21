package dev.creditwatch.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import dev.creditwatch.domain.CurrencyCode
import dev.creditwatch.domain.CloudInstance
import dev.creditwatch.domain.InstanceState
import dev.creditwatch.engine.RUNWAY_ALERT_THRESHOLDS_HOURS
import dev.creditwatch.engine.RunwayResult
import dev.creditwatch.engine.ThresholdArming
import dev.creditwatch.engine.isArmable
import dev.creditwatch.engine.thresholdArming
import dev.creditwatch.notifications.PhoneAlertPublisher
import dev.creditwatch.notifications.PhoneAlertResult
import dev.creditwatch.notifications.PhoneAlertTarget
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
private val critical @Composable get() = palette.critical
private val white @Composable get() = palette.text

/** The popover is the whole product; settings slides in over it rather than opening a window. */
private enum class Pane { RUNWAY, SETTINGS }

/** Mirrors [MonitoringController.refreshNow]: during a backoff the request is dropped, so don't offer it. */
private val MonitoringState.canRefresh: Boolean get() = connected && !busy && !backingOff

fun main() = application {
    val http = remember { VastProvider.newHttpClient() }
    val initialAlerts = remember { AlertSettings.load() }
    val phone = remember { PhoneAlertPublisher(http) }
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
            alertThresholdHours = initialAlerts.enabledThresholdHours,
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
    var alerts by remember { mutableStateOf(initialAlerts) }
    var phoneStatus by remember { mutableStateOf<String?>(null) }
    val setAlerts: (AlertPreferences) -> Unit = { updated ->
        val thresholdsChanged = updated.enabledThresholdHours != alerts.enabledThresholdHours
        alerts = updated
        AlertSettings.save(updated)
        if (thresholdsChanged) controller.setAlertThresholds(updated.enabledThresholdHours)
    }
    val testPhone = {
        phoneStatus = "Sending…"
        alerts.pairingTarget?.let { target ->
            scope.launch {
                phoneStatus = phone.publish(target, "CreditWatch",
                    "Test alert. Pairing works.").describe()
            }
        } ?: run { phoneStatus = "Pair a phone first." }
        Unit
    }
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
            val depleted = event.runway == RunwayResult.BalanceDepleted
            val body = if (depleted) "Balance depleted."
                else "Safe runway is below ${event.thresholdHours}h (${formatRunway(event.runway)}). Based on known costs."
            if (isTraySupported) {
                trayState.sendNotification(Notification("CreditWatch — low runway", body,
                    if (event.thresholdHours <= 1) Notification.Type.Error else Notification.Type.Warning))
            }
            // The phone push is best effort and never blocks or breaks the desktop notification.
            alerts.phoneTarget?.let { target ->
                val result = phone.publish(target, "CreditWatch — low runway", body,
                    urgent = depleted || event.thresholdHours <= 1)
                if (result != PhoneAlertResult.Delivered) phoneStatus = result.describe()
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
                themeMode, setTheme, quit, alerts, setAlerts, testPhone, phoneStatus)
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
    alerts: AlertPreferences, onAlerts: (AlertPreferences) -> Unit,
    onTestPhone: () -> Unit, phoneStatus: String?,
) {
    // Floating over the menu bar it needs its own rounded edge; as a plain window it must not.
    val shape: Shape = if (isTraySupported) RoundedCornerShape(16.dp) else RectangleShape
    Column(Modifier.fillMaxSize().background(background, shape)
        .then(if (isTraySupported) Modifier.border(1.dp, muted.copy(alpha = .22f), shape) else Modifier)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        when {
            pane == Pane.SETTINGS ->
                SettingsPane(state, controller, closing, { onPane(Pane.RUNWAY) }, themeMode, onThemeChange,
                    onQuit, alerts, onAlerts, onTestPhone, phoneStatus)
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
        IconAction(CwIcons.Settings, "Settings", onSettings)
        IconAction(CwIcons.Close, "Close", onClose)
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
            val depleted = state.summary?.safeRunway == RunwayResult.BalanceDepleted
            val tone = runwayTone(state)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Caption("SAFE RUNWAY")
                Spacer(Modifier.weight(1f))
                when {
                    depleted -> {
                        Icon(CwIcons.Alert, contentDescription = null, tint = critical,
                            modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("depleted", color = critical, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                    state.activeRunwayThresholdHours != null -> {
                        Icon(CwIcons.Alert, contentDescription = null, tint = tone,
                            modifier = Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("below ${state.activeRunwayThresholdHours}h", color = tone,
                            fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            // "0m" reads as a countdown that is still running. Being out of credit is a different
            // state from having very little left, and the headline is the only place that lands.
            Text(if (depleted) "Out of credit" else state.summary?.let { formatRunway(it.safeRunway) } ?: "—",
                color = tone,
                fontSize = if (depleted) 32.sp else 50.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(runwaySubline(state), color = if (depleted) critical else muted,
                fontSize = 11.sp, lineHeight = 15.sp)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCell("BALANCE", balanceText(state), state.trends.balance,
                if (state.summary?.safeRunway == RunwayResult.BalanceDepleted) critical else healthy,
                Modifier.weight(1f), projection = balanceProjection(state))
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
        IconAction(CwIcons.Refresh, "Refresh now", controller::refreshNow, enabled = state.canRefresh && !closing)
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
            linkFailed = runCatching {
                uriHandler.openUri("https://docs.vast.ai/guides/reference/keys")
            }.isFailure
        }) {
            Text("Where to find your key", color = accent, fontSize = 11.sp,
                textDecoration = TextDecoration.Underline)
            Spacer(Modifier.width(5.dp))
            Icon(CwIcons.ExternalLink, contentDescription = null, tint = accent,
                modifier = Modifier.size(11.dp))
        }
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
    alerts: AlertPreferences, onAlerts: (AlertPreferences) -> Unit,
    onTestPhone: () -> Unit, phoneStatus: String?,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconAction(CwIcons.ChevronLeft, "Back to runway", onBack)
        Spacer(Modifier.width(4.dp))
        Text("Settings", color = white, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }

    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(2.dp))

        ProvidersSection(state, controller, closing, onBack)
        AlertsSection(state, alerts, onAlerts, closing)
        PhoneSection(alerts, onAlerts, onTestPhone, phoneStatus, closing)

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("APPEARANCE")
            Spacer(Modifier.weight(1f))
            ThemeToggle(themeMode, onThemeChange, closing)
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

/**
 * One row per provider, so a second account is an obvious next step rather than a rewrite of
 * the pane. Providers without an adapter are listed and visibly unavailable: hiding them would
 * be tidier, but a user who came for RunPod deserves to learn that here rather than by hunting.
 */
@Composable
private fun ProvidersSection(
    state: MonitoringState, controller: MonitoringController, closing: Boolean, onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("PROVIDERS")
            Spacer(Modifier.weight(1f))
            Hint("Each provider holds its own credit, so each one has its own runway. " +
                "CreditWatch monitors one connected account today.")
        }
        PROVIDER_CATALOGUE.forEach { entry ->
            val connected = entry.adapter && entry.id == "vast" && state.connected
            Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp))
                .padding(horizontal = 11.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(
                    if (connected) healthy else muted.copy(alpha = if (entry.adapter) .6f else .3f),
                    CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(entry.name, color = if (entry.adapter) white else muted,
                    fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                when {
                    connected -> TextButton(onClick = { controller.removeAccount() },
                        enabled = !state.busy && !closing,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("Remove", color = amber, fontSize = 11.sp)
                    }
                    entry.adapter -> TextButton(onClick = onBack, enabled = !closing,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)) {
                        Text("Connect", color = accent, fontSize = 11.sp)
                    }
                    else -> {
                        Text("Not yet", color = muted, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Hint(buildString {
                            append(entry.note ?: "Not supported yet")
                            append(". CreditWatch needs a read-only adapter for ")
                            append(entry.name)
                            append(" before it can report its credit.")
                        })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = !closing) {
            runCatching { uriHandler.openUri("https://github.com/Astralchemist/creditwatch/issues") }
        }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(CwIcons.Plus, contentDescription = null, tint = accent, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(7.dp))
            Text("Request another provider", color = accent, fontSize = 11.sp)
        }
        Text("Your key is held in the operating system's secure store. Removing an account also " +
            "attempts to clear its local monitoring history and alerts.",
            color = muted, fontSize = 10.sp, lineHeight = 14.sp)
    }
}

/**
 * The thresholds, as switches. The section heading carries "safe runway" once; each row is
 * then just a duration with a hover hint, rather than the same five words three times over.
 */
@Composable
private fun AlertsSection(
    state: MonitoringState, alerts: AlertPreferences,
    onAlerts: (AlertPreferences) -> Unit, closing: Boolean,
) {
    val runway = state.summary?.safeRunway ?: RunwayResult.Unavailable
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("SAFE RUNWAY ALERTS")
            Spacer(Modifier.weight(1f))
            Hint("Notifies once when safe runway first falls past a mark, then at most every " +
                "six hours while it stays there.")
        }
        // Three marks on one scale read better as a row than as a stack of near-identical lines.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RUNWAY_ALERT_THRESHOLDS_HOURS.forEach { hours ->
                val armed = hours in alerts.enabledThresholdHours
                val arming = thresholdArming(hours, runway)
                // An armed threshold is never taken away by the reading; only arming a new one is.
                val blocked = !armed && !arming.isArmable()
                val tripped = state.activeRunwayThresholdHours == hours
                Column(Modifier.weight(1f).background(panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${hours}h",
                            color = when {
                                blocked -> muted.copy(alpha = .5f)
                                tripped -> amber
                                armed -> white
                                else -> muted
                            },
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(5.dp))
                        Hint(thresholdHint(hours, arming, armed, blocked))
                    }
                    Switch(
                        checked = armed,
                        onCheckedChange = { on ->
                            onAlerts(alerts.copy(enabledThresholdHours =
                                if (on) alerts.enabledThresholdHours + hours
                                else alerts.enabledThresholdHours - hours))
                        },
                        enabled = !blocked && !closing,
                        colors = SwitchDefaults.colors(checkedThumbColor = accent),
                        modifier = Modifier.size(width = 34.dp, height = 20.dp),
                    )
                    // Always drawn so the three cells keep the same height as their states change.
                    Text(
                        when {
                            tripped -> "alerted"
                            blocked -> "held"
                            else -> ""
                        },
                        color = if (tripped) amber else muted.copy(alpha = .7f),
                        fontSize = 9.sp, maxLines = 1,
                    )
                }
            }
        }
        if (alerts.enabledThresholdHours.isEmpty()) {
            Text("Every runway alert is off. Nothing will warn you before the credit runs out.",
                color = amber, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

private fun thresholdHint(hours: Int, arming: ThresholdArming, armed: Boolean, blocked: Boolean): String = when {
    blocked && arming is ThresholdArming.AlreadyBelow ->
        "Safe runway is already under ${hours}h. Switching this on now would fire at once " +
            "instead of warning you early, so it is held until the runway recovers."
    blocked && arming == ThresholdArming.Depleted ->
        "The credit is already gone, so there is nothing left for a ${hours}h warning to catch."
    armed -> "On. Notifies when safe runway falls below ${hours}h."
    else -> "Off. Nothing will fire at the ${hours}h mark."
}

/**
 * Pairing a phone. The QR code carries the subscribe URL only — the provider key never leaves
 * the Keychain, and nothing in a published alert names the account.
 */
@Composable
private fun PhoneSection(
    alerts: AlertPreferences, onAlerts: (AlertPreferences) -> Unit,
    onTestPhone: () -> Unit, phoneStatus: String?, closing: Boolean,
) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Caption("PHONE")
            Spacer(Modifier.weight(1f))
            Hint("Alerts are published from this computer to a private ntfy topic. They arrive " +
                "only while CreditWatch is running: a sleeping laptop measures nothing and so " +
                "sends nothing.")
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(CwIcons.Phone, contentDescription = null, tint = muted, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(9.dp))
            Text("Send alerts to a phone", color = white, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = alerts.phoneEnabled,
                onCheckedChange = { on ->
                    onAlerts(alerts.copy(
                        phoneEnabled = on,
                        // Pairing needs an address; make one the first time it is switched on.
                        phoneTopic = alerts.phoneTopic.ifBlank {
                            if (on) PhoneAlertTarget.randomTopic() else ""
                        },
                    ))
                },
                enabled = !closing,
                colors = SwitchDefaults.colors(checkedThumbColor = accent),
                modifier = Modifier.size(width = 34.dp, height = 20.dp),
            )
        }

        if (alerts.phoneEnabled) {
            val target = alerts.pairingTarget
            Column(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(12.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (target == null) {
                    Text("This server address is not usable. It must start with http:// or https://.",
                        color = amber, fontSize = 11.sp, lineHeight = 15.sp)
                } else {
                    // White behind the code whatever the theme: scanners want the contrast.
                    Box(Modifier.background(Color.White, RoundedCornerShape(8.dp)).padding(6.dp)) {
                        QrCode(target.subscribeUrl, Modifier.size(116.dp))
                    }
                    Text("Install ntfy, then scan to subscribe", color = muted, fontSize = 10.sp)
                    Text(target.subscribeUrl, color = accent, fontSize = 10.sp, maxLines = 2,
                        modifier = Modifier.clickable {
                            runCatching { uriHandler.openUri("https://ntfy.sh/docs/subscribe/phone/") }
                        })
                }
                OutlinedTextField(
                    value = alerts.phoneServer,
                    onValueChange = { onAlerts(alerts.copy(phoneServer = it.trim())) },
                    label = { Text("ntfy server", fontSize = 11.sp) },
                    singleLine = true, enabled = !closing,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onTestPhone, enabled = !closing && target != null,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        Text("Send test", color = accent, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { onAlerts(alerts.copy(phoneTopic = PhoneAlertTarget.randomTopic())) },
                        enabled = !closing,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        Text("New topic", color = muted, fontSize = 11.sp)
                    }
                }
                phoneStatus?.let {
                    Text(it, color = if (it.startsWith("Sent")) healthy else amber,
                        fontSize = 10.sp, lineHeight = 14.sp)
                }
                Text("Anyone who knows this address can read the alerts. They carry hours of " +
                    "runway only — never your key, balance or instances.",
                    color = muted, fontSize = 10.sp, lineHeight = 14.sp)
            }
        }
    }
}

/** A hover explanation, so a control can be one word wide and still be understood. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Hinted(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Box(Modifier.widthIn(max = 230.dp)
                .background(raised, RoundedCornerShape(8.dp))
                .border(1.dp, muted.copy(alpha = .25f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(text, color = white, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        delayMillis = 250,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 14.dp)),
        content = content,
    )
}

@Composable
private fun Hint(text: String) = Hinted(text) {
    Icon(CwIcons.Info, contentDescription = text, tint = muted.copy(alpha = .75f),
        modifier = Modifier.size(12.dp))
}

/**
 * Appearance in one glyph. Three labelled segments spent a third of the pane restating a choice
 * the icon already shows, so the icon is the control: it reads as the current mode and clicking
 * it moves to the next. The hover hint carries the words the segments used to.
 */
@Composable
private fun ThemeToggle(mode: ThemeMode, onChange: (ThemeMode) -> Unit, closing: Boolean) {
    val next = ThemeMode.entries[(mode.ordinal + 1) % ThemeMode.entries.size]
    Hinted("Appearance: ${mode.label}. Click for ${next.label}.") {
        Box(
            Modifier.size(26.dp).background(panel, RoundedCornerShape(8.dp))
                .clickable(enabled = !closing) { onChange(next) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(themeIcon(mode), contentDescription = "Appearance: ${mode.label}",
                tint = white, modifier = Modifier.size(14.dp))
        }
    }
}

private fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> CwIcons.Auto
    ThemeMode.DARK -> CwIcons.Moon
    ThemeMode.LIGHT -> CwIcons.Sun
}

private fun PhoneAlertResult.describe(): String = when (this) {
    PhoneAlertResult.Delivered -> "Sent. Check the phone."
    is PhoneAlertResult.Rejected -> "The server refused the alert (HTTP $status)."
    is PhoneAlertResult.Unreachable -> "Could not reach the server: $reason"
}

@Composable
private fun MetricCell(
    label: String, value: String, trend: CardTrend, tone: Color, modifier: Modifier,
    projection: TrendPoint? = null,
) {
    Column(modifier.background(panel, RoundedCornerShape(12.dp))
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Caption(label)
        Text(value, color = white, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Sparkline(trend, tone, Modifier.fillMaxWidth().height(20.dp),
            projection = projection, emptyColor = muted)
    }
}

@Composable
private fun InstanceRow(instance: CloudInstance) {
    Row(Modifier.fillMaxWidth().background(panel, RoundedCornerShape(10.dp))
        .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The row is about a piece of rented compute; a status dot said only that it existed.
        Icon(CwIcons.Chip, contentDescription = null, tint = healthy, modifier = Modifier.size(14.dp))
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
private fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.size(32.dp), contentPadding = PaddingValues(0.dp)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(16.dp),
            tint = muted.copy(alpha = if (enabled) 1f else .35f))
    }
}

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

/**
 * The headline colour carries the verdict: green when the runway is comfortable, amber once a
 * threshold has tripped, red when the last hour is gone or the credit already is. Stale data
 * stays amber, because an old healthy reading is not a healthy account.
 */
@Composable
private fun runwayTone(state: MonitoringState): Color {
    val summary = state.summary ?: return white
    return when {
        summary.safeRunway == RunwayResult.BalanceDepleted -> critical
        state.stale -> amber
        state.activeRunwayThresholdHours?.let { it <= 1 } == true -> critical
        state.activeRunwayThresholdHours != null -> amber
        summary.safeRunway is RunwayResult.Available -> healthy
        else -> white
    }
}

/** Where the balance line is headed: zero, at the moment the raw runway runs out. */
private fun balanceProjection(state: MonitoringState): TrendPoint? {
    val summary = state.summary ?: return null
    val runway = summary.rawRunway as? RunwayResult.Available ?: return null
    return TrendPoint(summary.sample.observedAt.plus(runway.duration), BigDecimal.ZERO)
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
    Main::class.java.`package`?.implementationVersion ?: "1.0.0"

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
