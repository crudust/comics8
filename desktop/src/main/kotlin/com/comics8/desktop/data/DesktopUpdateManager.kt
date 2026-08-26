package com.comics8.desktop.data

import com.comics8.core.network.FallbackDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.system.exitProcess

object DesktopUpdateManager {
    private val httpClient = OkHttpClient.Builder()
        .dns(FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()


    private val updateDir = File(System.getProperty("user.home"), ".comics8/updates").apply { mkdirs() }

    suspend fun downloadAndApplyUpdate(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val zipFileName = if (isWindows) "Comics8-win.zip" else "Comics8-mac.zip"
            val zipFile = File(updateDir, zipFileName)
            if (zipFile.exists()) zipFile.delete()

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "Comics8-Desktop/Updater")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("다운로드 실패 (HTTP ${response.code})"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("빈 응답 바디"))
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                    output.flush()
                }
            }

            onProgress(1.0f)

            // Extract ZIP
            val extractedDir = File(updateDir, "extracted").apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }

            unzip(zipFile, extractedDir)

            val currentPid = ProcessHandle.current().pid()
            val destApp = findCurrentAppPath() ?: error("설치된 Comics8 경로를 찾지 못했습니다.")

            if (isWindows) {
                applyWindowsUpdate(currentPid, extractedDir, destApp)
            } else {
                applyMacUpdate(currentPid, extractedDir, destApp)
            }

            // Terminate current process to allow updater to replace app
            exitProcess(0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun applyMacUpdate(currentPid: Long, extractedDir: File, destApp: File) {
        val extractedApp = extractedDir.walk().firstOrNull { it.isDirectory && it.name.endsWith(".app") }
            ?: error("압축 파일 내에서 .app 번들을 찾을 수 없습니다.")

        val scriptFile = File(updateDir, "relaunch.sh")
        scriptFile.writeText(
            """
            #!/bin/bash
            trap '' HUP
            PID=$currentPid
            NEW_APP="${extractedApp.absolutePath}"
            DEST_APP="${destApp.absolutePath}"

            for _ in {1..150}; do
                if ! kill -0 ${'$'}PID 2>/dev/null; then
                    break
                fi
                sleep 0.2
            done
            sleep 0.5

            rm -rf "${'$'}DEST_APP"
            cp -R "${'$'}NEW_APP" "${'$'}DEST_APP"
            xattr -dr com.apple.quarantine "${'$'}DEST_APP" 2>/dev/null || true
            chmod -R u+x "${'$'}DEST_APP/Contents/MacOS" 2>/dev/null || true
            open "${'$'}DEST_APP"
            """.trimIndent()
        )
        scriptFile.setExecutable(true)

        val logFile = File(updateDir, "relaunch.log")
        ProcessBuilder("/bin/bash", scriptFile.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
    }

    private fun applyWindowsUpdate(currentPid: Long, extractedDir: File, destApp: File) {
        val scriptFile = File(updateDir, "relaunch.bat")
        scriptFile.writeText(
            """
            @echo off
            setlocal
            set PID=$currentPid
            set NEW_DIR=${extractedDir.absolutePath}
            set DEST_DIR=${destApp.absolutePath}

            :wait_loop
            tasklist /FI "PID eq %PID%" 2>NUL | find "%PID%" >NUL
            if not errorlevel 1 (
                timeout /t 1 /nobreak >NUL
                goto wait_loop
            )
            timeout /t 1 /nobreak >NUL

            xcopy /E /Y /I "%NEW_DIR%\*" "%DEST_DIR%\" >NUL 2>&1

            if exist "%DEST_DIR%\Comics8.exe" (
                start "" "%DEST_DIR%\Comics8.exe"
            ) else if exist "%DEST_DIR%\Comics8.vbs" (
                start wscript "%DEST_DIR%\Comics8.vbs"
            ) else (
                start "" "%DEST_DIR%\Comics8.bat"
            )
            """.trimIndent()
        )

        val vbsLauncher = File(updateDir, "launch_relaunch.vbs")
        vbsLauncher.writeText(
            "Set WshShell = CreateObject(\"WScript.Shell\")\n" +
            "WshShell.Run Chr(34) & \"${scriptFile.absolutePath}\" & Chr(34), 0, False\n"
        )

        ProcessBuilder("wscript.exe", vbsLauncher.absolutePath).start()
    }

    private fun findCurrentAppPath(): File? {
        val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
        if (isWindows) {
            val codeSource = DesktopUpdateManager::class.java.protectionDomain?.codeSource?.location
            if (codeSource != null) {
                val file = runCatching { File(codeSource.toURI()) }.getOrNull()
                if (file != null) {
                    val dir = if (file.isFile) file.parentFile else file
                    if (dir != null) {
                        return if (dir.name.equals("app", ignoreCase = true)) dir.parentFile else dir
                    }
                }
            }
            val userDir = File(System.getProperty("user.dir"))
            if (userDir.exists()) return userDir
            return null
        }
        val cmd = ProcessHandle.current().info().command().orElse(null)
        if (cmd != null && cmd.contains(".app/")) {
            val appPath = cmd.substringBefore(".app/") + ".app"
            val file = File(appPath)
            if (file.exists()) return file
        }
        val defaultApp = File("/Applications/Comics8.app")
        return defaultApp.takeIf { it.exists() }
    }


    private fun unzip(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val newFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    if (
                        entry.name.contains("MacOS/") ||
                        entry.name.contains("/bin/") ||
                        entry.name.endsWith(".sh")
                    ) {
                        newFile.setExecutable(true)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
