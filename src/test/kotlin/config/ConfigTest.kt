package config

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
    @Test
    fun `runtime defaults to incus container mode`() {
        val cfg = AppConfig()
        assertEquals("incus", cfg.runtime.mode)
        assertEquals("images:ubuntu/24.04/cloud", cfg.incus.image)
        assertEquals("local", cfg.incus.remote)
        assertEquals("", cfg.incus.remoteKey)
    }

    @Test
    fun `parses runtime and incus sections from toml`() {
        val toml = """
            [node]
            name = "n1"
            [runtime]
            mode = "process"
            [incus]
            image = "images:debian/12/cloud"
            remote = "node-b"
            remote_key = "k3y"
        """.trimIndent()
        val cfg = Toml.decodeFromString<AppConfig>(toml)
        assertEquals("process", cfg.runtime.mode)
        assertEquals("images:debian/12/cloud", cfg.incus.image)
        assertEquals("node-b", cfg.incus.remote)
        assertEquals("k3y", cfg.incus.remoteKey)
        assertEquals("n1", cfg.node.name)
    }

    @Test
    fun `legacy config without the new sections keeps defaults`() {
        val cfg = Toml.decodeFromString<AppConfig>("[node]\nname = \"legacy\"\n")
        assertEquals("legacy", cfg.node.name)
        assertEquals("incus", cfg.runtime.mode)
        assertEquals("local", cfg.incus.remote)
    }
}
