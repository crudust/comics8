plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.8.18")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-x64:0.8.18")
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.8.18")
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
            modules(
                "java.sql",
                "java.naming",
                "java.desktop",
                "java.security.jgss",
                "java.security.sasl",
                "java.management",
                "jdk.unsupported",
            )
            packageName = "Comics8"
            packageVersion = "1.5.19"
            macOS {
                bundleID = "com.comics8.desktop"
                dockName = "Comics8"
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
            windows {
                menuGroup = "Comics8"
                upgradeUuid = "68c92f54-5264-4e92-969c-29369f69747a"
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
        }
    }
}

val packageWindowsPortable = tasks.register<Zip>("packageWindowsZip") {
    group = "compose desktop"
    description = "Packages a portable Windows distribution with launcher scripts and icons."
    dependsOn("packageUberJarForCurrentOS")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("Comics8-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main"))

    from(layout.buildDirectory.dir("compose/jars")) {
        include("*.jar")
        rename { "Comics8.jar" }
    }
    from(project.file("src/main/resources/icon.ico"))
    from(project.file("src/main/resources/icon.png"))
    from(project.file("src/main/resources/windows"))
}



