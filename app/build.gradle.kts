plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android") // Hiltプラグインを追加
    id("com.google.devtools.ksp")      // KSPプラグインを追加
}

android {
    namespace = "com.example.hutaburakari"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hutaburakari"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // ViewModel for Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose.android)
    // Coil for Compose (画像読み込み)
    implementation(libs.coil.compose)
    // Jsoup (HTMLパーサー)
    implementation(libs.jsoup)
    // Lifecycle-aware state collection
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)

    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:5.1.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.jsoup:jsoup:1.21.1")
    // implementation("com.google.code.gson:gson-extras:2.10.1") // この行を削除しました

    // Media3 (ExoPlayer) のライブラリを追加
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("com.google.dagger:hilt-android:2.55") // バージョンを2.56.2に変更
    ksp("com.google.dagger:hilt-compiler:2.55")      // バージョンを2.56.2に変更
    implementation("com.github.franmontiel:PersistentCookieJar:v1.0.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
