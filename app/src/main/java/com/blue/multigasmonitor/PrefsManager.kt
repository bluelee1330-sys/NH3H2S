package com.blue.multigasmonitor

import android.content.Context
import android.content.SharedPreferences

/**
 * MQTT 브로커 연결 설정을 SharedPreferences에 저장/로드.
 * 실제 배포 시 비밀번호는 EncryptedSharedPreferences 사용을 권장합니다.
 */
class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mqtt_settings", Context.MODE_PRIVATE)

    // 기본값은 지금 쓰는 HiveMQ Cloud 브로커 기준입니다. 브로커를 바꾸시면 여기 기본값도 같이 바꿔두면
    // 새로 설치하거나 새 폰에서 켤 때 주소/포트/TLS/토픽은 안 건드리고 계정만 입력하면 됩니다.
    // (계정/비밀번호는 보안상 기본값을 넣지 않고 매번 직접 입력하도록 비워둡니다.)
    var host: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_TLS, DEFAULT_USE_TLS)
        set(value) = prefs.edit().putBoolean(KEY_TLS, value).apply()

    /** 예: "gas" 이면 gas/1/NH3, gas/1/H2S, gas/1/TEMP 형태의 토픽을 구독합니다. */
    var topicPrefix: String
        get() = prefs.getString(KEY_TOPIC_PREFIX, DEFAULT_TOPIC_PREFIX) ?: DEFAULT_TOPIC_PREFIX
        set(value) = prefs.edit().putString(KEY_TOPIC_PREFIX, value).apply()

    val isConfigured: Boolean
        get() = host.isNotBlank()

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TLS = "use_tls"
        private const val KEY_TOPIC_PREFIX = "topic_prefix"

        private const val DEFAULT_HOST = "d058243e47ce4e6da5ed6823321c1690.s1.eu.hivemq.cloud"
        private const val DEFAULT_PORT = 8883
        private const val DEFAULT_USE_TLS = true
        private const val DEFAULT_TOPIC_PREFIX = "bluelee_nh3h2s"
    }
}
