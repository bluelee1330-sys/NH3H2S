package com.blue.multigasmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 알림 권한이 거부돼도 서비스 자체는 동작합니다(상태 알림만 안 보일 뿐).
            startMqttService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMqttService()
        }

        setContent {
            MaterialTheme {
                MultiGasMonitorScreen(
                    onSettingsSaved = { startMqttService() } // 설정 변경 후 서비스 재연결
                )
            }
        }
    }

    private fun startMqttService() {
        val intent = Intent(this, MqttForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }
}

private val CHANNELS = listOf("1", "2", "3")
private val TYPES = listOf("NH3", "H2S", "TEMP")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiGasMonitorScreen(onSettingsSaved: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }

    val readings by MqttRepository.readings.collectAsState()
    val connectionState by MqttRepository.connectionState.collectAsState()
    val deviceOnline by MqttRepository.deviceOnline.collectAsState()

    // 1초마다 갱신되는 "현재 시각" — 값이 오래됐는지(=보드가 멈췄는지) 판단하는 데 씀
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi Gas Monitor") },
                actions = {
                    ConnectionDot(connectionState)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { showSettings = true }) {
                        Text("설정")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            DeviceStatusBanner(deviceOnline = deviceOnline)

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val cells = TYPES.flatMap { type -> CHANNELS.map { ch -> ch to type } }
                items(cells) { (ch, type) ->
                    val key = "CH$ch/$type"
                    val reading = readings[key]
                    val stale = MqttRepository.isStale(reading, now)
                    GasCell(label = key, reading = reading, isStale = stale)
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            onDismiss = { showSettings = false },
            onSave = {
                showSettings = false
                onSettingsSaved()
            }
        )
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.CONNECTING -> Color(0xFFF9A825)
        ConnectionState.ERROR -> Color(0xFFC62828)
        ConnectionState.DISCONNECTED -> Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, shape = RoundedCornerShape(50))
    )
}

/**
 * 보드가 "prefix/status"에 online/offline을 발행하도록 펌웨어를 고친 경우에만 의미 있는 배너.
 * 아직 펌웨어에 그 기능이 없으면(deviceOnline == null) 아무것도 표시하지 않습니다.
 */
@Composable
private fun DeviceStatusBanner(deviceOnline: Boolean?) {
    if (deviceOnline == null) return

    val (bg, text) = if (deviceOnline) {
        Color(0xFFE8F5E9) to "보드 상태: 온라인"
    } else {
        Color(0xFFFFEBEE) to "보드 상태: 오프라인 (전원/네트워크 확인 필요)"
    }
    val textColor = if (deviceOnline) Color(0xFF2E7D32) else Color(0xFFC62828)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(text = text, fontSize = 13.sp, color = textColor, fontWeight = FontWeight.Bold)
    }
}

/** IoT MQTT Panel의 숫자 위젯과 비슷한 느낌의 카드 */
@Composable
private fun GasCell(label: String, reading: GasReading?, isStale: Boolean) {
    val displayValue = if (isStale) "--" else (reading?.displayValue ?: "--")
    val isError = !isStale && reading?.isError == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(4.dp))
            .background(Color.White)
            .padding(8.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = Color(0xFF444444)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayValue,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isStale -> Color(0xFFBDBDBD)
                    isError -> Color(0xFFC62828)
                    else -> Color(0xFF111111)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(onDismiss: () -> Unit, onSave: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PrefsManager(context) }

    var host by remember { mutableStateOf(prefs.host) }
    var port by remember { mutableStateOf(prefs.port.toString()) }
    var username by remember { mutableStateOf(prefs.username) }
    var password by remember { mutableStateOf(prefs.password) }
    var topicPrefix by remember { mutableStateOf(prefs.topicPrefix) }
    var useTls by remember { mutableStateOf(prefs.useTls) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MQTT 브로커 설정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("브로커 주소 (예: broker.example.com)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("포트 (예: 1883, TLS면 8883)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("사용자명 (없으면 비워둠)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("비밀번호") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = topicPrefix, onValueChange = { topicPrefix = it },
                    label = { Text("토픽 prefix (예: gas → gas/1/NH3 형태 구독)") },
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(checked = useTls, onCheckedChange = { useTls = it })
                    Text("TLS(SSL) 사용")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                prefs.host = host.trim()
                prefs.port = port.toIntOrNull() ?: 1883
                prefs.username = username.trim()
                prefs.password = password
                prefs.topicPrefix = topicPrefix.trim().ifBlank { "gas" }
                prefs.useTls = useTls
                onSave()
            }) { Text("저장 & 재연결") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
