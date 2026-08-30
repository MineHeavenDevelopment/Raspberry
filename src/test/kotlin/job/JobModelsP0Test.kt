package job

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobModelsP0Test {
    @Test
    fun `memory_mb is canonical and wins over legacy ram_mb`() {
        val s = ServerSettings.fromJson(JSONObject().put("memory_mb", 4096).put("ram_mb", 999))
        assertEquals(4096, s.ramMb)
        val s2 = ServerSettings.fromJson(JSONObject().put("ram_mb", 3072))
        assertEquals(3072, s2.ramMb)
        val s3 = ServerSettings.fromJson(JSONObject())
        assertEquals(2048, s3.ramMb)
    }

    @Test
    fun `memory bounds are coerced`() {
        val s = ServerSettings.fromJson(JSONObject().put("memory_mb", 1))
        assertEquals(512, s.ramMb)
        val s2 = ServerSettings.fromJson(JSONObject().put("memory_mb", 999999))
        assertEquals(65536, s2.ramMb)
    }

    @Test
    fun `software and world_type are lowercased`() {
        val s = ServerSettings.fromJson(JSONObject().put("software", "PAPER").put("world_type", "FLAT"))
        assertEquals("paper", s.software)
        assertEquals("flat", s.worldType)
    }

    @Test
    fun `canonical whitelist matches the contract`() {
        assertEquals(setOf("paper", "purpur", "vanilla", "fabric", "forge"), SUPPORTED_SOFTWARE)
        assertEquals(setOf("default", "flat", "amplified"), SUPPORTED_WORLD_TYPES)
    }

    @Test
    fun `create job parses with alias memory`() {
        val job = JobParser.parse(
            JSONObject()
                .put("type", "create_server")
                .put("request_id", "req-1")
                .put("settings", JSONObject().put("ram_mb", 1024).put("software", "purpur"))
                .toString()
        ) as? CoreJob.CreateServer
        assertTrue(job != null)
        assertEquals("req-1", job!!.requestId)
        assertEquals("purpur", job.settings.software)
        assertEquals(1024, job.settings.ramMb)
    }

    @Test
    fun `invalid jobs are rejected`() {
        assertNull(JobParser.parse("not-json"))
        assertNull(JobParser.parse(JSONObject().put("type", "unknown").toString()))
        assertNull(JobParser.parse(JSONObject().put("type", "create_server").toString()))
    }
}
