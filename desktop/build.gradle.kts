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

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.json:json:20240303")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.4")
}

compose.desktop {
    application {
        mainClass = "com.comics8.desktop.MainKt"
        nativeDistributions {
            modules("java.sql", "java.naming", "java.desktop")
            packageName = "Comics8"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.comics8.desktop"
                dockName = "Comics8"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
        }
    }
}
