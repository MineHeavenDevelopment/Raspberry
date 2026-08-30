package server

import utils.logger
import java.io.BufferedReader
import java.io.File
import java.util.ArrayDeque
import java.util.LinkedHashSet
import kotlin.concurrent.thread

// Per-server console router: a lightweight reader thread that tails
// logs/latest.log of the server workspace, keeps the last N lines in a ring
// buffer (served over GET /api/v1/servers/{id}/console), estimates the online
// player list from join/leave messages, and exposes sendCommand() which the
// ProcessRegistry uses to write to the server process stdin.
// (JSON line protocol equivalent: {"type":"log","line":".."} streams available
// through the HTTP console endpoint; {"type":"send_command","command":".."}
// maps to sendCommand(); {"type":"get_players"} maps to players().)
class ConsoleRouter(private val logFile: File) {
    private val ring = ArrayDeque<String>()
    private val onlinePlayers = LinkedHashSet<String>()
    private val ringCap = 1000

    @Volatile
    private var running = false

    private var tailer: Thread? = null

    fun start() {
        if (running) return
        running = true
        tailer = thread(name = "ConsoleRouter-${logFile.parentFile?.name ?: "unknown"}") { tailLoop() }
    }

    fun stop() {
        running = false
        tailer?.interrupt()
    }

    fun snapshot(lines: Int): List<String> = synchronized(ring) {
        val n = lines.coerceIn(1, ringCap)
        if (ring.size <= n) ring.toList() else ring.toList().takeLast(n)
    }

    fun players(): List<String> = synchronized(onlinePlayers) { onlinePlayers.toList() }

    private fun appendLine(line: String) {
        synchronized(ring) {
            if (ring.size >= ringCap) ring.removeFirst()
            ring.addLast(line)
        }
        trackPlayer(line)
    }

    private fun trackPlayer(line: String) {
        // Matches vanilla-style join/leave messages, e.g.
        // "Steve joined the game" / "Steve left the game" (approximate by design).
        val name = Regex("(?:\\[[^\\]]*]\\s*)*(\\S+) (joined|left) the game").find(line) ?: return
        val (player, action) = name.destructured
        synchronized(onlinePlayers) {
            if (action == "joined") onlinePlayers.add(player) else onlinePlayers.remove(player)
        }
    }

    private fun tailLoop() {
        var reader: BufferedReader? = null
        try {
            while (running) {
                val current = reader
                if (current != null) {
                    val line = current.readLine()
                    if (line != null) {
                        appendLine(line)
                        continue
                    }
                }
                // (Re)open the log if it exists yet; EOF or missing file -> wait and retry
                if (!logFile.exists()) {
                    reader?.close()
                    reader = null
                    Thread.sleep(500)
                    continue
                }
                if (reader == null) {
                    reader = logFile.bufferedReader()
                    continue
                }
                Thread.sleep(300)
            }
        } catch (_: InterruptedException) {
            // shutting down
        } catch (e: Exception) {
            if (running) logger("ConsoleRouter tail error for ${logFile.path}: ${e.message}", error = true)
        } finally {
            try {
                reader?.close()
            } catch (_: Exception) {
            }
        }
    }
}