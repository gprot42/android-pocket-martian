import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

android {
    namespace = "com.pocketmartian.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketmartian.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 41
        versionName = "0.0.41"

        val buildDate = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // The release key, from private-not-in-git/secrets/release-key.properties (a folder git
    // ignores), ~/.gradle/gradle.properties or POCKET_MARTIAN_* environment variables;
    // never from the repository itself.
    val keyFile = rootProject.file("private-not-in-git/secrets/release-key.properties")
    val keyProps = Properties().apply { if (keyFile.exists()) keyFile.inputStream().use { load(it) } }
    val releaseKey = listOf("STORE_FILE", "STORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        .associateWith { name ->
            keyProps.getProperty(name.lowercase())
                ?: (findProperty("pocketMartian.${name.lowercase()}") as String?)
                ?: System.getenv("POCKET_MARTIAN_$name")
        }
    val hasReleaseKey = releaseKey.values.all { !it.isNullOrBlank() }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseKey.getValue("STORE_FILE")!!)
                storePassword = releaseKey.getValue("STORE_PASSWORD")
                keyAlias = releaseKey.getValue("KEY_ALIAS")
                keyPassword = releaseKey.getValue("KEY_PASSWORD")
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
            // Without the release key this falls back to the debug keystore, which still
            // installs on a phone but which Google Play refuses.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

    // Where the app is distributed. Both are the same app (same package, same key), but
    // Google Play forbids an app updating itself outside Play, so only the GitHub build
    // checks GitHub releases and holds the permission to hand an APK to the installer.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "GITHUB_UPDATES", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "GITHUB_UPDATES", "false")
        }
    }

    // ABI splits for multi-architecture support (used in build.sh naming)
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
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
        // Let plain-JVM unit tests touch android.util.Log etc. without a stub crash.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Hilt - consistent version with plugin
    implementation("com.google.dagger:hilt-android:2.54")
    kapt("com.google.dagger:hilt-android-compiler:2.54")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Retrofit + OkHttp + Gson
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // DNS-over-HTTPS fallback when the network's resolver cannot find api.x.ai
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    // DataStore for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Secure storage for API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.7.0")

    // CommonMark for Markdown → HTML conversion (GFM tables support)
    implementation("org.commonmark:commonmark:0.23.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.23.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
