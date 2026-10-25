package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

/** Publishes only after NSD confirms registration; all mutable NSD state lives on the main thread. */
class NsdServiceManager(
    ctx: Context,
    private val onStatus: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val context = ctx.applicationContext
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableSetOf<Registration>()
    private val delayedTasks = mutableSetOf<Runnable>()
    private val compatibility = mutableMapOf<String, UsbMdnsAdvertiser>()
    private val compatibilityStates = mutableMapOf<String, String>()
    private val fallbackServices = linkedSetOf<MdnsService>()
    private var compatibilityGeneration = 0
    private var services = emptyList<MdnsService>()
    private var generation = 0
    private var restartPending = false
    private var registrationStatus = "未发布"
    @Volatile private var released = false

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post { block() }
    }

    private fun report(message: String) {
        if (!released) onStatus(message)
    }

    private fun reportRegistration(message: String) {
        registrationStatus = message
        publishState()
    }

    private fun record(message: String, error: Boolean = false) {
        if (error) Log.e(TAG, message) else Log.i(TAG, message)
        if (!released) log(message)
    }

    fun acquireMulticastLock() = onMain {
        if (released || multicastLock?.isHeld == true) return@onMain
        try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("yunyu_discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            record("无法获取多播锁：${e.message}", error = true)
        }
    }

    fun register(services: List<MdnsService>) = onMain {
        if (released) return@onMain
        this.services = services.map { it.copy(txt = it.txt.toMap()) }
        beginRefresh()
    }

    /** Re-publish after LAN changes, without overlapping old and new registrations. */
    fun refresh() = onMain {
        if (!released && services.isNotEmpty()) beginRefresh()
    }

    private fun beginRefresh() {
        generation++
        cancelDelayedTasks()
        stopCompatibility()
        fallbackServices.clear()
        restartPending = true
        reportRegistration("正在发布")
        registrations.toList().forEach {
            it.cancelled = true
            it.unregisterAttempts = 0
            unregister(it)
        }
        val cycle = generation
        later(CALLBACK_TIMEOUT_MS) {
            if (!released && cycle == generation && restartPending && registrations.isNotEmpty()) {
                // A stuck Android unregister must not leave the TV undiscoverable indefinitely.
                fallbackServices.addAll(services)
                startCompatibility()
                record("系统发现注销超时，已启用兼容发现", error = true)
            }
        }
        startWhenDrained()
    }

    private fun startWhenDrained() {
        if (released || !restartPending || registrations.isNotEmpty()) return
        restartPending = false
        stopCompatibility()
        fallbackServices.clear()
        if (services.isEmpty()) {
            reportRegistration("未发布")
            return
        }
        services.forEach { registerOne(it, generation, 0) }
    }

    private inner class Registration(val service: MdnsService, val generation: Int, val attempt: Int) {
        var registered = false
        var actualName = service.name
        var cancelled = false
        var unregistering = false
        var unregisterAttempts = 0
        var watchdog: Runnable? = null
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onMain {
                cancelWatchdog(this@Registration)
                registered = true
                actualName = info.serviceName
                if (released || cancelled || generation != this@NsdServiceManager.generation) {
                    cancelled = true
                    unregister(this@Registration)
                } else {
                    record("${service.type} 已发布：$actualName")
                    publishState()
                }
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = onMain {
                failed(this@Registration, "NSD错误 $code")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = onMain {
                cancelWatchdog(this@Registration)
                registrations.remove(this@Registration)
                record("${service.type} 已注销：${info.serviceName}")
                startWhenDrained()
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = onMain {
                unregistering = false
                record("${service.type} 注销失败：$code", error = true)
                if (cancelled && unregisterAttempts < MAX_UNREGISTER_ATTEMPTS) {
                    later(500L * unregisterAttempts.coerceAtLeast(1)) {
                        if (this@Registration in registrations) unregister(this@Registration)
                    }
                } else if (!released && restartPending) {
                    // Do not publish duplicates while Android still owns the old registration.
                    reportRegistration("系统发现尚未恢复，兼容发现将自动接续")
                }
            }
        }
    }

    private fun registerOne(service: MdnsService, cycle: Int, attempt: Int) {
        if (released || cycle != generation || restartPending) return
        val registration = Registration(service, cycle, attempt)
        registrations += registration
        registration.watchdog = later(CALLBACK_TIMEOUT_MS) {
            if (!released && cycle == generation && !registration.cancelled &&
                !registration.registered && registration in registrations) {
                registration.cancelled = true
                // Keep ownership of the listener: any late success is immediately unregistered.
                unregister(registration)
                fallbackServices += service
                startCompatibility()
                record("${service.type} 系统发现响应超时，已启用兼容发现", error = true)
            }
        }
        try {
            val info = NsdServiceInfo().apply {
                serviceName = service.name
                serviceType = service.type
                port = service.port
                service.txt.forEach { (key, value) -> setAttribute(key, value) }
            }
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration.listener)
        } catch (e: Exception) {
            failed(registration, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failed(registration: Registration, reason: String) {
        cancelWatchdog(registration)
        if (!registrations.remove(registration)) return
        record("${registration.service.type} 发布失败：$reason", error = true)
        if (released || registration.cancelled || registration.generation != generation) {
            startWhenDrained()
            return
        }
        if (registration.attempt < MAX_REGISTRATION_RETRIES) {
            reportRegistration("发布失败，正在重试")
            val delayMs = 1000L shl registration.attempt
            later(delayMs) {
                registerOne(registration.service, registration.generation, registration.attempt + 1)
            }
        } else {
            reportRegistration("系统发布失败，正在启用兼容发现")
            fallbackServices += registration.service
            startCompatibility()
        }
    }

    private fun startCompatibility() {
        // Do not race Android NSD for the same name. Only fill services whose bounded NSD retries failed.
        stopCompatibility()
        val cycle = generation
        val compatibilityCycle = compatibilityGeneration
        lanIpv4Links(context).map { it.interfaceName }.distinct().forEach { interfaceName ->
            val advertiser = UsbMdnsAdvertiser(
                services = fallbackServices.toList(),
                networkInterfaceName = interfaceName,
                onStatus = { status ->
                    if (!released && generation == cycle && compatibilityGeneration == compatibilityCycle) {
                        compatibilityStates[interfaceName] = status
                        record("$interfaceName $status")
                        publishState()
                    }
                },
                onLink = { name, address ->
                    if (!released && generation == cycle && compatibilityGeneration == compatibilityCycle && name != null && address != null) {
                        record("兼容发现已绑定 $name ${address.hostAddress}")
                    }
                },
            )
            compatibility[interfaceName] = advertiser
            advertiser.start()
        }
    }

    private fun stopCompatibility() {
        compatibilityGeneration++
        compatibility.values.forEach { it.stop() }
        compatibility.clear()
        compatibilityStates.clear()
    }

    private fun publishState() {
        if (compatibilityStates.values.any { it == "兼容广播已启动" }) {
            val name = services.firstOrNull { it.type.startsWith("_airplay.") }?.name
            report("兼容广播已启动${name?.let { "：$it" }.orEmpty()}")
            return
        }
        val current = registrations.filter { !it.cancelled && it.generation == generation && it.registered }
        if (current.isEmpty() || current.size != services.size || restartPending) {
            report(registrationStatus)
            return
        }
        val airplay = current.firstOrNull { it.service.type.startsWith("_airplay.") }
        report("已发布：${airplay?.actualName ?: current.first().actualName}")
    }

    private fun unregister(registration: Registration) {
        if (registration.unregistering) return
        registration.unregisterAttempts++
        try {
            nsdManager.unregisterService(registration.listener)
            registration.unregistering = true
        } catch (_: IllegalArgumentException) {
            if (registration.registered) {
                // Android no longer knows this listener, so there is no registration to wait for.
                registrations.remove(registration)
                startWhenDrained()
            }
            // For a pending registration, wait for its callback and unregister immediately then.
        } catch (e: Exception) {
            record("${registration.service.type} 注销异常：${e.message}", error = true)
            if (registration.unregisterAttempts < MAX_UNREGISTER_ATTEMPTS) {
                later(500L * registration.unregisterAttempts) {
                    if (registration in registrations) unregister(registration)
                }
            } else if (!released) {
                reportRegistration("系统发现尚未恢复，兼容发现将自动接续")
            }
        }
    }

    private fun cancelWatchdog(registration: Registration) {
        registration.watchdog?.let {
            handler.removeCallbacks(it)
            delayedTasks.remove(it)
        }
        registration.watchdog = null
    }

    private fun later(delayMs: Long, block: () -> Unit): Runnable {
        lateinit var task: Runnable
        task = Runnable {
            delayedTasks.remove(task)
            block()
        }
        delayedTasks += task
        handler.postDelayed(task, delayMs)
        return task
    }

    private fun cancelDelayedTasks() {
        delayedTasks.forEach(handler::removeCallbacks)
        delayedTasks.clear()
    }

    fun release() {
        // Mark immediately so queued callbacks cannot publish status or schedule new registrations.
        released = true
        onMain {
            generation++
            restartPending = false
            cancelDelayedTasks()
            stopCompatibility()
            registrations.toList().forEach {
                it.cancelled = true
                unregister(it)
            }
            try {
                multicastLock?.let { if (it.isHeld) it.release() }
            } catch (e: Exception) {
                Log.w(TAG, "释放多播锁失败", e)
            } finally {
                multicastLock = null
            }
        }
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val MAX_REGISTRATION_RETRIES = 4
        private const val MAX_UNREGISTER_ATTEMPTS = 3
        private const val CALLBACK_TIMEOUT_MS = 8_000L
    }
}
