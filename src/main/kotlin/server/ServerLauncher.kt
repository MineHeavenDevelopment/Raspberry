package server

import java.io.File

// Abstraction over how a Minecraft server runtime is started on this node:
// - ProcessLauncher: plain java child process on the host (dev mode)
// - incus.IncusLauncher: java inside a dedicated Incus container (product default)
// The returned Process is attached to the regular ProcessRegistry/ConsoleRouter
// pipeline in both modes.
interface ServerLauncher {
    fun launch(
        workspace: File,
        software: String,
        version: String,
        ramMb: Int,
        logFile: File,
        requestId: String,
        port: Int
    ): Process

    // Optional hook invoked after the runtime process exited (graceful stop or
    // crash); e.g. the incus launcher force-stops the container here.
    fun onStopped(requestId: String) {}
}
