import java.util.Properties
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appBaseVersionName = Properties().apply {
    rootProject.file("version.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}.getProperty("versionName", "1.0")
val ciBuildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull()
val appBuildNumber = ciBuildNumber ?: 1
val appVersionName = ciBuildNumber?.let { "$appBaseVersionName-b$it" } ?: appBaseVersionName
val ciKeystore: String? = System.getenv("XFILES_KEYSTORE")

android {
    namespace = "app.local1st.files"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.local1st.files"
        minSdk = 26
        targetSdk = 37
        versionCode = appBuildNumber
        versionName = appVersionName
    }

    flavorDimensions += "edition"
    productFlavors {
        create("mobile") {
            dimension = "edition"
        }
        create("tv") {
            dimension = "edition"
            applicationIdSuffix = ".tv"
            versionNameSuffix = "-tv"
        }
    }

    signingConfigs {
        if (ciKeystore != null) {
            create("release") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("XFILES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XFILES_KEY_ALIAS")
                keyPassword = System.getenv("XFILES_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (ciKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (ciKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en", "ar", "de", "es", "fr", "hi", "id", "it", "ja", "ko",
            "nl", "pl", "pt-rBR", "ru", "tr", "vi", "zh-rCN", "zh-rTW",
        )
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md}"
            excludes += listOf(
                "/frameworks/**",
                "/api_database/**",
                "/shadow/bundletool/com/android/support/migrateToAndroidx/**",
                "**/*.proto",
                "/*.proto",
            )
        }
    }
}

kotlin {
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.smbj)
    implementation(project(path = ":vendor:bundletool-shaded", configuration = "shadedRuntimeElements"))

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.cast)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    systemProperty("xfiles.repo", rootDir.absolutePath)
    val sdkDir = rootProject.file("local.properties").takeIf { it.exists() }?.let { propertiesFile ->
        Properties().apply { propertiesFile.inputStream().use { load(it) } }.getProperty("sdk.dir")
    }?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
    sdkDir?.let { File(it, "build-tools/37.0.0") }
        ?.let { buildTools -> listOf("aapt2", "aapt2.exe").map { File(buildTools, it) } }
        ?.firstOrNull { it.isFile }
        ?.let { systemProperty("xfiles.aapt2", it.absolutePath) }
    testLogging.showStandardStreams = true
}
