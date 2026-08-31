package server

import job.ServerSettings
import org.json.JSONObject
import utils.createDirectory
import utils.logger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

data class ServerMeta(
    val requestId: String,
    val port: Int,
    val software: String,
    val settings: ServerSettings,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("port", port)
        put("software", software)
        put("created_at", createdAt)
        put("settings", settings.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): ServerMeta = ServerMeta(
            requestId = o.optString("request_id"),
            port = o.optInt("port"),
            software = o.optString("software", "paper"),
            settings = ServerSettings.fromJson(o.optJSONObject("settings")),
            createdAt = o.optLong("created_at", System.currentTimeMillis())
        )
    }
}

// One tracked server (workspace) on this node.
class RunningServer(
    val requestId: String,
    val port: Int,
    val software: String,
    val settings: ServerSettings,
    val workspace: File
) {
    @Volatile var status: String = "provisioning"   // provisioning | running | stopped | failed
    @Volatile var lastError: String = ""            // human-readable failure reason (empty if none)
    @Volatile var process: Process? = null
    @Volatile var stopRequested: Boolean = false
    @Volatile var console: ConsoleRouter? = null

    // Optional hook invoked once after the runtime process exited (graceful
    // stop or crash); e.g. the incus launcher force-stops the container here.
    @Volatile var onStopped: (() -> Unit)? = null
    internal val stopHookFired = AtomicBoolean(false)

    fun fireOnStopped() {
        val hook = onStopped ?: return
        if (!stopHookFired.compareAndSet(false, true)) return
        try {
            hook()
        } catch (e: Exception) {
            logger("onStopped hook failed for $requestId: ${e.message}", error = true)
        }
    }

    fun statusJson(publicHost: String): JSONObject = JSONObject().apply {
        put("request_id", requestId)
        put("status", status)
        put("last_error", lastError)
        put("port", port)
        put("ip", publicHost)
        put("players", console?.players()?.size ?: 0)
        put("player_names", console?.players() ?: emptyList<String>())
        put("software", software)
        put("version", settings.version)
        put("motd", settings.motd)
    }
}

// Keeps every server of this node (request_id -> workspace/process), handles
// graceful stop (stdin "stop" then destroy), restart, and recovery after a
// core restart by scanning ./servers/*/metadata.json.
class ProcessRegistry {
    private val servers = ConcurrentHashMap<String, RunningServer>()

    fun get(requestId: String): RunningServer? = servers[requestId]

    fun all(): Collection<RunningServer> = servers.values

    fun activeCount(): Int = servers.values.count { it.status == "provisioning" || it.status == "running" }

    fun registerProvisioning(meta: ServerMeta, workspace: File): RunningServer {
        val rs = RunningServer(meta.requestId, meta.port, meta.software, meta.settings, workspace)
        rs.status = "provisioning"
        servers[meta.requestId] = rs
        return rs
    }

    fun attachProcess(rs: RunningServer, process: Process) {
        rs.process = process
        rs.stopRequested = false
        rs.stopHookFired.set(false) // re-arm the hook for the freshly launched runtime
        thread(name = "ProcessWatch-${rs.requestId}") {
            val code = runCatching { process.waitFor() }.getOrDefault(-1)
            if (!rs.stopRequested) {
                // Crashed or exited on its own while we considered it running
                if (rs.status == "running" || rs.status == "provisioning") {
                    rs.status = "stopped"
                    logger("Server ${rs.requestId} exited with code $code", error = false)
                }
            }
            rs.fireOnStopped()
        }
    }

    // Graceful stop: write "stop" to stdin, wait up to 30s, then destroy.
    fun stop(requestId: String): Boolean {
        val rs = servers[requestId] ?: return false
        val process = rs.process
        if (process == null || !process.isAlive) {
            rs.status = "stopped"
            rs.process = null
            rs.fireOnStopped()
            return true
        }
        rs.stopRequested = true
        try {
            process.outputStream.apply {
                write("stop\n".toByteArray())
                flush()
            }
        } catch (e: Exception) {
            logger("Could not send stop command to ${rs.requestId}: ${e.message}", error = true)
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                logger("Server ${rs.requestId} did not stop in 30s, killing", error = true)
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
        }
        rs.status = "stopped"
        rs.process = null
        rs.fireOnStopped()
        return true
    }

    // Recovers knowledge of existing servers after a core restart: scans
    // ./servers/*/metadata.json and registers them as stopped (available for
    // power start). Running PIDs are not resurrected.
    fun recover(serversDir: File): List<ServerMeta> {
        val recovered = mutableListOf<ServerMeta>()
        if (!serversDir.exists()) return recovered
        for (dir in serversDir.listFiles() ?: emptyArray()) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, "metadata.json")
            if (!metaFile.exists()) continue
            try {
                val meta = ServerMeta.fromJson(JSONObject(metaFile.readText()))
                if (meta.requestId.isBlank()) continue
                val rs = registerProvisioning(meta, dir)
                rs.console = ConsoleRouter(File(dir, "logs/latest.log")).also { it.start() }
                rs.status = "stopped"
                recovered += meta
                logger("Recovered server ${meta.requestId} (port ${meta.port}) as stopped", error = false)
            } catch (e: Exception) {
                logger("Failed to recover server metadata in ${dir.path}: ${e.message}", error = true)
            }
        }
        return recovered
    }

    fun saveMetadata(meta: ServerMeta, workspace: File) {
        try {
            createDirectory(workspace.path, "metadata.json").writeText(meta.toJson().toString(2))
        } catch (e: Exception) {
            logger("Failed to save metadata for ${meta.requestId}: ${e.message}", error = true)
        }
    }
}