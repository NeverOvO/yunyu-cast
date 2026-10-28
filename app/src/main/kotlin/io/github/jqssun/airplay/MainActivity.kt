package io.github.jqssun.airplay

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.net.Uri
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.PairingPinDialog
import io.github.jqssun.airplay.ui.AppUpdateDialog
import io.github.jqssun.airplay.ui.AutoLaunchPermissionDialog
import io.github.jqssun.airplay.onboarding.AutoLaunchOnboarding
import io.github.jqssun.airplay.onboarding.AutoLaunchStep
import io.github.jqssun.airplay.update.AppUpdateViewModel
import io.github.jqssun.airplay.ui.isTvDevice
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val updateViewModel: AppUpdateViewModel by viewModels()
    private var installScreenOpen by mutableStateOf(false)
    private var installPermissionScreenOpen by mutableStateOf(false)
    private var activityResumed by mutableStateOf(false)
    private var runtimePermissionsPending by mutableStateOf(false)
    private var autoLaunchOnboarding by mutableStateOf(AutoLaunchOnboarding(handled = true))
    private val onboardingPrefs by lazy { getSharedPreferences("auto_launch_onboarding", MODE_PRIVATE) }
    private val activePlayback by lazy {
        combine(viewModel.audioOnly, viewModel.videoPlaybackActive, viewModel.mirroringActive,
            viewModel.videoSessionPending) { audio, video, mirror, pending -> audio || video || mirror || pending }
            .distinctUntilChanged()
    }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installPermissionScreenOpen = false
        updateViewModel.permissionReturned(packageManager.canRequestPackageInstalls())
    }
    private val installUpdate = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        installScreenOpen = false
        updateViewModel.installMessage("如果还没有完成安装，可再点“安装更新”。当前版本仍可正常投屏。")
    }
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            viewModel.showPin(svc.currentPin)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _refreshForeground()
            _autoStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            viewModel.unbindService()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        runtimePermissionsPending = false
        if (it.isNotEmpty()) _syncLocalNetwork(requested = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        autoLaunchOnboarding = AutoLaunchOnboarding.restore(
            granted = canAutoLaunchCast(),
            handled = onboardingPrefs.getBoolean("handled", false),
            waitingForSettings = onboardingPrefs.getBoolean("waiting_for_settings", false),
            savedStep = savedInstanceState?.getString("auto_launch_step")?.let { name ->
                AutoLaunchStep.entries.firstOrNull { it.name == name }
            },
        )
        persistAutoLaunchOnboarding(autoLaunchOnboarding)
        runtimePermissionsPending = savedInstanceState?.getBoolean("runtime_permissions_pending") ?: false

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !_granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasLocalNetwork()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty() && !runtimePermissionsPending) {
            runtimePermissionsPending = true
            permissions.launch(missing.toTypedArray())
        }

        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    viewModel.updateFromService()
                    delay(200)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.pref(Prefs.KEEP_SCREEN_ON), activePlayback, viewModel.pinCode
                ) { keep, playing, pin ->
                    keep && (playing || pin != null)
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // auto-enter pre-declared in pip params
        if (!isTvDevice() && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.serverState, activePlayback,
                        viewModel.videoPlaybackActive, viewModel.videoPlaybackAspect,
                        viewModel.videoAspect
                    ) { _, _, _, _, _ -> }.collect { setPictureInPictureParams(_pipParams()) }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(activePlayback, viewModel.pinCode) {
                    playing, pin -> !playing && pin == null
                }.distinctUntilChanged().collectLatest { idle ->
                    if (idle) {
                        delay(2_500)
                        updateViewModel.checkIfDue()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updateViewModel.state.collect {
                    if (updateViewModel.shouldAutoInstall()) requestUpdateInstall()
                }
            }
        }

        setContent {
            AirPlayTheme {
                val updateState by updateViewModel.state.collectAsState()
                val casting by activePlayback.collectAsState(initial = hasActivePlayback())
                val pin by viewModel.pinCode.collectAsState()
                MainScreen(
                    viewModel = viewModel,
                    isInPip = isInPip.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork,
                    onExit = ::exitToTv,
                    onEndSession = { service?.endCurrentSession() },
                    updateLabel = updateState.entryLabel,
                    onOpenUpdates = updateViewModel::open
                )
                if (!isInPip.value) {
                    PairingPinDialog(pin = pin, onDismiss = viewModel::dismissPin)
                }
                AppUpdateDialog(
                    state = updateState.copy(dialogVisible = updateState.dialogVisible && !casting && pin == null),
                    onDismiss = updateViewModel::dismiss,
                    onCheck = updateViewModel::check,
                    onDownload = updateViewModel::download,
                    onInstall = ::requestUpdateInstall,
                    onCancelDownload = updateViewModel::cancelDownload
                )
                if (activityResumed && !runtimePermissionsPending && !casting && pin == null &&
                    !isInPip.value && !updateState.dialogVisible && !installScreenOpen && !installPermissionScreenOpen) {
                    AutoLaunchPermissionDialog(
                        step = autoLaunchOnboarding.step,
                        onOpenSettings = ::requestAutoLaunchPermission,
                        onDefer = { persistAutoLaunchOnboarding(autoLaunchOnboarding.defer()) },
                        onClose = {
                            persistAutoLaunchOnboarding(autoLaunchOnboarding.dismiss())
                            if (updateViewModel.shouldAutoInstall()) requestUpdateInstall()
                        },
                    )
                }
            }
        }
    }

    private fun requestUpdateInstall() {
        if (installScreenOpen || installPermissionScreenOpen) return
        if (runtimePermissionsPending || autoLaunchOnboarding.waitingForSettings ||
            (autoLaunchOnboarding.step != AutoLaunchStep.NONE && !updateViewModel.state.value.dialogVisible)) return
        updateViewModel.stopAutoInstall()
        if (hasActivePlayback() || viewModel.pinCode.value != null) {
            updateViewModel.installMessage("正在投屏，请先在手机上结束投屏，再点“安装更新”。")
            return
        }
        val file = updateViewModel.fileForInstall() ?: run {
            updateViewModel.missingPreparedFile()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            updateViewModel.requireInstallPermission()
            try {
                installPermissionScreenOpen = true
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                installPermissionScreenOpen = false
                updateViewModel.requireInstallPermission("请在电视系统设置中允许“云屿投屏”安装应用，然后返回这里继续安装。")
            } catch (_: SecurityException) {
                installPermissionScreenOpen = false
                updateViewModel.requireInstallPermission("电视系统暂不允许安装，请先在系统设置中开启安装权限。")
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, AppUpdateViewModel.APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("云屿投屏更新", uri)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            installScreenOpen = true
            updateViewModel.installerOpened()
            installUpdate.launch(intent)
        } catch (_: ActivityNotFoundException) {
            installScreenOpen = false
            updateViewModel.installMessage("这台电视未提供可用的安装页面，请通过电视文件管理器安装新版。")
        } catch (_: Exception) {
            installScreenOpen = false
            updateViewModel.installMessage("暂时无法打开安装页面，请稍后再点“安装更新”。")
        }
    }

    private fun _granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun _autoStart() {
        val svc = service ?: return
        if (svc.serverState.value != AirPlayService.ServerState.RUNNING && hasLocalNetwork()) {
            viewModel.startServer()
        }
    }
    
    private fun _refreshForeground() {
        if (service?.serverState?.value == AirPlayService.ServerState.RUNNING) {
            startService(Intent(this, AirPlayService::class.java))
        }
    }

    private fun _syncLocalNetwork(requested: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val p = Manifest.permission.ACCESS_LOCAL_NETWORK
        val prev = viewModel.localNetwork.value
        val now = when {
            hasLocalNetwork() -> LocalNetworkAccess.GRANTED
            shouldShowRequestPermissionRationale(p) -> LocalNetworkAccess.ASK
            requested || prev == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
            else -> LocalNetworkAccess.ASK
        }
        viewModel.setLocalNetwork(now)
        if (now == LocalNetworkAccess.GRANTED && prev != LocalNetworkAccess.GRANTED) _autoStart()
    }

    private fun _requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (viewModel.localNetwork.value == LocalNetworkAccess.SETTINGS) {
            startActivity(appSettingsIntent())
        } else {
            runtimePermissionsPending = true
            permissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    override fun onStart() {
        super.onStart()
        _syncLocalNetwork()
        _refreshForeground()
        _autoStart()
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        val granted = canAutoLaunchCast()
        val next = if (autoLaunchOnboarding.waitingForSettings) autoLaunchOnboarding.settingsReturned(granted)
            else AutoLaunchOnboarding.restore(granted, autoLaunchOnboarding.handled, false, autoLaunchOnboarding.step)
        if (next != autoLaunchOnboarding) persistAutoLaunchOnboarding(next)
    }

    override fun onPause() {
        activityResumed = false
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("auto_launch_step", autoLaunchOnboarding.step.name)
        outState.putBoolean("runtime_permissions_pending", runtimePermissionsPending)
        super.onSaveInstanceState(outState)
    }

    private fun persistAutoLaunchOnboarding(state: AutoLaunchOnboarding) {
        autoLaunchOnboarding = state
        onboardingPrefs.edit().putBoolean("handled", state.handled)
            .putBoolean("waiting_for_settings", state.waitingForSettings).apply()
    }

    private fun requestAutoLaunchPermission() {
        if (canAutoLaunchCast()) {
            persistAutoLaunchOnboarding(autoLaunchOnboarding.settingsReturned(true))
            return
        }
        persistAutoLaunchOnboarding(autoLaunchOnboarding.openSettings())
        if (!openAutoLaunchSettings()) {
            persistAutoLaunchOnboarding(autoLaunchOnboarding.settingsUnavailable())
        }
    }

    private fun hasActivePlayback() = viewModel.audioOnly.value || viewModel.videoPlaybackActive.value ||
        viewModel.mirroringActive.value || viewModel.videoSessionPending.value

    private fun exitToTv() {
        service?.endCurrentSession()
        finish()
    }

    fun enterPip() {
        if (isTvDevice() || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        enterPictureInPictureMode(_pipParams())
    }

    private fun _pipParams(): PictureInPictureParams {
        val aspect = if (viewModel.videoPlaybackActive.value) viewModel.videoPlaybackAspect.value
            else viewModel.videoAspect.value
        // Android accepts PiP ratios only in [1 / 2.39, 2.39]. Portrait phones
        // and malformed stream metadata must not cause IllegalArgumentException.
        val ratio = if (aspect.isFinite()) aspect else 16f / 9f
        val rational = Rational((ratio * 1000).toInt().coerceIn(419, 2390), 1000)
        val builder = PictureInPictureParams.Builder().setAspectRatio(rational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(_shouldAutoPip())
        }
        return builder.build()
    }

    private fun _shouldAutoPip(): Boolean =
        !isTvDevice() && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            hasActivePlayback() && autoLaunchOnboarding.step == AutoLaunchStep.NONE

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && _shouldAutoPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        isInPip.value = inPip
    }

    // Leaving the screen never changes receiver availability. The foreground service
    // remains discoverable for the next phone connection.

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        super.onDestroy()
    }
}
