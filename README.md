# Multi Gas Monitor (독립 Android 앱 예제)

IoT MQTT Panel 없이, MQTT 브로커에서 직접 데이터를 받아 표시하는 최소 동작 예제 프로젝트입니다.
스크린샷의 CH1~3 × NH3/H2S/TEMP 3×3 그리드를 그대로 재현했습니다.

## 구조

- `MqttForegroundService` — 앱이 백그라운드에 있어도 MQTT 연결을 유지하는 Foreground Service.
  HiveMQ MQTT Client(MQTT 3.1.1)로 브로커에 접속하고, `설정한 prefix/+/+` 토픽을 구독합니다.
  예) prefix가 `gas`이면 `gas/1/NH3`, `gas/2/H2S`, `gas/3/TEMP` 형태의 토픽을 모두 받습니다.
- `MqttRepository` — 마지막 수신 값을 담고 있는 저장소(StateFlow). 화면은 여기를 구독해서 그립니다.
- `MainActivity` — Jetpack Compose로 만든 3×3 그리드 UI + 설정(브로커 주소/포트/계정/토픽 prefix) 다이얼로그.
- `PrefsManager` — 브로커 설정을 SharedPreferences에 저장.

## Android Studio 없이 APK만 받고 싶다면 (GitHub Actions)

이 프로젝트에는 `.github/workflows/build-apk.yml`이 포함되어 있어서, GitHub에 올리기만 하면
GitHub 서버가 대신 빌드해서 APK를 만들어 줍니다. 로컬에 아무것도 설치할 필요가 없습니다.

1. github.com에 계정이 없다면 만듭니다(무료).
2. 새 저장소(Repository)를 하나 만듭니다. Public/Private 상관없습니다.
3. 저장소 페이지의 **Add file → Upload files**에서, 압축을 푼 `MultiGasMonitor` 폴더 전체를
   끌어다 놓습니다(Chrome/Edge에서는 폴더째 드래그&드롭이 폴더 구조를 유지한 채 업로드됩니다).
   git이나 명령줄을 몰라도 됩니다.
4. Commit(커밋)을 누르면 자동으로 **Actions** 탭에서 빌드가 시작됩니다(몇 분 정도 걸립니다).
5. 빌드가 끝나면 해당 Actions 실행 결과 페이지 하단 **Artifacts**에서
   `multi-gas-monitor-debug-apk`를 다운로드하면 그 안에 `app-debug.apk`가 들어 있습니다.
6. 폰에서 "출처를 알 수 없는 앱 설치" 허용 후 그 apk를 설치하면 됩니다.

(참고: 이 디버그 APK는 서명이 개발용 키로 되어 있어 설치는 되지만 스토어 배포용은 아닙니다.)

## 직접 로컬에서 빌드하고 싶다면 (Android Studio 없이, 명령줄만)

Android Studio(전체 IDE, 1GB+)를 설치하지 않고도, 훨씬 가벼운 **Android SDK Command-line Tools**만
설치해서 터미널에서 빌드할 수 있습니다.

1. JDK 17 설치 (예: Temurin 17).
2. https://developer.android.com/studio#command-tools 에서 "Command line tools only" 다운로드(수십 MB 수준).
3. 압축을 풀고 `sdkmanager --licenses` 로 라이선스 동의, 이어서
   `sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"` 로 필요한 것만 설치.
4. 이 프로젝트 폴더에서 `gradle wrapper` 한 번 실행해 `gradlew`를 만든 뒤 `./gradlew assembleDebug`
   (또는 시스템에 설치된 gradle로 바로 `gradle assembleDebug`).
5. `app/build/outputs/apk/debug/app-debug.apk` 가 결과물입니다.

이 방법도 GUI 없이 CLI만 쓰지만, 최초 SDK 다운로드(수백 MB)와 환경변수(`ANDROID_HOME`) 설정이 필요해서
**위의 GitHub Actions 방법이 훨씬 간단**합니다.

## 실행 방법 (Android Studio 사용 시)

1. Android Studio(최신 버전 권장)에서 이 폴더를 **Open**으로 엽니다. Gradle wrapper jar가 빠져 있으므로
   "Gradle wrapper가 없습니다. 생성할까요?" 안내가 뜨면 승인하거나, `File > Sync Project with Gradle Files`를 실행하세요.
2. 앱을 실행한 뒤, 우측 상단 **설정** 버튼을 눌러 브로커 주소/포트/계정/토픽 prefix를 입력합니다.
   - 지금 IoT MQTT Panel에 등록된 것과 같은 브로커 주소/포트/계정을 그대로 넣으면 됩니다.
   - 토픽 구조가 `gas/1/NH3` 형태가 아니라면 `MqttRepository.onMessage()`의 파싱 로직만 맞게 고치면 됩니다.
3. 저장하면 서비스가 재연결되고, 값이 들어오는 대로 그리드에 표시됩니다. 숫자가 아닌 값(`Err` 등)은 빨간색으로 표시됩니다.

## 실제 사용 전 점검할 부분

- **토픽/페이로드 형식 확인**: 지금은 "토픽 마지막 세그먼트 = 종류(NH3/H2S/TEMP), 그 앞 세그먼트 = 채널 번호",
  "페이로드 = 숫자 문자열" 이라고 가정했습니다. 실제 펌웨어가 JSON 등 다른 형식으로 보낸다면 파싱 로직만 바꾸면 됩니다.
- **Client ID 중복**: 같은 브로커에 IoT MQTT Panel과 동시에 접속하면 Client ID가 겹치지 않아야 합니다
  (현재 코드는 매번 랜덤 ID를 사용하므로 기본적으로 안전합니다).
- **비밀번호 저장**: 데모용으로 평문 SharedPreferences를 사용했습니다. 실제 배포 시 `EncryptedSharedPreferences`
  또는 Android Keystore 기반 저장으로 교체를 권장합니다.
- **HiveMQ MQTT Client 버전**: `app/build.gradle.kts`에 1.3.0으로 고정했습니다. Maven Central에서 최신 버전을 확인해 올리세요.
  https://central.sonatype.com/artifact/com.hivemq/hivemq-mqtt-client
- **알림 권한**: Android 13(API 33) 이상은 알림 권한이 없으면 Foreground Service 알림이 안 보일 뿐, 연결 자체는 동작합니다.

## 다음에 추가하면 좋은 것

- Room DB로 값 이력을 저장해서 그래프로 보여주기
- 임계값 초과 시 로컬 알림/진동
- 여러 브로커(사이트) 프로필 전환
- Wi-Fi 끊김 시 배너로 상태 안내
