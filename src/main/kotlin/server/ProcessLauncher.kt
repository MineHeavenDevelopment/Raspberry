package server

import utils.logger
import java.io.File

// Dev-mode runtime: runs the server as a direct java child process on the node
// host (the previous ServerProvisioner.startProcess behavior, unchanged).
class ProcessLauncher(private val downloader: SoftwareDownloader) : ServerLauncher {

    override fun launch(
        workspace: File,
        software: String,
        version: String,
        ramMb: Int,
        logFile: File,
        requestId: String,
        port: Int
    ): Process {
        val java = "java"
        val command: List<String> = when (software) {
            "forge" -> {
                val installer = downloader.resolveJar("forge", version)
                ForgeInstaller.ensureInstalled(workspace, java, installer)
                val runScript = if (ForgeInstaller.isWindows()) File(workspace, "run.bat") else File(workspace, "run.sh")
                if (!runScript.exists()) {
                    throw IllegalStateException("Forge run script not found after install (expected ${runScript.name})")
                }
                ForgeInstaller.writeUserJvmArgs(workspace, ramMb)
                if (ForgeInstaller.isWindows()) listOf("cmd", "/c", "run.bat") else listOf("sh", "run.sh")
            }
            else -> {
                val jar = downloader.resolveJar(software, version)
                writeStartScripts(workspace, jar, ramMb)
                listOf(java, "-Xmx${ramMb}M", "-Xms512M", "-jar", jar.absolutePath, "nogui")
            }
        }

        val pb = ProcessBuilder(command)
            .directory(workspace)
            .redirectErrorStream(true)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        logger("Launching ${software} $version: ${command.joinToString(" ")}", error = false)
        return pb.start()
    }

    private fun writeStartScripts(workspace: File, jar: File, ramMb: Int) {
        File(workspace, "start.bat").writeText(
            "@echo off\r\ncd /d \"%~dp0\"\r\njava -Xmx${ramMb}M -Xms512M -jar \"${jar.absolutePath.replace("\"", "^\"")}\" nogui\r\npause\r\n"
        )
        File(workspace, "start.sh").writeText(
            "#!/bin/sh\ncd \"\$(dirname \"\$0\")\"\njava -Xmx${ramMb}M -Xms512M -jar \"${jar.absolutePath}\" nogui\n"
        )
        File(workspace, "start.sh").setExecutable(true)
    }
}
