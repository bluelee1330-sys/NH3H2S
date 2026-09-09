package com.blue.multigasmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * MQTT 브로커에 계속 붙어있는 Foreground Service.
 * IoT MQTT Panel이 백그라운드에서 하던 일(연결 유지 + 구독 + 값 갱신)을 그대로 대체합니다.
 */
class MqttForegroundService : Service() {

    private var client: Mqtt3AsyncClient? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("연결 중..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        client?.disconnect()
        MqttRepository.setConnectionState(ConnectionState.DISCONNECTED)
        super.onDestroy()
    }

    private fun connect() {
        val prefs = PrefsManager(applicationContext)
        if (!prefs.isConfigured) {
            updateNotification("브로커 설정이 없습니다. 앱에서 설정을 입력하세요.")
            return
        }

        // 기존 연결이 있으면 정리
        client?.disconnect()

        MqttRepository.setConnectionState(ConnectionState.CONNECTING)

        val builder = Mqtt3Client.builder()
            .identifier("multigasmonitor-" + UUID.randomUUID().toString().take(8))
            .serverHost(prefs.host)
            .serverPort(prefs.port)
            .automaticReconnectWithDefaultConfig()

        if (prefs.useTls) {
            builder.sslWithDefaultConfig()
        }

        val newClient = builder.buildAsync()
        client = newClient

        val connectBuilder = newClient.connectWith()
        if (prefs.username.isNotBlank()) {
            connectBuilder
                .simpleAuth()
                .username(prefs.username)
                .password(prefs.password.toByteArray(StandardCharsets.UTF_8))
                .applySimpleAuth()
        }

        connectBuilder.send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    MqttRepository.setConnectionState(ConnectionState.ERROR)
                    updateNotification("연결 실패: ${throwable.message}")
                } else {
                    MqttRepository.setConnectionState(ConnectionState.CONNECTED)
                    updateNotification("연결됨 · ${prefs.host}:${prefs.port}")
                    subscribe(newClient, prefs.topicPrefix)
                }
            }
    }

    private fun subscribe(client: Mqtt3AsyncClient, topicPrefix: String) {
        // 예: gas/1/NH3, gas/2/H2S, gas/3/TEMP 처럼 "prefix/채널/종류" 형태를 전부 구독
        val filter = "$topicPrefix/+/+"

        client.subscribeWith()
            .topicFilter(filter)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                val topic = publish.topic.toString()
                val payload = String(
                    publish.payloadAsBytes,
                    StandardCharsets.UTF_8
                )
                MqttRepository.onMessage(topic, payload)
            }
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    MqttRepository.setConnectionState(ConnectionState.ERROR)
                    updateNotification("구독 실패: ${throwable.message}")
                }
            }
    }

    // ---- 알림(Foreground Service 필수) ----

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MQTT 연결 상태",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Multi Gas Monitor")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val CHANNEL_ID = "mqtt_status_channel"
        private const val NOTIFICATION_ID = 1
    }
}
