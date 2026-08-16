plugins {
    alias(libs.plugins.android.application)
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
    buildFeatures {
        buildConfig = true
    }
}

base.archivesName.set("FacebookAppAdsRemover-v${android.defaultConfig.versionName}")

dependencies {
    compileOnly("com.github.deltazefiro:XposedBridge:main-SNAPSHOT")
    implementation("org.luckypray:dexkit:2.0.7")
    implementation(libs.androidx.core.ktx)

}
