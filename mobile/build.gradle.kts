plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.notaricus.voicenote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notaricus.voicenote"  // shared with :wear for Data Layer pairing
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
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions { jvmTarget = "21" }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.material)
    implementation(libs.work.runtime.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.documentfile)
}
