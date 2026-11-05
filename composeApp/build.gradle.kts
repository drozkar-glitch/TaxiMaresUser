import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(compose.preview)
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.webkit:webkit:1.12.1")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }
    }
}

val appVer = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "2.0"
val ciBuildNumber = System.getenv("BUILD_NUM")?.toIntOrNull()
    ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100)
    ?: 110

android {
    namespace = "cz.mares.taxi.user"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.mares.taxi.user"
        minSdk = 24
        targetSdk = 36
        versionCode = ciBuildNumber
        versionName = "$appVer.$ciBuildNumber"
        // Huawei AppGallery App ID: 117624369
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            val propFiles = listOf(
                rootProject.file("keystore.properties"),
                rootProject.file("android_keystore.props"),
                project.file("keystore.properties")
            )
            propFiles.firstOrNull { it.exists() }?.let { propFile ->
                FileInputStream(propFile).use { props.load(it) }
            }

            val envKeystorePath = System.getenv("CM_KEYSTORE_PATH")
                ?: System.getenv("ANDROID_KEYSTORE_PATH")
                ?: System.getenv("STORE_FILE")
                ?: System.getenv("KEYSTORE_FILE")
                ?: props.getProperty("storeFile")

            val resolvedFile = envKeystorePath?.let { path ->
                val f = File(path)
                if (f.exists()) f else rootProject.file(path)
            }?.takeIf { it.exists() }

            if (resolvedFile != null) {
                storeFile = resolvedFile
                storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                    ?: System.getenv("STORE_PASSWORD")
                    ?: props.getProperty("storePassword")
                keyAlias = System.getenv("CM_KEY_ALIAS")
                    ?: System.getenv("KEY_ALIAS")
                    ?: props.getProperty("keyAlias")
                keyPassword = System.getenv("CM_KEY_PASSWORD")
                    ?: System.getenv("KEY_PASSWORD")
                    ?: props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile != null && releaseSign.storeFile!!.exists()) {
                signingConfig = releaseSign
            }
        }
        getByName("debug") {
            val releaseSign = signingConfigs.getByName("release")
            if (releaseSign.storeFile != null && releaseSign.storeFile!!.exists()) {
                signingConfig = releaseSign
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
