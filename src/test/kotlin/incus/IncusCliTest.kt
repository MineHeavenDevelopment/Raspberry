package incus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Pure arg-builder tests: no real incus execution happens here.
class IncusCliTest {
    private val cli = IncusCli()

    @Test
    fun `launch args keep image and set memory limit`() {
        assertEquals(
            listOf("incus", "launch", "images:ubuntu/24.04/cloud", "lum-abc", "-c", "limits.memory=2048MiB"),
            cli.launchArgs("lum-abc", "images:ubuntu/24.04/cloud", "local", 2048, idmapIsolated = false, metadata = emptyMap())
        )
    }

    @Test
    fun `launch args include remote only when not local`() {
        assertEquals(
            listOf("incus", "launch", "--remote", "node-b", "images:ubuntu/24.04/cloud", "lum-abc", "-c", "limits.memory=512MiB"),
            cli.launchArgs("lum-abc", "images:ubuntu/24.04/cloud", "node-b", 512, idmapIsolated = false, metadata = emptyMap())
        )
    }

    @Test
    fun `disk device args mount host workspace on container path`() {
        assertEquals(
            listOf("incus", "config", "device", "add", "lum-abc", "serverdisk", "disk", "source=/srv/servers/abc", "path=/server"),
            cli.addDiskDeviceArgs("lum-abc", "serverdisk", "/srv/servers/abc", "/server")
        )
    }

    @Test
    fun `proxy device args publish container port on host`() {
        assertEquals(
            listOf(
                "incus", "config", "device", "add", "lum-abc", "serverproxy", "proxy",
                "listen=tcp:0.0.0.0:25565", "connect=tcp:127.0.0.1:25565"
            ),
            cli.addProxyDeviceArgs("lum-abc", "serverproxy", 25565)
        )
    }

    @Test
    fun `exec args keep runtime command verbatim after separator`() {
        assertEquals(
            listOf("incus", "exec", "lum-abc", "--", "java", "-Xmx2048M", "-jar", "/server/server.jar", "nogui"),
            cli.execArgs("lum-abc", listOf("java", "-Xmx2048M", "-jar", "/server/server.jar", "nogui"))
        )
    }

    @Test
    fun `stop and delete force the container`() {
        assertEquals(listOf("incus", "stop", "-f", "lum-abc"), cli.stopForceArgs("lum-abc"))
        assertEquals(listOf("incus", "delete", "-f", "lum-abc"), cli.deleteArgs("lum-abc"))
    }

    @Test
    fun `unsafe container names are rejected before any command runs`() {
        assertFailsWith<IllegalArgumentException> { cli.requireSafeName("lum bad;rm -rf") }
        assertFailsWith<IllegalArgumentException> { cli.requireSafeName("luminous-srv-".repeat(8)) }
        cli.requireSafeName("lum-ok_name-1")
        assertTrue(cli.launchArgs("lum-ok_name-1", "img", "local", 512, idmapIsolated = false, metadata = emptyMap()).contains("lum-ok_name-1"))
    }
}
