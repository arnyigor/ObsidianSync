import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun loadLocalSecrets(fileName: String) = Properties().apply {
    rootProject.file(fileName)
        .takeIf { it.isFile }
        ?.inputStream()
        ?.use(::load)
}

val signingProperties = loadLocalSecrets("secret.properties")
val dotEnvProperties = loadLocalSecrets(".env")

fun String.normalizedSecret(): String? = trim()
    .removeSurrounding("\"")
    .removeSurrounding("'")
    .takeIf(String::isNotBlank)

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf(String::isNotBlank)
        ?: signingProperties.getProperty(name)?.normalizedSecret()
        ?: dotEnvProperties.getProperty(name)?.normalizedSecret()

val releaseKeystorePath = signingValue("ANDROID_KEYSTORE_PATH")
val releaseKeystorePassword = signingValue("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("ANDROID_KEY_ALIAS")
val releaseKeyPassword = signingValue("ANDROID_KEY_PASSWORD")
val releaseSigningValues = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)

check(releaseSigningValues.none { it != null } || releaseSigningValues.all { it != null }) {
    "Android release signing настроен не полностью. Заполните все ANDROID_* значения в environment, secret.properties или .env."
}

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.ktor.client.cio)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "ru.arny.obsidiansync"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "ru.arny.obsidiansync"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 2
        versionName = "1.0.1"
    }
    signingConfigs {
        if (
            releaseKeystorePath != null &&
            releaseKeystorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
