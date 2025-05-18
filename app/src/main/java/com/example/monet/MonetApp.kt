package com.example.monet

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaSession
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import android.content.Context

// Data Models
data class Playlist(
    val id: Long,
    val name: String,
    val sourceUrl: String? // YouTube playlist URL for imported playlists, null for manual
)

data class Song(
    val id: Long,
    val title: String,
    val url: String,
    val playlistId: Long,
    val isLocallyAdded: Boolean // True for manually added songs, false for imported
)

// Playback Service for Background Playback and Notification Widget
class PlaybackService : Service() {
    companion object {
        const val CHANNEL_ID = "MonetPlaybackChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "com.example.monet.PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.monet.NEXT"
        const val ACTION_STOP = "com.example.monet.STOP"
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var currentSong: Song? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (player.isPlaying) player.pause() else player.play()
                updateNotification()
            }
            ACTION_NEXT -> {
                (applicationContext as MainActivity).viewModel.playNext()
                updateNotification()
            }
            ACTION_STOP -> {
                player.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun playSong(song: Song) {
        currentSong = song
        player.setMediaItem(MediaItem.fromUri(song.url))
        player.prepare()
        player.play()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Monet Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val playPauseIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val nextIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_NEXT
        }
        val stopIntent = Intent(this, PlaybackService::class.java).apply {
            action = ACTION_STOP
        }

        val playPausePendingIntent = PendingIntent.getService(
            this, 0, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextPendingIntent = PendingIntent.getService(
            this, 0, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentSong?.title ?: "Monet")
            .setContentText("Playing")
            .addAction(
                if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Play/Pause",
                playPausePendingIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .addAction(android.R.drawable.ic_media_stop, "Stop", stopPendingIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionCompatToken)
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val manager = NotificationManagerCompat.from(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

// ViewModel for App Logic
class MonetViewModel : ViewModel() {
    private val playbackQueue = mutableListOf<Song>()
    private var currentSongIndex = -1
    private var playlistIdCounter = 1L
    private var songIdCounter = 1L

    val playlists = mutableStateListOf<Playlist>()
    val songs = mutableStateListOf<Song>()

    init {
        NewPipe.init(ServiceList.YouTube)
    }

    fun createPlaylist(name: String) {
        playlists.add(Playlist(id = playlistIdCounter++, name = name, sourceUrl = null))
    }

    fun importYouTubePlaylist(playlistUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val playlistInfo = PlaylistInfo.getInfo(playlistUrl)
                val newPlaylistId = playlistIdCounter++
                playlists.add(Playlist(id = newPlaylistId, name = playlistInfo.name, sourceUrl = playlistUrl))
                playlistInfo.relatedStreams.forEach { stream ->
                    if (stream is StreamInfoItem) {
                        songs.add(
                            Song(
                                id = songIdCounter++,
                                title = stream.name,
                                url = stream.url,
                                playlistId = newPlaylistId,
                                isLocallyAdded = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle error (e.g., invalid URL)
            }
        }
    }

    fun addSongToPlaylist(playlistId: Long, songUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val streamInfo = StreamInfo.getInfo(songUrl)
                songs.add(
                    Song(
                        id = songIdCounter++,
                        title = streamInfo.name,
                        url = streamInfo.url,
                        playlistId = playlistId,
                        isLocallyAdded = true
                    )
                )
            } catch (e: Exception) {
                // Handle error (e.g., invalid song URL)
            }
        }
    }

    fun syncPlaylist(playlist: Playlist) {
        if (playlist.sourceUrl == null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val playlistInfo = PlaylistInfo.getInfo(playlist.sourceUrl)
                val existingSongs = songs.filter { it.playlistId == playlist.id && !it.isLocallyAdded }
                val existingUrls = existingSongs.map { it.url }.toSet()
                playlistInfo.relatedStreams.forEach { stream ->
                    if (stream is StreamInfoItem && stream.url !in existingUrls) {
                        songs.add(
                            Song(
                                id = songIdCounter++,
                                title = stream.name,
                                url = stream.url,
                                playlistId = playlist.id,
                                isLocallyAdded = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Handle error (e.g., network issue)
            }
        }
    }

    fun loadSongs(playlistId: Long) {
        songs.clear()
        songs.addAll(this.songs.filter { it.playlistId == playlistId })
    }

    fun playSong(song: Song) {
        val intent = Intent(MainActivity.appContext, PlaybackService::class.java)
        MainActivity.appContext.startForegroundService(intent)
        val service = PlaybackService()
        service.playSong(song)
        playbackQueue.clear()
        playbackQueue.add(song)
        currentSongIndex = 0
    }

    fun playNext() {
        if (currentSongIndex + 1 < playbackQueue.size) {
            currentSongIndex++
            playSong(playbackQueue[currentSongIndex])
        } else {
            // Stop handled by PlaybackService
        }
    }

    fun addAsNext(song: Song) {
        if (currentSongIndex >= 0) {
            playbackQueue.add(currentSongIndex + 1, song)
        } else {
            playSong(song)
        }
    }
}

// Main Activity
class MainActivity : ComponentActivity() {
    companion object {
        lateinit var appContext: Context
        lateinit var viewModel: MonetViewModel
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        viewModel = MonetViewModel()

        setContent {
            MonetApp()
        }
    }

    fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val deniedPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (deniedPermissions.isNotEmpty()) {
            permissionLauncher.launch(deniedPermissions.toTypedArray())
        }
    }
}

// Jetpack Compose UI with Monet Theme
@Composable
fun MonetApp(viewModel: MonetViewModel = MainActivity.viewModel) {
    val context = LocalContext.current
    val dynamicColors = dynamicColorScheme(context)
    val activity = context as MainActivity
    var showPermissionDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val denied = permissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        showPermissionDialog = denied
    }

    MaterialTheme(
        colorScheme = dynamicColors,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes
    ) {
        if (showPermissionDialog) {
            AlertDialog(
                onDismissRequest = { /* Prevent dismissal */ },
                title = { Text("Permissions Required") },
                text = { Text("Monet needs Internet access to stream songs and Notifications to show playback controls.") },
                confirmButton = {
                    Button(
                        onClick = {
                            activity.checkAndRequestPermissions()
                            showPermissionDialog = false
                        }
                    ) {
                        Text("Grant Permissions")
                    }
                }
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var playlistName by remember { mutableStateOf("") }
                    var importUrl by remember { mutableStateOf("") }
                    var songUrl by remember { mutableStateOf("") }
                    var selectedPlaylistId by remember { mutableStateOf<Long?>(null) }

                    // Create Playlist
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text("Playlist Name") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Button(
                        onClick = {
                            if (playlistName.isNotBlank()) {
                                viewModel.createPlaylist(playlistName)
                                playlistName = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Create Playlist")
                    }

                    // Import YouTube Playlist
                    OutlinedTextField(
                        value = importUrl,
                        onValueChange = { importUrl = it },
                        label = { Text("YouTube Playlist URL") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = TextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                    Button(
                        onClick = {
                            if (importUrl.isNotBlank()) {
                                viewModel.importYouTubePlaylist(importUrl)
                                importUrl = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Import Playlist")
                    }

                    // Playlist List
                    Text(
                        "Playlists",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 16.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    LazyColumn {
                        items(viewModel.playlists) { playlist ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                selectedPlaylistId = playlist.id
                                                viewModel.loadSongs(playlist.id)
                                            }
                                    )
                                    if (playlist.sourceUrl != null) {
                                        TextButton(
                                            onClick = { viewModel.syncPlaylist(playlist) }
                                        ) {
                                            Text(
                                                "Sync",
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Add Song to Playlist
                    if (selectedPlaylistId != null) {
                        OutlinedTextField(
                            value = songUrl,
                            onValueChange = { songUrl = it },
                            label = { Text("YouTube Song URL") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline
                            )
                        )
                        Button(
                            onClick = {
                                if (songUrl.isNotBlank()) {
                                    viewModel.addSongToPlaylist(selectedPlaylistId, songUrl)
                                    songUrl = ""
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Add Song")
                        }
                    }

                    // Song List
                    if (selectedPlaylistId != null) {
                        Text(
                            "Songs",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(top = 16.dp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        LazyColumn {
                            items(viewModel.songs) { song ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    shape = MaterialTheme.shapes.small
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = song.title + if (song.isLocallyAdded) " (Added)" else "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Row {
                                            TextButton(
                                                onClick = { viewModel.playSong(song) },
                                                modifier = Modifier.padding(end = 8.dp)
                                            ) {
                                                Text(
                                                    "Play",
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            TextButton(
                                                onClick = { viewModel.addAsNext(song) }
                                            ) {
                                                Text(
                                                    "Play Next",
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper function for dynamic Monet theming
@Composable
fun dynamicColorScheme(context: android.content.Context): ColorScheme {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        androidx.compose.material3.dynamicLightColorScheme(context)
    } else {
        MaterialTheme.colorScheme
    }
}