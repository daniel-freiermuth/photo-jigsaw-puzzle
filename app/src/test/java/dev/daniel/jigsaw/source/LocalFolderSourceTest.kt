package dev.daniel.jigsaw.source

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalFolderSourceTest {

    /** Folders by document id; a document's URI is `content://t/<id>`. */
    private class FakeTree(private val folders: Map<String, List<Document>>) : DocumentTree {
        val listed = mutableListOf<String>()
        override val rootId = "root"

        override fun children(documentId: String): List<Document> {
            listed += documentId
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
