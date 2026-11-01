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

data class RadioStation(val name: String, val genre: String, val streamUrl: String, val isLossless: Boolean = false)

class MainActivity : ComponentActivity() {
    private lateinit var exoPlayer: ExoPlayer
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(c: Context, f: Boolean, p: Boolean): AudioSink {
                return DefaultAudioSink.Builder(c).setEnableFloatOutput(true).build()
            }
        }
        val attr = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        exoPlayer = ExoPlayer.Builder(this, renderersFactory).setAudioAttributes(attr, true).setHandleAudioBecomingNoisy(true).build()

        try {
            equalizer = Equalizer(1000, 0).apply { enabled = true }
            bassBoost = BassBoost(1000, 0).apply { enabled = true }
        } catch (_: Exception) {}

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0A0A0A), surface = Color(0xFF161616), primary = Color(0xFF00E676))) {
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
fun HiFiAppUI(player: ExoPlayer, eq: Equalizer?, bass: BassBoost?, context: Context) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("Válassz egy adót!") }
    var isPlaying by remember { mutableStateOf(false) }
    val tabs = listOf("📻 Rádió", "▶️ YT", "🟢 Spotify", "🎚️ EQ")

    Scaffold(
        bottomBar = {
            Column {
                Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("32-BIT HI-RES ENGINE", color = Color(0xFF00E676), fontSize = 10.sp)
                            Text(currentTitle, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 13.sp)
                        }
                        Button(onClick = {
                            if (player.isPlaying) { player.pause(); isPlaying = false } else { player.play(); isPlaying = true }
                        }) {
                            Text(if (isPlaying) "Szünet" else "Indítás", fontSize = 12.sp)
                        }
                    }
                }
                NavigationBar(containerColor = Color(0xFF121212)) {
                    tabs.forEachIndexed { index, title ->
                        NavigationBarItem(selected = selectedTab == index, onClick = { selectedTab = index }, label = { Text(title, fontSize = 11.sp) }, icon = {})
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF0A0A0A))) {
            when (selectedTab) {
                0 -> RadioTab(context) { station ->
                    player.setMediaItem(MediaItem.fromUri(station.streamUrl))
                    player.prepare()
                    player.play()
                    currentTitle = station.name
                    isPlaying = true
                }
                1 -> YouTubeMusicTab()
                2 -> SpotifyEmbeddedTab()
                3 -> EqualizerTab(eq, bass)
            }
        }
    }
}

suspend fun queryRadios(endpoint: String): List<RadioStation> = withContext(Dispatchers.IO) {
    val list = mutableListOf<RadioStation>()
    try {
        val url = URL("https://de1.api.radio-browser.info/json/stations/$endpoint")
        val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 4000 }
        if (conn.responseCode == 200) {
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "").trim()
                val sUrl = o.optString("url_resolved", o.optString("url", "")).trim()
                if (name.isNotEmpty() && sUrl.isNotEmpty()) {
                    list.add(RadioStation(name, o.optString("codec", "MP3"), sUrl, o.optString("codec").contains("FLAC", true)))
                }
            }
        }
    } catch (_: Exception) {}
    list
}

@Composable
fun RadioTab(context: Context, onPlay: (RadioStation) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var stations by remember { mutableStateOf(listOf(
        RadioStation("Radio Paradise (FLAC)", "US • FLAC", "https://stream.radioparadise.com/flac", true),
        RadioStation("Retro Rádió", "HU • MP3", "https://icast.connectmedia.hu/5001/live.mp3"),
        RadioStation("Rádió 1", "HU • MP3", "https://icast.connectmedia.hu/5201/live.mp3"),
        RadioStation("Petőfi Rádió", "HU • AAC", "https://icast.connectmedia.hu/4738/mr2.mp3")
    )) }
    var loading by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Világrádió Kereső", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Keresés (pl. Rock, Danubius)...") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (query.isNotBlank()) {
                    loading = true
                    scope.launch {
                        stations = queryRadios("search?name=${URLEncoder.encode(query, "UTF-8")}&limit=50&hidebroken=true")
                        loading = false
                    }
                }
            }) { Text("Keres") }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                loading = true
                scope.launch { stations = queryRadios("search?countrycode=HU&limit=50&hidebroken=true"); loading = false }
            }) { Text("🇭🇺 Magyar adók") }
            Button(onClick = {
                loading = true
                scope.launch { stations = queryRadios("search?codec=FLAC&limit=30&hidebroken=true"); loading = false }
            }) { Text("💎 FLAC") }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF00E676)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(stations) { s ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlay(s) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(s.genre, color = Color.Gray, fontSize = 12.sp)
                            }
                            if (s.isLossless) {
                                Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp)) {
                                    Text("FLAC", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(3.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun YouTubeMusicTab() {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            loadUrl("https://music.youtube.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun SpotifyEmbeddedTab() {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) }
            }
            webViewClient = WebViewClient()
            loadUrl("https://open.spotify.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun EqualizerTab(eq: Equalizer?, bass: BassBoost?) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Hi-Fi Master Equalizer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        if (eq == null) {
            Text("Indíts el egy rádiót az EQ aktiválásához!", color = Color.Gray)
            return@Column
        }
        val minEq = eq.bandLevelRange[0]
        val maxEq = eq.bandLevelRange[1]
        for (i in 0 until eq.numberOfBands) {
            val bIdx = i.toShort()
            val freq = eq.getCenterFreq(bIdx) / 1000
            var valState by remember { mutableFloatStateOf(eq.getBandLevel(bIdx).toFloat()) }
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("$freq Hz", color = Color.White, fontSize = 13.sp)
                    Text("${(valState / 100).toInt()} dB", color = Color(0xFF00E676), fontSize = 13.sp)
                }
                Slider(value = valState, onValueChange = { valState = it; eq.setBandLevel(bIdx, it.toInt().toShort()) }, valueRange = minEq.toFloat()..maxEq.toFloat())
            }
        }
    }
}
