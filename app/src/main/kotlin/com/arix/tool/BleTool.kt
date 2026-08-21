package com.arix.tool

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.arix.app.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙一条龙：扫描 + BLE(GATT) 连接/读/写/订阅 + 经典蓝牙 SPP 收发。
 *
 * 原来这里只有 `ble_scan`，描述里白纸黑字写着「只扫描不连接」——也就是说 AI 能告诉你「你旁边有个手环」，
 * 然后就没有然后了。真要跟设备说上话（读手环电量、给自制 BLE 小玩意发指令、跟 HC-05 那类串口模块通信）
 * 一步都做不到。这次把连接与收发补齐，仍然是**一个工具多用**（action 分派），不新开五个工具。
 *
 * 会话是**跨调用保持**的：connect 之后连接一直在，后续 read/write/subscribe 直接用地址找回来，
 * 直到 disconnect 或进程结束。BLE 的一次连接握手要一两秒，每个动作重连一次既慢又必然丢通知。
 *
 * 权限（清单已声明，运行时仍需用户在系统弹窗授予）：
 *   - Android 12 (API 31)+：BLUETOOTH_SCAN（扫描）、BLUETOOTH_CONNECT（连接/读名字/配对列表）
 *   - Android 11 及以下：ACCESS_FINE_LOCATION（旧系统 BLE 扫描强制要定位权限）
 *
 * ⚠ 用的是 API 33 起标记废弃的那套同步 GATT API（characteristic.value / writeCharacteristic(char)）。
 * 本 App targetSdk=28，系统按 targetSdk 给的是旧行为，这条路是稳的；哪天真把 targetSdk 提到 33+，
 * 这里要跟着换成带 value 参数的新重载，否则读回来的值会是空的。
 */
class BleTool(private val context: Context) : Tool {
    override val name = "bluetooth"
    override val description =
        "蓝牙：扫描附近设备、连上 BLE 设备读写特征值/订阅通知、跟经典蓝牙串口(SPP)模块收发数据。" +
            "action=scan 扫描(名称/MAC/信号)、paired 已配对设备、connect/disconnect 连接与断开、services 看有哪些服务和特征、" +
            "read/write 读写特征值、subscribe 订阅通知、notifications 取回收到的通知、spp_connect/spp_send/spp_read 走经典串口。" +
            "写入设备会先问过你。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription =
        "Bluetooth. action=scan: list nearby BLE devices (name/MAC/RSSI). paired: bonded devices. connect/disconnect: keep a GATT connection to address. " +
            "services: list the connected device's services and characteristics with their properties. read/write: a characteristic value (encoding=text|hex). " +
            "subscribe: turn on notifications; notifications: drain what arrived since last time. spp_connect/spp_send/spp_read: classic Bluetooth serial (SPP), for HC-05 style modules — the device must already be paired in system settings. " +
            "Connections persist between calls, so connect once and then read/write. Writing to a device asks the user first."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf(
                    "scan", "paired", "connect", "disconnect", "services", "read", "write",
                    "subscribe", "notifications", "spp_connect", "spp_send", "spp_read",
                )))
                put("description", "what to do, default scan")
            })
            put("address", JSONObject().apply { put("type", "string"); put("description", "device MAC like AA:BB:CC:DD:EE:FF, from scan or paired. Required for everything except scan/paired") })
            put("service", JSONObject().apply { put("type", "string"); put("description", "service UUID; 16-bit short form (180d) or full UUID. Optional — without it the characteristic is looked up across all services") })
            put("characteristic", JSONObject().apply { put("type", "string"); put("description", "characteristic UUID, short or full form") })
            put("value", JSONObject().apply { put("type", "string"); put("description", "data to send (write / spp_send)") })
            put("encoding", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("text", "hex")))
                put("description", "how `value` is written and how results are shown back. text = UTF-8 (default), hex = bytes like 01ff0a")
            })
            put("no_response", JSONObject().apply { put("type", "boolean"); put("description", "write without waiting for the device to acknowledge (WRITE_NO_RESPONSE). Only if the characteristic supports it") })
            put("enable", JSONObject().apply { put("type", "boolean"); put("description", "subscribe: true to turn notifications on (default), false to turn them off") })
            put("uuid", JSONObject().apply { put("type", "string"); put("description", "spp_connect: RFCOMM service UUID, defaults to the standard serial port profile") })
            put("timeout_ms", JSONObject().apply { put("type", "integer"); put("description", "scan duration or operation timeout in ms. scan default 6000 (2000~15000), other actions default 10000") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    // ============================================================
    // 会话表：连接跨调用保持
    // ============================================================

    private class GattSession(val address: String) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var connected = false
        @Volatile var lastStatus = 0
        @Volatile var lastActiveElapsed: Long = SystemClock.elapsedRealtime()
        val connectSignal = CompletableDeferred<Boolean>()
        val discoverSignal = CompletableDeferred<Boolean>()
        @Volatile var readSignal: CompletableDeferred<Pair<Int, ByteArray?>>? = null
        @Volatile var writeSignal: CompletableDeferred<Int>? = null
        @Volatile var descSignal: CompletableDeferred<Int>? = null

        private val notif = ArrayDeque<String>()
        fun push(line: String) = synchronized(notif) {
            notif.addLast(line)
            while (notif.size > 200) notif.removeFirst()   // 只留最近的：设备可能每秒推几十条，攒着只会撑爆内存
        }
        fun drain(): List<String> = synchronized(notif) { notif.toList().also { notif.clear() } }
        fun pending(): Int = synchronized(notif) { notif.size }

        /** 断开时把所有等着的操作叫醒——不然调用方要干等到超时，还以为设备只是慢。 */
        fun failPending(status: Int) {
            readSignal?.complete(status to null)
            writeSignal?.complete(status)
            descSignal?.complete(status)
        }

        fun close() {
            connected = false
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            gatt = null
        }
    }

    private class SppSession(val address: String, val socket: BluetoothSocket) {
        @Volatile var alive = true
        @Volatile var lastActiveElapsed: Long = SystemClock.elapsedRealtime()
        private val buf = ByteArrayOutputStream()
        private val cap = 64 * 1024

        fun startReader() {
            Thread {
                val chunk = ByteArray(2048)
                try {
                    val ins = socket.inputStream
                    while (alive) {
                        val n = ins.read(chunk)
                        if (n < 0) break
                        if (n > 0) synchronized(buf) {
                            buf.write(chunk, 0, n)
                            if (buf.size() > cap) {                 // 只留最近 cap 字节
                                val all = buf.toByteArray(); buf.reset()
                                buf.write(all, all.size - cap, cap)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally { alive = false }
            }.apply { isDaemon = true; name = "spp-$address" }.start()
        }

        fun drain(): ByteArray = synchronized(buf) { buf.toByteArray().also { buf.reset() } }
        fun send(bytes: ByteArray) { socket.outputStream.write(bytes); socket.outputStream.flush() }
        fun close() { alive = false; runCatching { socket.close() } }
    }

    private companion object {
        /** 一台手表同时挂太多连接既费电又必然掉线，卡个上限。 */
        const val MAX_SESSIONS = 4
        /** 空闲自动断开：跨调用保留连接，但 5 分钟没有任何工具动作就回收，避免 GATT/SPP 永久在线。 */
        const val IDLE_TIMEOUT_MS = 5L * 60_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val gattSessions = ConcurrentHashMap<String, GattSession>()
        val sppSessions = ConcurrentHashMap<String, SppSession>()
    }

    // ============================================================

    /** 执行任何蓝牙动作前先回收空闲连接。 */
    private fun closeIdleSessions() {
        val now = SystemClock.elapsedRealtime()
        gattSessions.entries.toList().forEach { (addr, s) ->
            if (now - s.lastActiveElapsed > IDLE_TIMEOUT_MS) {
                s.close()
                gattSessions.remove(addr)
            }
        }
        sppSessions.entries.toList().forEach { (addr, s) ->
            if (now - s.lastActiveElapsed > IDLE_TIMEOUT_MS) {
                s.close()
                sppSessions.remove(addr)
            }
        }
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "scan").trim().lowercase().ifBlank { "scan" }
        closeIdleSessions()

        val manager = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter = manager?.adapter
            ?: return ToolResult(tr("此设备不支持蓝牙。"), isError = true)
        if (!adapter.isEnabled) return ToolResult(tr("蓝牙未开启，请先打开蓝牙再试。"), isError = true)

        val missing = missingPermissions(action)
        if (missing.isNotEmpty()) return ToolResult(
            tr("缺少蓝牙权限：") + missing.joinToString("、") + tr("。请在系统设置里授予后再试。"), isError = true)

        val addr = params.optString("address", "").trim().uppercase()
        val timeout = params.optInt("timeout_ms", 10000).coerceIn(1000, 60000).toLong()

        return try {
            when (action) {
                "scan" -> scan(adapter, params)
                "paired" -> paired(adapter)
                "connect" -> connect(adapter, addr, timeout)
                "disconnect" -> disconnect(addr)
                "services" -> services(addr)
                "read" -> readChar(addr, params, timeout)
                "write" -> writeChar(addr, params, timeout)
                "subscribe" -> subscribe(addr, params, timeout)
                "notifications" -> notifications(addr, params)
                "spp_connect" -> sppConnect(adapter, addr, params)
                "spp_send" -> sppSend(addr, params)
                "spp_read" -> sppRead(addr, params)
                else -> ToolResult("未知 action「$action」。可选：scan/paired/connect/disconnect/services/read/write/subscribe/notifications/spp_connect/spp_send/spp_read", isError = true)
            }
        } catch (c: CancellationException) {
            throw c   // STOP 要停得掉正在等设备回话的这一段
        } catch (e: SecurityException) {
            ToolResult(tr("蓝牙操作被系统拒绝（权限不足）：") + (e.message ?: ""), isError = true)
        } catch (e: Exception) {
            ToolResult("蓝牙操作失败：${e.message}", isError = true)
        }
    }

    // ---- 扫描 / 配对列表 ----

    private data class Found(val address: String, var name: String, var rssi: Int)

    private suspend fun scan(adapter: BluetoothAdapter, params: JSONObject): ToolResult {
        val timeoutMs = params.optInt("timeout_ms", 6000).coerceIn(2000, 15000).toLong()
        val scanner = adapter.bluetoothLeScanner
            ?: return ToolResult(tr("无法获取蓝牙扫描器（蓝牙可能刚被关闭），请稍后再试。"), isError = true)

        val results = ConcurrentHashMap<String, Found>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) { record(result) }
            override fun onBatchScanResults(batch: MutableList<ScanResult>?) { batch?.forEach { record(it) } }
            override fun onScanFailed(errorCode: Int) { /* 忽略，超时后按已收集结果返回 */ }

            private fun record(r: ScanResult?) {
                r ?: return
                val a = r.device?.address ?: return
                // 优先用广播包里的名字（不需要 CONNECT 权限）；退回 device.name（API31+ 需 CONNECT，故 try 包住）
                val advName = r.scanRecord?.deviceName?.trim().orEmpty()
                val devName = if (advName.isNotBlank()) advName else safeDeviceName(r)
                val prev = results[a]
                if (prev == null) results[a] = Found(a, devName, r.rssi)
                else {
                    if (prev.name.isBlank() && devName.isNotBlank()) prev.name = devName
                    if (r.rssi > prev.rssi) prev.rssi = r.rssi   // 保留最强的一次信号
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        return withContext(Dispatchers.IO) {
            try { scanner.startScan(null, settings, callback) }
            catch (e: SecurityException) { return@withContext ToolResult(tr("蓝牙扫描被系统拒绝（权限不足）：") + (e.message ?: ""), isError = true) }
            catch (e: Exception) { return@withContext ToolResult(tr("启动蓝牙扫描失败：") + (e.message ?: ""), isError = true) }

            try { delay(timeoutMs) } finally { runCatching { scanner.stopScan(callback) } }

            val list = results.values.sortedByDescending { it.rssi }
            if (list.isEmpty()) return@withContext ToolResult(tr("扫描结束，附近没有发现可见的 BLE 设备。"))
            ToolResult(buildString {
                append(tr("扫描到 ")).append(list.size).append(tr(" 个 BLE 设备（信号越接近 0 越强）：\n"))
                list.forEachIndexed { i, d ->
                    append("${i + 1}. ${d.name.ifBlank { tr("(未知名称)") }}  ${d.address}  RSSI=${d.rssi}dBm\n")
                }
                append(tr("要跟某台说话就 connect 它的 MAC。经典蓝牙(串口/音箱那类)扫不到，用 paired 看已配对的。"))
            })
        }
    }

    private fun paired(adapter: BluetoothAdapter): ToolResult {
        val bonded = try { adapter.bondedDevices } catch (e: SecurityException) { null }
            ?: return ToolResult(tr("读不到已配对列表（缺蓝牙连接权限）"), isError = true)
        if (bonded.isEmpty()) return ToolResult(tr("这台设备还没有配对过任何蓝牙设备。经典蓝牙(SPP)必须先在系统设置里配对。"))
        return ToolResult(buildString {
            append(tr("已配对设备 ")).append(bonded.size).append("：\n")
            bonded.forEach { d ->
                val type = when (d.type) {
                    BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典"
                    BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
                    else -> "未知"
                }
                append("· ${safeName(d).ifBlank { tr("(未知名称)") }}  ${d.address}  [$type]\n")
            }
        }.trim())
    }

    // ---- GATT ----

    private suspend fun connect(adapter: BluetoothAdapter, addr: String, timeout: Long): ToolResult {
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) return ToolResult("MAC 地址不对：$addr（应形如 AA:BB:CC:DD:EE:FF，先 scan 或 paired 拿一个）", isError = true)
        gattSessions[addr]?.let { old ->
            if (old.connected) return ToolResult("已经连着 $addr 了，直接 services / read / write 就行。")
            old.close(); gattSessions.remove(addr)
        }
        if (gattSessions.size >= MAX_SESSIONS)
            return ToolResult("同时开着的蓝牙连接太多（${gattSessions.size}），先 disconnect 一个再连。", isError = true)

        val device = adapter.getRemoteDevice(addr)
        val s = GattSession(addr)
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> { s.connected = true; s.connectSignal.complete(true) }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        s.connected = false; s.lastStatus = status
                        s.connectSignal.complete(false)
                        s.discoverSignal.complete(false)
                        s.failPending(status)
                    }
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                s.discoverSignal.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
                s.readSignal?.complete(status to c?.value)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt?, c: BluetoothGattCharacteristic?, status: Int) {
                s.writeSignal?.complete(status)
            }
            override fun onDescriptorWrite(g: BluetoothGatt?, d: BluetoothGattDescriptor?, status: Int) {
                s.descSignal?.complete(status)
            }
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt?, c: BluetoothGattCharacteristic?) {
                c ?: return
                s.push("${shortUuid(c.uuid)}  ${toHex(c.value)}${previewText(c.value)}")
            }
        }
        s.gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            ?: return ToolResult("连不上 $addr（系统没给出 GATT 客户端）", isError = true)

        val ok = withTimeoutOrNull(timeout) { s.connectSignal.await() } ?: false
        if (!ok) {
            s.close()
            return ToolResult("连接 $addr 失败或超时（status=${s.lastStatus}）。BLE 设备大多要**离得近且没被别的手机连着**；" +
                "另外先 scan 一下确认它此刻真的在广播。", isError = true)
        }
        runCatching { s.gatt?.discoverServices() }
        val discovered = withTimeoutOrNull(15000) { s.discoverSignal.await() } ?: false
        gattSessions[addr] = s
        if (!discovered) return ToolResult("已连上 $addr，但没能读出它的服务列表。可以再 disconnect + connect 试一次。")
        return ToolResult("已连上 $addr。\n" + describeServices(s) +
            "\n连接会一直保持到 disconnect；接着可以 read/write/subscribe。")
    }

    private fun disconnect(addr: String): ToolResult {
        val g = gattSessions.remove(addr)?.also { it.close() }
        val p = sppSessions.remove(addr)?.also { it.close() }
        return when {
            g != null && p != null -> ToolResult("已断开 $addr（BLE 与串口都断了）")
            g != null -> ToolResult("已断开 $addr")
            p != null -> ToolResult("已关闭与 $addr 的串口连接")
            else -> ToolResult("$addr 本来就没连着")
        }
    }

    private fun services(addr: String): ToolResult {
        val s = session(addr) ?: return notConnected(addr)
        return ToolResult(describeServices(s))
    }

    private fun describeServices(s: GattSession): String {
        val svcs = s.gatt?.services.orEmpty()
        if (svcs.isEmpty()) return "这台设备没有暴露任何服务（或还没发现完）。"
        return buildString {
            append("服务与特征（读写时 service 可以省略，我会全局找 characteristic）：\n")
            svcs.forEach { svc ->
                append("· 服务 ${shortUuid(svc.uuid)}\n")
                svc.characteristics.forEach { c ->
                    append("    - ${shortUuid(c.uuid)}  [${propsOf(c)}]\n")
                }
            }
        }.trim()
    }

    private fun propsOf(c: BluetoothGattCharacteristic): String {
        val p = c.properties
        return buildList {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-rsp")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }.joinToString(",").ifBlank { "无" }
    }

    @Suppress("DEPRECATION")
    private suspend fun readChar(addr: String, params: JSONObject, timeout: Long): ToolResult {
        val s = session(addr) ?: return notConnected(addr)
        val c = findChar(s, params) ?: return charNotFound(s, params)
        val d = CompletableDeferred<Pair<Int, ByteArray?>>().also { s.readSignal = it }
        if (s.gatt?.readCharacteristic(c) != true)
            return ToolResult("读不了 ${shortUuid(c.uuid)}：这个特征的属性是 [${propsOf(c)}]，多半不支持读。", isError = true)
        val r = withTimeoutOrNull(timeout) { d.await() }
            ?: return ToolResult("读 ${shortUuid(c.uuid)} 超时（${timeout}ms），设备没回话。", isError = true)
        s.readSignal = null
        if (r.first != BluetoothGatt.GATT_SUCCESS)
            return ToolResult("读失败，GATT status=${r.first}（133 通常是连接不稳，重连一次再试；15/5 是需要配对/加密）。", isError = true)
        val v = r.second ?: ByteArray(0)
        return ToolResult("${shortUuid(c.uuid)} = ${format(v, params)}（${v.size} 字节）")
    }

    @Suppress("DEPRECATION")
    private suspend fun writeChar(addr: String, params: JSONObject, timeout: Long): ToolResult {
        val s = session(addr) ?: return notConnected(addr)
        val c = findChar(s, params) ?: return charNotFound(s, params)
        val bytes = decode(params) ?: return ToolResult("value 空的或不是合法的 hex。text 编码直接给文本，hex 编码给形如 01ff0a 的十六进制。", isError = true)
        gateWrite("向蓝牙设备 $addr 的 ${shortUuid(c.uuid)} 写入 ${bytes.size} 字节：${format(bytes, params).take(80)}")
            ?.let { return ToolResult(it, isError = true) }

        val noRsp = params.optBoolean("no_response", false) &&
            (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        c.writeType = if (noRsp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                      else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        // ⚠ 必须写成 setValue(...)：它返回 boolean，不是合法的属性 setter，`c.value = x` 编译不过
        c.setValue(bytes)
        val d = CompletableDeferred<Int>().also { s.writeSignal = it }
        if (s.gatt?.writeCharacteristic(c) != true)
            return ToolResult("写不进 ${shortUuid(c.uuid)}：这个特征的属性是 [${propsOf(c)}]，多半不支持写。", isError = true)
        val status = withTimeoutOrNull(timeout) { d.await() }
            ?: return ToolResult("写 ${shortUuid(c.uuid)} 超时（${timeout}ms），设备没确认。", isError = true)
        s.writeSignal = null
        return if (status == BluetoothGatt.GATT_SUCCESS) ToolResult("已写入 ${shortUuid(c.uuid)}（${bytes.size} 字节）")
        else ToolResult("写失败，GATT status=$status", isError = true)
    }

    @Suppress("DEPRECATION")
    private suspend fun subscribe(addr: String, params: JSONObject, timeout: Long): ToolResult {
        val s = session(addr) ?: return notConnected(addr)
        val c = findChar(s, params) ?: return charNotFound(s, params)
        val enable = params.optBoolean("enable", true)
        val gatt = s.gatt ?: return notConnected(addr)
        if (!gatt.setCharacteristicNotification(c, enable))
            return ToolResult("订阅 ${shortUuid(c.uuid)} 失败：这个特征的属性是 [${propsOf(c)}]。", isError = true)
        // 光调 setCharacteristicNotification 只改了本机这一侧；**必须再往 CCCD 描述符里写一下**，
        // 设备那头才会真的开始推。少了这一步的表现是「订阅成功但永远收不到东西」。
        val cccd = c.getDescriptor(CCCD)
        if (cccd != null) {
            val indicate = (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            cccd.setValue(when {      // 同上：setValue 返回 boolean，不能当属性赋值
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            })
            val d = CompletableDeferred<Int>().also { s.descSignal = it }
            if (gatt.writeDescriptor(cccd)) {
                val st = withTimeoutOrNull(timeout) { d.await() }
                s.descSignal = null
                if (st != null && st != BluetoothGatt.GATT_SUCCESS)
                    return ToolResult("订阅时写 CCCD 失败，status=$st", isError = true)
            }
        }
        return ToolResult(if (enable)
            "已订阅 ${shortUuid(c.uuid)} 的通知。设备推过来的数据先攒着，用 action=notifications 取（最多留最近 200 条）。"
        else "已取消订阅 ${shortUuid(c.uuid)}。")
    }

    private suspend fun notifications(addr: String, params: JSONObject): ToolResult {
        val s = session(addr) ?: return notConnected(addr)
        // 等一会儿再看：刚订阅就来取多半是空的，而模型拿到空结果往往就放弃了
        val waitMs = params.optInt("timeout_ms", 0).coerceIn(0, 30000).toLong()
        if (waitMs > 0 && s.pending() == 0) {
            val until = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < until && s.pending() == 0) delay(200)
        }
        val lines = s.drain()
        if (lines.isEmpty()) return ToolResult("$addr 目前没有新的通知（订阅过了吗？有些设备要先往某个特征写一条指令才开始推）。")
        return ToolResult("$addr 收到 ${lines.size} 条通知：\n" + lines.joinToString("\n"))
    }

    // ---- 经典蓝牙 SPP ----

    private suspend fun sppConnect(adapter: BluetoothAdapter, addr: String, params: JSONObject): ToolResult {
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) return ToolResult("MAC 地址不对：$addr（先用 paired 看已配对的设备）", isError = true)
        sppSessions[addr]?.let { if (it.alive) return ToolResult("$addr 的串口已经连着了，直接 spp_send / spp_read。") else { it.close(); sppSessions.remove(addr) } }
        if (sppSessions.size >= MAX_SESSIONS) return ToolResult("同时开着的串口连接太多，先 disconnect 一个。", isError = true)
        val uuid = params.optString("uuid", "").trim().takeIf { it.isNotBlank() }?.let { uuidOf(it) } ?: SPP_UUID
        val device = adapter.getRemoteDevice(addr)
        if (device.bondState != BluetoothDevice.BOND_BONDED)
            return ToolResult("$addr 还没配对。经典蓝牙串口必须先在**系统设置的蓝牙页**里配对（要输配对码），配好再来连。", isError = true)
        return withContext(Dispatchers.IO) {
            val socket = try { device.createRfcommSocketToServiceRecord(uuid) }
                catch (e: Exception) { return@withContext ToolResult("建串口失败：${e.message}", isError = true) }
            runCatching { adapter.cancelDiscovery() }   // 扫描没停的话 connect 会很慢甚至失败
            try { socket.connect() } catch (e: Exception) {
                runCatching { socket.close() }
                return@withContext ToolResult("连不上 $addr 的串口：${e.message}。确认设备开着、在范围内、且没被别的手机占着。", isError = true)
            }
            val s = SppSession(addr, socket).also { it.startReader() }
            sppSessions[addr] = s
            ToolResult("已连上 $addr 的串口（SPP）。spp_send 发数据、spp_read 取它回的数据。")
        }
    }

    private suspend fun sppSend(addr: String, params: JSONObject): ToolResult {
        val s = sppSessions[addr]?.takeIf { it.alive } ?: return ToolResult("$addr 的串口没连着，先 spp_connect。", isError = true)
        s.lastActiveElapsed = SystemClock.elapsedRealtime()
        val bytes = decode(params) ?: return ToolResult("value 空的或不是合法的 hex。", isError = true)
        gateWrite("通过蓝牙串口向 $addr 发送 ${bytes.size} 字节：${format(bytes, params).take(80)}")
            ?.let { return ToolResult(it, isError = true) }
        return withContext(Dispatchers.IO) {
            try { s.send(bytes); ToolResult("已发送 ${bytes.size} 字节给 $addr。对方的回复用 spp_read 取。") }
            catch (e: Exception) { s.alive = false; ToolResult("发送失败：${e.message}（连接可能已经断了，重新 spp_connect）", isError = true) }
        }
    }

    private suspend fun sppRead(addr: String, params: JSONObject): ToolResult {
        val s = sppSessions[addr] ?: return ToolResult("$addr 的串口没连着，先 spp_connect。", isError = true)
        s.lastActiveElapsed = SystemClock.elapsedRealtime()
        val waitMs = params.optInt("timeout_ms", 2000).coerceIn(0, 30000).toLong()
        val until = System.currentTimeMillis() + waitMs
        var data = s.drain()
        while (data.isEmpty() && System.currentTimeMillis() < until && s.alive) {
            delay(200); data = s.drain()
        }
        if (data.isEmpty()) return ToolResult(
            if (s.alive) "$addr 暂时没有发回任何数据。" else "$addr 的串口已经断开了，重新 spp_connect。")
        return ToolResult("$addr 发回 ${data.size} 字节：${format(data, params)}")
    }

    // ---- 公共零件 ----

    private fun session(addr: String): GattSession? =
        gattSessions[addr]?.takeIf { it.connected }?.also { it.lastActiveElapsed = SystemClock.elapsedRealtime() }

    private fun notConnected(addr: String) =
        ToolResult(if (addr.isBlank()) "要哪台设备？给 address（先 scan 或 paired 拿 MAC）" else "$addr 没有连着，先 action=connect。", isError = true)

    private fun charNotFound(s: GattSession, params: JSONObject) = ToolResult(
        "没找到特征 ${params.optString("characteristic", "(没给)")}。这台设备有的是：\n" + describeServices(s), isError = true)

    /** service 给了就在那个服务里找，没给就全局找第一个匹配的——短 UUID 在同一台设备上极少重名。 */
    private fun findChar(s: GattSession, params: JSONObject): BluetoothGattCharacteristic? {
        val gatt = s.gatt ?: return null
        val cu = uuidOf(params.optString("characteristic", "")) ?: return null
        val su = uuidOf(params.optString("service", ""))
        if (su != null) return gatt.getService(su)?.getCharacteristic(cu)
        return gatt.services.firstNotNullOfOrNull { it.getCharacteristic(cu) }
    }

    /** 16 位短号（180d / 0x180D）自动补成完整 UUID——蓝牙标准服务都是这么写的。 */
    private fun uuidOf(raw: String): UUID? {
        val t = raw.trim().removePrefix("0x").removePrefix("0X")
        if (t.isBlank()) return null
        return runCatching {
            when (t.length) {
                4 -> UUID.fromString("0000$t-0000-1000-8000-00805f9b34fb")
                8 -> UUID.fromString("$t-0000-1000-8000-00805f9b34fb")
                else -> UUID.fromString(t)
            }
        }.getOrNull()
    }

    /** 完整 UUID 里若是标准的 0000xxxx-… 就只印那 4 位，一屏能多放几行。 */
    private fun shortUuid(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8) else s
    }

    private fun decode(params: JSONObject): ByteArray? {
        val v = params.optString("value", "")
        if (v.isEmpty()) return null
        return if (params.optString("encoding", "text").equals("hex", true)) {
            val clean = v.replace(Regex("[\\s:,-]"), "")
            if (clean.length % 2 != 0 || !clean.matches(Regex("[0-9a-fA-F]+"))) null
            else ByteArray(clean.length / 2) { ((clean[it * 2].digitToInt(16) shl 4) or clean[it * 2 + 1].digitToInt(16)).toByte() }
        } else v.toByteArray(Charsets.UTF_8)
    }

    private fun format(bytes: ByteArray, params: JSONObject): String =
        if (params.optString("encoding", "text").equals("hex", true)) toHex(bytes)
        else toHex(bytes) + previewText(bytes)

    private fun toHex(b: ByteArray?): String =
        b?.joinToString("") { "%02x".format(it) }?.ifBlank { "(空)" } ?: "(空)"

    /** 值常常本来就是一段文本（很多自制模块直接发 ASCII）。可打印才附上，别拿乱码充数。 */
    private fun previewText(b: ByteArray?): String {
        if (b == null || b.isEmpty()) return ""
        val t = runCatching { String(b, Charsets.UTF_8) }.getOrNull() ?: return ""
        val printable = t.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..0x10FFFF && !it.isISOControl() }
        return if (printable && t.isNotBlank()) "  «$t»" else ""
    }

    /**
     * 往设备里写东西**每次都问一遍**。
     *
     * 为什么不靠工具等级：整个工具是 STANDARD（扫描/读取本来就无害，天天弹框才是折磨），
     * 而 permissionLevel 是按工具定的，同一个工具里放不下两种。写入是唯一会真的改变物理世界的动作
     * （门锁、开关、自制固件），单独在动作粒度上过一次闸，键是 bluetooth_write——
     * 用户点「始终允许」只放开写入这一件事。返回 null=放行，否则=给模型的话。
     */
    private suspend fun gateWrite(intent: String): String? {
        val d = ToolPermissionManager.confirmAction(
            key = "bluetooth_write", level = AndroidPermissionLevel.ACCESSIBILITY,
            intent = intent, riskNote = "写入蓝牙设备会真的改变它的状态（开关、锁、固件参数），而且没法撤销。",
        ) ?: return "当前场景问不到用户，而往蓝牙设备写数据必须他点头，本次没有执行。"
        return if (d == ToolPermissionManager.Decision.ALLOW_ONCE || d == ToolPermissionManager.Decision.ALWAYS_ALLOW) null
        else "用户没有同意这次写入，已取消。"
    }

    /** 返回当前系统版本下仍缺失的必需权限（人话名称），空列表表示齐全。 */
    private fun missingPermissions(action: String): List<String> {
        val need = mutableListOf<Pair<String, String>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (action == "scan") need.add(android.Manifest.permission.BLUETOOTH_SCAN to "附近的蓝牙设备(扫描)")
            need.add(android.Manifest.permission.BLUETOOTH_CONNECT to "附近的蓝牙设备(连接/读名称)")
        } else if (action == "scan") {
            need.add(android.Manifest.permission.ACCESS_FINE_LOCATION to "精确位置(旧系统BLE扫描必需)")
        }
        return need.filter {
            ContextCompat.checkSelfPermission(context, it.first) != PackageManager.PERMISSION_GRANTED
        }.map { it.second }
    }

    private fun safeDeviceName(r: ScanResult): String = try { r.device?.name?.trim().orEmpty() } catch (_: SecurityException) { "" }
    private fun safeName(d: BluetoothDevice): String = try { d.name?.trim().orEmpty() } catch (_: SecurityException) { "" }
}
