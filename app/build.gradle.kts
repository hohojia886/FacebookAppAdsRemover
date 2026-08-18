plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "tn.loukious.facebookappadsremover"
    compileSdk = 35

    defaultConfig {
        applicationId = "tn.loukious.facebookappadsremover"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            /*
            // [2026-08-17 00:47] Original:
            isMinifyEnabled = false
            */
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        buildConfig = true
    }
}

base.archivesName.set("FacebookAppAdsRemover-v${android.defaultConfig.versionName}")

dependencies {
    compileOnly("com.github.deltazefiro:XposedBridge:3137dcc")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.0.7")
    implementation(libs.androidx.core.ktx)

}
