package com.blue.multigasmonitor

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.nio.charset.StandardCharsets

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class GasReading(
    val displayValue: String,   // 화면에 표시할 문자열, 예: "0.0" 또는 "Err"
    val isError: Boolean,
    val updatedAtMillis: Long
)

/**
 * 화면(위젯 9개)이 구독하는 최신 값 저장소.
 * MqttForegroundService가 메시지를 받을 때마다 여기를 갱신하고,
 * MainActivity(Compose)는 StateFlow를 collect해서 그리는 구조입니다.
 * IoT MQTT Panel의 "위젯"이 하던 역할을 이 클래스가 대신합니다.
 */
object MqttRepository {

    // key 형식: "CH1/NH3", "CH2/H2S", "CH3/TEMP" ...
    private val _readings = MutableStateFlow<Map<String, GasReading>>(emptyMap())
    val readings: StateFlow<Map<String, GasReading>> = _readings

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // ESP32 보드가 "prefix/board"에 online/offline(LWT)을 발행하면 여기 반영됩니다.
    // "status"가 아니라 "board"인 이유: 펌웨어가 이미 "prefix/status"를 3채널 JSON
    // 통합 토픽으로, "prefix/chN/status"를 채널별 ACTIVE/ERROR로 쓰고 있어서 겹치지
    // 않는 이름을 새로 골랐습니다.
    // null = 아직 한 번도 못 받음(펌웨어가 아직 이 기능을 안 쓰거나, 값이 오기 전)
    private val _deviceOnline = MutableStateFlow<Boolean?>(null)
    val deviceOnline: StateFlow<Boolean?> = _deviceOnline

    // OUT1/OUT2/OUT3 실제 상태(key=1,2,3). "prefix/outN/state"를 펌웨어가 발행하면
    // 여기 반영됩니다 - 폰에서 눌러도, ESP32 LCD에서 터치해도 결국 이 토픽 하나로
    // 모이기 때문에 양쪽 어디서 바꾸든 서로 동기화됩니다. 아직 못 받은 채널은 없음
    // (버튼은 기본 OFF로 표시).
    private val _outputStates = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val outputStates: StateFlow<Map<Int, Boolean>> = _outputStates

    // 현재 연결된 MQTT 클라이언트(및 그때 설정된 topicPrefix)에 대한 참조.
    // OUT1/2/3 버튼에서 ESP32로 ON/OFF 명령을 보낼 때 씁니다.
    // MqttForegroundService가 연결 성공/종료할 때마다 attachClient()로 갱신해줍니다.
    @Volatile
    private var mqttClient: Mqtt3AsyncClient? = null
    @Volatile
    private var currentTopicPrefix: String = ""

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun attachClient(client: Mqtt3AsyncClient?, topicPrefix: String) {
        mqttClient = client
        currentTopicPrefix = topicPrefix
    }

    /**
     * OUT1/OUT2/OUT3 버튼 -> "prefix/outN/cmd" 토픽으로 "ON"/"OFF" 문자열을 발행합니다.
     * (예: bluelee_nh3h2s/out1/cmd). 아직 연결이 안 돼 있으면 조용히 무시합니다.
     * 펌웨어가 이 토픽들을 구독해서 relayState를 갱신하도록 별도 코드가 추가돼야
     * 실제로 릴레이(OUT1~3)가 움직입니다.
     */
    fun publishOutput(outIndex: Int, on: Boolean) {
        val client = mqttClient ?: return
        val prefix = currentTopicPrefix.ifBlank { return }
        val topic = "$prefix/out$outIndex/cmd"
        val payload = if (on) "ON" else "OFF"
        client.publishWith()
            .topic(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload.toByteArray(StandardCharsets.UTF_8))
            .send()
    }

    /** 값이 너무 오래됐으면(=보드가 멈췄을 가능성) true. 화면에서 "--"로 표시할 때 사용. */
    fun isStale(reading: GasReading?, nowMillis: Long, thresholdMillis: Long = 30_000L): Boolean {
        return reading == null || (nowMillis - reading.updatedAtMillis) > thresholdMillis
    }

    /**
     * 토픽(예: "bluelee_nh3h2s/ch1/nh3")과 페이로드 문자열(예: "0.0")을 받아 저장소에 반영.
     * 채널 세그먼트는 "1", "ch1", "CH1" 등 어떤 형태로 오든 숫자만 뽑아서 사용합니다.
     * "prefix/board" 토픽(online/offline, LWT)은 보드 상태로 별도 처리합니다.
     * ("prefix/status"와 "prefix/chN/status"는 펌웨어가 이미 JSON/ACTIVE-ERROR 값으로
     *  쓰고 있으므로 절대 여기서 가로채면 안 됩니다.)
     * 토픽 규칙이 이보다 더 다르면 이 파싱 로직만 바꾸면 됩니다.
     */
    fun onMessage(topic: String, payload: String) {
        val parts = topic.trim('/').split("/")
        if (parts.isEmpty()) return

        if (parts.last().equals("board", ignoreCase = true)) {
            _deviceOnline.value = payload.trim().equals("online", ignoreCase = true)
            return
        }

        // "prefix/outN/state" — ESP32 LCD를 직접 터치해서 릴레이가 바뀐 경우도
        // 이 토픽으로 발행되도록 펌웨어를 고쳐서, 폰 쪽 버튼도 같이 따라 바뀌게 합니다.
        if (parts.size >= 2 &&
            parts.last().equals("state", ignoreCase = true) &&
            parts[parts.size - 2].startsWith("out", ignoreCase = true)
        ) {
            val outIndex = parts[parts.size - 2].drop(3).toIntOrNull() ?: return
            val on = payload.trim().equals("on", ignoreCase = true)
            _outputStates.update { it + (outIndex to on) }
            return
        }
        if (parts.size < 2) return

        val type = parts.last().uppercase()          // NH3 / H2S / TEMP
        val channelRaw = parts[parts.size - 2]         // 1 / ch1 / CH1 ...
        val channel = channelRaw.filter { it.isDigit() }.ifBlank { channelRaw }

        val key = "CH$channel/$type"
        val trimmed = payload.trim()
        val isError = trimmed.equals("err", ignoreCase = true) ||
            trimmed.toDoubleOrNull() == null

        _readings.update { current ->
            current + (key to GasReading(
                displayValue = if (isError) "Err" else trimmed,
                isError = isError,
                updatedAtMillis = System.currentTimeMillis()
            ))
        }
    }

    fun clear() {
        _readings.value = emptyMap()
    }
}
