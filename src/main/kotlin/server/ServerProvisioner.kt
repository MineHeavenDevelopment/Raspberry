package server

import config.AppConfig
import incus.IncusCli
import incus.IncusLauncher
import job.CoreJob
import job.SUPPORTED_SOFTWARE
import job.SUPPORTED_WORLD_TYPES
import job.ServerSettings
import org.json.JSONObject
import redis.EventPublisher
import utils.logger
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// Turns a create_server job into a fully provisioned, running Minecraft server:
//   ./servers/{request_id}/  (eula.txt, server.properties, start.bat, start.sh,
//                             plugins/, mods/, logs/latest.log, metadata.json)
// Publishes server_build_success ONLY after the allocated port actually started
// listening (retry TCP check up to ~120s); on timeout the process is killed and
// server_build_failed is published.
class ServerProvisioner(
    private val config: AppConfig,
    private val ports: PortAllocator,
    private val registry: ProcessRegistry,
    private val events: EventPublisher,
    private val downloader: SoftwareDownloader
) {
    val serversDir = File("./servers").apply { mkdirs() }

    // Runtime selection: incus containers are the product default; "process"
    // keeps the old direct-java-on-host behavior for development.
    private val launcher: ServerLauncher = when (config.runtime.mode.trim().lowercase()) {
        "incus" -> IncusLauncher(IncusCli(), downloader, config.incus.image, config.incus.remote)
        "process" -> ProcessLauncher(downloader)
        else -> throw IllegalArgumentException(
            "Invalid config: runtime.mode '${config.runtime.mode}' (expected 'incus' or 'process')"
        )
    }

    // Exposes the incus launcher (when active) so CoreRuntime reconciliation can
    // reuse the canonical container-name format and owned-prefix listing.
    fun incusLauncherOrNull(): incus.IncusLauncher? = launcher as? incus.IncusLauncher

    // Iran-friendly shared cache: pre-downloaded jars (mojang_{v}.jar etc) are
    // copied from the global cache dir into each new workspace so paperclip
    // never needs to re-download them. After a successful boot, new cache jars
    // are promoted back into the global cache for the next server.
    private fun cacheDir(): File = File("./cache").apply { mkdirs() }

    private fun seedServerCache(workspace: File, version: String, requestId: String) {
        val src = cacheDir()
        if (!src.isDirectory) return
        val dst = File(workspace, "cache").apply { mkdirs() }
        // mojang vanilla jar used by paperclip/fabric/forge installers
        for (name in listOf("mojang_$version.jar", "server_$version.jar")) {
            val f = File(src, name)
            if (f.isFile && !File(dst, name).exists()) {
                f.copyTo(File(dst, name), overwrite = false)
                logger("operation=cache server_id=$requestId result=seed file=$name", error = false)
            }
        }
    }

    private fun promoteServerCache(workspace: File, version: String) {
        val ws = File(workspace, "cache")
        if (!ws.isDirectory) return
        val dst = cacheDir()
        for (f in ws.listFiles() ?: emptyArray()) {
            if (!f.isFile) continue
            if (f.name.startsWith("mojang_$version") && f.length() > 1_000_000) {
                val target = File(dst, f.name)
                if (!target.exists()) {
                    f.copyTo(target, overwrite = false)
                    logger("operation=cache result=promote file=${f.name} size=${f.length()}", error = false)
                }
            }
        }
    }
    private val fileHttp: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    // ---- entry points (called from the job worker) ----

    fun handleCreate(job: CoreJob.CreateServer) {
        val requestId = sanitizeId(job.requestId)
        if (registry.activeCount() >= config.node.maxServers) {
            val reason = "Node capacity reached: max_servers=${config.node.maxServers} concurrent servers allowed"
            logger("operation=provision server_id=$requestId result=rejected reason=\"$reason\"", error = true)
            events.buildFailed(job.requestId, reason)
            return
        }
        // P0: canonical software/world_type validation before any work happens.
        val softwareLower = job.settings.software.lowercase().trim()
        if (softwareLower !in SUPPORTED_SOFTWARE) {
            val reason = "Unsupported software '${job.settings.software}' (supported: $SUPPORTED_SOFTWARE)"
            logger("operation=provision server_id=$requestId result=rejected reason=\"$reason\"", error = true)
            events.buildFailed(job.requestId, reason)
            return
        }
        val worldLower = job.settings.worldType.lowercase().trim()
        if (worldLower !in SUPPORTED_WORLD_TYPES) {
            val reason = "Unsupported world_type '${job.settings.worldType}' (supported: $SUPPORTED_WORLD_TYPES)"
            logger("operation=provision server_id=$requestId result=rejected reason=\"$reason\"", error = true)
            events.buildFailed(job.requestId, reason)
            return
        }
        try {
            provision(requestId, job)
        } catch (e: Exception) {
            logger("operation=provision server_id=$requestId result=failed error=\"${e.message}\"", error = true)
            events.buildFailed(job.requestId, e.message ?: "provisioning failed")
        }
    }

    fun handlePower(job: CoreJob.PowerServer) {
        val serverId = sanitizeId(job.serverId)
        logger("operation=power action=${job.action} server_id=$serverId received", error = false)
        try {
            when (job.action) {
                "stop" -> {
                    registry.stop(serverId)
                    events.powerSuccess(serverId, "stop")
                }
                "start" -> {
                    startExisting(serverId)
                    events.powerSuccess(serverId, "start")
                }
                "restart" -> {
                    registry.stop(serverId)
                    startExisting(serverId)
                    events.powerSuccess(serverId, "restart")
                }
            }
        } catch (e: Exception) {
            logger("operation=power action=${job.action} server_id=$serverId result=failed error=\"${e.message}\"", error = true)
        }
    }

    // ---- provisioning pipeline ----

    private fun provision(requestId: String, job: CoreJob.CreateServer) {
        val s = job.settings
        val software = s.software.lowercase().trim()

        val workspace = File(serversDir, requestId).apply { mkdirs() }
        val port = ports.allocate(requestId)

        val meta = ServerMeta(requestId = requestId, port = port, software = software, settings = s)
        registry.saveMetadata(meta, workspace)
        val rs = registry.registerProvisioning(meta, workspace)

        writeEula(workspace)
        writeServerProperties(workspace, s, port)
        seedServerCache(workspace, s.version, requestId)
        downloadFiles(workspace, "plugins", s.plugins)
        downloadFiles(workspace, "mods", s.mods)

        val logFile = rotateLog(workspace)
        val console = ConsoleRouter(logFile).also { it.start() }
        rs.console = console

        logger("operation=provision server_id=$requestId result=starting software=$software version=${s.version} port=$port memory_mb=${s.ramMb}", error = false)
        val process = launcher.launch(workspace, software, s.version, s.ramMb, logFile, requestId, port)
        rs.process = process
        rs.onStopped = { launcher.onStopped(requestId) }
        registry.attachProcess(rs, process)

        try {
            waitUntilListening(port, process, console, timeoutMs = 900_000)
        } catch (e: Exception) {
            process.destroyForcibly()
            rs.status = "failed"
            rs.lastError = e.message ?: "boot failed"
            rs.process = null
            throw e
        }

        rs.status = "running"
        promoteServerCache(workspace, s.version)
        logger("operation=provision server_id=$requestId result=success port=$port", error = false)
        events.buildSuccess(job.requestId, config.node.publicHost, port)
    }

    // Resolve the PID listening on the given port (Windows: netstat -ano, Linux: ss/lsof).
    private fun findPidOnPort(port: Int): Long? {
        return try {
            val isWin = System.getProperty("os.name").lowercase().contains("windows")
            val line: String? = if (isWin) {
                val pr = ProcessBuilder("cmd", "/c", "netstat", "-ano", "-p", "TCP").start()
                pr.inputStream.bufferedReader().readLines().firstOrNull {
                    it.contains(":$port ") && it.contains("LISTENING")
                }
            } else {
                val pr = ProcessBuilder("ss", "-lptn").start()
                pr.inputStream.bufferedReader().readLines().firstOrNull { it.contains(":$port ") }
            }
            line?.trim()?.split(Regex("\\s+"))?.lastOrNull()?.toLongOrNull()
        } catch (e: Exception) {
            logger("findPidOnPort($port) failed: ${e.message}", error = true)
            null
        }
    }

    private fun killPidOnPort(port: Int) {
        val pid = findPidOnPort(port) ?: return
        try {
            logger("Killing orphan pid $pid on port $port", error = false)
            val isWin = System.getProperty("os.name").lowercase().contains("windows")
            if (isWin) ProcessBuilder("taskkill", "/F", "/PID", pid.toString()).start().waitFor()
            else ProcessBuilder("kill", "-9", pid.toString()).start().waitFor()
        } catch (e: Exception) {
            logger("killPidOnPort($port) failed: ${e.message}", error = true)
        }
    }

    // Power-start of an existing (recovered or stopped) workspace.
    fun startExisting(requestId: String) {
        val workspace = File(serversDir, requestId)
        val metaFile = File(workspace, "metadata.json")
        if (!metaFile.exists()) {
            throw IllegalStateException("Server '$requestId' has no workspace on this node (cannot start)")
        }
        val meta = ServerMeta.fromJson(JSONObject(metaFile.readText()))
        seedServerCache(workspace, meta.settings.version, requestId)
        val existing = registry.get(requestId)
        val rs = existing ?: registry.registerProvisioning(meta, workspace).also { it.status = "stopped" }
        if (rs.status == "running" && rs.process?.isAlive == true) {
            logger("Server $requestId already running", error = false)
            return
        }
        if (rs.status == "provisioning" && rs.process?.isAlive == true) {
            // Boot already in progress (e.g. create_server auto-boot). Do NOT launch a
            // second JVM on the same world - it would die on the session.lock.
            logger("Server $requestId boot already in progress - start ignored", error = false)
            return
        }

        // If the world port is already served by an orphan JVM (survived a core restart),
        // adopt it instead of launching a second process that dies on the session.lock.
        if (isPortListening(meta.port)) {
            // The port is served, but we have no Process handle (orphan from a previous
            // core run or adopted-boot). A JVM we do not own gives us no stdin, so the
            // console would be read-only. Kill the orphan and relaunch under our control
            // so commands work - the world state is on disk, a reboot is safe.
            logger("Server $requestId port ${meta.port} already listening - taking over orphan process", error = false)
            killPidOnPort(meta.port)
            val deadline = System.currentTimeMillis() + 15_000
            while (isPortListening(meta.port) && System.currentTimeMillis() < deadline) Thread.sleep(300)
        }

        val logFile = rotateLog(workspace)
        if (rs.console == null) rs.console = ConsoleRouter(logFile).also { it.start() }
        rs.status = "provisioning"

        val process = launcher.launch(workspace, meta.software, meta.settings.version, meta.settings.ramMb, logFile, requestId, meta.port)
        rs.process = process
        rs.onStopped = { launcher.onStopped(requestId) }
        registry.attachProcess(rs, process)
        try {
            waitUntilListening(meta.port, process, rs.console, timeoutMs = 900_000)
        } catch (e: Exception) {
            process.destroyForcibly()
            rs.status = "failed"
            rs.lastError = e.message ?: "boot failed"
            rs.process = null
            throw e
        }
        rs.status = "running"
        logger("Server $requestId started on port ${meta.port}", error = false)
    }

    // ---- workspace files ----

    private fun writeEula(workspace: File) {
        File(workspace, "eula.txt").writeText("#Accepted by Raspberry core provisioning\neula=true\n")
    }

    private fun writeServerProperties(workspace: File, s: ServerSettings, port: Int) {
        val levelType = when (s.worldType.lowercase().trim()) {
            "flat" -> "minecraft:flat"
            "amplified" -> "minecraft:amplified"
            else -> "minecraft:normal"
        }
        val props = buildString {
            append("#Generated by Raspberry core\n")
            append("server-port=$port\n")
            append("server-ip=0.0.0.0\n")
            append("motd=${escapeProperties(s.motd)}\n")
            append("max-players=${s.maxPlayers}\n")
            append("online-mode=${s.onlineMode}\n")
            append("level-type=$levelType\n")
            append("hardcore=${s.hardcore}\n")
            append("enable-rcon=false\n")
            append("spawn-protection=0\n")
        }
        File(workspace, "server.properties").writeText(props)
    }

    private fun downloadFiles(workspace: File, folder: String, files: List<job.PluginFile>) {
        if (files.isEmpty()) return
        val dir = File(workspace, folder).apply { mkdirs() }
        for (f in files) {
            val name = if (f.name.endsWith(".jar")) f.name else "${f.name}.jar"
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) continue
            logger("Downloading $folder file '${f.name}' from ${f.url}", error = false)
            try {
                val request = HttpRequest.newBuilder(URI.create(f.url))
                    .timeout(Duration.ofMinutes(10))
                    .GET()
                    .build()
                fileHttp.send(request, HttpResponse.BodyHandlers.ofInputStream()).body().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                if (target.length() <= 0) throw IllegalStateException("empty download")
            } catch (e: Exception) {
                target.delete()
                throw IllegalStateException("Failed to download ${folder.removeSuffix("s")} '${f.name}': ${e.message}")
            }
        }
    }

    // ---- port listening wait ----

    private fun isPortListening(port: Int): Boolean = try {
        java.net.Socket().use { sock ->
            sock.connect(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), port), 300)
            true
        }
    } catch (e: Exception) { false }

    private fun waitUntilListening(port: Int, process: Process, console: ConsoleRouter?, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                val last = console?.snapshot(15)?.joinToString(" | ") ?: ""
                throw IllegalStateException(
                    "Server process exited during startup (code ${process.exitValue()}). Log tail: $last"
                )
            }
            if (ports.isListening(port)) return
            Thread.sleep(1000)
        }
        throw IllegalStateException("Server did not open port $port within ${timeoutMs / 1000}s (startup timeout)")
    }

    // ---- misc helpers ----

    private fun rotateLog(workspace: File): File {
        val logs = File(workspace, "logs").apply { mkdirs() }
        val latest = File(logs, "latest.log")
        if (latest.exists()) {
            val backup = File(logs, "latest-${System.currentTimeMillis()}.log")
            latest.renameTo(backup) || latest.delete()
        }
        return latest.apply { createNewFile() }
    }

    private fun sanitizeId(raw: String): String =
        raw.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80).ifBlank { "server-${System.currentTimeMillis()}" }

    private fun escapeProperties(v: String): String = v.replace("\\", "\\\\").replace("=", "\\=").replace(":", "\\:")
}

