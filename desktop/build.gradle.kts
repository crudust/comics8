plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}

compose.desktop {
    application {
        mainClass = "com.comics8.desktop.MainKt"
        nativeDistributions {
            modules("java.sql", "java.naming", "java.desktop")
            packageName = "Comics8"
            packageVersion = "1.5.9"
            macOS {
                bundleID = "com.comics8.desktop"
                dockName = "Comics8"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}
