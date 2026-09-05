package server

import org.json.JSONObject
import utils.createDirectory
import utils.logger
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.InetSocketAddress
import java.net.Socket

// Allocates a free port inside [startPort, endPort] for every server request.
// Allocations are persisted to ./state/ports.json so ports survive core restarts
// (a stopped server keeps its port and can be powered on again on the same port).
class PortAllocator(
    private val startPort: Int,
    private val endPort: Int,
    stateFile: File
) {
    private val stateFile: File = createDirectory(stateFile.parent ?: ".", stateFile.name)
    private val allocated = LinkedHashMap<String, Int>() // requestId -> port

    init {
        loadState()
    }

    @Synchronized
    fun allocate(requestId: String): Int {
        allocated[requestId]?.let {
            logger("Port ${it} already reserved for $requestId", error = false)
            return it
        }
        val used = allocated.values.toHashSet()
        // pick a RANDOM free port so every new server lands on a different port
        for (port in (startPort..endPort).shuffled()) {
            if (used.contains(port)) continue
            if (!isPortFree(port)) continue
            allocated[requestId] = port
            saveState()
            logger("Allocated port $port to $requestId", error = false)
            return port
        }
        throw IllegalStateException(
            "No free port available in range $startPort-$endPort (used: ${used.size})"
        )
    }

    @Synchronized
    fun get(requestId: String): Int? = allocated[requestId]

    @Synchronized
    fun release(requestId: String) {
        if (allocated.remove(requestId) != null) saveState()
    }

    @Synchronized
    fun usedPorts(): Set<Int> = allocated.values.toHashSet()

    // Real availability check: try to actually bind the port.
    fun isPortFree(port: Int): Boolean {
        if (port !in 1..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // True when something started listening on the port (server boot finished).
    fun isListening(port: Int, timeoutMs: Int = 1500): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun loadState() {
        if (!stateFile.exists()) return
        try {
            val json = JSONObject(stateFile.readText())
            for (key in json.keys()) {
                val port = json.optInt(key, -1)
                if (port in startPort..endPort) allocated[key] = port
            }
            logger("Loaded ${allocated.size} port reservation(s) from ${stateFile.path}", error = false)
        } catch (e: Exception) {
            logger("Failed to read port state file: ${e.message}", error = true)
        }
    }

    private fun saveState() {
        try {
            val json = JSONObject()
            for ((id, port) in allocated) json.put(id, port)
            stateFile.writeText(json.toString(2))
        } catch (e: Exception) {
            logger("Failed to save port state file: ${e.message}", error = true)
        }
    }
}