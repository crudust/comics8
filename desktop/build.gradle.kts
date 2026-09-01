import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    runtimeOnly(libs.skiko.windows.x64)
    runtimeOnly(libs.skiko.macos.x64)
    runtimeOnly(libs.skiko.macos.arm64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    implementation(libs.coroutines.swing)
    implementation(libs.sqlite)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
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
            packageVersion = "1.2.10"
            description = "Comics8 Monitor Desktop"
            macOS {
                bundleID = "com.comics8.desktop"
                dockName = "Comics8"
                iconFile.set(project.file("src/main/resources/icon.icns"))
                fileAssociation("application/zip", "zip", "ZIP comic archive")
                fileAssociation("application/vnd.comicbook+zip", "cbz", "Comic Book ZIP archive")
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

    @get:Input
    abstract val expectedSha256: Property<String>

    @TaskAction
    fun run() {
        val target = runtimeDir.get().asFile
        val zip = jreZip.get().asFile

        if (!zip.exists()) {
            zip.parentFile.mkdirs()
            println("Downloading Adoptium Temurin 17 Windows x64 JRE...")
            val url = URI("https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.14%2B7/OpenJDK17U-jre_x64_windows_hotspot_17.0.14_7.zip").toURL()
            val partialZip = File(zip.parentFile, "${zip.name}.part")
            partialZip.delete()
            try {
                url.openStream().use { input ->
                    partialZip.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                verifySha256(partialZip)
                moveReplacing(partialZip, zip)
            } finally {
                partialZip.delete()
            }
        } else {
            verifySha256(zip)
        }

        if (!File(target, "bin/javaw.exe").exists()) {
            println("Extracting Windows JRE runtime into ${target.absolutePath}...")
            val staging = File(target.parentFile, "${target.name}.part")
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val stagingRoot = staging.toPath().toAbsolutePath().normalize()
                ZipInputStream(zip.inputStream().buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryPath = entry.name.replace('\\', '/').substringAfter('/')
                        if (entryPath.isNotEmpty()) {
                            val outPath = stagingRoot.resolve(entryPath).normalize()
                            check(outPath.startsWith(stagingRoot)) {
                                "Unsafe path in Windows JRE archive: ${entry.name}"
                            }
                            val outFile = outPath.toFile()
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile.mkdirs()
                                outFile.outputStream().use { output ->
                                    zis.copyTo(output)
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                check(File(staging, "bin/javaw.exe").isFile) {
                    "Downloaded Windows JRE archive does not contain bin/javaw.exe"
                }
                target.deleteRecursively()
                moveReplacing(staging, target)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun verifySha256(file: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual.equals(expectedSha256.get(), ignoreCase = true)) {
            "Windows JRE checksum mismatch: expected ${expectedSha256.get()}, got $actual"
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

val prepareWindowsJre = tasks.register<PrepareWindowsJreTask>("prepareWindowsJre") {
    group = "compose desktop"
    description = "Downloads and extracts Adoptium Temurin OpenJDK 17 Windows x64 JRE into build/windows-runtime/runtime"
    runtimeDir.set(layout.buildDirectory.dir("windows-runtime/runtime"))
    jreZip.set(rootProject.layout.projectDirectory.file(".gradle/windows-jre-cache/temurin-jre-17-win-x64.zip"))
    expectedSha256.set("d42f84605c8e27c38998b44ac493d1067abbe45be89969c935d71a858393405c")
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
