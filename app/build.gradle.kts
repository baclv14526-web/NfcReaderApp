import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Đọc thông tin keystore từ:
// 1) Biến môi trường (dùng trong GitHub Actions - xem .github/workflows/build-apk.yml)
// 2) File keystore.properties ở thư mục gốc (dùng khi build local, KHÔNG commit file này lên git)
val keystoreProps = Properties()
val keystorePropsFile = rootProject.file("keystore.properties")
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

fun cfg(key: String, envKey: String): String =
    System.getenv(envKey) ?: keystoreProps.getProperty(key) ?: ""

android {
    namespace = "com.example.nfcreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.nfcreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val storeFilePath = cfg("storeFile", "KEYSTORE_PATH")
            if (storeFilePath.isNotBlank()) {
                storeFile = file(storeFilePath)
                storePassword = cfg("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = cfg("keyAlias", "KEY_ALIAS")
                keyPassword = cfg("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Chỉ ký nếu có đủ thông tin keystore (tránh lỗi build khi chưa cấu hình)
            val storeFilePath = cfg("storeFile", "KEYSTORE_PATH")
            if (storeFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
