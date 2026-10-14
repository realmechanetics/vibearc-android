plugins {
    alias(libs.plugins.android.application)
}

val admobAppId = providers.gradleProperty("VIBEARC_ADMOB_APP_ID")
    .getOrElse("ca-app-pub-3940256099942544~3347511713")
val settingsBannerAdUnitId = providers.gradleProperty("VIBEARC_ADMOB_SETTINGS_BANNER_ID")
    .getOrElse("ca-app-pub-3940256099942544/9214589741")

android {
    namespace = "com.mechanetics.vibearc"
    buildFeatures {
        resValues = true
    }
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mechanetics.vibearc"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["admobAppId"] = admobAppId
        resValue("string", "admob_settings_banner_id", settingsBannerAdUnitId)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.androidx.media)
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}