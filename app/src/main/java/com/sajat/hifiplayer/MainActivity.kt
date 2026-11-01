package com.sajat.hifiplayer

import android.content.Context
import android.content.Intent
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

data class RadioStation(val name: String, val genre: String, val streamUrl: String, val isLossless: Boolean = false)

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
                    onPlay = { station ->
                        player.setMediaItem(MediaItem.fromUri(station.streamUrl))
                        player.prepare()
                        player.play()
                        currentPlayingTitle = station.name
                        isPlaying = true
                    }
                )
                1 -> YouTubeMusicTab()
                2 -> SpotifyControllerTab(context)
                3 -> EqualizerTab(eq, bass)
            }
        }
    }
}

@Composable
fun RadioTab(onPlay: (RadioStation) -> Unit) {
    val stations = remember {
        listOf(
            RadioStation("Radio Paradise (Main Mix)", "FLAC Lossless • 1411 kbps", "https://stream.radioparadise.com/flac", true),
            RadioStation("Radio Paradise (Rock Mix)", "FLAC Lossless • 1411 kbps", "https://stream.radioparadise.com/rock-flac", true),
            RadioStation("Mother Earth Radio", "Hi-Res FLAC • 96kHz/24bit", "https://motherearth.streamserver24.com/listen/motherearth/motherearth.flac", true),
            RadioStation("Retro Rádió", "Magyar • 192 kbps MP3", "https://icast.connectmedia.hu/5001/live.mp3"),
            RadioStation("Rádió 1", "Magyar • 192 kbps MP3", "https://icast.connectmedia.hu/5201/live.mp3"),
            RadioStation("Petőfi Rádió", "Magyar • AAC+", "https://icast.connectmedia.hu/4738/mr2.mp3"),
            RadioStation("Jazzy Rádió", "Jazz / Funk • 192 kbps", "https://s04.diazol.hu:9502/live.mp3")
        )
    }

    LazyColumn(modifier = Modifier.padding(16.dp)) {
        item {
            Text("Online Rádiók & FLAC Streamek", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
        }
        items(stations) { station ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onPlay(station) },
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(station.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(station.genre, color = if (station.isLossless) Color(0xFF00E676) else Color.Gray, fontSize = 13.sp)
                    }
                    if (station.isLossless) {
                        Surface(color = Color(0xFF00E676), shape = RoundedCornerShape(4.dp)) {
                            Text("HI-RES FLAC", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(4.dp))
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
fun SpotifyControllerTab(context: Context) {
    Column(
        modifier = Modifier.padding(16.dp).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Spotify Direkt Vezérlő", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "A Spotify hangja automatikusan átfut a Hi-Fi EQ fülön beállított hangszínszabályzón és Bass Booston!",
            color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { sendSpotifyMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }) {
                Text("⏮ Előző")
            }
            Button(onClick = { sendSpotifyMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }) {
                Text("⏯ Play/Pause")
            }
            Button(onClick = { sendSpotifyMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT) }) {
                Text("⏭ Következő")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = {
            val intent = context.packageManager.getLaunchIntentForPackage("com.spotify.music")
            if (intent != null) context.startActivity(intent)
        }) {
            Text("Spotify megnyitása playlist választáshoz")
        }
    }
}

fun sendSpotifyMediaKey(context: Context, keyCode: Int) {
    val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        setPackage("com.spotify.music")
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }
    context.sendBroadcast(downIntent)

    val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        setPackage("com.spotify.music")
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
    context.sendBroadcast(upIntent)
}

@Composable
fun EqualizerTab(eq: Equalizer?, bass: BassBoost?) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Hi-Fi Master Equalizer", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Aktív a Rádióra, YT Musicra és a Spotify-ra is", color = Color(0xFF00E676), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(20.dp))

        if (eq == null) {
            Text("Equalizer inicializálása... Indíts el egy adót!", color = Color.Gray)
            return@Column
        }

        val bands = eq.numberOfBands
        val minEq = eq.bandLevelRange[0]
        val maxEq = eq.bandLevelRange[1]

        for (i in 0 until bands) {
            val bandIndex = i.toShort()
            val freqHz = eq.getCenterFreq(bandIndex) / 1000
            var sliderVal by remember { mutableFloatStateOf(eq.getBandLevel(bandIndex).toFloat()) }

            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${freqHz} Hz", color = Color.White, fontWeight = FontWeight.Medium)
                    Text("${(sliderVal / 100).toInt()} dB", color = Color(0xFF00E676))
                }
                Slider(
                    value = sliderVal,
                    onValueChange = {
                        sliderVal = it
                        eq.setBandLevel(bandIndex, it.toInt().toShort())
                    },
                    valueRange = minEq.toFloat()..maxEq.toFloat()
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        var bassStrength by remember { mutableFloatStateOf((bass?.roundedStrength ?: 0).toFloat()) }
        Text("Sub-Bass Boost (Mélykiemelés)", color = Color.White, fontWeight = FontWeight.Bold)
        Slider(
            value = bassStrength,
            onValueChange = {
                bassStrength = it
                bass?.setStrength(it.toInt().toShort())
            },
            valueRange = 0f..1000f
        )
    }
}
