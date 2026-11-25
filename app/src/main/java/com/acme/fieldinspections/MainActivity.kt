@file:OptIn(ExperimentalMaterial3Api::class)

package com.acme.fieldinspections

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

// ── Data ─────────────────────────────────────────────────────────────────────

data class Site(val id: String, val name: String, val address: String, val kind: String, val last: String)

val SITES = listOf(
    Site("lis-012", "Substation 12", "Rua do Açúcar 88, Lisbon", "Electrical", "Inspected 14 days ago"),
    Site("prt-004", "Water treatment plant", "Estrada da Circunvalação, Porto", "Water", "Inspected 32 days ago"),
    Site("lis-207", "Data center, hall B", "Parque das Nações, Lisbon", "Facilities", "Inspected 6 days ago"),
    Site("fao-031", "Pumping station 3", "Estrada de Faro 12, Faro", "Water", "Overdue: 61 days"),
)

val CHECKS = listOf(
    "Access and signage",
    "Fire safety equipment",
    "Electrical panels",
    "Leaks or water damage",
    "Structural damage",
)

const val DEFAULT_QUEUE_URL = "http://10.0.2.2:3105"

// ── Colors (dark, like the ODC Portal) ───────────────────────────────────────

val Page = Color(0xFF181A1F)
val CardBg = Color(0xFF24262C)
val Border = Color(0xFF3A3F48)
val Text1 = Color(0xFFF9FAFB)
val Text2 = Color(0xFFB3BAC4)
val Indigo = Color(0xFF5252F2)
val IndigoT = Color(0xFFA2A5FE)
val Green = Color(0xFF4ED795)
val Amber = Color(0xFFF4B736)
val Red = Color(0xFFF56A5F)

private val Scheme = darkColorScheme(
    primary = Indigo, onPrimary = Text1, background = Page, surface = Page, onSurface = Text1,
    onBackground = Text1, surfaceVariant = CardBg, onSurfaceVariant = Text2, outline = Border,
)

// ── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("queue", MODE_PRIVATE)
        applyExtras(intent, prefs)
        setContent { MaterialTheme(colorScheme = Scheme) { App(prefs) } }
    }

    /** `adb shell am start -n com.acme.fieldinspections/.MainActivity --es queueUrl … --es apiKey …` configures the queue. */
    private fun applyExtras(intent: Intent?, prefs: SharedPreferences) {
        val url = intent?.getStringExtra("queueUrl")
        val key = intent?.getStringExtra("apiKey")
        if (url.isNullOrBlank() && key.isNullOrBlank()) return
        prefs.edit().apply {
            if (!url.isNullOrBlank()) putString("url", url.trim())
            if (!key.isNullOrBlank()) putString("key", key.trim())
        }.apply()
    }
}

// ── Queue client ─────────────────────────────────────────────────────────────

/** Publishes one JSON message to the queue's REST API: POST {url}/messages. Returns the message's sequence number. */
suspend fun publish(url: String, key: String, payload: JSONObject): Long = withContext(Dispatchers.IO) {
    val c = URL(url.trimEnd('/') + "/messages").openConnection() as HttpURLConnection
    try {
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        val body = JSONObject().put("body", payload).put("contentType", "json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).let { it.optString("message", it.optString("error", text)) } }.getOrDefault(text)
            throw IllegalStateException("The queue answered $code: ${msg.take(160)}")
        }
        JSONObject(text).optLong("seq", -1)
    } finally {
        c.disconnect()
    }
}

// ── UI ───────────────────────────────────────────────────────────────────────

sealed interface Screen {
    data object Sites : Screen
    data class Inspect(val site: Site) : Screen
    data object Setup : Screen
}

@Composable
fun App(prefs: SharedPreferences) {
    var url by remember { mutableStateOf(prefs.getString("url", DEFAULT_QUEUE_URL) ?: DEFAULT_QUEUE_URL) }
    var key by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var screen by remember { mutableStateOf<Screen>(Screen.Sites) }
    val sent = remember { mutableStateListOf<String>() }

    BackHandler(enabled = screen != Screen.Sites) { screen = Screen.Sites }

    Box(Modifier.fillMaxSize().background(Page)) {
        when (val s = screen) {
            Screen.Sites -> SitesScreen(connected = key.isNotBlank(), sent = sent, onOpen = { screen = Screen.Inspect(it) }, onSetup = { screen = Screen.Setup })
            is Screen.Inspect -> InspectScreen(s.site, url, key, onBack = { screen = Screen.Sites }, onSent = { sent.add(s.site.id) }, onSetup = { screen = Screen.Setup })
            Screen.Setup -> SetupScreen(url, key, onBack = { screen = Screen.Sites }) { u, k ->
                url = u; key = k
                prefs.edit().putString("url", u).putString("key", k).apply()
                screen = Screen.Sites
            }
        }
    }
}

@Composable
fun Bar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Text1)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Text2)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Text1) }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Page),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))

@Composable
fun SitesScreen(connected: Boolean, sent: List<String>, onOpen: (Site) -> Unit, onSetup: () -> Unit) {
    Scaffold(
        containerColor = Page,
        topBar = {
            Bar("Field Inspections", "Acme Field Services") {
                IconButton(onClick = onSetup) { Icon(Icons.Filled.Settings, contentDescription = "Queue setup", tint = Text2) }
            }
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = onSetup).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(if (connected) Green else Amber)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (connected) "Sends inspections to the support-tickets queue" else "Not connected to a queue yet. Tap to set it up.",
                        color = Text2, fontSize = 13.sp,
                    )
                }
            }
            item { Text("Today's sites", color = IndigoT, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            items(SITES) { site ->
                Panel(Modifier.clickable { onOpen(site) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Place, contentDescription = null, tint = IndigoT)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(site.name, color = Text1, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(site.address, color = Text2, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("${site.kind} · ${site.last}", color = if (site.last.startsWith("Overdue")) Amber else Text2, fontSize = 12.sp)
                        }
                        if (site.id in sent) Icon(Icons.Filled.CheckCircle, contentDescription = "Sent", tint = Green)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun InspectScreen(site: Site, url: String, key: String, onBack: () -> Unit, onSent: () -> Unit, onSetup: () -> Unit) {
    val issues = remember(site.id) { mutableStateListOf<String>() }
    var severity by remember(site.id) { mutableStateOf("Low") }
    var notes by remember(site.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember(site.id) { mutableStateOf<Pair<Boolean, String>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(containerColor = Page, topBar = { Bar(site.name, site.address, onBack) }) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel {
                Text("Checklist", color = IndigoT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                CHECKS.forEach { check ->
                    val issue = check in issues
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(check, color = Text1, modifier = Modifier.weight(1f), fontSize = 15.sp)
                        FilterChip(selected = !issue, onClick = { issues.remove(check) }, label = { Text("OK") })
                        Spacer(Modifier.width(6.dp))
                        FilterChip(selected = issue, onClick = { if (!issue) issues.add(check) }, label = { Text("Issue") })
                    }
                }
            }
            Panel {
                Text("Severity", color = IndigoT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach { s ->
                        FilterChip(selected = severity == s, onClick = { severity = s }, label = { Text(s) })
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(), minLines = 3,
                )
            }
            Button(
                onClick = {
                    if (key.isBlank()) { onSetup(); return@Button }
                    busy = true; result = null
                    val payload = JSONObject()
                        .put("type", "inspection")
                        .put("siteId", site.id)
                        .put("site", site.name)
                        .put("severity", severity.lowercase())
                        .put("issues", JSONArray(issues.toList()))
                        .put("notes", notes)
                        .put("submittedFrom", "Field Inspections (Android)")
                        .put("submittedAt", Instant.now().toString())
                    scope.launch {
                        result = try {
                            val seq = publish(url, key, payload)
                            onSent()
                            true to "Sent to support-tickets · message $seq"
                        } catch (e: Exception) {
                            false to (e.message ?: "Couldn't reach the queue.")
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Text1, strokeWidth = 2.dp)
                else Text(if (key.isBlank()) "Set up the queue first" else "Submit inspection", fontSize = 16.sp)
            }
            result?.let { (ok, msg) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null, tint = if (ok) Green else Red)
                    Spacer(Modifier.width(8.dp))
                    Text(msg, color = if (ok) Green else Red, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SetupScreen(url0: String, key0: String, onBack: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember { mutableStateOf(url0) }
    var key by remember { mutableStateOf(key0) }
    Scaffold(containerColor = Page, topBar = { Bar("Queue setup", "Where inspections are sent", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Queue URL") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text(
                "Demo only. In ODC the app would call the queue with the signed-in user's identity, never a stored key.",
                color = Text2, fontSize = 13.sp,
            )
            Button(onClick = { onSave(url.trim(), key.trim()) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Indigo)) {
                Text("Save")
            }
        }
    }
}
