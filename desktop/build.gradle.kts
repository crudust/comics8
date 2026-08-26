import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream

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
            packageVersion = "0.1.1"
            description = "Comics8 Monitor Desktop"
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

abstract class PrepareWindowsJreTask : DefaultTask() {
    @get:OutputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:Internal
    abstract val jreZip: RegularFileProperty

    @TaskAction
    fun run() {
        val target = runtimeDir.get().asFile
        val zip = jreZip.get().asFile

        if (!zip.exists()) {
            zip.parentFile.mkdirs()
            println("Downloading Adoptium Temurin 17 Windows x64 JRE...")
            val url = URI("https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.14%2B7/OpenJDK17U-jre_x64_windows_hotspot_17.0.14_7.zip").toURL()
            url.openStream().use { input ->
                zip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        if (!File(target, "bin/javaw.exe").exists()) {
            println("Extracting Windows JRE runtime into ${target.absolutePath}...")
            target.deleteRecursively()
            target.mkdirs()

            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryPath = entry.name.substringAfter("/")
                    if (entryPath.isNotEmpty()) {
                        val outFile = File(target, entryPath)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile.mkdirs()
                            outFile.outputStream().use { fos ->
                                zis.copyTo(fos)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }
}

val prepareWindowsJre = tasks.register<PrepareWindowsJreTask>("prepareWindowsJre") {
    group = "compose desktop"
    description = "Downloads and extracts Adoptium Temurin OpenJDK 17 Windows x64 JRE into build/windows-runtime/runtime"
    runtimeDir.set(layout.buildDirectory.dir("windows-runtime/runtime"))
    jreZip.set(rootProject.layout.projectDirectory.file(".gradle/windows-jre-cache/temurin-jre-17-win-x64.zip"))
}

val compileWindowsResource by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "Compiles Windows launcher resources using windres"
    val rcFile = file("src/main/c/launcher.rc")
    val resFile = layout.buildDirectory.file("windows-launcher/launcher.res")

    inputs.file(rcFile)
    inputs.file(file("src/main/resources/icon.ico"))
    outputs.file(resFile)

    doFirst {
        resFile.get().asFile.parentFile.mkdirs()
    }

    commandLine("x86_64-w64-mingw32-windres", rcFile.absolutePath, "-O", "coff", "-o", resFile.get().asFile.absolutePath)
}

val compileWindowsLauncher by tasks.registering(Exec::class) {
    group = "compose desktop"
    description = "Compiles the native Windows Comics8.exe launcher using MinGW"
    dependsOn(compileWindowsResource)

    val cFile = file("src/main/c/launcher.c")
    val resFile = layout.buildDirectory.file("windows-launcher/launcher.res")
    val exeFile = layout.buildDirectory.file("windows-launcher/Comics8.exe")

    inputs.file(cFile)
    inputs.file(resFile)
    outputs.file(exeFile)

    doFirst {
        exeFile.get().asFile.parentFile.mkdirs()
    }

    commandLine(
        "x86_64-w64-mingw32-gcc",
        "-mwindows",
        "-municode",
        "-O2",
        "-s",
        cFile.absolutePath,
        resFile.get().asFile.absolutePath,
        "-o",
        exeFile.get().asFile.absolutePath
    )
}

val packageWindowsPortable = tasks.register<Zip>("packageWindowsZip") {
    group = "compose desktop"
    description = "Packages a clean standalone portable Windows distribution with Comics8.exe and app/ subfolder."
    dependsOn("packageUberJarForCurrentOS", prepareWindowsJre, compileWindowsLauncher)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("Comics8-win.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main"))

    // Native launcher Comics8.exe at the root level
    from(layout.buildDirectory.dir("windows-launcher")) {
        include("Comics8.exe")
    }

    // Comics8.jar placed inside app/ subfolder
    into("app") {
        from(layout.buildDirectory.dir("compose/jars")) {
            include("*.jar")
            rename { "Comics8.jar" }
        }
    }

    // Bundled JRE runtime inside runtime/ subfolder
    from(layout.buildDirectory.dir("windows-runtime")) {
        include("runtime/**")
    }
}
