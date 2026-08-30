package incus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncusP0Test {
    private val cli = IncusCli()
    private val launcher = IncusLauncher(
        cli = cli,
        downloader = server.SoftwareDownloader(java.io.File("./build/tmp/incus-p0-test"), autoDownload = false, maxConcurrent = 1),
        image = "images:ubuntu/24.04/cloud",
        remote = "local"
    )

    @Test
    fun `canonical container name format is luminous-srv`() {
        assertEquals("luminous-srv-web-12", launcher.containerName("web-12"))
        assertEquals("luminous-srv-a_b", launcher.containerName("a b"))
    }

    @Test
    fun `container names stay dns safe and max 63 chars`() {
        val name = launcher.containerName("x".repeat(200))
        assertEquals(63, name.length)
        assertTrue(Regex("^luminous-srv-[a-zA-Z0-9-_]+$").matches(name))
    }

    @Test
    fun `launch args enforce unprivileged isolation and metadata`() {
        val args = cli.launchArgs(
            "luminous-srv-abc", "img", "local", 2048,
            idmapIsolated = true,
            metadata = mapOf(
                "user.luminous.server_id" to "luminous-srv-abc",
                "user.luminous.request_id" to "abc",
                "user.luminous.node_id" to "node-1",
                "user.luminous.runtime_version" to "1"
            )
        )
        assertTrue(args.contains("security.idmap.isolated=true"))
        assertTrue(args.contains("user.luminous.runtime_version=1"))
        assertTrue(args.contains("limits.memory=2048MiB"))
        assertTrue(!args.any { it.contains("security.privileged") }, "privileged must never appear")
    }

    @Test
    fun `launch args skip isolation when disabled by config`() {
        val args = cli.launchArgs("luminous-srv-abc", "img", "local", 512, idmapIsolated = false, metadata = emptyMap())
        assertTrue(!args.contains("security.idmap.isolated=true"))
        assertTrue(!args.any { it.contains("security.privileged") })
    }

    @Test
    fun `owned container listing uses the canonical prefix`() {
        val args = launcher.listOwnedArgs()
        assertEquals(listOf("incus", "list", "^luminous-srv-.*", "--format", "csv"), args)
    }

    @Test
    fun `metadata values are sanitized before reaching launch args`() {
        val sanitized = launcher.sanitizeMetaValue("a b;c")
        assertEquals("a_b_c", sanitized)
        val args = cli.launchArgs("luminous-srv-x", "img", "local", 512, true, mapOf("user.luminous.node_id" to launcher.sanitizeMetaValue("a b;c")))
        val meta = args.first { it.startsWith("user.luminous.node_id=") }
        assertTrue(!meta.contains(" ") && !meta.contains(";"))
    }
}
