package dev.daniel.jigsaw.ui

import dev.daniel.jigsaw.puzzle.CanvasSpec
import dev.daniel.jigsaw.puzzle.PuzzleSettings
import dev.daniel.jigsaw.source.HttpResource

/**
 * Identifies the picture behind gallery tile cache entries. A new revision is a changed picture: its
 * tiles must not come from the old one's cache entries.
 */
internal fun tileContent(resource: HttpResource) = "${resource.url} ${resource.revision.orEmpty()}"

/** Cache key of [item]'s untouched tile: everything the scatter it shows is rendered from. */
internal fun untouchedTileKey(item: GalleryImage, canvas: CanvasSpec, settings: PuzzleSettings) =
    "untouched:${tileContent(item.image.thumbnail)}:$canvas:${settings.rotatePieces}:${settings.pieces.target}"
