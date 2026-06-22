import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
}

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

fun releaseSigningProperty(propertyName: String, environmentName: String): String? {
    return releaseKeystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }
}

val releaseStoreFile = releaseSigningProperty("storeFile", "SMSPUSHER_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProperty("storePassword", "SMSPUSHER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProperty("keyAlias", "SMSPUSHER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProperty("keyPassword", "SMSPUSHER_RELEASE_KEY_PASSWORD")
val hasReleaseSigningConfig = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

val sharedVersionPropertiesFile = rootProject.file("../../version.properties")
val sharedVersionProperties = Properties().apply {
    check(sharedVersionPropertiesFile.isFile) {
        "Missing shared version file: ${sharedVersionPropertiesFile.path}"
    }
    sharedVersionPropertiesFile.inputStream().use(::load)
}

fun sharedVersionProperty(propertyName: String): String {
    return sharedVersionProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: error("Missing $propertyName in ${sharedVersionPropertiesFile.path}")
}

val appVersionName = sharedVersionProperty("ANDROID_VERSION_NAME")
val androidVersionCode = sharedVersionProperty("ANDROID_VERSION_CODE").toIntOrNull()?.takeIf { it > 0 }
    ?: error("ANDROID_VERSION_CODE must be a positive integer in ${sharedVersionPropertiesFile.path}")

android {
    namespace = "com.hippo2cat.smspusher"
    compileSdk = 35
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.hippo2cat.smspusher"
        minSdk = 26
        targetSdk = 35
        versionCode = androidVersionCode
        versionName = appVersionName
        setProperty("archivesBaseName", "SmsPusher")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("org.slf4j:slf4j-api:2.0.16")
    implementation("com.github.tony19:logback-android:3.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.15.1")
}

val rustCryptoManifest = rootProject.file("rust-crypto/Cargo.toml")
val rustCryptoSourceDir = rootProject.file("rust-crypto/src")
val sharedCryptoCrateDir = rootProject.file("../../services/desktop-rust/crates/smspusher-crypto")
val rustCryptoOutput = rootProject.file("src/main/jniLibs/arm64-v8a/libsmspusher_crypto_jni.so")

tasks.register<Exec>("buildRustCryptoArm64") {
    workingDir = rootProject.file("rust-crypto")
    inputs.file(rustCryptoManifest)
    inputs.dir(rustCryptoSourceDir)
    inputs.file(sharedCryptoCrateDir.resolve("Cargo.toml"))
    inputs.file(sharedCryptoCrateDir.resolve("build.rs"))
    inputs.dir(sharedCryptoCrateDir.resolve("src"))
    outputs.file(rustCryptoOutput)
    environment("ANDROID_NDK_HOME", android.ndkDirectory.absolutePath)
    environment("ANDROID_NDK_ROOT", android.ndkDirectory.absolutePath)
    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-o",
        "../src/main/jniLibs",
        "build",
        "--release"
    )
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("buildRustCryptoArm64")
}

tasks.matching { it.name in setOf("assembleRelease", "bundleRelease") }.configureEach {
    doFirst {
        check(hasReleaseSigningConfig) {
            "Release signing is not configured. Run apps/android/scripts/generate-android-release-keystore.sh " +
                "or create apps/android/keystore.properties from keystore.properties.example."
        }
    }
}
