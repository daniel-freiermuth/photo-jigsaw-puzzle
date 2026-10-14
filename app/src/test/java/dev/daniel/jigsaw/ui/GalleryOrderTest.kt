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
}
