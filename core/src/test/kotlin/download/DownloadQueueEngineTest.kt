package com.comics8.core.download

import com.comics8.core.model.EpisodeItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class DownloadQueueEngineTest {
    @Test
    fun processesInOrderAndReturnsToIdle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val processed = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        val engine = DownloadQueueEngine(scope, { task, report ->
            processed += task.episode.wrId
            report(1, 1)
            if (processed.size == 2) done.complete(Unit)
        })

        engine.enqueue(listOf(task("1"), task("2")))
        withTimeout(2_000) { done.await() }
        withTimeout(2_000) {
            while (engine.progress.value.isRunning) kotlinx.coroutines.yield()
        }

        assertThat(processed).containsExactly("1", "2").inOrder()
        assertThat(engine.progress.value).isEqualTo(DownloadProgressState())
        engine.close()
        scope.cancel()
    }

    private fun task(id: String) = DownloadTask(
        sourceId = "local",
        toonId = "toon",
        toonTitle = "Title",
        toonThumbUrl = "",
        toonHref = "",
        episode = EpisodeItem(wrId = id, title = id, href = id, date = "", thumbUrl = ""),
    )
}
