package com.sajat.hifiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class RadioStation(val name: String, val genre: String, val streamUrl: String, val isLossless: Boolean = false)

object PlayerHolder {
    var exoPlayer: ExoPlayer? = null
}

// Háttérszolgáltatás, ami megakadályozza, hogy lezárt telefonnál megálljon a rádió
class RadioBackgroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "hifi_radio_channel"
        val channel = NotificationChannel(channelId, "Hi-Fi Rádió Háttérszolgáltatás", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)

        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Hi-Fi Player fut")
            .setContentText("A rádió a háttérben szól...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }
}

class AutoMediaService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = PlayerHolder.exoPlayer
        if (player != null) {
            mediaSession = MediaSession.Builder(this, player).build()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var exoPlayer: ExoPlayer

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
        PlayerHolder.exoPlayer = exoPlayer

        try {
            // Háttérszolgáltatások indítása a lezárás alatti zenelejátszáshoz és Android Autóhoz
            startForegroundService(Intent(this, RadioBackgroundService::class.java))
            startForegroundService(Intent(this, AutoMediaService::class.java))
        } catch (_: Exception) {}

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0A0A0A), surface = Color(0xFF161616), primary = Color(0xFF00E676))) {
                HiFiAppUI(exoPlayer, this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }
}

fun loadSavedFavorites(context: Context): List<RadioStation> {
    val prefs = context.getSharedPreferences("hifi_favs", Context.MODE_PRIVATE)
    val raw = prefs.getString("fav_json", null)
    if (raw.isNullOrEmpty()) {
        return listOf(
            RadioStation("BDPST Rock", "HU • Rock", "http://stream.bdpstrock.hu:8000/live"),
            RadioStation("Radio Paradise (FLAC)", "US • FLAC", "https://stream.radioparadise.com/flac", true),
            RadioStation("Retro Rádió", "HU • MP3", "https://icast.connectmedia.hu/5001/live.mp3"),
            RadioStation("Rádió 1", "HU • MP3", "https://icast.connectmedia.hu/5201/live.mp3"),
            RadioStation("Petőfi Rádió", "HU • AAC", "https://icast.connectmedia.hu/4738/mr2.mp3")
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
    context.getSharedPreferences("hifi_favs", Context.MODE_PRIVATE).edit().putString("fav_json", arr.toString()).apply()
}

@Composable
fun HiFiAppUI(player: ExoPlayer, context: Context) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var currentTitle by remember { mutableStateOf("Válassz egy adót!") }
    var currentMetadata by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    val tabs = listOf("📻 Rádió", "▶️ YT Music", "🟢 Spotify")

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)
                val title = mediaMetadata.title?.toString() ?: ""
                val artist = mediaMetadata.artist?.toString() ?: ""
                currentMetadata = if (artist.isNotEmpty() && title.isNotEmpty()) "$artist - $title" else title.ifEmpty { artist }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Scaffold(
        bottomBar = {
            Column {
                Surface(color = Color(0xFF1E1E1E), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(currentTitle, color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                            if (currentMetadata.isNotEmpty()) {
                                Text("🎵 $currentMetadata", color = Color.White, fontSize = 11.sp, maxLines = 1)
                            }
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
                    currentMetadata = ""
                    isPlaying = true
                }
                1 -> YouTubeMusicTab()
                2 -> SpotifyControllerTab(context)
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
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf(loadSavedFavorites(context)) }
    var stations by remember { mutableStateOf(favorites) }
    var loading by remember { mutableStateOf(false) }
    var modeTitle by remember { mutableStateOf("⭐ Kedvencek") }
    var showAddDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
        Text("Rádiólejátszó & Kedvencek", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Keresés a világban...") }, modifier = Modifier.weight(1f), singleLine = true)
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                if (query.isNotBlank()) {
                    loading = true
                    modeTitle = "🔍 Találatok: $query"
                    scope.launch {
                        stations = queryRadios("search?name=${URLEncoder.encode(query, "UTF-8")}&limit=50&hidebroken=true")
                        loading = false
                    }
                }
            }) { Text("Keres") }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { modeTitle = "⭐ Kedvencek"; stations = favorites }) { Text("⭐ Kedvencek") }
            Button(onClick = {
                loading = true
                modeTitle = "🇭🇺 Magyar adók"
                scope.launch { stations = queryRadios("search?countrycode=HU&limit=50&hidebroken=true"); loading = false }
            }) { Text("🇭🇺 Magyar") }
            Button(onClick = {
                loading = true
                modeTitle = "💎 FLAC"
                scope.launch { stations = queryRadios("search?codec=FLAC&limit=30&hidebroken=true"); loading = false }
            }) { Text("💎 FLAC") }
            Button(onClick = { showAddDialog = true }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676))) {
                Text("➕ Saját Link", color = Color.Black)
            }
        }

        if (showAddDialog) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(color = Color(0xFF1E1E1E), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Saját rádió webcím (URL) hozzáadása", color = Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(value = customName, onValueChange = { customName = it }, placeholder = { Text("Rádió neve (pl. BDPST Rock)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, placeholder = { Text("Stream URL (pl. http://...)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { showAddDialog = false }) { Text("Mégse", color = Color.Gray) }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            if (customName.isNotBlank() && customUrl.isNotBlank()) {
                                val newStation = RadioStation(customName.trim(), "Egyéni Stream", customUrl.trim(), customUrl.contains("flac", true))
                                favorites = favorites + newStation
                                saveFavorites(context, favorites)
                                stations = favorites
                                modeTitle = "⭐ Kedvencek"
                                customName = ""
                                customUrl = ""
                                showAddDialog = false
                            }
                        }) { Text("Mentés a Kedvencekbe") }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(modeTitle, color = Color(0xFF00E676), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))

        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF00E676)) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(stations) { s ->
                    val isFav = favorites.any { it.streamUrl == s.streamUrl }
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onPlay(s) }, colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A))) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(s.genre, color = Color.Gray, fontSize = 12.sp)
                            }
                            if (s.isLossless) {
                                Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(end = 8.dp)) {
                                    Text("FLAC", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(3.dp))
                                }
                            }
                            Text(
                                text = if (isFav) "⭐" else "☆",
                                fontSize = 22.sp,
                                color = if (isFav) Color(0xFFFFD700) else Color.Gray,
                                modifier = Modifier.clickable {
                                    favorites = if (isFav) favorites.filterNot { it.streamUrl == s.streamUrl } else favorites + s
                                    saveFavorites(context, favorites)
                                    if (modeTitle == "⭐ Kedvencek") stations = favorites
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
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(r: PermissionRequest) { r.grant(r.resources) }
            }
            webViewClient = WebViewClient()
            loadUrl("https://music.youtube.com")
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun SpotifyControllerTab(context: Context) {
    Column(modifier = Modifier.padding(16.dp).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Spotify Lejátszó Vezérlő", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Jelentkezz be a hivatalos Spotify appban, majd innen kényelmesen vezérelheted.", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { sendKey(context, "com.spotify.music", KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) { Text("⏮ Előző") }
            Button(onClick = { sendKey(context, "com.spotify.music", KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) { Text("⏯ Play/Pause") }
            Button(onClick = { sendKey(context, "com.spotify.music", KeyEvent.KEYCODE_MEDIA_NEXT) }) { Text("⏭ Következő") }
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            if (intent != null) context.startActivity(intent)
        }) {
            Text("Spotify Alkalmazás Megnyitása")
        }
    }
}

fun sendKey(context: Context, packageName: String, keyCode: Int) {
    try {
        val down = Intent(Intent.ACTION_MEDIA_BUTTON).apply { setPackage(packageName); putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)) }
        context.sendBroadcast(down)
        val up = Intent(Intent.ACTION_MEDIA_BUTTON).apply { setPackage(packageName); putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode)) }
        context.sendBroadcast(up)
    } catch (_: Exception) {}
}
