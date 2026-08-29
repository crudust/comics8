import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

fun signingValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("comics8.signing.storeFile", "COMICS8_SIGNING_STORE_FILE")
    ?: "keystore/release.keystore"
val releaseStorePassword = signingValue("comics8.signing.storePassword", "COMICS8_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = signingValue("comics8.signing.keyAlias", "COMICS8_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("comics8.signing.keyPassword", "COMICS8_SIGNING_KEY_PASSWORD")
val hasReleaseSigningCredentials = listOf(
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it != null }

abstract class ValidateReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val storeFilePath: Property<String>

    @get:Internal
    abstract val configuredStorePassword: Property<String>

    @get:Input
    @get:Optional
    abstract val configuredKeyAlias: Property<String>

    @get:Internal
    abstract val configuredKeyPassword: Property<String>

    @TaskAction
    fun validate() {
        val missing = buildList {
            if (!configuredStorePassword.isPresent) add("COMICS8_SIGNING_STORE_PASSWORD / comics8.signing.storePassword")
            if (!configuredKeyAlias.isPresent) add("COMICS8_SIGNING_KEY_ALIAS / comics8.signing.keyAlias")
            if (!configuredKeyPassword.isPresent) add("COMICS8_SIGNING_KEY_PASSWORD / comics8.signing.keyPassword")
        }
        check(missing.isEmpty()) {
            "Android release signing is not configured. Missing: ${missing.joinToString()}. " +
                "Set environment variables on the release host or private Gradle properties."
        }
        check(File(storeFilePath.get()).isFile) {
            "Android release keystore was not found: ${storeFilePath.get()}. " +
                "Set COMICS8_SIGNING_STORE_FILE or comics8.signing.storeFile."
        }
    }
}

val validateReleaseSigning by tasks.registering(ValidateReleaseSigningTask::class) {
    group = "verification"
    description = "Validates the external Android release signing configuration."
    storeFilePath.set(rootProject.file(releaseStoreFile).absolutePath)
    releaseStorePassword?.let(configuredStorePassword::set)
    releaseKeyAlias?.let(configuredKeyAlias::set)
    releaseKeyPassword?.let(configuredKeyPassword::set)
}

android {
    namespace = "com.comics8.monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.comics8.monitor"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.2.4"
    }

    signingConfigs {
        if (hasReleaseSigningCredentials) create("release") {
            storeFile = rootProject.file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standalone") {
            dimension = "distribution"
            buildConfigField("Boolean", "IS_PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("Boolean", "IS_PLAY_STORE", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Keep debug builds/tests usable without release secrets, but fail every release package explicitly.
tasks.matching {
    (it.name.startsWith("assemble") || it.name.startsWith("bundle")) && it.name.endsWith("Release")
}.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.play:app-update:2.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
