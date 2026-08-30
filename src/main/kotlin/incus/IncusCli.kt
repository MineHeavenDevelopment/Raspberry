package incus

import java.io.File

// Thin, typed wrapper around the `incus` binary. Every command is executed via
// ProcessBuilder with an explicit argument array (no shell interpolation) and
// container names are validated before use. The pure public arg builders are
// unit-tested without ever invoking incus.
class IncusCli(private val binary: String = "incus") {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private val safeName = Regex("^[a-zA-Z0-9-_]{1,63}$")

    // ---- pure command builders (public + testable, no incus execution) ----

    fun versionArgs(): List<String> = listOf(binary, "version")

    fun listArgs(name: String): List<String> = listOf(binary, "list", "^$name$", "--format", "csv")

    fun listByPrefixArgs(prefix: String): List<String> = listOf(binary, "list", "^$prefix.*", "--format", "csv")

    // P0: launch with unprivileged isolation config and luminous metadata
    // (never security.privileged). All flags are pure/testable.
    fun launchArgs(
        name: String,
        image: String,
        remote: String,
        ramMb: Int,
        idmapIsolated: Boolean,
        metadata: Map<String, String>
    ): List<String> = buildList {
        add(binary)
        add("launch")
        if (remote.isNotBlank() && remote != "local") {
            add("--remote")
            add(remote)
        }
        add(image)
        add(name)
        add("-c")
        add("limits.memory=${ramMb}MiB")
        if (idmapIsolated) {
            add("-c")
            add("security.idmap.isolated=true")
        }
        for ((key, value) in metadata) {
            add("-c")
            add("$key=$value")
        }
    }

    fun startArgs(name: String): List<String> = listOf(binary, "start", name)

    fun addDiskDeviceArgs(name: String, device: String, hostPath: String, containerPath: String): List<String> =
        listOf(binary, "config", "device", "add", name, device, "disk", "source=$hostPath", "path=$containerPath")

    fun addProxyDeviceArgs(name: String, device: String, port: Int): List<String> =
        listOf(
            binary, "config", "device", "add", name, device, "proxy",
            "listen=tcp:0.0.0.0:$port", "connect=tcp:127.0.0.1:$port"
        )

    fun execArgs(name: String, command: List<String>): List<String> =
        listOf(binary, "exec", name, "--") + command

    fun stopForceArgs(name: String): List<String> = listOf(binary, "stop", "-f", name)

    fun deleteArgs(name: String): List<String> = listOf(binary, "delete", "-f", name)

    // Public + pure: rejects any name outside [a-zA-Z0-9-_]{1,63} before it can
    // reach an incus command (defense in depth on top of IncusLauncher.sanitize).
    fun requireSafeName(name: String) {
        require(safeName.matches(name)) { "Unsafe incus container name: '$name'" }
    }

    // ---- operations ----

    // Version probe: true only when the incus binary exists and answers.
    fun available(): Boolean = run(versionArgs()).ok

    fun exists(name: String): Boolean {
        requireSafeName(name)
        val r = run(listArgs(name))
        return r.ok && r.output.isNotBlank()
    }

    fun launch(name: String, image: String, remote: String, ramMb: Int, idmapIsolated: Boolean = true, metadata: Map<String, String> = emptyMap()): Result {
        requireSafeName(name)
        return run(launchArgs(name, image, remote, ramMb, idmapIsolated, metadata))
    }

    // Starts an existing (stopped) container; "already running" counts as success.
    fun start(name: String): Result {
        requireSafeName(name)
        val r = run(startArgs(name))
        return if (r.ok || r.output.contains("already running", ignoreCase = true)) Result(0, r.output) else r
    }

    fun addDiskDevice(name: String, device: String, hostPath: String, containerPath: String): Result {
        requireSafeName(name)
        return tolerate(run(addDiskDeviceArgs(name, device, hostPath, containerPath)), "already exists")
    }

    fun addProxyDevice(name: String, device: String, port: Int): Result {
        requireSafeName(name)
        return tolerate(run(addProxyDeviceArgs(name, device, port)), "already exists")
    }

    // Long-running exec: returns the java.lang.Process so the caller streams
    // stdout/stderr and can forward stdin (server console "stop"/commands).
    fun exec(name: String, command: List<String>, logFile: File? = null): Process {
        requireSafeName(name)
        val pb = ProcessBuilder(execArgs(name, command))
        pb.redirectErrorStream(true)
        if (logFile != null) pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        return pb.start()
    }

    fun stopForce(name: String): Result {
        requireSafeName(name)
        return run(stopForceArgs(name))
    }

    fun delete(name: String): Result {
        requireSafeName(name)
        return run(deleteArgs(name))
    }

    // ---- helpers ----

    private fun tolerate(r: Result, ignoredFragment: String): Result =
        if (r.ok || r.output.contains(ignoredFragment, ignoreCase = true)) Result(0, r.output) else r

    private fun run(args: List<String>): Result = try {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        Result(process.waitFor(), output.trim())
    } catch (e: Exception) {
        Result(-1, e.message ?: e.javaClass.simpleName)
    }
}

