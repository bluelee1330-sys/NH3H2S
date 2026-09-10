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

    // connect()를 부를 때마다(화면 재개 시 등) 기존 클라이언트를 disconnect()하고 완전히
    // 새 클라이언트를 만드는데, 그 "옛날" 클라이언트의 addDisconnectedListener 콜백이
    // 비동기라서 "새" 클라이언트가 이미 연결에 성공한 뒤에 뒤늦게 도착하는 경우가 있었습니다.
    // 그러면 실제로는 잘 연결돼서 데이터도 잘 받고 있는데, 뒤늦은 옛날 이벤트가 상태를
    // CONNECTING(주황 점)으로 덮어써버리는 문제가 생깁니다. connect()를 부를 때마다 이
    // 번호를 하나씩 증가시키고, 리스너 콜백에서 "지금도 내가 최신 연결이 맞는지" 확인해서
    // 낡은 클라이언트의 이벤트는 무시하도록 막습니다.
    private var connectGeneration = 0

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
        MqttRepository.attachClient(null, "")
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

        // 이번 connect() 호출이 "몇 번째"인지 기록해두고, 아래 리스너들이 나중에 불릴 때
        // 그사이 더 최신 connect()가 또 불려서 자기가 이미 낡은 연결이 된 건 아닌지
        // 확인하는 데 씁니다(위 주석 참고).
        val myGeneration = ++connectGeneration

        MqttRepository.setConnectionState(ConnectionState.CONNECTING)

        val builder = Mqtt3Client.builder()
            .identifier("multigasmonitor-" + UUID.randomUUID().toString().take(8))
            .serverHost(prefs.host)
            .serverPort(prefs.port)
            .automaticReconnectWithDefaultConfig()
            // (2026-09-10) automaticReconnectWithDefaultConfig()는 끊긴 TCP/TLS 연결만
            // 알아서 다시 붙여줄 뿐, 구독은 자동으로 복구해주지 않습니다. 그래서 화면/
            // 와이파이가 잠깐이라도 흔들려서 재연결이 한 번 일어나면, 연결 자체는 살아
            // 있는데 구독이 없어서 값이 더 이상 안 들어오는 문제가 있었습니다(상단 초록
            // 점/보드 상태는 마지막으로 받은 값 그대로라 "온라인"으로 계속 보임).
            // addConnectedListener는 최초 연결이든 자동 재연결이든 "연결될 때마다" 항상
            // 호출되므로, 여기서 매번 다시 구독해서 이 문제를 근본적으로 막습니다.
            .addConnectedListener {
                if (myGeneration != connectGeneration) return@addConnectedListener   // 낡은 연결의 뒤늦은 이벤트는 무시
                MqttRepository.setConnectionState(ConnectionState.CONNECTED)
                updateNotification("연결됨 · ${prefs.host}:${prefs.port}")
                client?.let { c ->
                    MqttRepository.attachClient(c, prefs.topicPrefix)
                    subscribe(c, prefs.topicPrefix)
                }
            }
            .addDisconnectedListener {
                if (myGeneration != connectGeneration) return@addDisconnectedListener   // 낡은 연결의 뒤늦은 이벤트는 무시
                // 자동 재연결이 백그라운드에서 다시 시도하는 중이라는 뜻이라 CONNECTING으로
                // 표시합니다. 재연결에 성공하면 addConnectedListener가 다시 불려서
                // CONNECTED로 돌아갑니다.
                MqttRepository.setConnectionState(ConnectionState.CONNECTING)
                updateNotification("연결 끊김 - 재연결 시도 중...")
            }

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

        // 성공 시 처리(상태 갱신/구독)는 위 addConnectedListener가 담당합니다(최초 연결도
        // 거기서 한 번 불립니다). 여기서는 "최초 연결 시도 자체가 실패"한 경우만 봅니다.
        connectBuilder.send()
            .whenComplete { _, throwable ->
                if (myGeneration != connectGeneration) return@whenComplete   // 낡은 연결의 뒤늦은 이벤트는 무시
                if (throwable != null) {
                    MqttRepository.setConnectionState(ConnectionState.ERROR)
                    updateNotification("연결 실패: ${throwable.message}")
                }
            }
    }

    private fun subscribe(client: Mqtt3AsyncClient, topicPrefix: String) {
        // 예: gas/1/NH3, gas/2/H2S, gas/3/TEMP 뿐 아니라 gas/board(보드 online/offline, LWT)까지
        // prefix 아래 전부 받기 위해 다중 레벨 와일드카드(#) 사용
        val filter = "$topicPrefix/#"

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
