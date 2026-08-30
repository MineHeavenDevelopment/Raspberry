package server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import java.nio.file.Files

class SafePathsP1Test {
    private fun tempRoot(): File {
        val root = Files.createTempDirectory("p1-roots").toFile()
        File(root, "eula.txt").writeText("eula=true")
        File(root, "metadata.json").writeText("{}")
        File(root, "plugins").mkdirs()
        File(File(root, "plugins"), "EssentialsX.jar").writeBytes(byteArrayOf(1, 2, 3))
        return root
    }

    @Test
    fun `read allows eula and normal files`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateRead(root, "root", "eula.txt"))
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateRead(root, "plugins", "EssentialsX.jar"))
    }

    @Test
    fun `read rejects traversal and metadata`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateRead(root, "root", "../secret"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateRead(root, "root", "metadata.json"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateRead(root, "plugins", "a/b.jar"))
    }

    @Test
    fun `delete rejects protected but allows plugin jars`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDelete(root, "root", "eula.txt"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDelete(root, "root", "server.properties"))
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateDelete(root, "plugins", "EssentialsX.jar"))
    }

    @Test
    fun `delete rejects traversal`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDelete(root, "root", "../../etc/passwd"))
    }
}
