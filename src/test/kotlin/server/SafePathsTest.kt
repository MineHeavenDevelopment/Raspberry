package server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Files

class SafePathsTest {
    private fun tempRoot(): File = Files.createTempDirectory("safe-roots").toFile()

    @Test
    fun `accepts a normal plugin upload`() {
        val root = tempRoot()
        val r = SafePaths.validateDestination(root, "plugins", "EssentialsX.jar")
        assertIs<SafePaths.Validation.Ok>(r)
        assertTrue(r.file.path.startsWith(root.absolutePath))
    }

    @Test
    fun `rejects traversal in the file name`() {
        val root = tempRoot()
        val r = SafePaths.validateDestination(root, "plugins", "..%2F..%2Fsecret") // no separators after sanitize? has none
        // name above has no separator; try explicit separators instead
        val r2 = SafePaths.validateDestination(root, "plugins", "../secret.jar")
        assertIs<SafePaths.Validation.Reject>(r2)
        val r3 = SafePaths.validateDestination(root, "plugins", "a/b.jar")
        assertIs<SafePaths.Validation.Reject>(r3)
    }

    @Test
    fun `rejects dotdot-only and empty names`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "root", ".."))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "root", ""))
    }

    @Test
    fun `rejects unknown subdir`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "../../etc", "x.jar"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "world", "x.jar"))
    }

    @Test
    fun `rejects forbidden extensions`() {
        val root = tempRoot()
        val r = SafePaths.validateDestination(root, "plugins", "evil.sh")
        assertIs<SafePaths.Validation.Reject>(r)
        val r2 = SafePaths.validateDestination(root, "plugins", "evil.jar.exe")
        assertIs<SafePaths.Validation.Reject>(r2)
    }

    @Test
    fun `protects core managed files from overwrite`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "root", "eula.txt"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "root", "server.properties"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateDestination(root, "root", "metadata.json"))
    }

    @Test
    fun `symlink pointing outside the root is rejected`() {
        val root = tempRoot()
        val plugins = File(root, "plugins").apply { mkdirs() }
        val outside = Files.createTempFile("outside", ".jar").toFile()
        val link = File(plugins, "evil.jar")
        runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }
            .onFailure { return } // symlink unsupported on this FS; skip
        val r = SafePaths.validateDestination(root, "plugins", "evil.jar")
        assertIs<SafePaths.Validation.Reject>(r)
    }

    @Test
    fun `list dir rejects escapes and accepts valid`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateListDir(root, "mods"))
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateListDir(root, "root"))
        assertIs<SafePaths.Validation.Reject>(SafePaths.validateListDir(root, "backups"))
    }

    @Test
    fun `extension allowlist accepts yaml and conf`() {
        val root = tempRoot()
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateDestination(root, "root", "spigot.yml"))
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateDestination(root, "root", "server.conf"))
        assertIs<SafePaths.Validation.Ok>(SafePaths.validateDestination(root, "root", "motd.txt"))
    }
}
