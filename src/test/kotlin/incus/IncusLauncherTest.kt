package incus

import server.SoftwareDownloader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncusLauncherTest {
    // No incus call happens in these tests; the downloader only creates its cache dir.
    private val launcher = IncusLauncher(
        cli = IncusCli(),
        downloader = SoftwareDownloader(File("./build/tmp/incus-launcher-test-versions"), autoDownload = false, maxConcurrent = 1),
        image = "images:ubuntu/24.04/cloud",
        remote = "local"
    )

    @Test
    fun `container name is prefixed and sanitized`() {
        assertEquals("luminous-srv-ab_cd_ef", launcher.containerName("ab/cd:ef"))
        assertEquals("luminous-srv-simple", launcher.containerName("simple"))
        assertEquals("luminous-srv-ab_cd_ef", launcher.containerName("ab cd ef"))
    }

    @Test
    fun `container name keeps only incus safe characters`() {
        val name = launcher.containerName("weird!!name@@2026///x")
        assertTrue(Regex("^luminous-srv-[a-zA-Z0-9-_]+$").matches(name), "unexpected name: $name")
    }

    @Test
    fun `long request ids are truncated to 63 characters total`() {
        val name = launcher.containerName("x".repeat(200))
        assertEquals(63, name.length)
        assertEquals("luminous-srv-", name.substring(0, 13))
        assertTrue(name.drop(13).all { it == 'x' })
    }

    @Test
    fun `blank request ids fall back to a valid name`() {
        assertEquals("luminous-srv-server", launcher.containerName("   "))
    }

    @Test
    fun `runtime java command targets the mounted workspace jar`() {
        assertEquals(
            listOf("java", "-Xmx2048M", "-Xms512M", "-jar", "/server/server.jar", "nogui"),
            launcher.javaCommand("server.jar", 2048)
        )
        assertEquals(
            listOf("java", "-Xmx512M", "-Xms512M", "-jar", "/server/server.jar", "nogui"),
            launcher.javaCommand("server.jar", 512)
        )
    }
}
