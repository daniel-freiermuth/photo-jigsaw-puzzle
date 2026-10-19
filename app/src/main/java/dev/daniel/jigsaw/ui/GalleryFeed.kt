package dev.daniel.jigsaw.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.daniel.jigsaw.source.ImageListStore
import dev.daniel.jigsaw.source.RemoteImage
import dev.daniel.jigsaw.source.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One timeline across all sources: newest [RemoteImage.takenAt] first; images without a date go
 * last. The sort is stable, so ties and undated images keep their source order.
 */
fun List<GalleryImage>.newestFirst(): List<GalleryImage> =
    sortedWith(compareByDescending(nullsFirst()) { it.image.takenAt })

/** A source (by [sourceId]) that couldn't be listed, and why. */
data class SourceFailure(val sourceId: String, val label: String, val reason: UiText)

/** Images from every source (fresh or last known), plus a failure for each source that didn't answer. */
data class GalleryContent(val images: List<GalleryImage>, val failures: List<SourceFailure>)

/** The listings to keep per source id, and the gallery they make up. */
data class MergedListings(val listings: Map<String, List<RemoteImage>>, val content: GalleryContent)

/**
 * Combines one listing attempt per source ([results], parallel to [sources]; null = not attempted)
 * with the [previous] listings: a source that answered shows its new images, one that failed or
 * wasn't asked keeps its previous ones. Sources no longer configured are dropped.
 */
fun mergeListings(
    sources: List<SourceConfig>,
    results: List<Result<List<RemoteImage>>?>,
    previous: Map<String, List<RemoteImage>>,
): MergedListings {
    val listings = sources.zip(results).associate { (source, result) ->
        source.id to (result?.getOrNull() ?: previous[source.id].orEmpty())
    }
    val images = sources.flatMap { source -> listings.getValue(source.id).map { GalleryImage(source, it) } }
    val failures = sources.zip(results).mapNotNull { (source, result) ->
        result?.exceptionOrNull()?.let { SourceFailure(source.id, source.label, it.toUiText()) }
    }
    return MergedListings(listings, GalleryContent(images.newestFirst(), failures))
}

/**
 * The gallery's images and how they stay current. While [keepFresh] runs — only while the gallery
 * is on screen — the list is never older than [MAX_AGE_MS]; when nobody looks, nothing is listed.
 * Every source is listed concurrently; one that can't be reached keeps its last known images, and
 * each source's latest listing is saved so the gallery appears at once on the next start.
 */
class GalleryFeed(
    private val scope: CoroutineScope,
    private val sources: () -> List<SourceConfig>,
    private val imageLists: ImageListStore,
    /** Monotonic milliseconds. */
    private val clock: () -> Long,
    private val list: suspend (SourceConfig) -> List<RemoteImage>,
    /** Runs before new content is shown (the gallery tiles are read from the saved puzzles here). */
    private val beforeShow: suspend (GalleryContent) -> Unit = {},
) {
    var state by mutableStateOf<Load<GalleryContent>>(Load.Loading)
        private set

    /** True during a refresh the user asked for; automatic ones run without a progress indicator. */
    var refreshing by mutableStateOf(false)
        private set

    private var job: Job? = null

    /** The images shown, per source id: the latest listing that succeeded (or the saved one). */
    private var listings: Map<String, List<RemoteImage>> = emptyMap()

    /** [clock] time the shown list was fetched; null when a refresh is due right away. */
    private var refreshedAt: Long? = null

    /** Lists the sources now, with progress shown (⟳, pull to refresh). */
    fun refresh() {
        start(showProgress = true)
    }

    /**
     * Refreshes whenever the list is due, until cancelled. Run it exactly while the gallery is
     * visible: coming back to it refreshes at once if the list is older than [MAX_AGE_MS] (or was never
     * loaded), otherwise when it reaches that age. A refresh still running when it is cancelled
     * finishes; none is started afterwards.
     */
    suspend fun keepFresh() {
        while (true) {
            // An abandoned listing is not waited for: its blocking network calls ignore cancellation.
            job?.takeUnless { it.isCancelled }?.join()
            val fetched = refreshedAt
            val wait = if (fetched == null) 0L else fetched + MAX_AGE_MS - clock()
            if (wait > 0) delay(wait) else start(showProgress = false).join()
        }
    }

    /**
     * The sources changed (one was added): refresh as soon as the gallery is visible. A listing in
     * flight is abandoned, as it lists the old sources and would mark the gallery fresh.
     */
    fun invalidate() {
        job?.cancel()
        refreshing = false
        refreshedAt = null
    }

    /**
     * Drops a removed source's images and failure now; any listing in flight is abandoned, as it would
     * bring them back.
     */
    fun forgetSource(id: String) {
        if (job?.isActive == true) invalidate()
        listings = listings - id
        (state as? Load.Ready)?.value?.let { content ->
            state = Load.Ready(
                content.copy(
                    images = content.images.filterNot { it.source.id == id },
                    failures = content.failures.filterNot { it.sourceId == id },
                ),
            )
        }
    }

    private fun start(showProgress: Boolean): Job {
        job?.cancel()
        val current = sources()
        refreshing = showProgress
        return scope.launch {
            if (state !is Load.Ready) {
                listings = imageLists.load()
                if (current.any { listings[it.id].orEmpty().isNotEmpty() }) {
                    show(mergeListings(current, current.map { null }, listings).content)
                }
            }
            val results = current.map { source ->
                async {
                    try {
                        Result.success(list(source))
                    } catch (e: IOException) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
            val merged = mergeListings(current, results, listings)
            listings = merged.listings
            show(merged.content)
            refreshing = false
            refreshedAt = clock()
            try {
                imageLists.save(merged.listings)
            } catch (e: IOException) {
                // Only costs the instant start next time.
                Log.w(TAG, "Could not save gallery listings", e)
            }
        }.also { job = it }
    }

    private suspend fun show(content: GalleryContent) {
        beforeShow(content)
        state = Load.Ready(content)
    }

    companion object {
        /** The gallery on screen is never older than this. */
        const val MAX_AGE_MS = 60_000L
        private const val TAG = "GalleryFeed"
    }
}
