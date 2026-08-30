package server

import utils.logger
import java.io.File
import java.util.concurrent.TimeUnit

// One-time host-side Forge server installation into a server workspace. Shared
// by both runtimes: the installer always runs on the node host (the workspace
// is shared/mounted), while the run script is later executed either on the
// host (process mode) or inside the container (incus mode).
object ForgeInstaller {
    fun ensureInstalled(workspace: File, java: String, installer: File) {
        val marker = File(workspace, ".forge-installed")
        val runScript = if (isWindows()) File(workspace, "run.bat") else File(workspace, "run.sh")
        if (marker.exists() && runScript.exists()) return
        logger("Installing Forge server from ${installer.name} (one-time)...", error = false)
        val process = ProcessBuilder(java, "-jar", installer.absolutePath, "--installServer")
            .directory(workspace)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("Forge installer timed out after 15 minutes")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("Forge installer exited with code ${process.exitValue()}")
        }
        marker.writeText("installed at ${System.currentTimeMillis()}")
    }

    fun writeUserJvmArgs(workspace: File, ramMb: Int) {
        val f = File(workspace, "user_jvm_args.txt")
        val content = f.readTextOrNull() ?: ""
        val stripped = content.lines().filterNot { it.trim().startsWith("-Xmx") }.joinToString("\n")
        f.writeText(stripped.trimEnd('\n') + "\n-Xmx${ramMb}M\n")
    }

    fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun File.readTextOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (e: Exception) {
        null
    }
}
