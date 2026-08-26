package com.comics8.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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
        destDir.mkdirs()
        val total = urls.size
        val files = arrayOfNulls<File>(total)
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val completed = AtomicInteger(0)
        coroutineScope {
            urls.mapIndexed { index, url ->
                async {
                    val dest = File(destDir, String.format("%04d.jpg", index + 1))
                    if (!dest.exists() || dest.length() == 0L) {
                        semaphore.withPermit {
                            if (fetchGapMs > 0L) delayMillis(fetchGapMs)
                            dest.writeBytes(fetchBytes(url))
                        }
                    }
                    files[index] = dest
                    onProgress(completed.incrementAndGet(), total)
                }
            }.awaitAll()
        }
        val resultFiles = files.map { it!! }
        return Result(resultFiles, resultFiles.sumOf { it.length() })
    }
}
