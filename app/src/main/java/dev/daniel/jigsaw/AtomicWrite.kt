package dev.daniel.jigsaw

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Replaces this file's content with [text], creating missing parent directories. Written to a
 * sibling `.tmp` file, synced to the storage device, and only then renamed over this one, so neither a
 * crash of the app nor a power loss right after the rename leaves a truncated or empty file behind.
 * Throws [IOException] if the write, the sync or the rename fails.
 *
 * Not `androidx.core.util.AtomicFile`: its `finishWrite` only logs a failed sync or rename, so a save
 * that never landed would look successful to the caller.
 */
internal fun File.writeTextAtomically(text: String) {
    parentFile?.mkdirs()
    val tmp = File("$path.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.encodeToByteArray())
        out.fd.sync()
    }
    if (!tmp.renameTo(this)) throw IOException("Could not save $this")
}
