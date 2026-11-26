package com.roamly.ui.tracker

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.NotificationItem
import com.roamly.tracker.LinkState
import com.roamly.tracker.TrackerAdvert
import com.roamly.ui.kit.AtlasButton
import com.roamly.ui.kit.AtlasDialog
import com.roamly.ui.kit.AtlasField
import com.roamly.ui.kit.AtlasSheet
import com.roamly.ui.kit.ButtonKind
import com.roamly.ui.kit.Dot
import com.roamly.ui.kit.EmptyState
import com.roamly.ui.kit.ErrorNote
import com.roamly.ui.kit.IconTile
import com.roamly.ui.kit.ListRow
import com.roamly.ui.kit.Panel
import com.roamly.ui.kit.PanelTone
import com.roamly.ui.kit.PushedScreen
import com.roamly.ui.kit.RowDivider
import com.roamly.ui.kit.RowGroup
import com.roamly.ui.kit.SectionTitle
import com.roamly.ui.kit.StatBlock
import com.roamly.ui.kit.dockAwareBottom
import com.roamly.ui.theme.Atlas
import com.roamly.ui.theme.AtlasType
import com.roamly.ui.theme.SquircleRadius
import com.roamly.ui.theme.SquircleShape
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Roamly Tracker: pairing, then managing it. While visible it holds a
 * Bluetooth session open: the tracker connects at its next beacon window (it
 * wakes its radio every minute or so) and then streams live status every two
 * seconds.
 */
@Composable
fun TrackerScreen(onBack: (() -> Unit)? = null, viewModel: TrackerViewModel = hiltViewModel()) {
    val link = viewModel.link
    val address by link.address.collectAsState()
    val context = LocalContext.current
    var permsOk by remember { mutableStateOf(link.hasPermissions()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsOk = link.hasPermissions() }
    LaunchedEffect(Unit) { if (!permsOk) permLauncher.launch(link.requiredPermissions()) }
    val c = Atlas.colors

    PushedScreen(title = "Roamly Tracker", onBack = onBack) {
        when {
            !permsOk -> EmptyState(
                Icons.Rounded.Bluetooth, "Bluetooth permission needed",
                body = "Roamly uses Bluetooth to talk to your tracker and to notice when it's nearby, so only one of them records while they're together.",
                tint = c.sky, action = "Continue", onAction = { permLauncher.launch(link.requiredPermissions()) },
            )
            !link.bluetoothOn() -> EmptyState(
                Icons.Rounded.Bluetooth, "Bluetooth is off", body = "Turn Bluetooth on to reach the tracker.", tint = c.sky,
                action = "Open Bluetooth settings",
                onAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
            address == null -> Pairing(viewModel)
            else -> Manage(viewModel)
        }
    }
}

// ── Pairing ─────────────────────────────────────────────────────────────────

@Composable
private fun Pairing(viewModel: TrackerViewModel) {
    val c = Atlas.colors
    val link = viewModel.link
    val candidates by link.pairCandidates.collectAsState()
    val bonding by link.bonding.collectAsState()
    val error by link.lastError.collectAsState()
    DisposableEffect(Unit) {
        link.startPairScan()
        onDispose { link.stopPairScan() }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            IconTile(Icons.Rounded.BluetoothSearching, tint = c.sky, size = 48.dp, iconSize = 24.dp)
            Spacer(Modifier.height(14.dp))
            Text("Pair over Bluetooth", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text("On the tracker: Menu → Bluetooth → Pair a phone. It appears below — tap it, then type the 6-digit code the tracker shows.",
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        val ready = candidates.filter { it.pairing }
        if (ready.isEmpty()) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.sky)
                Spacer(Modifier.width(12.dp))
                Text("Looking for a tracker in pairing mode…", style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
        ready.forEach { adv -> Candidate(adv, bonding) { link.pair(adv) } }
        val other = candidates.filterNot { it.pairing }
        if (other.isNotEmpty()) {
            Text("Nearby, not in pairing mode: " + other.joinToString { it.name ?: it.address }, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        error?.let { ErrorNote(it) }
    }
}

@Composable
private fun Candidate(adv: TrackerAdvert, bonding: Boolean, onPair: () -> Unit) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp), onClick = if (bonding) null else onPair) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Watch, tint = c.sky)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(adv.name ?: "Roamly Tracker", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("${adv.address} · ${adv.rssi} dBm" + (adv.battery?.let { " · $it%" } ?: ""), style = AtlasType.mono, color = c.textMuted)
            }
            if (bonding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
            else Text("Pair", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

// ── Management ──────────────────────────────────────────────────────────────

@Composable
private fun Manage(viewModel: TrackerViewModel) {
    val c = Atlas.colors
    val link = viewModel.link
    val state by link.linkState.collectAsState()
    val advert by link.advert.collectAsState()
    val near by link.near.collectAsState()
    val phonePause by link.phoneShouldPause.collectAsState()
    val status by link.status.collectAsState()
    val settings by link.settings.collectAsState()
    val reply by link.reply.collectAsState()
    val error by link.lastError.collectAsState()
    val name by viewModel.trackerName.collectAsState()
    val prompts by viewModel.prompts.collectAsState()

    DisposableEffect(Unit) {
        viewModel.startSession()
        onDispose { viewModel.endSession() }
    }
    LaunchedEffect(Unit) { viewModel.refreshPrompts() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = dockAwareBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Panel(Modifier.fillMaxWidth()) {
            val connected = state == LinkState.CONNECTED
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.Watch, tint = c.sky, size = 48.dp, iconSize = 24.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(name ?: "Roamly Tracker", style = MaterialTheme.typography.titleLarge, color = c.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (connected) c.success else if (near) c.amber else c.textFaint, 7.dp, pulse = state == LinkState.CONNECTING)
                        Spacer(Modifier.width(6.dp))
                        Text(connectionLabel(state, near), style = MaterialTheme.typography.labelMedium, color = c.textMuted)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val batt = status?.optJSONObject("batt")
            val pct = batt?.optInt("pct", -1)?.takeIf { it >= 0 } ?: advert?.battery
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                StatBlock(pct?.let { "$it" } ?: "—", "battery", unit = if (pct != null) "%" else null, small = true)
                batt?.optDouble("hours")?.takeIf { !it.isNaN() }?.let { h ->
                    StatBlock(fmtHours(h), if (batt.optString("state") == "charging") "to full" else "left", small = true)
                }
                batt?.optString("state")?.takeIf { it.isNotEmpty() && it != "unknown" }?.let { StatBlock(it.replaceFirstChar { ch -> ch.uppercase() }, "state", small = true) }
            }
            Spacer(Modifier.height(14.dp))
            Panel(Modifier.fillMaxWidth(), tone = PanelTone.Sunken, padding = PaddingValues(12.dp)) {
                Text(handoffLine(near, phonePause, advert, status), style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
            if (!connected) {
                Spacer(Modifier.height(10.dp))
                Text("The tracker wakes its radio about once a minute, so connecting can take up to a minute. Keep this screen open.",
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            error?.let { Spacer(Modifier.height(10.dp)); ErrorNote(it) }
        }

        if (prompts.isNotEmpty()) Prompts(prompts, viewModel)
        if (state == LinkState.CONNECTED) {
            Actions(status, reply, viewModel)
            status?.let { Diagnostics(it) }
            settings?.let { SettingsGroup(it, viewModel) }
            Console(viewModel)
        }
        Unpair(viewModel)
    }
}

private fun connectionLabel(state: LinkState, near: Boolean) = when (state) {
    LinkState.CONNECTED -> "Connected"
    LinkState.CONNECTING -> "Connecting…"
    else -> if (near) "Nearby" else "Not in range"
}

private fun handoffLine(near: Boolean, phonePause: Boolean, adv: TrackerAdvert?, status: JSONObject?): String {
    val rec = status?.optJSONObject("rec")
    return when {
        adv == null && status == null -> "Haven't heard from the tracker yet."
        !near -> "Apart — both the phone and the tracker are recording. You'll be asked which one you had."
        phonePause -> "Together — the tracker is recording, the phone's GPS is resting."
        rec?.optBoolean("phone_paused") == true || adv?.pausedForPhone == true -> "Together — the phone is recording, the tracker is resting."
        adv?.userPaused == true -> "The tracker is paused, so the phone is recording."
        adv?.trackerRecordsWhenTogether == true && adv.hasFix.not() -> "Together, but the tracker has no GPS fix yet — the phone keeps recording."
        else -> "Together."
    }
}

private fun fmtHours(h: Double): String = if (h >= 10) "${h.toInt()}h" else {
    val m = (h * 60).toInt(); if (m < 60) "${m}m" else "${m / 60}h${(m % 60).toString().padStart(2, '0')}"
}

@Composable
private fun Prompts(prompts: List<NotificationItem>, viewModel: TrackerViewModel) {
    val c = Atlas.colors
    Panel(Modifier.fillMaxWidth(), tone = PanelTone.Accent) {
        SectionTitle("Which one was with you?")
        prompts.forEach { item ->
            val a = item.devices.getOrNull(0)
            val b = item.devices.getOrNull(1)
            if (a == null || b == null) return@forEach
            Text(fmtRange(item.start, item.end), style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(
                if (item.overlap == "apart") "${a.name} and ${b.name} were in different places, so both recorded."
                else "${a.name} and ${b.name} recorded the same track.",
                style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.overlap == "apart") {
                    AtlasButton("I had ${a.name}", { viewModel.hide(item, b.id) }, compact = true)
                    AtlasButton("I had ${b.name}", { viewModel.hide(item, a.id) }, compact = true)
                } else {
                    AtlasButton("Hide ${a.name}", { viewModel.hide(item, a.id) }, compact = true)
                    AtlasButton("Hide ${b.name}", { viewModel.hide(item, b.id) }, compact = true)
                }
                AtlasButton("Keep both", { viewModel.keepBoth(item) }, kind = ButtonKind.Secondary, compact = true)
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

private fun fmtRange(start: String, end: String): String = try {
    val z = ZoneId.systemDefault()
    val s = Instant.parse(start).atZone(z)
    val e = Instant.parse(end).atZone(z)
    val d = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm")
    val t = DateTimeFormatter.ofPattern("HH:mm")
    "${s.format(d)} – ${e.format(if (s.toLocalDate() == e.toLocalDate()) t else d)}"
} catch (_: Exception) { "$start – $end" }

@Composable
private fun Actions(status: JSONObject?, reply: String?, viewModel: TrackerViewModel) {
    val c = Atlas.colors
    val link = viewModel.link
    val recording = status?.optJSONObject("rec")?.optBoolean("user", true) ?: true
    var confirmOff by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Actions")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtlasButton("Sync now", { link.command("sync") }, compact = true)
            AtlasButton(if (recording) "Pause recording" else "Resume recording", { link.command(if (recording) "pause" else "resume") }, kind = ButtonKind.Secondary, compact = true)
            AtlasButton("Restart", { link.command("restart") }, kind = ButtonKind.Secondary, compact = true)
            AtlasButton("Power off", { confirmOff = true }, kind = ButtonKind.Danger, compact = true)
        }
        reply?.let { r ->
            val msg = try { JSONObject(r).optString("msg") } catch (_: Exception) { r }
            if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg, style = MaterialTheme.typography.bodySmall, color = c.textMuted) }
        }
    }
    if (confirmOff) {
        AtlasDialog(
            title = "Power off the tracker?",
            onDismiss = { confirmOff = false },
            confirmText = "Power off",
            onConfirm = { confirmOff = false; link.command("poweroff") },
            destructive = true,
            icon = Icons.Rounded.PowerSettingsNew,
            body = { Text("It stops recording. To turn it back on, hold its middle button for 3 seconds.") },
        )
    }
}

@Composable
private fun Diagnostics(s: JSONObject) {
    val gps = s.optJSONObject("gps") ?: JSONObject()
    val store = s.optJSONObject("store") ?: JSONObject()
    val sync = s.optJSONObject("sync") ?: JSONObject()
    val rec = s.optJSONObject("rec") ?: JSONObject()
    val batt = s.optJSONObject("batt") ?: JSONObject()
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Diagnostics")
        val fix = when (gps.optInt("fix")) { 3 -> "3D fix"; 2 -> "2D fix"; else -> "no fix" }
        Line("GPS", "$fix · ${gps.optInt("sats")} used / ${gps.optInt("view")} seen (GPS ${gps.optInt("gps")} · BeiDou ${gps.optInt("bds")} · GLONASS ${gps.optInt("glo")})")
        if (gps.has("acc")) Line("Accuracy", "~${gps.optDouble("acc")} m · HDOP ${gps.optDouble("hdop")}")
        if (gps.has("lat")) Line("Position", "%.5f, %.5f".format(gps.optDouble("lat"), gps.optDouble("lon")))
        if (gps.has("age")) Line("Last fix", "${gps.optInt("age")} s ago · signal ${gps.optInt("snr")} dB-Hz")
        Line("GPS awake", "${gps.optDouble("duty")} % · " + (if (gps.optBoolean("asleep")) "standby now" else "awake now"))
        Line("Recording", (if (rec.optBoolean("adaptive")) "Adaptive (${rec.optString("motion")}), " else "") +
            "every ${rec.optInt("interval")} s · ${rec.optInt("recorded")} stored, ${rec.optInt("missed")} missed this boot")
        Line("On tracker", "${store.optInt("pending")} points waiting · ${store.optInt("free_kb")} KB free" + (if (store.optInt("held") > 0) " · ${store.optInt("held")} refused" else ""))
        Line("Sync", sync.optString("msg") + (if (!sync.optBoolean("auto")) " · auto-sync off" else ""))
        if (batt.has("v")) Line("Battery", "${batt.optDouble("v")} V · ${batt.optString("state")}" + (if (batt.optBoolean("measured")) " · measured rate" else " · estimated"))
        Line("Firmware", "${s.optString("fw")} · GPS ${gps.optString("ver")}")
        Line("Uptime", "${s.optLong("up") / 3600} h · ${s.optInt("boots")} boots · ${s.optInt("crashes")} crashes · last reset ${s.optString("last_reset")}", last = true)
    }
}

@Composable
private fun ColumnScope.Line(label: String, value: String, last: Boolean = false) {
    val c = Atlas.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = c.text)
    }
    if (!last) HorizontalDivider(color = c.line)
}

/** One editable tracker setting: console key, label, and its choices. */
private data class TrackerSetting(val key: String, val label: String, val options: List<Pair<Any, String>>)

private val SETTINGS = listOf(
    TrackerSetting("handoff", "When together, record on", listOf("tracker" to "Tracker", "phone" to "Phone")),
    TrackerSetting("interval", "Log interval", listOf(0 to "Adaptive", 10 to "10 s", 15 to "15 s", 30 to "30 s", 60 to "1 min", 120 to "2 min", 300 to "5 min")),
    TrackerSetting("mode", "GPS mode", listOf(0 to "Accurate", 1 to "Balanced", 2 to "Low power")),
    TrackerSetting("ble", "Bluetooth check-in", listOf(30 to "Every 30 s", 60 to "Every minute", 120 to "Every 2 min", 300 to "Every 5 min", 0 to "Off (turn back on at the tracker)")),
    TrackerSetting("minacc", "Min accuracy", listOf(0 to "Keep all", 15 to "15 m", 25 to "25 m", 50 to "50 m", 100 to "100 m")),
    TrackerSetting("autosync", "Auto-sync", listOf(1 to "When charging", 0 to "Off")),
    TrackerSetting("timeout", "Screen timeout", listOf(3 to "3 s", 5 to "5 s", 10 to "10 s", 15 to "15 s", 30 to "30 s", 60 to "1 min")),
    TrackerSetting("bright", "Brightness", (1..10).map { it * 10 to "${it * 10}%" }),
    TrackerSetting("units", "Units", listOf(0 to "Metric", 1 to "Imperial")),
    TrackerSetting("clock", "Clock", listOf(1 to "24 hour", 0 to "12 hour")),
    TrackerSetting("flip", "Flip screen", listOf(0 to "Off", 1 to "On")),
    TrackerSetting("batt", "Battery size", (2..16).map { it * 250 to "${it * 250} mAh" }),
)

@Composable
private fun SettingsGroup(settings: JSONObject, viewModel: TrackerViewModel) {
    val c = Atlas.colors
    var editing by remember { mutableStateOf<TrackerSetting?>(null) }
    SectionTitle("Tracker settings", Modifier.padding(top = 6.dp))
    RowGroup {
        SETTINGS.forEachIndexed { i, s ->
            val current = settings.opt(s.key)
            val label = s.options.firstOrNull { it.first.toString() == current?.toString() }?.second ?: current?.toString() ?: "—"
            ListRow(s.label, onClick = { editing = s }, showChevron = false,
                trailing = { Text(label, style = MaterialTheme.typography.labelLarge, color = c.accent) })
            if (i < SETTINGS.lastIndex) RowDivider(inset = 16.dp)
        }
    }
    editing?.let { s ->
        val current = settings.opt(s.key)?.toString()
        AtlasSheet(onDismiss = { editing = null }, title = s.label) {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(s.options) { (value, label) ->
                    ListRow(label, onClick = { viewModel.link.setSetting(s.key, value); editing = null }, showChevron = false,
                        trailing = if (value.toString() == current) ({ Icon(Icons.Rounded.Check, null, tint = c.accent) }) else null,
                        modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun Console(viewModel: TrackerViewModel) {
    val c = Atlas.colors
    val out by viewModel.link.console.collectAsState()
    var line by remember { mutableStateOf("status") }
    Panel(Modifier.fillMaxWidth()) {
        SectionTitle("Console")
        Text("The tracker's serial console over Bluetooth — try status, dump 20 or help.", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AtlasField(line, { line = it }, Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.link.runConsole(line) }))
            Spacer(Modifier.width(8.dp))
            AtlasButton("Run", { viewModel.link.runConsole(line) }, compact = true)
        }
        if (out.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(SquircleShape(SquircleRadius.control)).background(c.bg).padding(12.dp)) {
                Text(out, style = AtlasType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp), color = c.lime,
                    modifier = Modifier.horizontalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun Unpair(viewModel: TrackerViewModel) {
    val c = Atlas.colors
    var confirm by remember { mutableStateOf(false) }
    Spacer(Modifier.height(4.dp))
    AtlasButton("Unpair tracker", { confirm = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = Icons.Rounded.LinkOff)
    Text("Unpairing forgets the tracker on this phone and stops the hand-off; both then record all the time.",
        style = MaterialTheme.typography.bodySmall, color = c.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
    if (confirm) {
        AtlasDialog(
            title = "Unpair the tracker?",
            onDismiss = { confirm = false },
            confirmText = "Unpair",
            onConfirm = { confirm = false; viewModel.link.unpair() },
            destructive = true,
            icon = Icons.Rounded.LinkOff,
            body = { Text("You can pair again from You → Roamly Tracker.") },
        )
    }
}
