import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget

val desktopOnly =
    providers.gradleProperty("comics8.desktopOnly").orNull == "true" ||
        System.getenv("COMICS8_DESKTOP_ONLY") == "1" ||
        !rootProject.file("app").isDirectory

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

if (!desktopOnly) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(17)

    jvm("desktop")

    if (!desktopOnly) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "com.comics8.shared.ui"
            compileSdk = 37
            minSdk = 26
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.compose.multiplatform.material3)
            implementation(libs.compose.multiplatform.icons)
        }

        val commonJvmMain = create("commonJvmMain") {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":core"))
            }
        }

        named("desktopMain") {
            dependsOn(commonJvmMain)
        }

        if (!desktopOnly) {
            named("androidMain") {
                dependsOn(commonJvmMain)
                dependencies {
                    implementation(libs.activity.compose)
                }
            }
        }

        named("desktopTest") {
            dependencies {
                implementation(libs.junit)
                implementation(libs.truth)
            }
        }
    }
}
