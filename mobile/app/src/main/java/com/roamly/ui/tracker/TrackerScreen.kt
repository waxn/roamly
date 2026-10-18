package com.roamly.ui.tracker

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roamly.data.api.NotificationItem
import com.roamly.tracker.LinkState
import com.roamly.tracker.TrackerAdvert
import com.roamly.ui.theme.Clay
import com.roamly.ui.theme.ClayButton
import com.roamly.ui.theme.ClayCard
import com.roamly.ui.theme.ClayOutlinedButton
import com.roamly.ui.theme.ClaySectionHeader
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Tracker tab (and, before a tracker is paired, the pairing flow reached
 * from Settings). While visible it holds a Bluetooth session open: the
 * tracker connects at its next beacon window (it wakes its radio every minute
 * or so) and then streams live status every two seconds.
 */
@Composable
fun TrackerScreen(
    onBack: (() -> Unit)? = null,
    viewModel: TrackerViewModel = hiltViewModel(),
) {
    val link = viewModel.link
    val address by link.address.collectAsState()
    val context = LocalContext.current
    var permsOk by remember { mutableStateOf(link.hasPermissions()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permsOk = link.hasPermissions()
    }
    LaunchedEffect(Unit) { if (!permsOk) permLauncher.launch(link.requiredPermissions()) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            } else Spacer(Modifier.width(12.dp))
            Text("Roamly Tracker", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        when {
            !permsOk -> PermissionNeeded { permLauncher.launch(link.requiredPermissions()) }
            !link.bluetoothOn() -> Message("Bluetooth is off", "Turn Bluetooth on to reach the tracker.") {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            address == null -> PairingContent(viewModel)
            else -> ManageContent(viewModel)
        }
    }
}

@Composable
private fun PermissionNeeded(onGrant: () -> Unit) {
    Message(
        "Bluetooth permission needed",
        "Roamly uses Bluetooth to talk to your tracker and to notice when it's nearby, so only one of them records while they're together.",
        onGrant,
    )
}

@Composable
private fun Message(title: String, body: String, action: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Bluetooth, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            ClayButton(onClick = action) { Text("Continue") }
        }
    }
}

// ── Pairing ─────────────────────────────────────────────────────────────────

@Composable
private fun PairingContent(viewModel: TrackerViewModel) {
    val link = viewModel.link
    val candidates by link.pairCandidates.collectAsState()
    val bonding by link.bonding.collectAsState()
    val error by link.lastError.collectAsState()
    DisposableEffect(Unit) {
        link.startPairScan()
        onDispose { link.stopPairScan() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClayCard {
            ClaySectionHeader("Pair over Bluetooth", Icons.Rounded.BluetoothSearching)
            Spacer(Modifier.height(8.dp))
            Text(
                "On the tracker: Menu → Bluetooth → Pair a phone. It appears below; tap it, then type the 6-digit code the tracker shows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val ready = candidates.filter { it.pairing }
        if (ready.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Looking for a tracker in pairing mode…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        ready.forEach { adv -> CandidateRow(adv, bonding) { link.pair(adv) } }
        val other = candidates.filterNot { it.pairing }
        if (other.isNotEmpty()) {
            Text("Nearby, not in pairing mode: " + other.joinToString { it.name ?: it.address },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun CandidateRow(adv: TrackerAdvert, bonding: Boolean, onPair: () -> Unit) {
    ClayCard(onClick = if (bonding) null else onPair) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Watch, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(adv.name ?: "Roamly Tracker", fontWeight = FontWeight.SemiBold)
                Text("${adv.address} · ${adv.rssi} dBm" + (adv.battery?.let { " · $it%" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (bonding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Pair", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Management ──────────────────────────────────────────────────────────────

@Composable
private fun ManageContent(viewModel: TrackerViewModel) {
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Connection + who's recording
        ClayCard {
            ClaySectionHeader(name ?: "Roamly Tracker", Icons.Rounded.Watch, trailing = {
                Text(connectionLabel(state, near), style = MaterialTheme.typography.labelMedium,
                    color = if (state == LinkState.CONNECTED) Clay.colors.success else MaterialTheme.colorScheme.onSurfaceVariant)
            })
            Spacer(Modifier.height(8.dp))
            val batt = status?.optJSONObject("batt")
            val pct = batt?.optInt("pct", -1)?.takeIf { it >= 0 } ?: advert?.battery
            Text(
                listOfNotNull(
                    pct?.let { "Battery $it%" },
                    batt?.optString("state")?.takeIf { it.isNotEmpty() && it != "unknown" },
                    batt?.optDouble("hours")?.takeIf { !it.isNaN() }?.let { h -> if (batt.optString("state") == "charging") "full in ${fmtHours(h)}" else "${fmtHours(h)} left" },
                ).joinToString(" · ").ifEmpty { "Waiting for the tracker…" },
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(handoffLine(near, phonePause, advert, status), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state != LinkState.CONNECTED) {
                Spacer(Modifier.height(6.dp))
                Text("The tracker wakes its radio about once a minute, so connecting can take up to a minute. Keep this screen open.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (prompts.isNotEmpty()) PromptsCard(prompts, viewModel)

        if (state == LinkState.CONNECTED) {
            ActionsCard(status, reply, viewModel)
            status?.let { DiagnosticsCard(it) }
            settings?.let { SettingsCard(it, viewModel) }
            ConsoleCard(viewModel)
        }
        UnpairCard(viewModel)
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
private fun PromptsCard(prompts: List<NotificationItem>, viewModel: TrackerViewModel) {
    ClayCard {
        ClaySectionHeader("Which one was with you?", Icons.Rounded.Handshake)
        prompts.forEach { item ->
            Spacer(Modifier.height(10.dp))
            val a = item.devices.getOrNull(0)
            val b = item.devices.getOrNull(1)
            if (a == null || b == null) return@forEach
            Text(fmtRange(item.start, item.end), fontWeight = FontWeight.SemiBold)
            Text(
                if (item.overlap == "apart") "${a.name} and ${b.name} were in different places, so both recorded."
                else "${a.name} and ${b.name} recorded the same track.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.overlap == "apart") {
                    ClayButton(onClick = { viewModel.hide(item, b.id) }) { Text("I had ${a.name}") }
                    ClayButton(onClick = { viewModel.hide(item, a.id) }) { Text("I had ${b.name}") }
                } else {
                    ClayButton(onClick = { viewModel.hide(item, a.id) }) { Text("Hide ${a.name}") }
                    ClayButton(onClick = { viewModel.hide(item, b.id) }) { Text("Hide ${b.name}") }
                }
                ClayOutlinedButton(onClick = { viewModel.keepBoth(item) }) { Text("Keep both") }
            }
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
private fun ActionsCard(status: JSONObject?, reply: String?, viewModel: TrackerViewModel) {
    val link = viewModel.link
    val recording = status?.optJSONObject("rec")?.optBoolean("user", true) ?: true
    var confirmOff by remember { mutableStateOf(false) }
    ClayCard {
        ClaySectionHeader("Actions", Icons.Rounded.CloudSync)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClayButton(onClick = { link.command("sync") }) { Text("Sync now") }
            ClayOutlinedButton(onClick = { link.command(if (recording) "pause" else "resume") }) {
                Text(if (recording) "Pause recording" else "Resume recording")
            }
            ClayOutlinedButton(onClick = { link.command("restart") }) { Text("Restart") }
            ClayOutlinedButton(onClick = { confirmOff = true }) { Text("Power off") }
        }
        reply?.let { r ->
            val msg = try { JSONObject(r).optString("msg") } catch (_: Exception) { r }
            if (msg.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(msg, style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Power off the tracker?") },
            text = { Text("It stops recording. To turn it back on, hold its middle button for 3 seconds.") },
            confirmButton = { TextButton(onClick = { confirmOff = false; link.command("poweroff") }) { Text("Power off") } },
            dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DiagnosticsCard(s: JSONObject) {
    val gps = s.optJSONObject("gps") ?: JSONObject()
    val store = s.optJSONObject("store") ?: JSONObject()
    val sync = s.optJSONObject("sync") ?: JSONObject()
    val rec = s.optJSONObject("rec") ?: JSONObject()
    val batt = s.optJSONObject("batt") ?: JSONObject()
    ClayCard {
        ClaySectionHeader("Diagnostics", Icons.Rounded.GpsFixed)
        Spacer(Modifier.height(8.dp))
        val fix = when (gps.optInt("fix")) { 3 -> "3D fix"; 2 -> "2D fix"; else -> "no fix" }
        Row2("GPS", "$fix · ${gps.optInt("sats")} used / ${gps.optInt("view")} seen " +
            "(GPS ${gps.optInt("gps")} · BeiDou ${gps.optInt("bds")} · GLONASS ${gps.optInt("glo")})")
        if (gps.has("acc")) Row2("Accuracy", "~${gps.optDouble("acc")} m · HDOP ${gps.optDouble("hdop")}")
        if (gps.has("lat")) Row2("Position", "%.5f, %.5f".format(gps.optDouble("lat"), gps.optDouble("lon")))
        if (gps.has("age")) Row2("Last fix", "${gps.optInt("age")} s ago · signal ${gps.optInt("snr")} dB-Hz")
        Row2("GPS awake", "${gps.optDouble("duty")} % · " + (if (gps.optBoolean("asleep")) "standby now" else "awake now"))
        Row2("Recording", (if (rec.optBoolean("adaptive")) "Adaptive (${rec.optString("motion")}), " else "") +
            "every ${rec.optInt("interval")} s · ${rec.optInt("recorded")} stored, ${rec.optInt("missed")} missed this boot")
        Row2("On tracker", "${store.optInt("pending")} points waiting · ${store.optInt("free_kb")} KB free" +
            (if (store.optInt("held") > 0) " · ${store.optInt("held")} refused" else ""))
        Row2("Sync", sync.optString("msg") + (if (!sync.optBoolean("auto")) " · auto-sync off" else ""))
        if (batt.has("v")) Row2("Battery", "${batt.optDouble("v")} V · ${batt.optString("state")}" +
            (if (batt.optBoolean("measured")) " · measured rate" else " · estimated"))
        Row2("Firmware", "${s.optString("fw")} · GPS ${gps.optString("ver")}")
        Row2("Uptime", "${s.optLong("up") / 3600} h · ${s.optInt("boots")} boots · ${s.optInt("crashes")} crashes · last reset ${s.optString("last_reset")}")
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
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
private fun SettingsCard(settings: JSONObject, viewModel: TrackerViewModel) {
    ClayCard {
        ClaySectionHeader("Tracker settings", Icons.Rounded.Tune)
        Spacer(Modifier.height(4.dp))
        SETTINGS.forEach { s -> SettingRow(s, settings.opt(s.key)) { v -> viewModel.link.setSetting(s.key, v) } }
    }
}

@Composable
private fun SettingRow(s: TrackerSetting, current: Any?, onPick: (Any) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val currentLabel = s.options.firstOrNull { it.first.toString() == current?.toString() }?.second ?: current?.toString() ?: "—"
    Box {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { open = true }) { Text(currentLabel) }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            s.options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onPick(value) })
            }
        }
    }
}

@Composable
private fun ConsoleCard(viewModel: TrackerViewModel) {
    val out by viewModel.link.console.collectAsState()
    var line by remember { mutableStateOf("status") }
    ClayCard {
        ClaySectionHeader("Console", Icons.Rounded.Terminal)
        Spacer(Modifier.height(6.dp))
        Text("The tracker's serial console over Bluetooth — try status, dump 20 or help.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = line, onValueChange = { line = it }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { viewModel.link.runConsole(line) }),
            )
            Spacer(Modifier.width(8.dp))
            ClayButton(onClick = { viewModel.link.runConsole(line) }) { Text("Run") }
        }
        if (out.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(out, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

@Composable
private fun UnpairCard(viewModel: TrackerViewModel) {
    var confirm by remember { mutableStateOf(false) }
    ClayCard {
        ClaySectionHeader("Pairing", Icons.Rounded.Info)
        Spacer(Modifier.height(8.dp))
        Text("Unpairing forgets the tracker on this phone and stops the hand-off; both then record all the time.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        ClayOutlinedButton(onClick = { confirm = true }) { Text("Unpair tracker") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Unpair the tracker?") },
            text = { Text("You can pair again from Settings → Roamly Tracker.") },
            confirmButton = { TextButton(onClick = { confirm = false; viewModel.link.unpair() }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
