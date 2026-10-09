import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun getBuildTimestamp(): Date = Date()
fun getFormattedVersionName(): String = SimpleDateFormat("yy.MM.dd_HHmm", Locale.US).format(getBuildTimestamp())
fun getFormattedVersionCode(): Int = SimpleDateFormat("yyDDDHHmm", Locale.US).format(getBuildTimestamp()).toInt()

android {
    namespace = "com.olegskal.mushroom"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.olegskal.mushroom"
        minSdk = 26
        targetSdk = 34
        versionCode = getFormattedVersionCode()
        versionName = getFormattedVersionName()
        resourceConfigurations += setOf("en")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.mapsforge:mapsforge-map-android:0.25.0")
    implementation("org.mapsforge:mapsforge-themes:0.25.0")
    implementation("org.mapsforge:mapsforge-poi-android:0.25.0")
    implementation("com.github.abrensch.brouter:brouter-core:v1.7.9")
    implementation("com.github.abrensch.brouter:brouter-mapaccess:v1.7.9")
    implementation("com.github.abrensch.brouter:brouter-util:v1.7.9")
    implementation("com.github.abrensch.brouter:brouter-expressions:v1.7.9")
    implementation("com.github.abrensch.brouter:brouter-codec:v1.7.9")
}

tasks.matching {
    it.name.contains("Manifest", ignoreCase = true) || it.name.contains("Package", ignoreCase = true)
}.configureEach {
    outputs.upToDateWhen { false }
}
