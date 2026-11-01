package com.sajat.hifiplayer

import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RadioStation(
    val name: String,
    val genre: String,
    val streamUrl: String,
    val isLossless: Boolean = false
)

class MainActivity : ComponentActivity() {
    private lateinit var exoPlayer: ExoPlayer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .build()
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        try {
            equalizer = Equalizer(1000, 0).apply { enabled = true }
            bassBoost = BassBoost(1000, 0).apply { enabled = true }
        } catch (e: Exception) {
            exoPlayer.addListener(object : androidx.media3.common.Player.Listener {
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    try {
                        equalizer = Equalizer(1000, audioSessionId).apply { enabled = true }
                        bassBoost = BassBoost(1000, audioSessionId).apply { enabled = true }
                    } catch (_: Exception) {}
                }
            })
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0A0A0A),
                    surface = Color(0xFF161616),
                    primary = Color(0xFF00E676)
                )
            ) {
                HiFiAppUI(exoPlayer, equalizer, bassBoost, this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        equalizer?.release()
        bassBoost?.release()
        exoPlayer.release()
    }
}

@Composable
fun HiFiAppUI(
    player: ExoPlayer,
    eq: Equalizer?,
    bass: BassBoost?,
    context: Context
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentPlayingTitle by remember { mutableStateOf("Válassz egy adót!") }
    var isPlaying by remember { mutableStateOf(false) }

    val tabs = listOf("📻 Rádió", "▶️ YT Music", "🟢 Spotify", "🎚️ Hi-Fi EQ")

    Scaffold(
        bottomBar = {
            Column {
                Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("32-BIT FLOAT HI-RES ENGINE", color = Color(0xFF00E676), fontSize = 10.sp)
                            Text(currentPlayingTitle, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                        Button(onClick = {
                            if (player.isPlaying) {
                                player.pause()
                                isPlaying = false
                            } else {
                                player.play()
                                isPlaying = true
                            }
                        }) {
                            Text(if (isPlaying) "Szünet" else "Indítás")
                        }
                    }
                }
                NavigationBar(containerColor = Color(0xFF121212)) {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            label = { Text(title, fontSize = 11.sp) },
                            icon = {}
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0A0A0A))) {
            when (selectedTab) {
                0 -> RadioTab(
                    context = context,
                    onPlay = { station ->
                        player.setMediaItem(MediaItem.fromUri(station.streamUrl))
                        player.prepare()
                        player.play()
                        currentPlayingTitle = station.name
                        isPlaying = true
                    }
                )
                1 -> YouTubeMusicTab()
                2 -> SpotifyEmbeddedTab(context)
                3 -> EqualizerTab(eq, bass)
            }
        }
    }
}

suspend fun queryWorldRadios(endpoint: String): List<RadioStation> = withContext(Dispatchers.IO) {
    val list = mutableListOf<RadioStation>()
    val servers = listOf("de1.api.radio-browser.info", "at1.api.radio-browser.info", "nl1.api.radio-browser.info")
    for (server in servers) {
        try {
            val url = URL("https://$server/json/stations/$endpoint")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "HiFiPlayer/2.0")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "").trim()
                    val streamUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val codec = obj.optString("codec", "MP3").uppercase()
                    val bitrate = obj.optInt("bitrate", 0)
                    val country = obj.optString("countrycode", "")
                    if (name.isNotEmpty() && streamUrl.isNotEmpty()) {
                        val isFlac = codec.contains("FLAC")
                        val info = buildString {
                            if (country.isNotEmpty()) append("$country • ")
                            append(codec)
                            if (bitrate > 0) append(" • $bitrate kbps")
                        }
                        list.add(RadioStation(name, info, streamUrl, isFlac))
                    }
                }
                break
            }
        } catch (_: Exception) {}
    }
    list
}

fun loadSavedFavorites(context: Context): List<RadioStation> {
    val prefs = context.getSharedPreferences("hifi_favs", Context.MODE_PRIVATE)
    val raw = prefs.getString("fav_json", null)
    if (raw.isNullOrEmpty()) {
        return listOf(
            RadioStation("Radio Paradise (Main Mix)", "US • FLAC • 1411 kbps", "https://stream.radioparadise.com/flac", true),
            RadioStation("Radio Paradise (Rock Mix)", "US • FLAC • 1411 kbps", "https://stream.radioparadise.com/rock-flac", true),
            RadioStation("Mother Earth Radio", "DE • FLAC • 96kHz/24bit", "https://motherearth.streamserver24.com/listen/motherearth/motherearth.flac", true),
            RadioStation("Retro Rádió", "HU • MP3 • 192 kbps", "https://icast.connectmedia.hu/5001/live.mp3"),
            RadioStation("Rádió 1", "HU • MP3 • 192 kbps", "https://icast.connectmedia.hu/5201/live.mp3"),
            RadioStation("Petőfi Rádió", "HU • AAC+", "https://icast.connectmedia.hu/4738/mr2.mp3"),
            RadioStation("Jazzy Rádió", "HU • MP3 • 192 kbps", "https://s04.diazol.hu:9502/live.mp3")
        )
    }
    val list = mutableListOf<RadioStation>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(RadioStation(o.getString("n"), o.getString("g"), o.getString("u"), o.optBoolean("l", false)))
        }
    } catch (_: Exception) {}
    return list
}

fun saveFavorites(context: Context, list: List<RadioStation>) {
    val arr = JSONArray()
    list.forEach {
        val o = JSONObject().apply {
            put("n", it.name)
            put("g", it.genre)
            put("u", it.streamUrl)
            put("l", it.isLossless)
        }
        arr.put(o)
    }
    context.getSharedPreferences("hifi_favs", Context.MODE_PRIVATE)
        .edit().putString("fav_json", arr.toString()).apply()
}

@Composable
fun RadioTab(context: Context, onPlay: (RadioStation) -> Unit) {
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(loadSavedFavorites(context)) }
    var displayedStations by remember { mutableStateOf(favorites) }
    var isLoading by remember { mutableStateOf(false) }
    var currentCategory by remember { mutableStateOf("⭐ Kedvencek") }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Világrádió Kereső (40.000+ adó)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Rádió neve, város, stílus...", fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (searchQuery.isNotBlank()) {
                        isLoading = true
                        currentCategory = "🔍 Találatok: $searchQuery"
                        scope.launch {
                            val q = URLEncoder.encode(searchQuery.trim(), "UTF-8")
                            displayedStations = queryWorldRadios("search?name=$q&limit=60&hidebroken=true&order=clickcount&reverse=true")
                            isLoading = false
                        }
                    }
                }
            ) {
                Text("Keres")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                currentCategory = "⭐ Kedvencek"
                displayedStations = favorites
            }) { Text("⭐ Kedvencek") }

            OutlinedButton(onClick = {
                isLoading = true
                currentCategory = "🇭🇺 Magyar Rádiók"
                scope.launch {
                    displayedStations = queryWorldRadios("search?countrycode=HU&limit=80&hidebroken=true&order=clickcount&reverse=true")
                    isLoading = false
                }
            }) { Text("🇭🇺 Magyar adók") }

            OutlinedButton(onClick = {
                isLoading = true
                currentCategory = "💎 Hi-Res FLAC Rádiók"
                scope.launch {
                    displayedStations = queryWorldRadios("search?codec=FLAC&limit=60&hidebroken=true&order=clickcount&reverse=true")
                    isLoading = false
                }
            }) { Text("💎 Hi-Res FLAC") }

            OutlinedButton(onClick = {
                isLoading = true
                currentCategory = "🌍 Világ Top 50"
                scope.launch {
                    displayedStations = queryWorldRadios("topclick/50")
                    isLoading = false
                }
            }) { Text("🌍 Világ Top 50") }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(currentCategory, color = Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF00E676))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayedStations) { station ->
                    val isFav = favorites.any { it.streamUrl == station.streamUrl }
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { onPlay(station) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(station.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(station.genre, color = if (station.isLossless) Color(0xFF00E676) else Color.Gray, fontSize = 12.sp)
                            }
                            if (station.isLossless) {
                                Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 8.dp)) {
                                    Text("FLAC", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(4.dp))
                                }
                            }
                            Text(
                                text = if (isFav) "⭐" else "☆",
                                fontSize = 22.sp,
                                color = if (isFav) Color(0xFFFFD700) else Color.Gray,
                                modifier = Modifier.padding(start = 8.dp).clickable {
                                    favorites = if (isFav) {
                                        favorites.filterNot { it.streamUrl == station.streamUrl }
                                    } else {
                                        favorites + station
                                    }
                                    saveFavorites(context, favorites)
                                    if (currentCategory == "⭐ Kedvencek") {
                                        displayedStations = favorites
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeMusicTab() {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                loadUrl("https://music.youtube.com")
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun SpotifyEmbeddedTab(context: Context) {
    var useBuiltInWeb by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Felső vezérlősáv: Belső lejátszó + Gyorsgombok
        Surface(color = Color(0xFF141414), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { useBuiltInWeb = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (useBuiltInWeb) Color(0xFF00E676).copy(alpha = 0.2f) else Color.Transparent
                        )
                    ) {
                        Text("🌐 Belső Spotify", fontSize = 12.sp, color = Color.White)
                    }
                    OutlinedButton(
                        onClick = { useBuiltInWeb = false },
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (!useBuiltInWeb) Color(0xFF00E676).copy(alpha = 0.2f) else Color.Transparent
                        )
                    ) {
                        Text("📱 Távirányító", fontSize = 12.sp, color = Color.White)
                    }
                }
            }
        }

        if (useBuiltInWeb) {
            // BEÉ
