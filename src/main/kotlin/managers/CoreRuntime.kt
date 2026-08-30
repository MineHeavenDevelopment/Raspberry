package managers

import config.AppConfig
import redis.clients.jedis.Jedis
import job.CoreJob
import server.PortAllocator
import server.ProcessRegistry
import server.ServerProvisioner
import server.SoftwareDownloader
import utils.logger
import java.io.File
import java.util.concurrent.Executors

// Application-wide singletons for the core node, initialized exactly once
// when the Ktor module loads.
object CoreRuntime {
    lateinit var config: AppConfig
        private set
    lateinit var ports: PortAllocator
        private set
    lateinit var registry: ProcessRegistry
        private set
    lateinit var provisioner: ServerProvisioner
        private set
    lateinit var downloader: SoftwareDownloader
        private set
    lateinit var redis: RedisManager
        private set

    @Volatile
    var started = false
        private set

    @Synchronized
    fun start() {
        if (started) return
        config = ConfigManager.loadConfig()

        val node = config.node
        if (node.endPort < node.startPort) {
            error("Invalid config: node.end_port (${node.endPort}) must be >= node.start_port (${node.startPort})")
        }

        // Fail fast on an unknown runtime mode (incus = product default, process = dev)
        val runtimeMode = config.runtime.mode.trim().lowercase()
        if (runtimeMode !in setOf("incus", "process")) {
            error("Invalid config: runtime.mode '${config.runtime.mode}' (expected 'incus' or 'process')")
        }

        downloader = SoftwareDownloader(
            versionsDir = File(config.paths.versionsDir),
            autoDownload = config.download.autoDownload,
            maxConcurrent = config.download.maxConcurrent
        )
        ports = PortAllocator(
            startPort = node.startPort,
            endPort = node.endPort,
            stateFile = File("./state/ports.json")
        )
        registry = ProcessRegistry()

        // Sequential worker: jobs are processed one at a time (downloads are
        // internally throttled by download.max_concurrent).
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "Job-Worker") }

        redis = RedisManager(
            config = config,
            onCreate = { job -> worker.submit { provisioner.handleCreate(job) } },
            onPower = { job -> worker.submit { provisioner.handlePower(job) } }
        )
        provisioner = ServerProvisioner(config, ports, registry, redis.events, downloader)

        // P0: canonical token + env override must be applied from the real config.
        api.CoreToken.applyConfig(config.api)

        // Recover existing workspaces so stopped servers can be powered again.
        val recovered = registry.recover(File("./servers"))
        if (recovered.isNotEmpty()) logger("Recovered ${recovered.size} existing server(s) for power management", error = false)

        // P0 reconciliation: a server whose port is still listening right now is
        // running, not stopped (the old behavior wrongly assumed stopped always).
        var runningRestored = 0
        for (meta in recovered) {
            val rs = registry.get(meta.requestId) ?: continue
            if (ports.isListening(meta.port)) {
                rs.status = "running"
                runningRestored++
                logger("operation=reconcile server_id=${meta.requestId} result=running (port ${meta.port} is listening)", error = false)
            }
        }
        if (runningRestored > 0) logger("operation=reconcile result=summary running_restored=$runningRestored", error = false)

        // P0 reconciliation (incus mode): containers named luminous-srv-* that
        // have local metadata but are missing from the registry are registered.
        if (runtimeMode == "incus") {
            runCatching { reconcileIncusContainers(recovered.map { it.requestId }.toSet()) }
                .onFailure { logger("operation=reconcile result=incus_list_failed error=\"${it.message}\"", error = true) }
        }

        redis.startConsumer()
        started = true
        logger("Raspberry core started: node=${node.name} http=${config.api.httpPort} ports=${node.startPort}-${node.endPort} max_servers=${node.maxServers} runtime=$runtimeMode", error = false)
    }

    // Registers metadata-backed workspaces found as incus containers but absent
    // from the registry (e.g. created by a previous core installation).
    private fun reconcileIncusContainers(knownRequestIds: Set<String>) {
        val cli = incus.IncusCli()
        if (!cli.available()) return
        val launcher = ServerProvisioner(config, ports, registry, redis.events, downloader)
        val incusLauncher = launcher.incusLauncherOrNull() ?: return
        val listArgs = incusLauncher.listOwnedArgs()
        val process = ProcessBuilder(listArgs).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val runningNames = output.lineSequence()
            .map { it.substringBefore(',') }
            .filter { it.startsWith("luminous-srv-") }
            .toSet()
        val serversDir = File("./servers")
        val dirs = serversDir.listFiles() ?: return
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val metaFile = File(dir, "metadata.json")
            if (!metaFile.exists()) continue
            val meta = runCatching { server.ServerMeta.fromJson(org.json.JSONObject(metaFile.readText())) }.getOrNull() ?: continue
            if (meta.requestId.isBlank() || meta.requestId in knownRequestIds) continue
            val containerName = incusLauncher.containerName(meta.requestId)
            if (containerName !in runningNames) continue
            val rs = registry.registerProvisioning(meta, dir)
            rs.console = server.ConsoleRouter(File(dir, "logs/latest.log")).also { it.start() }
            rs.status = if (ports.isListening(meta.port)) "running" else "stopped"
            logger("operation=reconcile server_id=${meta.requestId} result=registered_from_incus status=${rs.status}", error = false)
        }
    }

    // P0 readiness check: short-timeout Redis ping.
    fun redisAvailable(): Boolean = try {
        Jedis(config.redis.address, config.redis.port).use { jedis ->
            jedis.ping() == "PONG"
        }
    } catch (e: Exception) {
        false
    }

    fun statusSnapshot(): org.json.JSONObject = org.json.JSONObject().apply {
        put("node", config.node.name)
        put("max_servers", config.node.maxServers)
        put("active_servers", registry.activeCount())
        put("servers", org.json.JSONArray().apply {
            registry.all().forEach { put(it.statusJson(config.node.publicHost)) }
        })
    }
}
