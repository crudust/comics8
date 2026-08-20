package com.comics8.desktop.data

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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val updateDir = File(System.getProperty("user.home"), ".comics8/updates").apply { mkdirs() }

    suspend fun downloadAndApplyUpdate(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val zipFile = File(updateDir, "Comics8-mac.zip")
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

            val extractedApp = extractedDir.walk().firstOrNull { it.isDirectory && it.name.endsWith(".app") }
                ?: return@withContext Result.failure(Exception("압축 파일 내에서 .app 번들을 찾을 수 없습니다."))

            val currentPid = ProcessHandle.current().pid()
            val destApp = findCurrentAppPath() ?: error("설치된 Comics8.app 경로를 찾지 못했습니다.")

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
            ProcessBuilder("/usr/bin/nohup", "/bin/bash", scriptFile.absolutePath)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(logFile)
                .redirectErrorStream(true)
                .start()

            // Terminate current process to allow updater to replace app
            exitProcess(0)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun findCurrentAppPath(): File? {
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
