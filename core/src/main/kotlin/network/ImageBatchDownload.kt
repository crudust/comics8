package com.comics8.core.network

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ImageBatchDownload {
    const val CONCURRENCY = 3
    const val FETCH_GAP_MS = 40L

    data class Result(val files: List<File>, val totalBytes: Long)

    suspend fun toNumberedFiles(
        urls: List<String>,
        destDir: File,
        fetchBytes: suspend (String) -> ByteArray,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        concurrency: Int = CONCURRENCY,
        fetchGapMs: Long = FETCH_GAP_MS,
        delayMillis: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ): Result {
        if (urls.isEmpty()) return Result(emptyList(), 0L)
        check(destDir.exists() || destDir.mkdirs()) { "Unable to create destination directory: $destDir" }
        val total = urls.size
        val files = arrayOfNulls<File>(total)
        val fetchGate = Mutex()
        val progressGate = Mutex()
        var completed = 0
        coroutineScope {
            val jobs = Channel<Pair<Int, String>>(capacity = concurrency.coerceAtLeast(1))
            repeat(concurrency.coerceIn(1, total)) {
                launch {
                    for ((index, url) in jobs) {
                        val dest = File(destDir, String.format("%04d.jpg", index + 1))
                        if (!dest.exists() || dest.length() == 0L) {
                            // Serialize only fetch starts. Downloads themselves still run concurrently.
                            fetchGate.withLock {
                                if (fetchGapMs > 0L) delayMillis(fetchGapMs)
                            }
                            val temp = File.createTempFile(".${dest.name}.", ".part", destDir)
                            try {
                                temp.writeBytes(fetchBytes(url))
                                moveAtomically(temp, dest)
                            } finally {
                                if (temp.exists()) temp.delete()
                            }
                        }
                        files[index] = dest
                        progressGate.withLock {
                            completed++
                            onProgress(completed, total)
                        }
                    }
                }
            }
            urls.forEachIndexed { index, url -> jobs.send(index to url) }
            jobs.close()
        }
        val resultFiles = files.map { it!! }
        return Result(resultFiles, resultFiles.sumOf { it.length() })
    }

    private fun moveAtomically(source: File, destination: File) {
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
