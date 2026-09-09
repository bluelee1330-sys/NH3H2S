package com.blue.multigasmonitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /**
     * 토픽(예: "gas/1/NH3")과 페이로드 문자열(예: "0.0")을 받아 저장소에 반영.
     * 토픽 규칙이 다르면 MqttForegroundService의 파싱 로직만 바꾸면 됩니다.
     */
    fun onMessage(topic: String, payload: String) {
        val parts = topic.trim('/').split("/")
        if (parts.size < 2) return

        val type = parts.last().uppercase()          // NH3 / H2S / TEMP
        val channel = parts[parts.size - 2]           // 1 / 2 / 3

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
