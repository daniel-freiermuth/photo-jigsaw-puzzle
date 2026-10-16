package dev.daniel.jigsaw

import java.io.File
import java.io.IOException

/**
 * Replaces this file's content with [text], creating missing parent directories. Written to a
 * sibling `.tmp` file first and then renamed over this one, so a crash mid-write never leaves a
 * truncated file behind. Throws [IOException] if the write or the rename fails.
 */
internal fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File("$path.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) throw IOException("Could not save $this")
}
