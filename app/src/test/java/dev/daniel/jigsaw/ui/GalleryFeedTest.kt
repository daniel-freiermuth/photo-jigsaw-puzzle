package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryFeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val source = SourceConfig.ImmichShare("im", "Immich", "https://p/share/k")

    private fun remote(id: String) = RemoteImage(id, id, HttpResource("https://p/$id"), HttpResource("https://p/$id"))

    private fun TestScope.store() = ImageListStore(
        File(tmp.root, "listings.json"),
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
}
