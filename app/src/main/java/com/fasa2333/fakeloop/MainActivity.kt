package com.fasa2333.fakeloop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.fasa2333.fakeloop.ui.theme.FakeLoopTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var blePeripheralManager: BlePeripheralManager
    private lateinit var prefs: SharedPreferences

    private var autoPeripheralStarted: Boolean = false
    private var pendingStartAfterPermission: Boolean = false

    private val targetBtName = "LR029429BD"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && pendingStartAfterPermission) {
            setBluetoothName(targetBtName)
            if (!blePeripheralManager.isAdvertising()) {
                blePeripheralManager.startPeripheral()
                autoPeripheralStarted = true
            }
            pendingStartAfterPermission = false
        }
    }

    companion object {
        private const val KEY_TARGET_JUMPS = "targetJumps"
        private const val KEY_TARGET_TIME = "targetTime"
        private const val KEY_TARGET_SPEED = "targetSpeed"
        private const val KEY_AUTO_MODE = "autoMode"
        private const val KEY_DECEL_RATE = "decelRate"
        private const val KEY_RANDOM_ENABLED = "randomEnabled"
        private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
        private const val KEY_LAST_USED_TARGET_JUMPS = "lastUsedTargetJumps"
        private const val DEFAULT_TARGET_JUMPS = 800
        private const val DEFAULT_TARGET_TIME = 400
        private const val DEFAULT_TARGET_SPEED = 120
        private const val DEFAULT_DECEL_RATE = "0.2"
        private const val AUTO_MODE_TIME = "time"
        private const val AUTO_MODE_SPEED = "speed"
        private const val MAX_PACKET_JUMPS = 6553
        private const val RANDOM_JUMP_MAX = 50
    }

    @SuppressLint("MissingPermission")
    private fun ensureNameAndStartIfAllowed() {
        setBluetoothName(targetBtName)
        if (hasAllNeededPermissions()) {
            if (!blePeripheralManager.isAdvertising()) {
                blePeripheralManager.startPeripheral()
                autoPeripheralStarted = true
            }
        } else {
            pendingStartAfterPermission = true
            requestNeededPermissions()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        blePeripheralManager = BlePeripheralManager(this)
        prefs = getPreferences(Context.MODE_PRIVATE)
        ensureNameAndStartIfAllowed()

        setContent {
            FakeLoopTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val toRequest = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun buildJumpPacket(count: Int): ByteArray {
        val packet = ByteArray(16)
        packet[0] = 0x6F.toByte()
        packet[1] = 0x04.toByte()
        packet[2] = 0x0B.toByte()
        packet[3] = 0x00
        packet[4] = 0x00
        packet[5] = 0x00

        val raw = count * 10
        packet[6] = ((raw shr 8) and 0xFF).toByte()
        packet[7] = (raw and 0xFF).toByte()
        packet[8] = 0x69.toByte()

        val refA = intArrayOf(0x30, 0x00, 0x57, 0x03, 0xD8, 0x02)
        val refB = intArrayOf(0xB1, 0x28, 0x8F, 0x01, 0x5E, 0x03)
        val t = (count - 800.0) / 800.0
        for (i in 0..5) {
            packet[9 + i] = ((refA[i] + t * (refB[i] - refA[i])).roundToInt() and 0xFF).toByte()
        }

        var sum = 0
        for (i in 0..14) {
            sum += packet[i].toInt() and 0xFF
        }
        packet[15] = (sum and 0xFF).toByte()
        return packet
    }

    private fun calcCurrentSpeed(elapsedSec: Long, totalSec: Long, avgJpm: Double, decelRate: Double): Double {
        val elapsed = elapsedSec.toDouble()
        val duration = totalSec.toDouble().coerceAtLeast(1.0)
        val linear = avgJpm * (1.0 + decelRate) - (2.0 * decelRate * avgJpm / duration) * elapsed
        val noise = (Math.random() - 0.5) * avgJpm * 0.05
        return maxOf(avgJpm * 0.3, linear + noise)
    }

    private fun formatTime(seconds: Long): String {
        val minutes = seconds / 60
        val restSeconds = seconds % 60
        return "%02d:%02d".format(minutes, restSeconds)
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        var showCustomDialog by remember { mutableStateOf(false) }
        var customHex by remember { mutableStateOf("") }
        var customError by remember { mutableStateOf("") }
        var showDisclaimer by remember { mutableStateOf(true) }

        var targetJumpsStr by remember {
            mutableStateOf(prefs.getInt(KEY_TARGET_JUMPS, DEFAULT_TARGET_JUMPS).toString())
        }
        var targetTimeStr by remember {
            mutableStateOf(prefs.getInt(KEY_TARGET_TIME, DEFAULT_TARGET_TIME).toString())
        }
        var targetSpeedStr by remember {
            mutableStateOf(prefs.getInt(KEY_TARGET_SPEED, DEFAULT_TARGET_SPEED).toString())
        }
        var autoMode by remember {
            mutableStateOf(prefs.getString(KEY_AUTO_MODE, AUTO_MODE_TIME) ?: AUTO_MODE_TIME)
        }
        var decelRateStr by remember {
            mutableStateOf(prefs.getString(KEY_DECEL_RATE, DEFAULT_DECEL_RATE) ?: DEFAULT_DECEL_RATE)
        }
        var randomEnabled by remember {
            mutableStateOf(prefs.getBoolean(KEY_RANDOM_ENABLED, true))
        }
        var keepScreenOnEnabled by remember {
            mutableStateOf(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
        }
        var isAutoRunning by remember { mutableStateOf(false) }
        var isAutoPaused by remember { mutableStateOf(false) }
        var autoProgress by remember { mutableStateOf(0) }
        var autoTotal by remember { mutableStateOf(0) }
        var autoRandomAdded by remember { mutableStateOf(0) }
        var autoCurrentSpeed by remember { mutableStateOf(0.0) }
        var autoElapsedSec by remember { mutableStateOf(0L) }
        var autoTotalSec by remember { mutableStateOf(0L) }
        var autoError by remember { mutableStateOf("") }
        val autoScope = rememberCoroutineScope()
        var autoJob by remember { mutableStateOf<Job?>(null) }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Fake Loop",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "若小程序扫描不到设备，请检查是否给予 Fake Loop 所需的权限，或在系统设置里修改设备名称为 \"$targetBtName\"。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        blePeripheralManager.notifySubscribers(hexStringToByteArray("6F0201000072"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "发送开始包")
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            blePeripheralManager.notifySubscribers(buildJumpPacket(800))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "发送 800 下")
                    }

                    Button(
                        onClick = {
                            blePeripheralManager.notifySubscribers(buildJumpPacket(1600))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(text = "发送 1600 下")
                    }
                }

                Button(
                    onClick = { showCustomDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "发送自定义数据")
                }
            }

            HorizontalDivider()

            Text(
                text = "自动跳绳",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isAutoRunning) {
                        randomEnabled = !randomEnabled
                        prefs.edit().putBoolean(KEY_RANDOM_ENABLED, randomEnabled).apply()
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "为目标跳数增加随机值")
                Switch(
                    checked = randomEnabled,
                    onCheckedChange = { enabled ->
                        if (!isAutoRunning) {
                            randomEnabled = enabled
                            prefs.edit().putBoolean(KEY_RANDOM_ENABLED, enabled).apply()
                        }
                    },
                    enabled = !isAutoRunning
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        keepScreenOnEnabled = !keepScreenOnEnabled
                        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOnEnabled).apply()
                        if (!keepScreenOnEnabled || isAutoRunning) {
                            setKeepScreenOn(keepScreenOnEnabled && isAutoRunning)
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "自动跳绳时保持屏幕常亮")
                Switch(
                    checked = keepScreenOnEnabled,
                    onCheckedChange = { enabled ->
                        keepScreenOnEnabled = enabled
                        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
                        setKeepScreenOn(enabled && isAutoRunning)
                    }
                )
            }

            OutlinedTextField(
                value = targetJumpsStr,
                onValueChange = { value ->
                    if (!isAutoRunning) {
                        targetJumpsStr = value.filter { it.isDigit() }
                        autoError = ""
                    }
                },
                label = { Text("目标跳数") },
                suffix = { Text("下") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAutoRunning,
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (!isAutoRunning) {
                            autoMode = AUTO_MODE_TIME
                            prefs.edit().putString(KEY_AUTO_MODE, autoMode).apply()
                            autoError = ""
                        }
                    },
                    enabled = !isAutoRunning || autoMode == AUTO_MODE_TIME,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (autoMode == AUTO_MODE_TIME) "✓ 按时间" else "按时间")
                }
                Button(
                    onClick = {
                        if (!isAutoRunning) {
                            autoMode = AUTO_MODE_SPEED
                            prefs.edit().putString(KEY_AUTO_MODE, autoMode).apply()
                            autoError = ""
                        }
                    },
                    enabled = !isAutoRunning || autoMode == AUTO_MODE_SPEED,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (autoMode == AUTO_MODE_SPEED) "✓ 按速度" else "按速度")
                }
            }

            if (autoMode == AUTO_MODE_TIME) {
                OutlinedTextField(
                    value = targetTimeStr,
                    onValueChange = { value ->
                        if (!isAutoRunning) {
                            targetTimeStr = value.filter { it.isDigit() }
                            autoError = ""
                        }
                    },
                    label = { Text("目标时间") },
                    suffix = { Text("秒") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAutoRunning,
                    singleLine = true
                )
            } else {
                OutlinedTextField(
                    value = targetSpeedStr,
                    onValueChange = { value ->
                        if (!isAutoRunning) {
                            targetSpeedStr = value.filter { it.isDigit() }
                            autoError = ""
                        }
                    },
                    label = { Text("平均速度") },
                    suffix = { Text("次/分钟") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isAutoRunning,
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = decelRateStr,
                onValueChange = { value ->
                    if (!isAutoRunning) {
                        decelRateStr = value.filter { it.isDigit() || it == '.' }
                        autoError = ""
                    }
                },
                label = { Text("减速率") },
                placeholder = { Text("0~1，0 为匀速") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAutoRunning,
                singleLine = true
            )

            if (autoError.isNotEmpty()) {
                Text(text = autoError, color = Color.Red, fontSize = 14.sp)
            }

            if (!isAutoRunning) {
                Button(
                    onClick = {
                        val requestedTotal = targetJumpsStr.toIntOrNull()
                        val requestedTimeSec = targetTimeStr.toLongOrNull()
                        val requestedSpeed = targetSpeedStr.toIntOrNull()
                        val decel = decelRateStr.toDoubleOrNull()?.coerceIn(0.0, 1.0)

                        val maxRequestedTotal = MAX_PACKET_JUMPS - if (randomEnabled) RANDOM_JUMP_MAX else 0
                        if (requestedTotal == null || requestedTotal <= 0 || requestedTotal > maxRequestedTotal) {
                            autoError = "目标跳数需为 1~$maxRequestedTotal 的整数"
                            return@Button
                        }
                        if (decel == null) {
                            autoError = "减速率需为 0~1 之间的小数"
                            return@Button
                        }

                        val randomAdded = if (randomEnabled) (0..RANDOM_JUMP_MAX).random() else 0
                        val total = requestedTotal + randomAdded
                        val avgJpm: Double
                        val totalSec: Long
                        if (autoMode == AUTO_MODE_TIME) {
                            if (requestedTimeSec == null || requestedTimeSec <= 0 || requestedTimeSec > 60000) {
                                autoError = "目标时间需为 1~60000 秒"
                                return@Button
                            }
                            totalSec = requestedTimeSec
                            avgJpm = total.toDouble() / totalSec * 60.0
                        } else {
                            if (requestedSpeed == null || requestedSpeed <= 0 || requestedSpeed > 60000) {
                                autoError = "平均速度需为 1~60000 次/分钟"
                                return@Button
                            }
                            avgJpm = requestedSpeed.toDouble()
                            totalSec = ceil(total.toDouble() / avgJpm * 60.0).toLong().coerceAtLeast(1L)
                            if (totalSec > 60000) {
                                autoError = "按当前速度预计用时超过 60000 秒"
                                return@Button
                            }
                        }
                        prefs.edit()
                            .putInt(KEY_TARGET_JUMPS, requestedTotal)
                            .putInt(KEY_TARGET_TIME, targetTimeStr.toIntOrNull() ?: DEFAULT_TARGET_TIME)
                            .putInt(KEY_TARGET_SPEED, targetSpeedStr.toIntOrNull() ?: DEFAULT_TARGET_SPEED)
                            .putString(KEY_AUTO_MODE, autoMode)
                            .putString(KEY_DECEL_RATE, decelRateStr)
                            .putBoolean(KEY_RANDOM_ENABLED, randomEnabled)
                            .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOnEnabled)
                            .apply()

                        autoTotal = total
                        autoRandomAdded = randomAdded
                        autoTotalSec = totalSec
                        autoProgress = 0
                        autoElapsedSec = 0L
                        autoCurrentSpeed = avgJpm
                        autoError = ""
                        isAutoRunning = true
                        isAutoPaused = false
                        setKeepScreenOn(keepScreenOnEnabled)

                        autoJob = autoScope.launch {
                            blePeripheralManager.notifySubscribers(hexStringToByteArray("6F0201000072"))

                            var elapsed = 0L
                            var current = 0
                            while (elapsed < totalSec && current < total && isActive) {
                                delay(1000L)
                                if (isAutoPaused) {
                                    continue
                                }

                                elapsed++
                                val speed = calcCurrentSpeed(elapsed, totalSec, avgJpm, decel)
                                val increment = (speed / 60).roundToInt().coerceAtLeast(1)
                                current = if (elapsed >= totalSec) {
                                    total
                                } else {
                                    minOf(total, current + increment)
                                }
                                autoProgress = current
                                autoCurrentSpeed = speed
                                autoElapsedSec = elapsed
                                blePeripheralManager.notifySubscribers(buildJumpPacket(current))
                            }

                            if (isActive) {
                                blePeripheralManager.notifySubscribers(buildJumpPacket(total))
                                prefs.edit().putInt(KEY_LAST_USED_TARGET_JUMPS, total).apply()
                            }
                            isAutoRunning = false
                            isAutoPaused = false
                            setKeepScreenOn(false)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始自动跳绳")
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "已完成：$autoProgress / $autoTotal 下",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (randomEnabled) {
                        Text(
                            text = "本次随机增加：$autoRandomAdded 下",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                    }
                    Text(
                        text = "当前速度：${autoCurrentSpeed.roundToInt()} 次/分钟",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "用时：${formatTime(autoElapsedSec)}，剩余：${formatTime((autoTotalSec - autoElapsedSec).coerceAtLeast(0L))}",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isAutoPaused = !isAutoPaused },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isAutoPaused) "继续" else "暂停")
                        }
                        Button(
                            onClick = {
                                autoJob?.cancel()
                                isAutoRunning = false
                                isAutoPaused = false
                                setKeepScreenOn(false)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("停止")
                        }
                    }
                }
            }

            if (showCustomDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showCustomDialog = false
                        customHex = ""
                        customError = ""
                    },
                    title = { Text(text = "发送自定义 Hex") },
                    text = {
                        Column {
                            Text(text = "请输入十六进制字符串，例如 6F0201000072：", fontSize = 12.sp)
                            TextField(
                                value = customHex,
                                onValueChange = {
                                    customHex = it
                                    customError = ""
                                },
                                placeholder = { Text(text = "Hex 字符串") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (customError.isNotEmpty()) {
                                Text(text = customError, color = Color.Red, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val hex = customHex.replace("\\s".toRegex(), "")
                                val ok = hex.matches(Regex("^[0-9a-fA-F]+$")) && hex.length % 2 == 0
                                if (!ok) {
                                    customError = "输入不是有效的偶数长度十六进制字符串"
                                    return@TextButton
                                }
                                val payload = try {
                                    hexStringToByteArray(hex)
                                } catch (e: Exception) {
                                    customError = "Hex 转换失败"
                                    return@TextButton
                                }
                                blePeripheralManager.notifySubscribers(payload)
                                showCustomDialog = false
                                customHex = ""
                            }
                        ) {
                            Text("发送")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showCustomDialog = false
                                customHex = ""
                                customError = ""
                            }
                        ) {
                            Text("取消")
                        }
                    }
                )
            }

            if (showDisclaimer) {
                AlertDialog(
                    onDismissRequest = { },
                    title = { Text(text = "免责声明") },
                    text = {
                        Text(
                            text = "本软件仅供蓝牙通信技术的交流与学习使用。严禁利用本软件进行任何形式的体育打卡作弊或虚假记录。因违规使用产生的一切后果由用户自行承担，开发者不承担任何法律责任。"
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { showDisclaimer = false }) {
                            Text(text = "我知道了")
                        }
                    },
                    dismissButton = null
                )
            }

            val uriHandler = LocalUriHandler.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "by 风洒青泥",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "觉得好用吗？点这里前往 GitHub，给项目一个 Star 支持一下。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/fasa70/Fake_Loop")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        setKeepScreenOn(false)
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun setBluetoothName(name: String): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                    return false
                }
            }
            try {
                val method = BluetoothAdapter::class.java.getMethod("setName", String::class.java)
                method.invoke(adapter, name)
            } catch (ex: Exception) {
                adapter.name = name
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val cleaned = s.replace("\\s".toRegex(), "")
        val len = cleaned.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] =
                ((Character.digit(cleaned[i], 16) shl 4) + Character.digit(cleaned[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun hasAllNeededPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    FakeLoopTheme {
        Greeting("Android")
    }
}
