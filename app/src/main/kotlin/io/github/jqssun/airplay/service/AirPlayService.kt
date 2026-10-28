package io.github.jqssun.airplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.changes
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.hasLocalNetwork
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.realDisplaySize
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DacpPlayer
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.audio.VolumeBroadcast
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.MdnsService
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.discovery.UsbMdnsAdvertiser
import io.github.jqssun.airplay.download.VideoDownloader
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import io.github.jqssun.airplay.viewmodel.DebugInfo
import java.io.ByteArrayOutputStream
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.github.jqssun.airplay.discovery.lanIpv4Addresses
import io.github.jqssun.airplay.dlna.DlnaRenderer
import io.github.jqssun.airplay.dlna.DlnaPlayerController
import io.github.jqssun.airplay.dlna.DlnaPlaybackSnapshot
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var usbAdvertiser: UsbMdnsAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    @Volatile private var sessionExitGeneration = 0L
    @Volatile private var acceptingNativeCallbacks = false
    private var destroyed = false
    private val mediaCallbackLock = Any()
    private val nativeConnections = NativeConnectionTracker()
    @Volatile private var closingAirPlayUntil = 0L
    @Volatile private var airPlayAudioActive = false
    @Volatile private var airPlayAudioUsesScreen = false
    @Volatile private var airPlayMirrorRunning = false

    private fun isClosingAirPlay(): Boolean = SystemClock.elapsedRealtime() < closingAirPlayUntil

    private val _airPlayDiscoveryStatus = MutableStateFlow("未启动")
    val airPlayDiscoveryStatus = _airPlayDiscoveryStatus.asStateFlow()
    private val _dlnaStatus = MutableStateFlow("未启动")
    val dlnaStatus = _dlnaStatus.asStateFlow()
    private val _networkAddresses = MutableStateFlow<List<String>>(emptyList())
    val networkAddresses = _networkAddresses.asStateFlow()
    private val _playbackError = MutableStateFlow("")
    val playbackError = _playbackError.asStateFlow()

    // Discovery sockets are started/stopped serially off the UI thread.
    private val discoveryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "yunyu-discovery").apply { isDaemon = true }
    }
    private val discoveryGeneration = AtomicInteger()
    private var dlnaRenderer: DlnaRenderer? = null // discoveryExecutor only
    private var networkCallbackRegistered = false
    private val networkRefresh = Runnable {
        if (destroyed) return@Runnable
        val addresses = lanIpv4Addresses(this)
        if (addresses != _networkAddresses.value) refreshDiscovery()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private fun schedule() {
            _mainHandler.removeCallbacks(networkRefresh)
            _mainHandler.postDelayed(networkRefresh, 1200)
        }
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) = schedule()
    }

    // DLNA owns the shared video player after Play; discovery TCP connections are not media sessions.
    private var dlnaUri = ""
    private var dlnaMetadata = ""
    private var dlnaState = "NO_MEDIA_PRESENT"
    private var dlnaTransportStatus = "OK"
    @Volatile private var dlnaOwnsPlayer = false
    private var dlnaStartPositionMs = 0L
    private var dlnaMuted = false

    val videoRenderer = VideoRenderer(this)
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }
    private val videoDownloaderDelegate = lazy { VideoDownloader(this) }
    val videoDownloader by videoDownloaderDelegate

    // hls urls point at the native httpd, valid only while the session lives
    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation = _videoLocation.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // bumped per /play so the ui resets transport state on back-to-back plays too
    private val _videoPlaySeq = MutableStateFlow(0L)
    val videoPlaySeq = _videoPlaySeq.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize = _videoPlaybackSize.asStateFlow()

    // container/manifest metadata
    private val _videoTitle = MutableStateFlow("")
    val videoTitle = _videoTitle.asStateFlow()

    // recent /playback-info polls with no playback = pending video; polls start ~1s before /play
    @Volatile private var _lastVideoPollAt = 0L
    @Volatile private var _videoPollSuppressed = false

    fun videoSessionPending(): Boolean =
        !_videoPlaybackActive.value && !_audioOnly.value && !_mirroringActive.value &&
            !_videoPollSuppressed &&
            SystemClock.elapsedRealtime() - _lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS

    // set once mirroring reports a real size; stops with session
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    lateinit var dacpPlayer: DacpPlayer
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSessionCompat? = null
    private var mediaReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    @Volatile private var _syncClientVol = Prefs.SYNC_CLIENT_VOLUME.def
    private var _pendingServerVolEchoes = 0
    // senders park volume at -30 as route closes
    private var _serverPreZeroIdx = -1
    @Volatile private var _clientVol: Float? = null
    private var _clientVolTarget = -1f
    private var _clientVolHint = 0
    private var _clientVolDir = 0
    private var _clientVolSteps = 0
    private val _clientVolTimeout = Runnable { _clientVolDriveEnd() }

    var logCallback: ((String) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    val currentPin: String? get() = _lastPin
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        _networkAddresses.value = lanIpv4Addresses(this)
        val cm = getSystemService(ConnectivityManager::class.java)
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                networkCallback
            )
            networkCallbackRegistered = true
        }.onFailure { log("网络监听无法启动：${it.message}") }
        dacpController = DacpController(this)
        dacpPlayer = DacpPlayer(
            mainLooper,
            dacp = { dacpController },
            snapshot = {
                DacpPlayer.Snapshot(
                    track = _trackInfo.value,
                    artworkData = _coverArtBytes,
                    durationMs = _durationMs.value,
                    playing = _playing.value,
                    active = _audioOnly.value && _connectionCount.value > 0,
                )
            },
            positionMs = ::currentPositionMs,
            setPlaying = ::_setPlaying,
        )
        mediaSession = MediaSessionCompat(this, "AirPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(true)
                        return
                    }
                    _setPlaying(true)
                    dacpController?.play()
                }
                override fun onPause() {
                    if (_videoPlaybackActive.value) {
                        airPlayVideoPlayer.setPlaying(false)
                        return
                    }
                    _setPlaying(false)
                    dacpController?.pause()
                }
                override fun onStop() {
                    if (_videoPlaybackActive.value) stopVideoPlayback()
                }
                override fun onFastForward() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(VIDEO_SEEK_STEP_MS)
                }
                override fun onRewind() {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.seekBy(-VIDEO_SEEK_STEP_MS)
                }
                override fun onSeekTo(pos: Long) {
                    if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(pos / 1000f)
                }
                override fun onSkipToNext() {
                    if (!_videoPlaybackActive.value) dacpController?.nextItem()
                }
                override fun onSkipToPrevious() {
                    if (!_videoPlaybackActive.value) dacpController?.prevItem()
                }
            })
        }
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (_pendingServerVolEchoes > 0) { _pendingServerVolEchoes--; return }
                if (!_syncClientVol || _connectionCount.value == 0) return
                val target = idx.toFloat() / _serverVolMax()
                Log.d(TAG, "receiver volume $prev -> $idx, sync sender to $target")
                _serverPreZeroIdx = -1
                _driveClientVol(target, if (idx > prev) 1 else -1)
            }
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VolumeBroadcast.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        airPlayVideoPlayer.onVideoSize = { width, height, aspect ->
            _videoPlaybackAspect.value = aspect
            _videoPlaybackSize.value = width to height
        }
        airPlayVideoPlayer.onTitle = { if (!it.isNullOrBlank()) _videoTitle.value = it }
        airPlayVideoPlayer.onDiagnostic = ::log
        airPlayVideoPlayer.onError = { message ->
            _playbackError.value = "影片播放失败：$message。请重新投屏；仍失败可查看连接帮助中的日志。"
            if (dlnaOwnsPlayer) dlnaTransportStatus = "ERROR_OCCURRED"
            log(_playbackError.value)
        }
        airPlayVideoPlayer.onEnded = { _endVideoPlayback("AirPlay Video stopped (player)") }
        airPlayVideoPlayer.onPlaybackInfo = { snapshot ->
            if (nativeHandle != 0L && !dlnaOwnsPlayer) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, snapshot.position, snapshot.duration,
                    snapshot.rate, snapshot.ready, snapshot.playWhenReady)
            }
            if (dlnaOwnsPlayer && _videoPlaybackActive.value) {
                dlnaState = when {
                    snapshot.buffering -> "TRANSITIONING"
                    snapshot.playWhenReady -> "PLAYING"
                    else -> "PAUSED_PLAYBACK"
                }
            }
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(snapshot.position, snapshot.rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (snapshot.position * 1000).toLong(),
                    durationMs = if (snapshot.duration > 0f) (snapshot.duration * 1000).toLong() else 0L,
                    playing = snapshot.playWhenReady,
                    speed = snapshot.speed,
                    skipSilence = snapshot.skipSilence,
                    buffering = snapshot.buffering,
                )
            }
        }
        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }
        lifecycleScope.launch {
            prefs.observe { this[Prefs.SYNC_CLIENT_VOLUME] }.collect {
                _syncClientVol = it
                _clientVolDriveEnd()
                _applyClientVol()
            }
        }
        lifecycleScope.launch {
            prefs.changes()
                .filter { Prefs.all[it]?.restart == true }
                .debounce(SERVER_RESTART_DEBOUNCE_MS)
                .collect { _restartServer() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // A null intent is Android restoring the foreground receiver after process reclaim.
        // Availability is a product invariant, not a switch tied to the current Activity.
        if (!hasLocalNetwork()) {
            _failStart()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        promoteToForeground()
        _startServer(ensureServiceStarted = false)
        if (_serverState.value != ServerState.RUNNING) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    fun startServer() {
        _startServer(ensureServiceStarted = true)
    }

    private fun _startServer(ensureServiceStarted: Boolean) {
        if (destroyed) return
        if (_serverState.value == ServerState.RUNNING) return
        if (!hasLocalNetwork()) {
            log("Local network permission denied")
            _failStart()
            return
        }
        val effectiveName = prefs[Prefs.SERVER_NAME].ifBlank { Prefs.SERVER_NAME.def }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        _playbackError.value = ""
        _airPlayDiscoveryStatus.value = "正在发布"
        nsdManager = NsdServiceManager(this,
            onStatus = { _airPlayDiscoveryStatus.value = it },
            log = ::log
        ).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs[Prefs.ALLOW_NEW_CONN]
        val requirePin = prefs[Prefs.REQUIRE_PIN]

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        // see: https://github.com/google/oboe/blob/main/docs/GettingStarted.md#obtaining-optimal-latency
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        // apply settings from preferences
        val maxFps = prefs[Prefs.MAX_FPS]
        val overscanned = prefs[Prefs.OVERSCANNED]
        val audioLatencyMs = prefs[Prefs.AUDIO_LATENCY_MS]
        val (reqW, reqH) = _displaySize(clamp = false)
        val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, Prefs.forced(prefs[Prefs.H265]))
        val alac = prefs[Prefs.ALAC_ENABLED]
        val aac = prefs[Prefs.AAC_ENABLED]

        videoRenderer.enforceSdr = prefs[Prefs.ENFORCE_SDR]
        videoRenderer.keyAllowFrameDrop = prefs[Prefs.KEY_ALLOW_FRAME_DROP]
        videoRenderer.selector.maxOperatingRate = Prefs.forced(prefs[Prefs.OPERATING_RATE])
        videoRenderer.benchmarkLog = prefs[Prefs.BENCHMARK_LOG]
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs[Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE]
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        val advertiseVideo = prefs[Prefs.ADVERTISE_VIDEO]
        val advertiseMirroring = prefs[Prefs.ADVERTISE_MIRRORING]
        val advertiseAudio = prefs[Prefs.ADVERTISE_AUDIO]
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetLang(nativeHandle, ConfigurationCompat.getLocales(resources.configuration).toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_MIRROR, advertiseMirroring)
        NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_AUDIO, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs[Prefs.SERVER_PORT]
        acceptingNativeCallbacks = true
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        val services = buildList {
            if (advertiseAudio) add(MdnsService(raopName, "_raop._tcp", port, raopTxt))
            add(MdnsService(resolvedName, "_airplay._tcp", port, airplayTxt))
        }
        if (prefs[Prefs.USB_ONLY]) {
            _airPlayDiscoveryStatus.value = "仅通过 USB 发布（可在设置切换为所有网络）"
            usbAdvertiser = UsbMdnsAdvertiser(services, onLink = { iface, addr ->
                log(if (iface == null) "USB link down" else "USB link up on $iface (${addr?.hostAddress})")
            }).apply { start() }
        } else {
            nsdManager?.register(services)
        }

        _serverState.value = ServerState.RUNNING
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        _startDlna(effectiveName)
        log("Server started on port $port")
    }

    fun refreshDiscovery() {
        if (destroyed) return
        _networkAddresses.value = lanIpv4Addresses(this)
        if (_serverState.value != ServerState.RUNNING) return
        if (!prefs[Prefs.USB_ONLY]) nsdManager?.refresh()
        val generation = discoveryGeneration.get()
        discoveryExecutor.execute {
            if (generation != discoveryGeneration.get()) return@execute
            runCatching { dlnaRenderer?.refreshNetwork() }
                .onFailure {
                    if (generation == discoveryGeneration.get()) _dlnaStatus.value = "刷新失败：${it.message}"
                    log("DLNA 刷新失败：${it.message}")
                }
        }
        log("刷新局域网设备发现：${_networkAddresses.value.joinToString()}")
    }

    private fun _startDlna(name: String) {
        _networkAddresses.value = lanIpv4Addresses(this)
        val generation = discoveryGeneration.incrementAndGet()
        _dlnaStatus.value = "正在启动"
        discoveryExecutor.execute {
            if (generation != discoveryGeneration.get()) return@execute
            dlnaRenderer?.stop()
            val renderer = DlnaRenderer(this, name, dlnaController,
                onStatus = { if (generation == discoveryGeneration.get()) _dlnaStatus.value = it },
                log = ::log
            )
            dlnaRenderer = renderer
            runCatching { renderer.start() }.onFailure {
                renderer.stop()
                if (generation == discoveryGeneration.get()) _dlnaStatus.value = "启动失败：${it.message}"
                log("DLNA 启动失败：${it.message}")
            }
        }
    }

    private fun _stopDlna() {
        discoveryGeneration.incrementAndGet()
        _dlnaStatus.value = "未启动"
        if (!discoveryExecutor.isShutdown) discoveryExecutor.execute {
            dlnaRenderer?.stop()
            dlnaRenderer = null
        }
    }

    private fun <T> _onPlayerThread(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val generation = sessionExitGeneration
        val task = FutureTask<T> {
            check(generation == sessionExitGeneration) { "本次投屏已结束" }
            action()
        }
        check(_mainHandler.post(task)) { "播放器已关闭" }
        return try {
            task.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            task.cancel(false)
            _mainHandler.removeCallbacks(task)
            throw IllegalStateException(e.cause?.message ?: "播放器响应超时", e)
        }
    }

    private fun _ensureDlnaAvailable() {
        check(!isClosingAirPlay()) { "正在结束上一段投屏，请稍后重新选择电视" }
        check(_serverState.value == ServerState.RUNNING) { "接收服务已停止" }
        check(!airPlayAudioActive && !airPlayMirrorRunning && !_mirroringActive.value &&
            !(_videoPlaybackActive.value && !dlnaOwnsPlayer)) {
            "AirPlay 正在播放，请先在手机上停止当前 AirPlay 投屏"
        }
    }

    // Call only for a real media start, never for an HTTP probe or playback-info query.
    private fun _claimAirPlayPlayback() {
        val alreadyPlaying = !dlnaOwnsPlayer &&
            (_mirroringActive.value || _audioOnly.value || _videoPlaybackActive.value)
        if (dlnaOwnsPlayer) _endVideoPlayback("切换至 AirPlay")
        _resetDlnaSession()
        _applyClientVol()
        if (!alreadyPlaying && shouldLaunchOnConnect()) launchMainActivity()
    }

    private fun _resetDlnaSession() {
        dlnaUri = ""
        dlnaMetadata = ""
        dlnaState = "NO_MEDIA_PRESENT"
        dlnaTransportStatus = "OK"
        dlnaOwnsPlayer = false
        dlnaStartPositionMs = 0
        if (dlnaMuted) airPlayVideoPlayer.setClientGain(1f)
        dlnaMuted = false
    }

    private fun _dlnaTitle(metadata: String): String = runCatching {
        if (metadata.contains("<!DOCTYPE", ignoreCase = true)) return@runCatching ""
        val parser = android.util.Xml.newPullParser()
        parser.setInput(metadata.reader())
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name.substringAfter(':') == "title") {
                return@runCatching parser.nextText().take(256)
            }
            parser.next()
        }
        ""
    }.getOrDefault("")

    private val dlnaController = object : DlnaPlayerController {
        override fun setUri(uri: String, metadata: String) = _onPlayerThread {
            _ensureDlnaAvailable()
            val sameUri = uri == dlnaUri
            if (dlnaOwnsPlayer) _endVideoPlayback("DLNA 更换影片")
            dlnaUri = uri
            dlnaMetadata = metadata
            dlnaStartPositionMs = 0
            dlnaState = if (uri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
            dlnaTransportStatus = "OK"
            _playbackError.value = ""
            _videoPlaybackInfo.value = VideoPlaybackInfo(playing = false)
            log("DLNA 已接收影片，同一地址=$sameUri")
        }

        override fun play() = _onPlayerThread {
            _ensureDlnaAvailable()
            check(dlnaUri.isNotBlank()) { "尚未收到影片地址" }
            dlnaTransportStatus = "OK"
            if (dlnaOwnsPlayer && _videoPlaybackActive.value) {
                setVideoPlaying(true)
                log("DLNA Play 继续当前播放器")
            } else {
                dlnaOwnsPlayer = true
                dlnaState = "TRANSITIONING"
                airPlayVideoPlayer.setClientGain(if (dlnaMuted) 0f else 1f)
                _beginVideoPlayback(dlnaUri, dlnaStartPositionMs / 1000f, _dlnaTitle(dlnaMetadata))
                log("DLNA Play 新建播放器，起点=${dlnaStartPositionMs}ms")
                if (shouldLaunchOnConnect()) launchMainActivity()
            }
            log("DLNA 开始播放")
        }

        override fun pause() = _onPlayerThread {
            check(dlnaOwnsPlayer && _videoPlaybackActive.value) { "没有正在播放的 DLNA 影片" }
            setVideoPlaying(false)
        }

        override fun stop() = _onPlayerThread {
            if (dlnaOwnsPlayer) _endVideoPlayback("DLNA 停止播放")
            dlnaState = if (dlnaUri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
            dlnaStartPositionMs = 0
        }

        override fun seek(positionMs: Long) = _onPlayerThread {
            _ensureDlnaAvailable()
            check(dlnaUri.isNotBlank()) { "尚未收到影片地址" }
            val duration = _videoPlaybackInfo.value.durationMs
            dlnaStartPositionMs = positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
            log("DLNA Seek 目标=${dlnaStartPositionMs}ms，已有播放器=$dlnaOwnsPlayer")
            if (dlnaOwnsPlayer) seekVideoTo(dlnaStartPositionMs, "DLNA")
        }

        override fun setVolume(percent: Int) = _onPlayerThread {
            _ensureDlnaAvailable()
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) / 100f * max).roundToInt(), 0)
        }

        override fun setMute(muted: Boolean) = _onPlayerThread {
            _ensureDlnaAvailable()
            dlnaMuted = muted
            if (dlnaOwnsPlayer) airPlayVideoPlayer.setClientGain(if (muted) 0f else 1f)
            Unit
        }

        override fun snapshot(): DlnaPlaybackSnapshot = _onPlayerThread {
            val info = _videoPlaybackInfo.value
            val current = if (dlnaOwnsPlayer && _videoPlaybackActive.value) airPlayVideoPlayer.snapshot() else null
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            DlnaPlaybackSnapshot(
                uri = dlnaUri,
                metadata = dlnaMetadata,
                state = if (current == null) dlnaState else when {
                    current.buffering -> "TRANSITIONING"
                    current.playWhenReady -> "PLAYING"
                    else -> "PAUSED_PLAYBACK"
                },
                positionMs = if (dlnaOwnsPlayer) current?.let { (it.position * 1000).toLong() } ?: info.positionMs else dlnaStartPositionMs,
                durationMs = if (dlnaUri.isNotEmpty()) current?.let { (it.duration * 1000).toLong().coerceAtLeast(0) } ?: info.durationMs else 0,
                volume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).roundToInt(),
                muted = dlnaMuted,
                transportStatus = dlnaTransportStatus
            )
        }
    }

    private fun _orientationFollowsDevice(): Boolean =
        prefs[Prefs.RESOLUTION] == Prefs.AUTO

    private fun _displaySize(clamp: Boolean = true): Pair<Int, Int> {
        val res = prefs[Prefs.RESOLUTION]
        val portrait = when (res) {
            Prefs.PORTRAIT -> true
            Prefs.LANDSCAPE -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == Prefs.PORTRAIT || res == Prefs.LANDSCAPE) return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs[Prefs.MAX_FPS])
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        // A start failure can also follow permission loss while a session is alive.
        // Use the complete teardown path so stale counts, player state and locks reset.
        _shutdown()
        _airPlayDiscoveryStatus.value = "启动失败，请查看日志"
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        _shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun _restartServer() {
        if (_serverState.value != ServerState.RUNNING) return
        _shutdown()
        _startServer(ensureServiceStarted = false)
        if (_serverState.value != ServerState.RUNNING) stopSelf()
    }

    private fun _shutdown() {
        _serverState.value = ServerState.STOPPED
        acceptingNativeCallbacks = false
        sessionExitGeneration++
        synchronized(mediaCallbackLock) { /* Native teardown must not race renderer work. */ }
        closingAirPlayUntil = 0L
        airPlayAudioActive = false
        airPlayAudioUsesScreen = false
        airPlayMirrorRunning = false
        _stopDlna()
        _airPlayDiscoveryStatus.value = "未启动"
        _resetDlnaSession()
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.reset()
        usbAdvertiser?.stop()
        usbAdvertiser = null
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        airPlayVideoPlayer.stop()
        mediaSession?.isActive = false
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        _videoPlaybackInfo.value = VideoPlaybackInfo()
        _lastVideoPollAt = 0
        _videoPollSuppressed = false
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _progressBaseTime = 0
        _videoLocation.value = null
        _videoTitle.value = ""
        clearPin()
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        nativeConnections.reset()
        _clientVol = null
        _clientVolDriveEnd()
        _restoreServerVolume()
        _applyClientVol()
        _refreshDacpPlayer()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun setVideoPlaying(playing: Boolean) {
        if (dlnaOwnsPlayer) dlnaState = if (playing) "PLAYING" else "PAUSED_PLAYBACK"
        airPlayVideoPlayer.setPlaying(playing)
    }

    fun seekVideoTo(positionMs: Long, origin: String = "本机") {
        airPlayVideoPlayer.scrub(positionMs / 1000f, origin)
    }

    fun setVideoScrubbing(enabled: Boolean) {
        airPlayVideoPlayer.setScrubbing(enabled)
    }

    fun setVideoSpeed(speed: Float) {
        airPlayVideoPlayer.setSpeed(speed)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        airPlayVideoPlayer.setSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    /** Ends only the current media session; the listeners, foreground service and discovery stay up. */
    fun endCurrentSession() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val hadAirPlay = nativeConnections.current().count > 0 || _mirroringActive.value ||
            (_videoPlaybackActive.value && !dlnaOwnsPlayer)
        synchronized(mediaCallbackLock) {
            sessionExitGeneration++
            closingAirPlayUntil = if (hadAirPlay) SystemClock.elapsedRealtime() + 2500L else 0L
            airPlayAudioActive = false
            airPlayAudioUsesScreen = false
            airPlayMirrorRunning = false
            audioRenderer.stop()
            videoRenderer.stopSession()
        }
        _videoPollSuppressed = true
        _lastVideoPollAt = 0
        _endVideoPlayback("已退出本次投屏，接收保持开启")
        _resetDlnaSession()
        clearPin()
        _mirroringActive.value = false
        _audioOnly.value = false
        _videoLocation.value = null
        _videoTitle.value = ""
        _videoPlaybackInfo.value = VideoPlaybackInfo(playing = false)
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _progressBaseTime = 0
        dacpController?.reset()
        _clientVol = null
        _clientVolDriveEnd()
        _restoreServerVolume()
        _applyClientVol()
        mediaSession?.isActive = false
        _refreshDacpPlayer()
        _updateMediaNotification()
        if (nativeHandle != 0L) {
            // The native HTTP worker owns connection teardown. No server or mDNS restart.
            NativeBridge.nativeDisconnectClients(nativeHandle)
        }
        log("界面已退出，继续接收下一次投屏")
    }

    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)")

    fun downloadVideo() {
        _videoLocation.value?.let { videoDownloader.start(it) }
    }

    private fun _endVideoPlayback(message: String) {
        if (!_videoPlaybackActive.value) return
        // lingering polls after a stop must not bounce the UI back to a pending session
        _videoPollSuppressed = true
        _videoPlaybackActive.value = false
        if (dlnaOwnsPlayer) {
            dlnaState = if (dlnaUri.isEmpty()) "NO_MEDIA_PRESENT" else "STOPPED"
            dlnaOwnsPlayer = false
            dlnaStartPositionMs = 0
            _videoPlaybackInfo.value = VideoPlaybackInfo(playing = false)
        }
        airPlayVideoPlayer.stop()
        if (!_audioOnly.value) mediaSession?.isActive = false
        log(message)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        destroyed = true
        _mainHandler.removeCallbacks(networkRefresh)
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        _shutdown()
        airPlayVideoPlayer.release()
        if (videoDownloaderDelegate.isInitialized()) videoDownloader.release()
        _mainHandler.removeCallbacksAndMessages(null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        discoveryExecutor.shutdown()
        dacpPlayer.release()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    // Native protocol workers must never mutate session/UI state concurrently with Back,
    // DLNA or service teardown. Frame/format work stays synchronous on the media threads.
    private fun postNativeState(allowClosing: Boolean = false, block: () -> Unit) {
        if (!acceptingNativeCallbacks) return
        val generation = sessionExitGeneration
        _mainHandler.post {
            if (!acceptingNativeCallbacks || generation != sessionExitGeneration ||
                (!allowClosing && isClosingAirPlay())) return@post
            block()
        }
    }

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            videoRenderer.feedFrame(data, ntpTimeNs, isH265)
        }
    }

    override fun onVideoSessionPoll() {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            _lastVideoPollAt = SystemClock.elapsedRealtime()
        }
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        postNativeState {
            _claimAirPlayPlayback()
            _beginVideoPlayback(location, startPositionSeconds)
            log("AirPlay 视频开始播放")
        }
    }

    private fun _beginVideoPlayback(location: String, startPositionSeconds: Float, title: String = "") {
        _playbackError.value = ""
        _videoLocation.value = location
        _videoPlaySeq.value++
        _videoPollSuppressed = false
        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = (startPositionSeconds * 1000).toLong())
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoTitle.value = title
        _videoPlaybackActive.value = true
        airPlayVideoPlayer.play(location, startPositionSeconds)
        mediaSession?.isActive = true
    }

    override fun onVideoScrub(positionSeconds: Float) {
        postNativeState {
            if (!dlnaOwnsPlayer) airPlayVideoPlayer.scrub(positionSeconds)
        }
    }

    override fun onVideoRate(rate: Float) {
        postNativeState {
            if (!dlnaOwnsPlayer) airPlayVideoPlayer.setRate(rate)
        }
    }

    override fun onVideoStop() {
        postNativeState {
            if (!dlnaOwnsPlayer) _endVideoPlayback("AirPlay Video stopped")
        }
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            airPlayAudioActive = true
            airPlayAudioUsesScreen = usingScreen
            // The native RTP worker may decode immediately after this callback returns.
            audioRenderer.start()
            audioRenderer.setFormat(ct, spf)
            postNativeState {
                if (!airPlayAudioActive || airPlayAudioUsesScreen != usingScreen) return@postNativeState
                _claimAirPlayPlayback()
                clearPin()
                if (!usingScreen) _setPlaying(true)
                if (!usingScreen && !_audioOnly.value) _setAudioOnly(true)
                log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
            }
        }
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            val valid = w.isFinite() && h.isFinite() && w > 0 && h > 0
            if (valid) videoRenderer.setResolution(w.toInt(), h.toInt())
            postNativeState {
                clearPin()
                if (valid) {
                    _videoAspect.value = w / h
                    _videoResolution.value = "${w.toInt()}x${h.toInt()}"
                    _mirroringActive.value = true
                }
                log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
            }
        }
    }

    override fun onClientVolume(db: Float) {
        if (!db.isFinite()) return
        postNativeState {
            val frac = _dbToFrac(db)
            _clientVol = frac
            Log.d(TAG, "sender volume ${db}dB, frac $frac")
            val driving = _clientVolTarget >= 0f
            if (driving) _clientVolStep()
            if (!driving || !_syncClientVol) _applyClientVol()
        }
    }

    override fun onClientVolumeQuery(): Float {
        val db = _fracToDb(_clientVolExpected())
        Log.d(TAG, "sender volume query -> ${db}dB")
        return db
    }

    private fun _clientVolExpected(): Float = if (_syncClientVol) _serverVolFrac() else _clientVol ?: 1f

    fun clientVolume(): Float = _clientVol ?: _clientVolExpected()

    // synced: receiver volume broadcast then drives sender
    fun setClientVolume(frac: Float) {
        if (_syncClientVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _serverVolIdx(frac), 0)
            return
        }
        _driveClientVol(frac, if (frac > clientVolume()) 1 else -1)
    }

    private fun _applyClientVol() {
        if (dlnaOwnsPlayer) return
        val frac = _clientVol
        if (!_syncClientVol) {
            _setClientGain(_clientGain(frac ?: 1f))
            return
        }
        _setClientGain(1f)
        if (frac == null) return
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val idx = _serverVolIdx(frac)
        _serverPreZeroIdx = if (idx == 0) (if (_serverPreZeroIdx < 0) cur else _serverPreZeroIdx) else -1
        if (idx != cur) {
            _pendingServerVolEchoes++
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        }
    }

    private fun _restoreServerVolume() {
        if (_serverPreZeroIdx < 0) return
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _serverPreZeroIdx, 0)
        _serverPreZeroIdx = -1
    }

    private fun _setClientGain(gain: Float) {
        if (gain != audioRenderer.clientGain) audioRenderer.setClientGain(gain)
        // DLNA controls this player's gain independently of the native engine.
        airPlayVideoPlayer.setClientGain(gain)
    }

    private fun _serverVolMax() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private fun _serverVolIdx(frac: Float) = (frac * _serverVolMax()).roundToInt()
    private fun _serverVolFrac() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / _serverVolMax()

    private fun _driveClientVol(target: Float, hint: Int) {
        val active = _clientVolTarget >= 0f
        _clientVolTarget = target
        _clientVolHint = hint
        _clientVolDir = 0
        _clientVolSteps = 0
        if (!active) _clientVolStep()
    }

    private fun _clientVolStep() {
        _mainHandler.removeCallbacks(_clientVolTimeout)
        val target = _clientVolTarget
        if (target < 0f) return
        val frac = _clientVol
        val want = when {
            frac == null -> _clientVolHint
            abs(target - frac) <= CLIENT_VOL_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (_clientVolDir != 0 && want != _clientVolDir) || _clientVolSteps >= CLIENT_VOL_MAX_STEPS) {
            _clientVolDriveEnd()
            return
        }
        _clientVolDir = want
        _clientVolSteps++
        if (want > 0) dacpController?.volumeUp() else dacpController?.volumeDown()
        _mainHandler.postDelayed(_clientVolTimeout, CLIENT_VOL_TIMEOUT_MS)
    }

    private fun _clientVolDriveEnd() {
        _mainHandler.removeCallbacks(_clientVolTimeout)
        if (_clientVolTarget >= 0f) Log.d(TAG, "sender volume drive done: sender $_clientVol, target $_clientVolTarget")
        _clientVolTarget = -1f
        _clientVolDir = 0
    }

    override fun onConnectionInit() {
        if (!acceptingNativeCallbacks) return
        nativeConnections.connected()
        postNativeState(allowClosing = true) {
            _connectionCount.value = nativeConnections.current().count
            if (isClosingAirPlay()) {
                if (nativeHandle != 0L) NativeBridge.nativeDisconnectClients(nativeHandle)
                return@postNativeState
            }
            log("Sender connected (${_connectionCount.value})")
        }
    }

    override fun onConnectionDestroy() {
        if (!acceptingNativeCallbacks) return
        val disconnected = nativeConnections.disconnected()
        postNativeState(allowClosing = true) {
            _connectionCount.value = nativeConnections.current().count
            synchronized(mediaCallbackLock) {
                // A new native media start can arrive before this main-thread
                // callback. An older disconnect must not stop the new output.
                if (!nativeConnections.ownsFinalDisconnect(disconnected)) return@synchronized
                airPlayAudioActive = false
                airPlayAudioUsesScreen = false
                airPlayMirrorRunning = false
                // Teardown may complete after DLNA has already started another film.
                // Only clear AirPlay state; never deactivate that new player's media session.
                if (!dlnaOwnsPlayer) _endVideoPlayback("AirPlay Video stopped (disconnect)")
                audioRenderer.stop()
                _audioOnly.value = false
                _lastVideoPollAt = 0
                _videoPollSuppressed = isClosingAirPlay()
                _coverArtBytes = null
                _trackInfo.value = TrackInfo()
                _positionMs.value = 0
                _durationMs.value = 0
                _progressBaseTime = 0
                dacpController?.reset()
                _clientVol = null
                _clientVolDriveEnd()
                if (!dlnaOwnsPlayer) {
                    _restoreServerVolume()
                    _applyClientVol()
                    mediaSession?.isActive = false
                }
                _refreshDacpPlayer()
                _updateMediaNotification()
            }
            log("Sender disconnected (${_connectionCount.value})")
        }
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        postNativeState {
            if (_lastPin == pin) return@postNativeState
            _lastPin = pin
            pinCallback?.invoke(pin)
            _updateMediaNotification()
            if (shouldLaunchOnConnect()) launchMainActivity()
        }
    }

    override fun onMetadata(data: ByteArray) {
        if (!acceptingNativeCallbacks || isClosingAirPlay()) return
        val generation = sessionExitGeneration
        val map = DmapParser.parse(data)
        postNativeState {
            if (generation != sessionExitGeneration) return@postNativeState
            val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
            _trackInfo.value = info
            if (info.durationMs > 0) _durationMs.value = info.durationMs
            _updateMediaMetadata()
            _refreshDacpPlayer()
            log("Track: ${info.artist} - ${info.title}")
        }
    }

    override fun onCoverArt(data: ByteArray) {
        // Keep image decoding off the main thread; capture the session before decoding.
        if (!acceptingNativeCallbacks || isClosingAirPlay() || data.size > MAX_COVER_ART_BYTES) return
        val generation = sessionExitGeneration
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sampleSize = 1
        while ((bounds.outWidth.toLong() + sampleSize - 1) / sampleSize > MAX_COVER_ART_EDGE ||
            (bounds.outHeight.toLong() + sampleSize - 1) / sampleSize > MAX_COVER_ART_EDGE) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return
        // Media3 artwork consumers must receive the same bounded image, not the original.
        val artworkBytes = ByteArrayOutputStream().use { output ->
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                bmp.recycle()
                return
            }
            output.toByteArray()
        }
        postNativeState {
            if (generation != sessionExitGeneration) return@postNativeState
            _coverArtBytes = artworkBytes
            _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
            _updateMediaMetadata()
            _refreshDacpPlayer()
        }
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        postNativeState {
            // RAOP reports unsigned 32-bit sample timestamps, including across rollover.
            val durationSamples = (end - start) and 0xffff_ffffL
            val positionSamples = ((curr - start) and 0xffff_ffffL).coerceAtMost(durationSamples)
            val posMs = positionSamples * 1000 / 44100
            val durMs = durationSamples * 1000 / 44100
            // pause/resume transitions emit degenerate progress; keep the last good value
            if (durMs <= 0) return@postNativeState
            _positionMs.value = posMs
            _durationMs.value = durMs
            _progressBaseMs = posMs
            _progressBaseTime = SystemClock.elapsedRealtime()
            _playing.value = true
            _updatePlaybackState()
            _refreshDacpPlayer()
        }
    }

    override fun onAudioTeardown() {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            airPlayAudioActive = false
            airPlayAudioUsesScreen = false
            postNativeState {
                if (airPlayAudioActive) return@postNativeState
                if (!dlnaOwnsPlayer) {
                    _setPlaying(false)
                    if (!_videoPlaybackActive.value) _setAudioOnly(false)
                }
            }
        }
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        postNativeState {
            dacpController?.update(dacpId, activeRemote)
            log("DACP: $dacpId")
        }
    }

    override fun onMirrorRunning(running: Boolean) {
        synchronized(mediaCallbackLock) {
            if (!acceptingNativeCallbacks || isClosingAirPlay()) return
            airPlayMirrorRunning = running
            // Must precede the first frame on this same native worker.
            if (running) videoRenderer.startSession() else videoRenderer.stopSession()
            postNativeState {
                if (airPlayMirrorRunning != running) return@postNativeState
                if (running) _claimAirPlayPlayback() else _mirroringActive.value = false
                if (!dlnaOwnsPlayer) {
                    _setAudioOnly(!running && airPlayAudioActive && !airPlayAudioUsesScreen)
                }
            }
        }
    }

    private fun _setAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        _refreshDacpPlayer()
        if (audioOnly && !prev) {
            mediaSession?.isActive = true
            log("Audio mode")
        } else if (!audioOnly && prev) {
            mediaSession?.isActive = false
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            _updateMediaNotification()
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        val info = _trackInfo.value
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, info.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, info.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, info.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, _durationMs.value)
        info.coverArt?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        mediaSession?.setMetadata(builder.build())
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        val positionBeforeChange = currentPositionMs()
        _playing.value = playing
        _refreshDacpPlayer()
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = positionBeforeChange
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        // the sender's silent raop audio session must not overwrite video session state
        if (_videoPlaybackActive.value) return
        val isPlaying = _playing.value
        val pbState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val speed = if (isPlaying) 1f else 0f
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(pbState, _positionMs.value, speed, SystemClock.elapsedRealtime())
            .build()
        mediaSession?.setPlaybackState(state)
        _updateMediaNotification()
    }

    // consumers extrapolate position from (position, speed, updateTime): push only discontinuities
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val playing = rate > 0f
        val posMs = (positionSeconds * 1000).toLong()
        val now = SystemClock.elapsedRealtime()
        val expectedMs = _lastVideoStatePosMs +
            if (_lastVideoStateRate > 0f) ((now - _lastVideoStateAtMs) * _lastVideoStateRate).toLong() else 0L
        if (rate == _lastVideoStateRate && abs(posMs - expectedMs) < 1000) return
        _lastVideoStateRate = rate
        _lastVideoStatePosMs = posMs
        _lastVideoStateAtMs = now
        val state = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                posMs,
                rate,
                now
            )
            .build()
        mediaSession?.setPlaybackState(state)
    }

    private var _lastVideoStateRate = -1f
    private var _lastVideoStatePosMs = 0L
    private var _lastVideoStateAtMs = 0L

    private fun _refreshDacpPlayer() {
        _mainHandler.post { if (!destroyed) dacpPlayer.refresh() }
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        serverVolume = 100 * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / _serverVolMax(),
        clientVolume = (100 * clientVolume()).roundToInt(),
        audio = audioRenderer.audioDebug(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit().putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)).apply()
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    private fun shouldLaunchOnConnect(): Boolean {
        return prefs[Prefs.LAUNCH_ON_CONNECT]
    }

    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.sessionToken?.let { token ->
                builder.setStyle(
                    MediaNotificationCompat.MediaStyle()
                        .setMediaSession(token)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun launchMainActivity() {
        val generation = sessionExitGeneration
        _mainHandler.post {
            if (generation != sessionExitGeneration || isClosingAirPlay() || _serverState.value != ServerState.RUNNING) return@post
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
            }
        }
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        private const val CLIENT_VOL_EPS = 0.033f
        private const val CLIENT_VOL_MAX_STEPS = 32
        private const val CLIENT_VOL_TIMEOUT_MS = 800L
        private const val CHANNEL_ID = "airplay_service"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_PAUSE = "io.github.jqssun.airplay.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.jqssun.airplay.NEXT"
        const val ACTION_PREV = "io.github.jqssun.airplay.PREV"
        const val ACTION_START_SERVER = "io.github.jqssun.airplay.START_SERVER"
        // shared with dpad/double-tap seeks
        const val VIDEO_SEEK_STEP_MS = 10_000L
        const val VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L
        private const val MAX_COVER_ART_BYTES = 8 * 1024 * 1024
        private const val MAX_COVER_ART_EDGE = 1024

        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
        private const val SERVER_RESTART_DEBOUNCE_MS = 500L

        // airplay volume is linear in dB along sender slider
        private fun _dbToFrac(db: Float) = if (db <= -144f) 0f else ((db + 30f) / 30f).coerceIn(0f, 1f)
        private fun _fracToDb(frac: Float) = if (frac <= 0f) -144f else -30f + 30f * frac
        private fun _clientGain(frac: Float) = if (frac <= 0f) 0f else 10f.pow(_fracToDb(frac) / 20f)
    }
}
