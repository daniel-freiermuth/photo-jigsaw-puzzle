package dev.daniel.jigsaw.source

import android.content.ContentResolver
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
import android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID
import android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
import android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
import android.provider.DocumentsContract.Document.COLUMN_SIZE
import android.provider.DocumentsContract.Document.MIME_TYPE_DIR
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Lists the pictures in a folder on the device, including its subfolders. The folder comes from the
 * system folder picker (Storage Access Framework), so the app needs no storage permission; files and
 * folders whose names start with `.` (Android's trash, thumbnail caches) are skipped.
 */
class LocalFolderSource(
    private val tree: DocumentTree,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageSource {

    override suspend fun listImages(): List<RemoteImage> = withContext(ioDispatcher) {
        val pending = ArrayDeque(listOf(tree.rootId))
        val images = mutableListOf<RemoteImage>()
        var visited = 0
        while (pending.isNotEmpty() && visited < MAX_FOLDERS) {
            visited++
            for (document in tree.children(pending.removeFirst())) {
                when {
                    document.name.startsWith(".") -> Unit
                    document.isFolder -> pending.addLast(document.id)
                    document.mimeType.isPuzzleImage() -> images += image(document)
                }
            }
        }
        if (pending.isNotEmpty()) throw TooManyFoldersException(MAX_FOLDERS)
        images.sortedBy { it.name.lowercase() }
    }

    private fun image(document: Document): RemoteImage {
        // A file changed in place keeps its URI; its modification time and size tell the versions apart.
        val resource = DocumentResource(tree.uri(document.id), "${document.lastModified}:${document.size}")
        return RemoteImage(
            id = document.id,
            name = document.name,
            thumbnail = resource,
            full = resource,
            takenAt = document.lastModified,
        )
    }

    private companion object {
        /** Like a share: walking more folders than this takes too long to be worth waiting for. */
        const val MAX_FOLDERS = 200
    }
}

/** One entry of a folder; [lastModified] in epoch milliseconds, absent fields null. */
data class Document(
    val id: String,
    val name: String,
    val mimeType: String,
    val isFolder: Boolean,
    val lastModified: Long? = null,
    val size: Long? = null,
)

/** A folder tree, addressed by document ids. Blocking. */
interface DocumentTree {
    val rootId: String

    /** The folder's direct children; throws [IOException] if it can't be read. */
    fun children(documentId: String): List<Document>

    /** The `content://` URI a document's bytes are read from. */
    fun uri(documentId: String): String
}

/**
 * The document tree under [treeUri], read through the [resolver]. Throws [IllegalArgumentException]
 * if [treeUri] isn't a tree URI.
 */
class ResolverDocumentTree(private val resolver: ContentResolver, treeUri: String) : DocumentTree {
    private val tree = treeUri.toUri()
    override val rootId: String = DocumentsContract.getTreeDocumentId(tree)

    override fun children(documentId: String): List<Document> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val cursor = folderAccess { resolver.query(children, COLUMNS, null, null, null) }
            ?: throw FolderUnavailableException()
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val mimeType = c.getString(MIME_TYPE).orEmpty()
                    add(
                        Document(
                            id = c.getString(ID),
                            name = c.getString(NAME).orEmpty(),
                            mimeType = mimeType,
                            isFolder = mimeType == MIME_TYPE_DIR,
                            lastModified = if (c.isNull(LAST_MODIFIED)) null else c.getLong(LAST_MODIFIED),
                            size = if (c.isNull(SIZE)) null else c.getLong(SIZE),
                        ),
                    )
                }
            }
        }
    }

    override fun uri(documentId: String): String =
        DocumentsContract.buildDocumentUriUsingTree(tree, documentId).toString()

    private companion object {
        val COLUMNS = arrayOf(
            COLUMN_DOCUMENT_ID,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_LAST_MODIFIED,
            COLUMN_SIZE,
        )
        const val ID = 0
        const val NAME = 1
        const val MIME_TYPE = 2
        const val LAST_MODIFIED = 3
        const val SIZE = 4
    }
}

/** The folder is gone, or the app lost its access to it (e.g. the storage holding it was removed). */
class FolderUnavailableException(cause: Throwable? = null) : IOException("The folder can't be read", cause)

/** The document's bytes, from [uri]. */
internal fun readDocument(resolver: ContentResolver, uri: String): ByteArray = folderAccess {
    resolver.openInputStream(uri.toUri())?.use { it.readBytes() }
} ?: throw FileNotFoundException(uri)

/** Document providers report a folder that is gone or no longer granted with these, rather than IOExceptions. */
internal inline fun <T> folderAccess(block: () -> T): T = try {
    block()
} catch (e: SecurityException) {
    throw FolderUnavailableException(e)
} catch (e: IllegalArgumentException) {
    throw FolderUnavailableException(e)
}

/** A MIME type the puzzle can decode: the raster formats BitmapFactory reads (no TIFF, PSD or SVG). */
internal fun String.isPuzzleImage(): Boolean = substringBefore(';').trim().lowercase() in decodableImageTypes

private val decodableImageTypes = setOf(
    "image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/gif", "image/webp",
    "image/bmp", "image/x-ms-bmp", "image/vnd.wap.wbmp", "image/x-icon", "image/vnd.microsoft.icon",
    "image/heic", "image/heif", "image/avif", "image/x-adobe-dng",
)

/** The folder's path for display, e.g. `primary:DCIM/Camera`; [treeUri] as given if it isn't a tree URI. */
fun folderPath(treeUri: String): String = try {
    DocumentsContract.getTreeDocumentId(treeUri.toUri())
} catch (_: IllegalArgumentException) {
    treeUri
}
