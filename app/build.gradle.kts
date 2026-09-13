import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

// ---- Version ---------------------------------------------------------------------
// A release takes its version from the git tag: the workflow builds tag v1.2.3 with
// -PversionName=1.2.3. versionCode follows from it (major*10000 + minor*100 + patch), so
// every higher version installs over the previous one with nothing else to bump.
val appVersionName: String = (findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"
val appVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(appVersionName)
        ?: throw GradleException("versionName must start with MAJOR.MINOR.PATCH, got '$appVersionName'")
    val (major, minor, patch) = match.destructured.toList().map { it.toInt() }
    if (minor > 99 || patch > 99) throw GradleException("minor and patch must be 0-99, got '$appVersionName'")
    major * 10_000 + minor * 100 + patch
}

// ---- Release signing ---------------------------------------------------------------
// The key never lives in the repo. On this PC it comes from keystore.properties (git-ignored,
// see keystore.properties.example); on GitHub Actions from environment variables that the
// release workflow fills in from repository secrets.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("COVERDECK_KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("COVERDECK_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("COVERDECK_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("COVERDECK_KEY_PASSWORD", "keyPassword")
val hasReleaseKey = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }

android {
    namespace = "com.raihan.coverdeck"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raihan.coverdeck"
        // Z Flip 5 shipped on Android 13; this unit is on Android 16 / One UI 8.5.
        minSdk = 33
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key a local release build still works, signed with the debug
            // key; the check below stops that from ever happening on CI.
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any {
        it.project == project && (it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease"))
    }
    if (buildsRelease && !hasReleaseKey) {
        if (System.getenv("CI") == "true") {
            throw GradleException("No release signing key: set the KEYSTORE_* and KEY_* repository secrets.")
        }
        logger.warn("CoverDeck: no keystore.properties, so this release build is signed with the debug key.")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    compileOnly(libs.hiddenapibypass)
    implementation(libs.hiddenapibypass)
}
