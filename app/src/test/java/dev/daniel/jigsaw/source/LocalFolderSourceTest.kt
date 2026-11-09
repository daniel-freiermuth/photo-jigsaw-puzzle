package dev.daniel.jigsaw.source

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalFolderSourceTest {

    /**
     * Folders by document id; a document's URI is `content://t/<id>`. Listing a folder in [gone] fails as missing,
     * one in [unavailable] as unreadable (revoked grant, removed storage).
     */
    private class FakeTree(
        private val folders: Map<String, List<Document>>,
        private val gone: Set<String> = emptySet(),
        private val unavailable: Set<String> = emptySet(),
    ) : DocumentTree {
        val listed = mutableListOf<String>()
        override val rootId = "root"

        override fun children(documentId: String): List<Document> {
            listed += documentId
            if (documentId in gone) throw java.io.FileNotFoundException(documentId)
            if (documentId in unavailable) throw FolderUnavailableException(SecurityException("Permission Denial"))
            return folders.getValue(documentId)
        }

        override fun uri(documentId: String) = "content://t/$documentId"
    }

    private fun folder(id: String) = Document(id, id.substringAfterLast('/'), "vnd.android.document/directory", true)

    private fun file(id: String, mimeType: String, lastModified: Long? = 1, size: Long? = 2) =
        Document(id, id.substringAfterLast('/'), mimeType, false, lastModified, size)

    private fun TestScope.list(tree: DocumentTree) =
        LocalFolderSource(tree, StandardTestDispatcher(testScheduler))

    @Test
    fun `pictures in the folder and its subfolders are listed by name, other files and hidden entries skipped`() =
        runTest {
            val tree = FakeTree(
                mapOf(
                    "root" to listOf(
                        file("root/b.JPG", "image/jpeg", lastModified = 300, size = 7),
                        file("root/notes.txt", "text/plain"),
                        file("root/logo.svg", "image/svg+xml"),
                        file("root/scan.tif", "image/tiff"),
                        file("root/layers.psd", "image/vnd.adobe.photoshop"),
                        file("root/.trashed-1-x.jpg", "image/jpeg"),
                        folder("root/.thumbnails"),
                        folder("root/sub"),
                    ),
                    "root/sub" to listOf(
                        file("root/sub/A.png", "image/png; charset=binary", lastModified = null, size = null),
                        folder("root/sub/deep"),
                    ),
                    "root/sub/deep" to listOf(file("root/sub/deep/c.webp", "image/webp")),
                ),
            )
            val images = list(tree).listImages()
            assertEquals(listOf("A.png", "b.JPG", "c.webp"), images.map { it.name })
            assertEquals(listOf("root", "root/sub", "root/sub/deep"), tree.listed)

            val b = images[1]
            assertEquals("root/b.JPG", b.id)
            assertEquals(300L, b.takenAt)
            assertEquals(DocumentResource("content://t/root/b.JPG", "300:7"), b.full)
            assertEquals(b.full, b.thumbnail)
            assertEquals(null, images[0].takenAt)
        }

    @Test
    fun `a picture changed in place gets a new revision`() = runTest {
        fun revision(lastModified: Long, size: Long) =
            list(FakeTree(mapOf("root" to listOf(file("root/a.jpg", "image/jpeg", lastModified, size)))))

        val before = revision(1, 100).listImages().single().full
        val edited = revision(2, 100).listImages().single().full
        val resized = revision(1, 101).listImages().single().full
        assertEquals(3, setOf(before.revision, edited.revision, resized.revision).size)
        assertEquals(before.location, edited.location)
    }

    @Test
    fun `a folder with more than 200 subfolders is refused rather than listed in part`() = runTest {
        val tree = folderChain(depth = 201)
        assertEquals(200, assertThrows<TooManyFoldersException> { list(tree).listImages() }.limit)
        assertEquals(200, tree.listed.size)
    }

    @Test
    fun `a folder with exactly 200 folders is listed in full`() = runTest {
        assertEquals(200, list(folderChain(depth = 200)).listImages().size)
    }

    @Test
    fun `a subfolder deleted while the folder is walked is skipped, the rest still listed`() = runTest {
        val tree = FakeTree(
            mapOf(
                "root" to listOf(folder("root/gone"), folder("root/sub"), file("root/a.jpg", "image/jpeg")),
                "root/sub" to listOf(file("root/sub/b.jpg", "image/jpeg")),
            ),
            gone = setOf("root/gone"),
        )
        assertEquals(listOf("a.jpg", "b.jpg"), list(tree).listImages().map { it.name })
    }

    @Test
    fun `a picked folder that is gone still fails the listing`() = runTest {
        val tree = FakeTree(emptyMap(), gone = setOf("root"))
        assertThrows<java.io.FileNotFoundException> { list(tree).listImages() }
    }

    @Test
    fun `a picked folder that can't be read fails the listing`() = runTest {
        val tree = FakeTree(emptyMap(), unavailable = setOf("root"))
        assertThrows<FolderUnavailableException> { list(tree).listImages() }
    }

    @Test
    fun `a subfolder that can't be read deep in the walk fails the listing rather than leaving it partial`() =
        runTest {
            val tree = FakeTree(
                mapOf(
                    "root" to listOf(file("root/a.jpg", "image/jpeg"), folder("root/sub")),
                    "root/sub" to listOf(file("root/sub/b.jpg", "image/jpeg"), folder("root/sub/deep")),
                ),
                unavailable = setOf("root/sub/deep"),
            )
            assertThrows<FolderUnavailableException> { list(tree).listImages() }
            assertEquals(listOf("root", "root/sub", "root/sub/deep"), tree.listed)
        }

    /** [depth] nested folders (the root included), each holding one picture and the next folder. */
    private fun folderChain(depth: Int): FakeTree {
        val ids = List(depth) { if (it == 0) "root" else "f$it" }
        return FakeTree(
            ids.mapIndexed { level, id ->
                id to listOfNotNull(
                    file("$id/p.jpg", "image/jpeg"),
                    ids.getOrNull(level + 1)?.let(::folder),
                )
            }.toMap(),
        )
    }
}

class ReadDocumentTest {

    /** ExternalStorageProvider's isChildDocument check on a tree URI fails this way when it can't resolve a file. */
    private val notAChild = IllegalArgumentException("Failed to determine if primary:a.jpg is child of primary:")

    @Test
    fun `a picture deleted since the listing is reported as a missing file, not an unreadable folder`() {
        val e = assertThrows<java.io.IOException> {
            readDocument("content://t/primary%3Aa.jpg", folderReadable = { true }) { throw notAChild }
        }
        assertEquals(java.io.FileNotFoundException::class.java, e.javaClass)
    }

    @Test
    fun `a picture whose folder or storage is gone is reported as an unavailable folder`() {
        assertThrows<FolderUnavailableException> {
            readDocument("content://t/primary%3Aa.jpg", folderReadable = { false }) { throw notAChild }
        }
    }

    @Test
    fun `a revoked grant is still reported as an unavailable folder`() {
        assertThrows<FolderUnavailableException> {
            readDocument("content://t/a", folderReadable = { true }) { throw SecurityException("Permission Denial") }
        }
    }
}

class FolderAccessTest {

    // Callers catch only IOException: anything else thrown here crashes the app.
    @Test
    fun `a revoked grant becomes an unavailable folder, an IOException keeping the cause`() {
        val revoked = SecurityException("Permission Denial")
        val e = assertThrows<java.io.IOException> { folderAccess { throw revoked } }
        assertInstanceOf(FolderUnavailableException::class.java, e)
        assertSame(revoked, e.cause)
    }

    @Test
    fun `a provider rejecting the document becomes an unavailable folder, an IOException keeping the cause`() {
        val rejected = IllegalArgumentException("Failed to determine if primary:a is child of primary:")
        val e = assertThrows<java.io.IOException> { folderAccess { throw rejected } }
        assertInstanceOf(FolderUnavailableException::class.java, e)
        assertSame(rejected, e.cause)
    }

    @Test
    fun `a result passes through and IOExceptions are left as they are`() {
        assertEquals(42, folderAccess { 42 })
        val missing = java.io.FileNotFoundException("a.jpg")
        assertSame(missing, assertThrows<java.io.IOException> { folderAccess { throw missing } })
    }
}

class PuzzleImageTypeTest {
    @Test
    fun `HEIF and AVIF count as pictures only on the API levels BitmapFactory decodes them`() {
        assertEquals(listOf(false, true, true), listOf(26, 28, 31).map { "image/heic".isPuzzleImage(it) })
        assertEquals(listOf(false, true), listOf(27, 28).map { "image/heif".isPuzzleImage(it) })
        assertEquals(listOf(false, true), listOf(30, 31).map { "image/avif".isPuzzleImage(it) })
        assertEquals(true, "image/jpeg".isPuzzleImage(26))
    }
}
