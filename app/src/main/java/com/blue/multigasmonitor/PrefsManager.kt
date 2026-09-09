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

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 1883)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var useTls: Boolean
        get() = prefs.getBoolean(KEY_TLS, false)
        set(value) = prefs.edit().putBoolean(KEY_TLS, value).apply()

    /** 예: "gas" 이면 gas/1/NH3, gas/1/H2S, gas/1/TEMP 형태의 토픽을 구독합니다. */
    var topicPrefix: String
        get() = prefs.getString(KEY_TOPIC_PREFIX, "gas") ?: "gas"
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
    }
}
