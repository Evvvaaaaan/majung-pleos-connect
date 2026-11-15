plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.majung.pleos"
    // 공식 가이드 사전 준비: Android SDK API 34 (Pleos Connect Emulator System Image)
    compileSdk = 34

    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.majung.pleos"
        // 공식 시작하기 가이드 필수 조건: minSdk API 28 이상
        minSdk = 28
        targetSdk = 34
        versionCode = 300
        versionName = "3.0.0"

        // 공개 중계 서버(HTTPS). 서버를 직접 띄우지 않아도 동승자 폰까지 연결된다.
        // 로컬 서버로 시험하려면 앱의 [설정]에서 http://10.0.2.2:8080 (에뮬레이터에서 본 호스트 PC)으로 바꾼다.
        buildConfigField("String", "DEFAULT_RELAY_URL", "\"https://majung-relay-6d6d41.azurewebsites.net\"")
        // 일회성 공유 토큰 최대 만료 시간: 20분(1,200,000ms), 탑승 완료 또는 수동 종료 시 즉시 파기
        buildConfigField("long", "SESSION_MAX_TTL_MS", "1200000L")
    }

    flavorDimensions += "runtime"
    productFlavors {
        create("pleos") {
            dimension = "runtime"
            // Pleos Connect Emulator (API 34 Cold Boot / RAM 4GB) 및 실차 SDK 실연동 빌드
            buildConfigField("boolean", "USE_PLEOS_HARDWARE_SDK", "true")
            buildConfigField("String", "RUNTIME_BADGE_LABEL", "\"[SDK 실연동]\"")
        }
        create("sim") {
            dimension = "runtime"
            applicationIdSuffix = ".sim"
            versionNameSuffix = "-sim"
            // 외부 Maven 인증이나 실차 없이 일반 Android Studio 에뮬레이터에서도 전체 6단계를 재현하는 빌드
            buildConfigField("boolean", "USE_PLEOS_HARDWARE_SDK", "false")
            buildConfigField("String", "RUNTIME_BADGE_LABEL", "\"[SIM 시뮬레이션]\"")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 서명 키가 없는 심사 환경에서도 debug 서명으로 즉시 설치 가능한 APK가 생성되도록 설정
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.osmdroid.android)
    implementation(libs.zxing.core)   // 진단 창의 동승자 링크 QR (시연·심사 때 폰으로 바로 열기)

    // 공식 가이드 기준 Pleos Connect SDK 의존성 (pleos flavor 전용)
    // - implementation("ai.pleos.playground:Vehicle:2.3.2")
    // - implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // - implementation("ai.pleos.playground:FusedLocation:1.1.3")
    // - implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.navi)
    "pleosImplementation"(libs.pleos.location)
    "pleosImplementation"(libs.pleos.tts)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
