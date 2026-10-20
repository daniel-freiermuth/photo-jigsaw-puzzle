package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GalleryOrderTest {
    private val holidays = SourceConfig.ImmichShare("ho", "Holidays", "https://p/share/h")
    private val immich = SourceConfig.ImmichShare("im", "Immich", "https://p/share/k")

    private fun image(source: SourceConfig, id: String, takenAt: Long?) =
        GalleryImage(source, RemoteImage(id, id, HttpResource(id), HttpResource(id), takenAt))

    @Test
    fun `images from all sources interleave newest first, undated ones last in source order`() {
        val merged = listOf(
            image(holidays, "ho-old", 100),
            image(holidays, "ho-undated-1", null),
            image(holidays, "ho-new", 400),
            image(immich, "im-mid", 300),
            image(immich, "im-undated-2", null),
            image(immich, "im-older", 200),
        )
        assertEquals(
            listOf("ho-new", "im-mid", "im-older", "ho-old", "ho-undated-1", "im-undated-2"),
            merged.newestFirst().map { it.image.id },
        )
    }

    @Test
    fun `a source that fails keeps its last known images and is reported, removed sources disappear`() {
        val more = SourceConfig.ImmichShare("u", "Immich 2", "https://e/share/k2")
        fun remote(id: String, takenAt: Long) = RemoteImage(id, id, HttpResource(id), HttpResource(id), takenAt)
        val previous = mapOf(
            "ho" to listOf(remote("ho-old", 100)),
            "im" to listOf(remote("im-stale", 200)),
            "gone" to listOf(remote("gone-1", 999)),
        )
        val merged = mergeListings(
            sources = listOf(holidays, immich, more),
            results = listOf(
                Result.success(listOf(remote("ho-new", 300))),
                Result.failure(java.net.UnknownHostException("p")),
                null,
            ),
            previous = previous,
        )
        assertEquals(listOf("ho-new", "im-stale"), merged.content.images.map { it.image.id })
        assertEquals(listOf("Immich"), merged.content.failures.map { it.label })
        assertEquals(setOf("ho", "im", "u"), merged.listings.keys)
        assertEquals(listOf("im-stale"), merged.listings.getValue("im").map { it.id })
    }
}
