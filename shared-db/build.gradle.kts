import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

val desktopOnly =
    providers.gradleProperty("comics8.desktopOnly").orNull == "true" ||
        System.getenv("COMICS8_DESKTOP_ONLY") == "1" ||
        !rootProject.file("app").isDirectory

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.google.devtools.ksp")
}

if (!desktopOnly) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm("desktop")

    if (!desktopOnly) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "com.comics8.shared.db"
            compileSdk = 37
            minSdk = 26
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.room.runtime)
            implementation(libs.coroutines.core)
        }

        named("desktopMain") {
            dependencies {
                api(libs.sqlite.bundled)
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.truth)
                implementation(libs.coroutines.core)
            }
        }
    }
}

dependencies {
    add("kspDesktop", libs.room.compiler)
    add("kspDesktop", libs.guava)
    if (!desktopOnly) {
        add("kspAndroid", libs.room.compiler)
        add("kspAndroid", libs.guava)
    }
}
