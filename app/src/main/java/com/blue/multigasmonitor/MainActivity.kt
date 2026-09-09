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
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val cells = TYPES.flatMap { type -> CHANNELS.map { ch -> ch to type } }
            items(cells) { (ch, type) ->
                val key = "CH$ch/$type"
                GasCell(label = key, reading = readings[key])
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

/** IoT MQTT Panel의 숫자 위젯과 비슷한 느낌의 카드 */
@Composable
private fun GasCell(label: String, reading: GasReading?) {
    val displayValue = reading?.displayValue ?: "--"
    val isError = reading?.isError == true

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
                color = if (isError) Color(0xFFC62828) else Color(0xFF111111)
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
