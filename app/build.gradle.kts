plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blue.multigasmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blue.multigasmonitor"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // 이 debug.keystore를 저장소에 커밋해서 매 빌드마다 같은 서명으로 APK를 만듭니다.
    // (CI 러너가 기본 debug.keystore를 매번 새로 생성하면, 빌드마다 서명이 달라져서
    //  폰에 재설치할 때 "업데이트"가 아니라 "삭제 후 새 설치"가 되어 저장된 설정이 날아갑니다.)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // HiveMQ MQTT Client가 끌고 오는 netty 라이브러리들끼리 META-INF 메타파일이 겹쳐서
    // mergeDebugJavaResource 단계에서 충돌하는 것을 방지
    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // MQTT client (MQTT 3.1.1 / 5.0) - check Maven Central for the newest release
    // https://central.sonatype.com/artifact/com.hivemq/hivemq-mqtt-client
    implementation("com.hivemq:hivemq-mqtt-client:1.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
