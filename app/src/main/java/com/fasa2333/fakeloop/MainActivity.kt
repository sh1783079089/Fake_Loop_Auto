package com.fasa2333.fakeloop

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fasa2333.fakeloop.ui.theme.FakeLoopTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var blePeripheralManager: BlePeripheralManager

    private var autoPeripheralStarted: Boolean = false
    private var pendingStartAfterPermission: Boolean = false

    private val TARGET_BT_NAME = "LR029429BD"

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            if (pendingStartAfterPermission) {
                setBluetoothName(TARGET_BT_NAME)
                if (!blePeripheralManager.isAdvertising()) {
                    blePeripheralManager.startPeripheral()
                    autoPeripheralStarted = true
                }
                pendingStartAfterPermission = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureNameAndStartIfAllowed() {
        setBluetoothName(TARGET_BT_NAME)
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

        if (hasAllNeededPermissions()) {
            setBluetoothName(TARGET_BT_NAME)
            if (!blePeripheralManager.isAdvertising()) {
                blePeripheralManager.startPeripheral()
                autoPeripheralStarted = true
            }
        } else {
            pendingStartAfterPermission = true
            requestNeededPermissions()
        }

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
        val toRequest = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (toRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    // Build a 16-byte result packet for the given jump count.
    // bytes[6-7] = count * 10 (big-endian); bytes[8-14] linearly interpolated from two
    // known real-device captures (N=800 and N=1600); bytes[15] = sum checksum.
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

        // Reference samples: N=800 and N=1600 (bytes 9-14)
        val refA = intArrayOf(0x30, 0x00, 0x57, 0x03, 0xD8, 0x02)
        val refB = intArrayOf(0xB1, 0x28, 0x8F, 0x01, 0x5E, 0x03)
        val t = (count - 800.0) / 800.0
        for (i in 0..5) {
            packet[9 + i] = ((refA[i] + t * (refB[i] - refA[i])).roundToInt() and 0xFF).toByte()
        }

        var sum = 0
        for (i in 0..14) sum += packet[i].toInt() and 0xFF
        packet[15] = (sum and 0xFF).toByte()

        return packet
    }

    // Linear deceleration model with ±5% random noise. Speed never drops below 30% of avg.
    private fun calcCurrentSpeed(elapsedSec: Long, totalSec: Long, avgJpm: Double, decelRate: Double): Double {
        val t = elapsedSec.toDouble()
        val d = totalSec.toDouble().coerceAtLeast(1.0)
        val linear = avgJpm * (1.0 + decelRate) - (2.0 * decelRate * avgJpm / d) * t
        val noise = (Math.random() - 0.5) * avgJpm * 0.05
        return maxOf(avgJpm * 0.3, linear + noise)
    }

    private fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        var showCustomDialog by remember { mutableStateOf(false) }
        var customHex by remember { mutableStateOf("") }
        var customError by remember { mutableStateOf("") }
        var showDisclaimer by remember { mutableStateOf(true) }

        // Auto jump state
        var autoCountStr by remember { mutableStateOf("800") }
        var autoSpeedStr by remember { mutableStateOf("120") }
        var autoDecelStr by remember { mutableStateOf("0.2") }
        var isAutoRunning by remember { mutableStateOf(false) }
        var autoProgress by remember { mutableStateOf(0) }
        var autoTotal by remember { mutableStateOf(0) }
        var autoCurrentSpeed by remember { mutableStateOf(0.0) }
        var autoElapsedSec by remember { mutableStateOf(0L) }
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
                text = "若小程序扫描不到设备，请检查是否给予了Fake Loop所需的权限，或在系统设置里修改设备名称为\"LR029429BD\"",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.fillMaxWidth()
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(onClick = {
                    blePeripheralManager.notifySubscribers(hexStringToByteArray("6F0201000072"))
                }) {
                    Text(text = "开始跳绳")
                }

                Button(onClick = {
                    blePeripheralManager.notifySubscribers(hexStringToByteArray("6F040B0000001F406930005703D802A8"))
                }) {
                    Text(text = "跳绳800下")
                }

                Button(onClick = {
                    blePeripheralManager.notifySubscribers(hexStringToByteArray("6F040B0000003E8069B1288F015E036C"))
                }) {
                    Text(text = "跳绳1600下")
                }

                Button(onClick = { showCustomDialog = true }) {
                    Text(text = "发送自定义数据")
                }
            }

            HorizontalDivider()

            // ── Auto jump section ──────────────────────────────────────
            Text(
                text = "自动跳绳",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )

            OutlinedTextField(
                value = autoCountStr,
                onValueChange = { if (!isAutoRunning) autoCountStr = it.filter { c -> c.isDigit() } },
                label = { Text("总次数") },
                suffix = { Text("下") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAutoRunning,
                singleLine = true
            )

            OutlinedTextField(
                value = autoSpeedStr,
                onValueChange = { if (!isAutoRunning) autoSpeedStr = it.filter { c -> c.isDigit() } },
                label = { Text("平均速度") },
                suffix = { Text("次/分钟") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAutoRunning,
                singleLine = true
            )

            OutlinedTextField(
                value = autoDecelStr,
                onValueChange = { if (!isAutoRunning) autoDecelStr = it },
                label = { Text("减速率") },
                placeholder = { Text("0~1，0 为匀速") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAutoRunning,
                singleLine = true
            )

            if (!isAutoRunning) {
                Button(
                    onClick = {
                        val total = autoCountStr.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                        val avgJpm = autoSpeedStr.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                        val decel = autoDecelStr.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f

                        autoTotal = total
                        autoProgress = 0
                        autoElapsedSec = 0L
                        autoCurrentSpeed = avgJpm.toDouble()
                        isAutoRunning = true

                        autoJob = autoScope.launch {
                            blePeripheralManager.notifySubscribers(hexStringToByteArray("6F0201000072"))

                            val totalSec = (total.toDouble() / avgJpm * 60).toLong().coerceAtLeast(1L)
                            var elapsed = 0L
                            var current = 0

                            while (current < total && isActive) {
                                delay(1000L)
                                elapsed++
                                val speed = calcCurrentSpeed(elapsed, totalSec, avgJpm.toDouble(), decel.toDouble())
                                val increment = (speed / 60).roundToInt().coerceAtLeast(1)
                                current = minOf(total, current + increment)
                                autoProgress = current
                                autoCurrentSpeed = speed
                                autoElapsedSec = elapsed
                                blePeripheralManager.notifySubscribers(buildJumpPacket(current))
                            }

                            if (isActive) {
                                blePeripheralManager.notifySubscribers(buildJumpPacket(total))
                            }
                            isAutoRunning = false
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
                    Text(
                        text = "当前速度：${autoCurrentSpeed.roundToInt()} 次/分钟",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = "用时：${formatTime(autoElapsedSec)}",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = {
                            autoJob?.cancel()
                            isAutoRunning = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("停止")
                    }
                }
            }
            // ── End auto jump section ──────────────────────────────────

            if (showCustomDialog) {
                AlertDialog(
                    onDismissRequest = { showCustomDialog = false; customHex = ""; customError = "" },
                    title = { Text(text = "发送自定义 Hex") },
                    text = {
                        Column {
                            Text(text = "请输入十六进制字符串（例如 6F0201000072）：", fontSize = 12.sp)
                            TextField(
                                value = customHex,
                                onValueChange = { customHex = it; customError = "" },
                                placeholder = { Text(text = "Hex 字符串") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (customError.isNotEmpty()) {
                                Text(text = customError, color = Color.Red, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
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
                        }) { Text("发送") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showCustomDialog = false; customHex = ""; customError = "" }) { Text("取消") }
                    }
                )
            }

            if (showDisclaimer) {
                AlertDialog(
                    onDismissRequest = { /* require explicit dismiss */ },
                    title = { Text(text = "免责声明") },
                    text = { Text(text = "本软件仅供蓝牙通信技术的交流与学习使用。严禁利用本软件进行任何形式的体育打卡作弊或虚假记录。因违规使用产生的一切后果由用户自行承担，开发者不承担任何法律责任。") },
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
                    text = "觉得好用吗？别忘了戳这里前往 GitHub，给我点个 Star 支持下我哦⭐~",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/fasa70/Fake_Loop")
                    }
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setBluetoothName(name: String): Boolean {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
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
            data[i / 2] = ((Character.digit(cleaned[i], 16) shl 4) + Character.digit(cleaned[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun hasAllNeededPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
