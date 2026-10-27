package io.github.jqssun.airplay.dlna

import android.content.Context
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.SocketException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Small UPnP AV renderer: discovery/control/eventing only; the app owns playback and Android lifecycle. */
class DlnaRenderer(
    context: Context,
    private val name: String,
    private val controller: DlnaPlayerController,
    private val onStatus: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val uuid = context.applicationContext.getSharedPreferences("yunyu_dlna", Context.MODE_PRIVATE).let { prefs ->
        prefs.getString("renderer_uuid", null) ?: "uuid:${UUID.randomUUID()}".also {
            prefs.edit().putString("renderer_uuid", it).apply()
        }
    }
    private data class Lan(val iface: NetworkInterface, val address: Inet4Address, val prefix: Int)
    private data class Subscription(
        val sid: String, val service: String, val callback: URI, val peer: InetAddress,
        @Volatile var expiresAt: Long, var seq: Long = 0, var lastBody: String? = null, var failures: Int = 0,
    ) {
        lateinit var delivery: LatestEventDelivery
        @Volatile var ready = false
    }
    private class Runtime(val lans: List<Lan>, val http: ServerSocket, val multicast: MulticastSocket) {
        val active = AtomicBoolean(true)
        val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
        val subscriptions = ConcurrentHashMap<String, Subscription>()
        val subscriptionLock = Any()
        val snapshotQueued = AtomicBoolean()
        // Only accessed by the single snapshot worker. Position is queried by SOAP, not evented.
        var lastEventSnapshot: DlnaPlaybackSnapshot? = null
        var eventBodies = emptyMap<String, String>()
        val announcementLock = Any()
        val searchQueued = AtomicInteger()
        val workers = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(24), { r ->
            Thread(r, "Yunyu-DLNA-http").apply { isDaemon = true }
        })
        val events = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32), { r ->
            Thread(r, "Yunyu-DLNA-notify").apply { isDaemon = true }
        })
        val snapshots = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1), { r ->
            Thread(r, "Yunyu-DLNA-state").apply { isDaemon = true }
        })
        val scheduler = ScheduledThreadPoolExecutor(2) { r ->
            Thread(r, "Yunyu-DLNA-events").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
    }
    @Volatile private var runtime: Runtime? = null
    private val commandLock = Any()
    private val diagnosticLock = Any()
    private val nextDiagnosticAt = mutableMapOf<String, Long>()
    private val targets get() = listOf("upnp:rootdevice", uuid, DlnaDescriptions.DEVICE) + DlnaDescriptions.services.values

    /** Invoke from IO. State callbacks may arrive on network workers. */
    @Synchronized fun start() {
        if (runtime != null) return
        var http: ServerSocket? = null
        var multicast: MulticastSocket? = null
        try {
            val lans = localInterfaces()
            if (lans.isEmpty()) { onStatus("等待局域网连接"); return }
            val httpServer = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0), 16) }
            http = httpServer
            val ssdpSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(1900))
                timeToLive = 2
            }
            multicast = ssdpSocket
            val joined = lans.filter { lan ->
                try { ssdpSocket.joinGroup(InetSocketAddress(GROUP, 1900), lan.iface); true }
                catch (e: Exception) { log("DLNA 无法加入 ${lan.iface.name}: ${e.message}"); false }
            }
            if (joined.isEmpty()) throw SocketException("局域网多播不可用")
            val rt = Runtime(joined, httpServer, ssdpSocket)
            runtime = rt
            Thread({ acceptLoop(rt) }, "Yunyu-DLNA-listen").apply { isDaemon = true }.start()
            Thread({ searchLoop(rt) }, "Yunyu-DLNA-ssdp").apply { isDaemon = true }.start()
            announce(rt, true)
            rt.scheduler.schedule({ if(rt.active.get()) announce(rt, true) }, 1, TimeUnit.SECONDS)
            rt.scheduler.scheduleAtFixedRate({ if(rt.active.get()) announce(rt, true) }, 90, 90, TimeUnit.SECONDS)
            rt.scheduler.scheduleWithFixedDelay({ queueEventSnapshot(rt) }, 500, 1000, TimeUnit.MILLISECONDS)
            val addresses = joined.map { it.address.hostAddress }.distinct().joinToString(" / ")
            onStatus("已启动 · $addresses:${httpServer.localPort}")
            log("DLNA 已启动，名称=$name，地址=$addresses，端口=${httpServer.localPort}")
        } catch (e: Exception) {
            runtime?.let { closeRuntime(it) }
            runtime = null
            runCatching { http?.close() }; runCatching { multicast?.close() }
            onStatus("启动失败：${e.message ?: e.javaClass.simpleName}")
            log("DLNA 启动失败：${e.message}")
        }
    }

    @Synchronized fun stop() {
        val rt = runtime ?: return
        runtime = null
        closeRuntime(rt)
        onStatus("已停止")
    }

    @Synchronized fun refreshNetwork() {
        runtime?.let { closeRuntime(it) }
        runtime = null
        start()
    }

    private fun closeRuntime(rt: Runtime) {
        if (!rt.active.getAndSet(false)) return
        announce(rt, false)
        runCatching { rt.http.close() }; runCatching { rt.multicast.close() }
        rt.clients.forEach { runCatching { it.close() } }
        rt.clients.clear()
        synchronized(rt.subscriptionLock) {
            rt.subscriptions.values.forEach { it.delivery.close() }
            rt.subscriptions.clear()
        }
        rt.scheduler.shutdownNow(); rt.workers.shutdownNow(); rt.events.shutdownNow(); rt.snapshots.shutdownNow()
    }

    private fun localInterfaces(): List<Lan> = Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { nif ->
        if (!nif.isUp || nif.isLoopback || nif.isPointToPoint || !nif.supportsMulticast()) emptyList()
        else nif.interfaceAddresses.mapNotNull { addr ->
            (addr.address as? Inet4Address)?.takeUnless { it.isLoopbackAddress || it.isAnyLocalAddress }
                ?.let { Lan(nif, it, addr.networkPrefixLength.toInt().coerceIn(0, 32)) }
        }
    }

    private fun usn(target: String) = if(target == uuid) uuid else "$uuid::$target"
    private fun location(rt: Runtime, lan: Lan) = "http://${lan.address.hostAddress}:${rt.http.localPort}/device.xml"
    private fun announce(rt: Runtime, alive: Boolean) = synchronized(rt.announcementLock) {
        if (alive && !rt.active.get()) return@synchronized
        rt.lans.forEach { lan ->
            try {
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(lan.address, 0))
                    socket.networkInterface = lan.iface
                    socket.timeToLive = 2
                    targets.forEach { target ->
                        val message = buildString {
                            append("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: $target\r\nNTS: ssdp:${if(alive) "alive" else "byebye"}\r\nUSN: ${usn(target)}\r\n")
                            if (alive) append("CACHE-CONTROL: max-age=180\r\nLOCATION: ${location(rt, lan)}\r\nSERVER: $SERVER\r\n")
                            append("\r\n")
                        }.toByteArray(Charsets.UTF_8)
                        socket.send(DatagramPacket(message, message.size, GROUP, 1900))
                    }
                }
            } catch (e: Exception) { if(rt.active.get()) log("DLNA 设备广播失败 ${lan.iface.name}：${e.message}") }
        }
    }

    private fun searchLoop(rt: Runtime) {
        val buffer = ByteArray(8192)
        while(rt.active.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                rt.multicast.receive(packet)
                val lines = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).split("\r\n")
                if (!lines.firstOrNull().orEmpty().equals("M-SEARCH * HTTP/1.1", true)) continue
                val headers = lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':'); if(i < 1) null else line.substring(0,i).lowercase(Locale.US) to line.substring(i+1).trim()
                }.toMap()
                if (!headers["man"].orEmpty().contains("ssdp:discover", true)) continue
                val st = headers["st"] ?: continue
                val matching = if(st.equals("ssdp:all", true)) targets else targets.filter { it.equals(st, true) }
                if (matching.isEmpty()) continue
                val address = packet.address
                val port = packet.port
                val lan = rt.lans.firstOrNull { sameSubnet(it, address) } ?: continue
                if(rt.searchQueued.incrementAndGet() > 32) { rt.searchQueued.decrementAndGet(); continue }
                val mx = headers["mx"]?.toLongOrNull()?.coerceIn(1,5) ?: 1L
                try { rt.scheduler.schedule({
                    try {
                        if(rt.active.get()) DatagramSocket(InetSocketAddress(lan.address, 0)).use { socket ->
                            matching.forEach { target ->
                                val data = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=180\r\nEXT:\r\n" +
                                    "LOCATION: ${location(rt, lan)}\r\nSERVER: $SERVER\r\nST: $target\r\nUSN: ${usn(target)}\r\n\r\n").toByteArray(Charsets.UTF_8)
                                socket.send(DatagramPacket(data, data.size, address, port))
                            }
                        }
                    } catch(e: Exception) { if(rt.active.get()) log("DLNA 搜索响应失败：${e.message}") }
                    finally { rt.searchQueued.decrementAndGet() }
                }, ThreadLocalRandom.current().nextLong(mx * 1000), TimeUnit.MILLISECONDS) }
                catch (_: RejectedExecutionException) { rt.searchQueued.decrementAndGet() }
            } catch(e: Exception) {
                if(rt.active.get()) {
                    log("DLNA 搜索接收异常：${e.message}")
                    onStatus("发现异常：${e.message ?: "请重新启动接收"}")
                }
                if(e is SocketException || !rt.active.get()) break
            }
        }
    }

    private fun sameSubnet(lan: Lan, remote: InetAddress): Boolean {
        if(remote !is Inet4Address) return false
        val local = lan.address.address; val other = remote.address
        var bits = lan.prefix
        for(i in local.indices) {
            val mask = if(bits >= 8) 255 else if(bits > 0) (255 shl (8 - bits)) and 255 else 0
            if((local[i].toInt() and mask) != (other[i].toInt() and mask)) return false
            bits = (bits - 8).coerceAtLeast(0)
        }
        return true
    }

    private fun acceptLoop(rt: Runtime) {
        while(rt.active.get()) {
            try {
                val socket = rt.http.accept().apply { soTimeout = 5000 }
                rt.clients.add(socket)
                if (!rt.active.get()) { rt.clients.remove(socket); socket.close(); break }
                try {
                    // A per-read timeout alone lets a trickling client hold every worker forever.
                    val deadline = rt.scheduler.schedule({ runCatching { socket.close() } }, 15, TimeUnit.SECONDS)
                    try { rt.workers.execute {
                        try { socket.use { try { handle(rt, it) } catch (_: Exception) {} } }
                        finally { deadline.cancel(false); rt.clients.remove(socket) }
                    } } catch (e: RejectedExecutionException) { deadline.cancel(false); throw e }
                } catch(_: RejectedExecutionException) { rt.clients.remove(socket); socket.close() }
            } catch(e: Exception) {
                if(rt.active.get()) {
                    log("DLNA HTTP 监听异常：${e.message}")
                    onStatus("控制服务异常：${e.message ?: "请重新启动接收"}")
                }
                break
            }
        }
    }

    private fun handle(rt: Runtime, socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        var requestMethod = "未知方法"
        var controlService = "未知服务"
        var controlAction = "未解析动作"
        try {
            val first = readLine(input, 8192).split(' ')
            if(first.size != 3 || !first[2].startsWith("HTTP/1.")) throw HttpError(400, "Bad Request")
            val method = first[0].uppercase(Locale.US)
            val path = first[1].substringBefore('?')
            requestMethod = method.takeIf { it in setOf("GET", "HEAD", "POST", "SUBSCRIBE", "UNSUBSCRIBE") } ?: "未知方法"
            controlService = path.split('/').getOrNull(1)?.takeIf { it in DlnaDescriptions.services } ?: "未知服务"
            val headers = linkedMapOf<String, String>()
            var bytes = 0
            while(true) {
                val line = readLine(input, 8192)
                bytes += line.length
                if(bytes > 32768) throw HttpError(431, "Request Header Fields Too Large")
                if(line.isEmpty()) break
                val i = line.indexOf(':')
                if(i < 1) throw HttpError(400, "Bad Request")
                val key = line.substring(0,i).lowercase(Locale.US)
                if(headers.put(key, line.substring(i+1).trim()) != null) throw HttpError(400, "Duplicate Header")
            }
            if(!rt.active.get()) throw HttpError(503, "Service Unavailable")
            if (headers["expect"].equals("100-continue", true)) { output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush() }
            val service = path.split('/').getOrNull(1).orEmpty()
            when(method) {
                "GET", "HEAD" -> {
                    val body = when {
                        path == "/device.xml" || path == "/description.xml" -> DlnaDescriptions.device(name, uuid)
                        service in DlnaDescriptions.services && path == "/$service/scpd.xml" -> DlnaDescriptions.scpd(service)
                        else -> throw HttpError(404, "Not Found")
                    }
                    response(output, 200, "OK", body, head = method == "HEAD")
                    if (method == "GET" && (path == "/device.xml" || path == "/description.xml")) {
                        diagnostic("device-description", "DLNA 设备描述已读取", 60_000L)
                    }
                }
                "POST" -> {
                    if(service !in DlnaDescriptions.services || path != "/$service/control") throw HttpError(404, "Not Found")
                    val body = readBody(input, headers).toString(Charsets.UTF_8)
                    try {
                        val command = DlnaSoap.parse(body)
                        controlAction = command.action.takeIf { it in DIAGNOSTIC_ACTIONS } ?: "未知动作"
                        if (command.action in IMPORTANT_CONTROL_ACTIONS) {
                            diagnostic("receive:$controlAction", "DLNA 收到控制：$controlService/$controlAction")
                        }
                        headers["soapaction"]?.trim('"')?.let {
                            if(it != "${DlnaDescriptions.services[service]}#${command.action}") throw DlnaActionException(401, "Invalid Action")
                        }
                        val answer = synchronized(commandLock) {
                            if(!rt.active.get()) throw DlnaActionException(501, "Action Failed")
                            DlnaSoap.execute(service, command, controller)
                        }
                        response(output, 200, "OK", answer)
                        if (service == "ConnectionManager" && command.action == "GetProtocolInfo") {
                            diagnostic("protocol-info", "DLNA 媒体能力已查询", 60_000L)
                        }
                    } catch(e: DlnaActionException) {
                        diagnostic("error", "DLNA 控制被拒绝：$controlService/$controlAction，UPnP ${e.code}，${controlFaultReason(e.code)}")
                        response(output, 500, "Internal Server Error", DlnaSoap.fault(e.code,e.message))
                    }
                    catch(e: Exception) {
                        diagnostic("error", "DLNA 控制失败：$controlService/$controlAction，UPnP 501，播放器暂时无法执行")
                        response(output, 500, "Internal Server Error", DlnaSoap.fault(501,"Action Failed"))
                    }
                }
                "SUBSCRIBE" -> {
                    if(service !in DlnaDescriptions.services || path != "/$service/event") throw HttpError(404, "Not Found")
                    subscribe(rt, socket, output, service, headers)
                }
                "UNSUBSCRIBE" -> {
                    val sid = headers["sid"] ?: throw HttpError(412, "Precondition Failed")
                    val sub = rt.subscriptions[sid] ?: throw HttpError(412, "Precondition Failed")
                    if(path != "/${sub.service}/event" || sub.peer != socket.inetAddress || headers.containsKey("callback") || headers.containsKey("nt")) throw HttpError(412, "Precondition Failed")
                    rt.subscriptions.remove(sid, sub)
                    sub.delivery.close()
                    response(output, 200, "OK", "")
                }
                else -> throw HttpError(405, "Method Not Allowed")
            }
        } catch(e: HttpError) {
            diagnostic("error", "DLNA HTTP 请求被拒绝：$requestMethod $controlService/$controlAction，HTTP ${e.code}")
            response(output, e.code, e.reason, "")
        } catch (e: Exception) {
            if (requestMethod == "POST") {
                diagnostic("error", "DLNA 控制请求未完成：$controlService/$controlAction，连接中断或读取失败")
            }
            throw e
        }
    }

    // Fixed keys and allowlisted labels keep diagnostics bounded and exclude URLs, metadata and headers.
    private fun diagnostic(key: String, message: String, intervalMs: Long = 1000L) {
        val now = SystemClock.elapsedRealtime()
        val emit = synchronized(diagnosticLock) {
            if (now < (nextDiagnosticAt[key] ?: 0L)) false
            else { nextDiagnosticAt[key] = now + intervalMs; true }
        }
        if (emit) log(message)
    }

    private fun controlFaultReason(code: Int): String = when (code) {
        401 -> "不支持的控制动作"
        402 -> "参数或 XML 格式无效"
        501 -> "播放器暂时无法执行"
        701 -> "当前播放状态不允许该动作"
        710 -> "不支持的跳转方式"
        711 -> "跳转位置无效"
        714 -> "媒体地址类型不支持"
        716 -> "媒体地址无效"
        717 -> "不支持的播放速度"
        718 -> "播放实例无效"
        else -> "控制请求被拒绝"
    }

    private fun subscribe(rt: Runtime, socket: Socket, output: OutputStream, service: String, headers: Map<String,String>) {
        val timeout = headers["timeout"]?.substringAfter("Second-", "")?.toLongOrNull()?.coerceIn(60,1800) ?: 1800L
        val existing = headers["sid"]
        if(existing != null) {
            if(headers.containsKey("callback") || headers.containsKey("nt")) throw HttpError(400, "Bad Request")
            synchronized(rt.subscriptionLock) {
                val sub = rt.subscriptions[existing] ?: throw HttpError(412,"Precondition Failed")
                if(sub.service != service || sub.peer != socket.inetAddress) throw HttpError(412,"Precondition Failed")
                sub.expiresAt = SystemClock.elapsedRealtime() + timeout*1000
            }
            response(output,200,"OK","", mapOf("SID" to existing,"TIMEOUT" to "Second-$timeout")); return
        }
        if(!headers["nt"].equals("upnp:event", true)) throw HttpError(412,"Precondition Failed")
        val callbackText = headers["callback"] ?: throw HttpError(412,"Precondition Failed")
        val callback = try { URI(Regex("<([^>]+)>").find(callbackText)?.groupValues?.get(1) ?: "") } catch(_: Exception) { throw HttpError(412,"Precondition Failed") }
        if(callback.scheme != "http" || callback.host.isNullOrBlank() || callback.userInfo != null || callback.fragment != null || callback.port !in -1..65535 || callback.port == 0) throw HttpError(412,"Precondition Failed")
        val callbackAddresses = try { InetAddress.getAllByName(callback.host).toList() } catch(_: Exception) { emptyList() }
        if(socket.inetAddress !in callbackAddresses) throw HttpError(412,"Precondition Failed")
        val sid = "uuid:${UUID.randomUUID()}"
        val sub = Subscription(sid,service,callback,socket.inetAddress,SystemClock.elapsedRealtime()+timeout*1000)
        sub.delivery = LatestEventDelivery(rt.events) { deliverEvent(rt, sub, it) }
        synchronized(rt.subscriptionLock) {
            if (!rt.active.get() || rt.subscriptions.size >= 32) throw HttpError(503,"Service Unavailable")
            rt.subscriptions[sid] = sub
        }
        try {
            // No event can be dispatched until the subscribe response has been flushed.
            response(output,200,"OK","",mapOf("SID" to sid,"TIMEOUT" to "Second-$timeout"))
            sub.ready = true
        } catch (e: Exception) {
            rt.subscriptions.remove(sid, sub)
            sub.delivery.close()
            throw e
        }
        queueEventSnapshot(rt)
    }

    private fun queueEventSnapshot(rt: Runtime) {
        if (!rt.active.get() || rt.subscriptions.isEmpty() || !rt.snapshotQueued.compareAndSet(false, true)) return
        try { rt.snapshots.execute {
            try { publishEvents(rt) } finally { rt.snapshotQueued.set(false) }
        } } catch (_: RejectedExecutionException) { rt.snapshotQueued.set(false) }
    }

    private fun publishEvents(rt: Runtime) {
        if(!rt.active.get() || rt.subscriptions.isEmpty()) return
        try {
            val now = SystemClock.elapsedRealtime()
            synchronized(rt.subscriptionLock) {
                rt.subscriptions.entries.removeIf {
                    (it.value.expiresAt <= now).also { expired -> if (expired) it.value.delivery.close() }
                }
            }
            if(rt.subscriptions.isEmpty()) return
            val snapshot = controller.snapshot().copy(positionMs = 0)
            if (snapshot != rt.lastEventSnapshot) {
                rt.eventBodies = DlnaDescriptions.services.keys.associateWith { DlnaSoap.event(it,snapshot) }
                rt.lastEventSnapshot = snapshot
            }
            synchronized(rt.subscriptionLock) {
                rt.subscriptions.values.forEach { sub ->
                    if(rt.active.get() && sub.ready) sub.delivery.submit(rt.eventBodies.getValue(sub.service))
                }
            }
        } catch(e: Exception) { if(rt.active.get()) log("DLNA 状态通知失败：${e.message}") }
    }

    private fun deliverEvent(rt: Runtime, sub: Subscription, body: String) {
        if(!rt.active.get() || rt.subscriptions[sub.sid] !== sub || sub.expiresAt <= SystemClock.elapsedRealtime() || sub.lastBody == body) return
        val socket = Socket()
        rt.clients.add(socket)
        var deadline: Future<*>? = null
        try {
            // Connect to the original subscriber IP, not a re-resolved callback host (no redirect/rebinding).
            socket.use {
                if (!rt.active.get()) return
                deadline = rt.scheduler.schedule({ runCatching { socket.close() } }, 5, TimeUnit.SECONDS)
                socket.connect(InetSocketAddress(sub.peer, if(sub.callback.port < 0) 80 else sub.callback.port), 1500)
                socket.soTimeout = 1500
                val path = sub.callback.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                val target = path + (sub.callback.rawQuery?.let { "?$it" } ?: "")
                val host = sub.callback.rawAuthority
                val data = body.toByteArray(Charsets.UTF_8)
                val out = socket.getOutputStream()
                out.write(("NOTIFY $target HTTP/1.1\r\nHOST: $host\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${data.size}\r\n" +
                    "NT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${sub.sid}\r\nSEQ: ${sub.seq}\r\nCONNECTION: close\r\n\r\n").toByteArray(Charsets.UTF_8))
                out.write(data); out.flush()
                val status = readLine(BufferedInputStream(socket.getInputStream()),8192).split(' ').getOrNull(1)?.toIntOrNull()
                if(status !in 200..299) throw java.io.IOException("Event response $status")
                sub.lastBody = body
                sub.seq = if(sub.seq == 0xffffffffL) 1 else sub.seq+1
                sub.failures = 0
            }
        } catch(_: Exception) {
            sub.failures++
            if(sub.failures >= 3) { rt.subscriptions.remove(sub.sid,sub); sub.delivery.close() }
        } finally { deadline?.cancel(false); rt.clients.remove(socket) }
    }

    private class HttpError(val code: Int,val reason: String): Exception(reason)
    private fun readLine(input: InputStream,max: Int): String {
        val bytes = ByteArrayOutputStream()
        while(true) {
            val b = input.read()
            if(b < 0) throw java.io.EOFException()
            if(b == 10) break
            if(bytes.size() >= max) throw HttpError(431,"Request Header Fields Too Large")
            bytes.write(b)
        }
        return bytes.toString("ISO-8859-1").removeSuffix("\r")
    }
    private fun readBody(input: InputStream,headers: Map<String,String>): ByteArray {
        val transfer = headers["transfer-encoding"]
        if(transfer != null && headers.containsKey("content-length")) throw HttpError(400,"Bad Request")
        if(transfer != null) {
            if(!transfer.equals("chunked",true)) throw HttpError(501,"Not Implemented")
            val result = ByteArrayOutputStream()
            while(true) {
                val size = readLine(input,128).substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400,"Bad Request")
                if(size < 0 || size > MAX_BODY - result.size()) throw HttpError(413,"Payload Too Large")
                if(size == 0) {
                    var trailerSize = 0
                    while(true) { val line = readLine(input,8192); trailerSize+=line.length; if(trailerSize>32768) throw HttpError(431,"Request Header Fields Too Large"); if(line.isEmpty()) break }
                    return result.toByteArray()
                }
                result.write(readExactly(input,size))
                if(readLine(input,2).isNotEmpty()) throw HttpError(400,"Bad Request")
            }
        }
        val size = headers["content-length"]?.toIntOrNull() ?: throw HttpError(411,"Length Required")
        if(size < 0 || size > MAX_BODY) throw HttpError(413,"Payload Too Large")
        return readExactly(input,size)
    }
    private fun readExactly(input: InputStream,count: Int): ByteArray {
        val data = ByteArray(count)
        var offset = 0
        while(offset<count) { val n=input.read(data,offset,count-offset); if(n<0) throw java.io.EOFException(); offset+=n }
        return data
    }
    private fun response(output: OutputStream,code:Int,reason:String,body:String,headers:Map<String,String> = emptyMap(),head:Boolean=false) {
        val data=body.toByteArray(Charsets.UTF_8)
        val h=buildString {
            append("HTTP/1.1 $code $reason\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nCONTENT-LENGTH: ${data.size}\r\nSERVER: $SERVER\r\nCONNECTION: close\r\n")
            headers.forEach { (key,value)->append("$key: $value\r\n") }
            append("\r\n")
        }
        output.write(h.toByteArray(Charsets.UTF_8)); if(!head) output.write(data); output.flush()
    }
    companion object {
        private val IMPORTANT_CONTROL_ACTIONS = setOf("SetAVTransportURI", "Play", "Pause", "Stop")
        private val DIAGNOSTIC_ACTIONS = IMPORTANT_CONTROL_ACTIONS + setOf(
            "Seek", "GetTransportInfo", "GetPositionInfo", "GetMediaInfo", "GetDeviceCapabilities",
            "GetTransportSettings", "GetCurrentTransportActions", "SetPlayMode", "GetVolume", "SetVolume",
            "GetMute", "SetMute", "ListPresets", "SelectPreset", "GetProtocolInfo", "GetCurrentConnectionIDs",
            "GetCurrentConnectionInfo",
        )
        private const val SERVER = "Android UPnP/1.0 YunyuCast/0.2"
        private const val MAX_BODY = 1024*1024
        private val GROUP: InetAddress = InetAddress.getByName("239.255.255.250")
    }
}
