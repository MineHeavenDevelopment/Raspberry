package incus

import server.ForgeInstaller
import server.ServerLauncher
import server.SoftwareDownloader
import utils.logger
import java.io.File

// Product-default runtime: every Minecraft server runs inside its own Incus
// container. The server workspace is mounted at /server (disk device) and the
// server port is published on the node host through a proxy device, so the
// existing PortAllocator.isListening check and ConsoleRouter log tailing keep
// working unchanged. The Process returned by `incus exec` is attached to the
// regular ProcessRegistry pipeline exactly like process mode. Forge is
// installed once on the node host (shared workspace); inside the container
// only the generated run script is executed.
class IncusLauncher(
    private val cli: IncusCli,
    private val downloader: SoftwareDownloader,
    private val image: String,
    private val remote: String,
    private val idmapIsolated: Boolean = true,
    private val runtimeVersion: Int = 1,
    private val nodeId: String = ""
) : ServerLauncher {

    override fun launch(
        workspace: File,
        software: String,
        version: String,
        ramMb: Int,
        logFile: File,
        requestId: String,
        port: Int
    ): Process {
        if (!cli.available()) {
            throw IllegalStateException("incus binary not found on node host")
        }
        val name = containerName(requestId)

        // Idempotent container creation: launch when missing, start when stopped.
        // P0: every container is unprivileged (security.idmap.isolated) and gets
        // luminous.* metadata for reconciliation; security.privileged is never set.
        if (!cli.exists(name)) {
            val metadata = mapOf(
                "user.luminous.server_id" to sanitizeMetaValue(name),
                "user.luminous.request_id" to sanitizeMetaValue(requestId),
                "user.luminous.node_id" to sanitizeMetaValue(nodeId),
                "user.luminous.runtime_version" to runtimeVersion.toString()
            )
            val r = cli.launch(name, image, remote, ramMb, idmapIsolated, metadata)
            if (!r.ok) {
                throw IllegalStateException(
                    "Failed to launch incus container '$name' (image=$image): ${r.output.take(300)}"
                )
            }
        } else if (!cli.start(name).ok) {
            throw IllegalStateException("Failed to start existing incus container '$name'")
        }

        val disk = cli.addDiskDevice(name, DISK_DEVICE, workspace.absolutePath, CONTAINER_WORKSPACE)
        if (!disk.ok) {
            throw IllegalStateException(
                "Failed to mount workspace into incus container '$name': ${disk.output.take(300)}"
            )
        }
        val proxy = cli.addProxyDevice(name, PROXY_DEVICE, port)
        if (!proxy.ok) {
            throw IllegalStateException(
                "Failed to publish port $port on incus container '$name': ${proxy.output.take(300)}"
            )
        }

        val runtimeCommand = prepareRuntime(workspace, software, version, ramMb)
        logger(
            "Launching $software $version inside incus container '$name' (image=$image, port=$port): ${runtimeCommand.joinToString(" ")}",
            error = false
        )
        return cli.exec(name, runtimeCommand, logFile)
    }

    // Called after the runtime process exited (graceful stop or crash):
    // force-stop the container but keep it (and its workspace) for the next
    // power start; containers are never deleted here.
    override fun onStopped(requestId: String) {
        val name = containerName(requestId)
        val r = cli.stopForce(name)
        if (!r.ok) logger("Could not stop incus container '$name': ${r.output.take(300)}", error = true)
    }

    // ---- host-side preparation (shared workspace) ----

    private fun prepareRuntime(workspace: File, software: String, version: String, ramMb: Int): List<String> {
        return if (software == "forge") {
            val installer = downloader.resolveJar("forge", version)
            ForgeInstaller.ensureInstalled(workspace, "java", installer)
            ForgeInstaller.writeUserJvmArgs(workspace, ramMb)
            listOf("sh", "$CONTAINER_WORKSPACE/run.sh")
        } else {
            val jar = downloader.resolveJar(software, version)
            copyIntoWorkspace(workspace, jar)
            javaCommand(JAR_NAME, ramMb)
        }
    }

    private fun copyIntoWorkspace(workspace: File, jar: File) {
        val target = File(workspace, JAR_NAME)
        if (target.exists() && target.length() == jar.length()) return
        jar.copyTo(target, overwrite = true)
    }

    // ---- pure builders (unit-tested without running incus) ----

    // Container name (P0 canonical format): "luminous-srv-" + sanitized request
    // id, <= 63 chars total (DNS-compatible), only [a-zA-Z0-9-_]; never blank.
    fun containerName(requestId: String): String {
        val clean = requestId.trim()
            .replace(Regex("[^a-zA-Z0-9-_]"), "_")
            .take(MAX_NAME_LENGTH - PREFIX.length)
            .ifBlank { "server" }
        return (PREFIX + clean).take(MAX_NAME_LENGTH)
    }

    // Reconciliation helper (P0): prefix used to list luminous-owned containers.
    fun listOwnedArgs(): List<String> = cli.listByPrefixArgs(PREFIX)

    fun sanitizeMetaValue(v: String): String =
        v.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_").take(64)

    fun javaCommand(jarName: String, ramMb: Int): List<String> =
        listOf("java", "-Xmx${ramMb}M", "-Xms512M", "-jar", "$CONTAINER_WORKSPACE/$jarName", "nogui")

    companion object {
        private const val PREFIX = "luminous-srv-"
        private const val MAX_NAME_LENGTH = 63
        const val DISK_DEVICE = "serverdisk"
        const val PROXY_DEVICE = "serverproxy"
        const val CONTAINER_WORKSPACE = "/server"
        const val JAR_NAME = "server.jar"
    }
}

