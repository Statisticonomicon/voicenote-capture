plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.notaricus.voicenote"
    compileSdk = 34

    defaultConfig {
        // Shared applicationId with :mobile so Wear Data Layer treats them as companion apps.
        applicationId = "com.notaricus.voicenote"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-phase1"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        // Core library desugaring: lets us use java.time etc. safely regardless of runtime.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.core.ktx)
    implementation(libs.wear)
    implementation(libs.watchface.complications.data.source.ktx)
    implementation(libs.lifecycle.service)
    implementation(libs.play.services.wearable)
}
