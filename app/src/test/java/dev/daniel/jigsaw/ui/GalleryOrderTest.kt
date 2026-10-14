package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.source.HttpResource
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryOrderTest {
    private val nextcloud = SourceConfig.NextcloudShare("nc", "NC", "https://c/s/t")
    private val immich = SourceConfig.ImmichShare("im", "Immich", "https://p/share/k")

    private fun image(source: SourceConfig, id: String, takenAt: Long?) =
        GalleryImage(source, RemoteImage(id, id, HttpResource(id), HttpResource(id), takenAt))

    @Test
    fun `images from all sources interleave newest first, undated ones last in source order`() {
        val merged = listOf(
            image(nextcloud, "nc-old", 100),
            image(nextcloud, "nc-undated-1", null),
            image(nextcloud, "nc-new", 400),
            image(immich, "im-mid", 300),
            image(immich, "im-undated-2", null),
            image(immich, "im-older", 200),
        )
        assertEquals(
            listOf("nc-new", "im-mid", "im-older", "nc-old", "nc-undated-1", "im-undated-2"),
            merged.newestFirst().map { it.image.id },
        )
    }

    @Test
    fun `a source that fails keeps its last known images and is reported, removed sources disappear`() {
        val urls = SourceConfig.UrlList("u", "Urls", "https://e/list.txt")
        fun remote(id: String, takenAt: Long) = RemoteImage(id, id, HttpResource(id), HttpResource(id), takenAt)
        val previous = mapOf(
            "nc" to listOf(remote("nc-old", 100)),
            "im" to listOf(remote("im-stale", 200)),
            "gone" to listOf(remote("gone-1", 999)),
        )
        val merged = mergeListings(
            sources = listOf(nextcloud, immich, urls),
            results = listOf(
                Result.success(listOf(remote("nc-new", 300))),
                Result.failure(java.net.UnknownHostException("p")),
                null,
            ),
            previous = previous,
        )
        assertEquals(listOf("nc-new", "im-stale"), merged.content.images.map { it.image.id })
        assertEquals(listOf("Immich"), merged.content.failures.map { it.label })
        assertEquals(setOf("nc", "im", "u"), merged.listings.keys)
        assertEquals(listOf("im-stale"), merged.listings.getValue("im").map { it.id })
    }
}
