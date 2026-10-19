package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryFeedTest {
    @TempDir
    lateinit var tmp: File

    private val source = SourceConfig.ImmichShare("im", "Immich", "https://p/share/k")

    private fun remote(id: String) = RemoteImage(id, id, HttpResource("https://p/$id"), HttpResource("https://p/$id"))

    private fun TestScope.store() = ImageListStore(
        File(tmp, "listings.json"),
        StandardTestDispatcher(testScheduler),
    )

    /** A feed whose single source answers via [list]; times are the test's virtual milliseconds. */
    private fun TestScope.feed(list: suspend (SourceConfig) -> List<RemoteImage>) = GalleryFeed(
        scope = backgroundScope,
        sources = { listOf(source) },
        imageLists = store(),
        clock = { testScheduler.currentTime },
        list = list,
    )

    @Test
    fun `while visible the gallery is relisted every minute, while hidden never`() = runTest {
        val listedAt = mutableListOf<Long>()
        val feed = feed {
            listedAt += testScheduler.currentTime
            listOf(remote("a"))
        }

        val visible = launch { feed.keepFresh() }
        advanceTimeBy(150_000)
        assertEquals(listOf(0L, 60_000L, 120_000L), listedAt)

        visible.cancel()
        advanceTimeBy(300_000)
        assertEquals(3, listedAt.size)

        // Back after five minutes away: listed at once.
        val again = launch { feed.keepFresh() }
        runCurrent()
        assertEquals(450_000L, listedAt.last())
        again.cancel()
    }

    @Test
    fun `coming back within the minute waits until the list is a minute old`() = runTest {
        val listedAt = mutableListOf<Long>()
        val feed = feed {
            listedAt += testScheduler.currentTime
            emptyList()
        }

        val first = launch { feed.keepFresh() }
        advanceTimeBy(20_000)
        first.cancel()
        advanceTimeBy(20_000)

        val back = launch { feed.keepFresh() }
        advanceTimeBy(19_000)
        assertEquals(listOf(0L), listedAt)
        advanceTimeBy(2_000)
        assertEquals(listOf(0L, 60_000L), listedAt)
        back.cancel()
    }

    @Test
    fun `after a source is added the gallery is relisted as soon as it is visible again`() = runTest {
        val listedAt = mutableListOf<Long>()
        val feed = feed {
            listedAt += testScheduler.currentTime
            emptyList()
        }

        val first = launch { feed.keepFresh() }
        advanceTimeBy(5_000)
        first.cancel()
        feed.invalidate()
        advanceTimeBy(5_000)

        val back = launch { feed.keepFresh() }
        runCurrent()
        assertEquals(listOf(0L, 10_000L), listedAt)
        back.cancel()
    }

    @Test
    fun `a source added while a listing is still running is listed as soon as the gallery is visible`() = runTest {
        val added = SourceConfig.ImmichShare("im2", "Immich 2", "https://p/share/k2")
        var sources = listOf(source)
        val slowAnswer = CompletableDeferred<List<RemoteImage>>()
        val listed = mutableListOf<String>()
        val feed = GalleryFeed(
            scope = backgroundScope,
            sources = { sources },
            imageLists = store(),
            clock = { testScheduler.currentTime },
            list = {
                listed += it.id
                if (it == source) slowAnswer.await() else listOf(remote("new"))
            },
        )

        // The gallery is left while the first source is still being listed.
        val first = launch { feed.keepFresh() }
        runCurrent()
        first.cancel()
        sources = listOf(source, added)
        feed.invalidate()
        slowAnswer.complete(listOf(remote("old")))
        runCurrent()

        val back = launch { feed.keepFresh() }
        advanceTimeBy(5_000)
        assertEquals(true, added.id in listed)
        assertEquals(
            setOf("old", "new"),
            (feed.state as Load.Ready).value.images.map { it.image.id }.toSet(),
        )
        back.cancel()
    }

    @Test
    fun `a source added while a listing is blocked in a source is listed without waiting for it`() = runTest {
        val added = SourceConfig.ImmichShare("im2", "Immich 2", "https://p/share/k2")
        var sources = listOf(source)
        // Like OkHttp's execute() on the I/O thread: blocks a thread and ignores coroutine cancellation.
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val slowServerAsked = CountDownLatch(1)
        val slowServer = CountDownLatch(1)
        val listed = mutableListOf<String>()
        val feed = GalleryFeed(
            scope = backgroundScope,
            sources = { sources },
            imageLists = store(),
            clock = { testScheduler.currentTime },
            list = {
                listed += it.id
                if (it == source) {
                    withContext(io) {
                        slowServerAsked.countDown()
                        slowServer.await()
                    }
                }
                emptyList()
            },
        )

        try {
            // The gallery is left while the first source is still being listed.
            val first = launch { feed.keepFresh() }
            runCurrent()
            slowServerAsked.await()
            first.cancel()
            sources = listOf(source, added)
            feed.invalidate()

            val back = launch { feed.keepFresh() }
            advanceTimeBy(5_000)
            assertEquals(true, added.id in listed)
            back.cancel()
        } finally {
            slowServer.countDown()
            io.close()
        }
    }

    @Test
    fun `the saved list is shown while the sources are still being asked`() = runTest {
        store().save(mapOf(source.id to listOf(remote("saved"))))
        val answer = CompletableDeferred<List<RemoteImage>>()
        val feed = feed { answer.await() }

        val visible = launch { feed.keepFresh() }
        runCurrent()
        assertEquals(listOf("saved"), (feed.state as Load.Ready).value.images.map { it.image.id })

        answer.complete(listOf(remote("fresh")))
        runCurrent()
        assertEquals(listOf("fresh"), (feed.state as Load.Ready).value.images.map { it.image.id })
        visible.cancel()
    }

    @Test
    fun `a removed source that failed to list is no longer reported as failing`() = runTest {
        val broken = SourceConfig.ImmichShare("bad", "Broken", "https://p/share/bad")
        var sources = listOf(source, broken)
        val feed = GalleryFeed(
            scope = backgroundScope,
            sources = { sources },
            imageLists = store(),
            clock = { testScheduler.currentTime },
            list = { if (it == broken) throw IOException("wrong password") else listOf(remote("a")) },
        )

        feed.refresh()
        runCurrent()
        assertEquals(listOf("Broken"), (feed.state as Load.Ready).value.failures.map { it.label })

        sources = listOf(source)
        feed.forgetSource(broken.id)
        val visible = launch { feed.keepFresh() }
        advanceTimeBy(30_000)
        assertEquals(emptyList<SourceFailure>(), (feed.state as Load.Ready).value.failures)
        visible.cancel()
    }
}
