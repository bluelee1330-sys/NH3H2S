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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    // onCreate에서 이미 연결을 한 번 시작하므로, 앱을 막 실행했을 때 onResume이 곧바로
    // 또 호출되면서 중복으로 재연결하지 않도록 첫 onResume은 건너뜁니다.
    private var isFirstResume = true

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

    override fun onResume() {
        super.onResume()
        if (isFirstResume) {
            isFirstResume = false
            return
        }
        // 화면이 꺼졌다 켜지거나(잠금화면 등) 다른 앱에 갔다가 돌아올 때, OS가 화면이
        // 꺼져 있는 동안 백그라운드 네트워크를 끊어버렸는데도 MQTT 클라이언트 라이브러리는
        // 그걸 모르고 "연결됨" 상태로 남아있는 경우가 있습니다. 이러면 값이 안 들어와서
        // 30초 후 전부 "--"로 표시되는데, 사용자가 설정 화면에서 "저장 & 재연결"을 눌러야만
        // 복구되는 문제가 있었습니다. 그래서 화면(앱)이 다시 보일 때마다 자동으로 재연결을
        // 시도해서, 사용자가 직접 재연결하지 않아도 되게 합니다.
        startMqttService()
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
    val outputStates by MqttRepository.outputStates.collectAsState()

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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CHANNELS.forEach { ch ->
                    ChannelColumn(
                        channel = ch,
                        readings = readings,
                        now = now,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // OUT1/OUT2/OUT3: 버튼 표시 상태는 "prefix/outN/state"로 받은 실제 값을 그대로
            // 보여줍니다(눌렀을 때 화면이 바로 바뀌는 게 아니라, ESP32가 상태를 확인해주고
            // 발행해줘야 바뀝니다) — 그래야 폰에서 눌러도, ESP32 LCD를 직접 터치해도
            // 양쪽이 항상 같은 값을 보게 됩니다.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (i in 1..3) {
                    OutButton(
                        outIndex = i,
                        isOn = outputStates[i] ?: false,
                        modifier = Modifier.weight(1f)
                    )
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
 * 보드가 "prefix/board"에 online/offline(LWT)을 발행하도록 펌웨어를 고친 경우에만 의미 있는 배너.
 * 아직 펌웨어에 그 기능이 없으면(deviceOnline == null) 아무것도 표시하지 않습니다.
 * ("prefix/status"는 기존 JSON 상태 토픽이라 겹치지 않게 이름을 다르게 씀)
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

/**
 * 채널 1개(CH1/CH2/CH3) 컬럼: 위에 "CH1" 같은 공통 제목, 그 아래 검은 테두리 박스 안에
 * NH3/H2S/TEMP 값이 세로로 쌓입니다. 예전처럼 각 값 라벨에 "CH1/"을 반복하지 않습니다.
 */
@Composable
private fun ChannelColumn(
    channel: String,
    readings: Map<String, GasReading>,
    now: Long,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "CH$channel",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TYPES.forEach { type ->
                val reading = readings["CH$channel/$type"]
                val stale = MqttRepository.isStale(reading, now)
                GasSubCell(label = type, reading = reading, isStale = stale)
            }
        }
    }
}

/** 채널 컬럼 안에 들어가는 값 하나(NH3/H2S/TEMP)짜리 작은 카드 */
@Composable
private fun GasSubCell(label: String, reading: GasReading?, isStale: Boolean) {
    val displayValue = if (isStale) "--" else (reading?.displayValue ?: "--")
    val isError = !isStale && reading?.isError == true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFFDDDDDD), RoundedCornerShape(4.dp))
            .background(Color.White)
            .padding(8.dp)
    ) {
        Text(text = label, fontSize = 13.sp, color = Color(0xFF444444))
        Text(
            text = displayValue,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                isStale -> Color(0xFFBDBDBD)
                isError -> Color(0xFFC62828)
                else -> Color(0xFF111111)
            },
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * OUT1/OUT2/OUT3 버튼. 누를 때마다 ON/OFF를 토글하면서 그 상태를 MQTT로 발행합니다.
 * 색상은 펌웨어 LCD의 OUT 버튼과 맞췄습니다(OFF=남색, ON=초록).
 */
@Composable
private fun OutButton(outIndex: Int, isOn: Boolean, modifier: Modifier = Modifier) {
    val bg = if (isOn) Color(0xFF00A843) else Color(0xFF2A2F63)

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, Color.White, RoundedCornerShape(6.dp))
            .clickable {
                // 여기서 바로 색을 안 바꾸고, 현재 알고 있는 상태의 반대값을 "명령"으로만
                // 보냅니다. 실제로 화면 색이 바뀌는 건 ESP32가 "prefix/outN/state"로
                // 확인 응답을 보내줄 때입니다(그래야 ESP32 LCD에서 직접 눌렀을 때도
                // 똑같은 방식으로 동기화됨).
                MqttRepository.publishOutput(outIndex, !isOn)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "OUT$outIndex",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
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
