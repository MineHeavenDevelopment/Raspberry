package job

import redis.RedisSubscriber
import utils.logger
import org.json.JSONObject
import redis.clients.jedis.Jedis
import kotlin.concurrent.thread

// Persistent job consumer. Subscribes to the canonical jobs channel
// (config: redis.jobs_channel = "luminous:core:jobs") AND the legacy
// "raspberry" channel. create_server / power_server messages coming from
// either channel are dispatched; anything else on the legacy channel
// (old smp-like messages) is only logged.
class JobConsumer(
    private val address: String,
    private val port: Int,
    private val jobsChannel: String,
    private val legacyChannel: String,
    private val onCreate: (CoreJob.CreateServer) -> Unit,
    private val onPower: (CoreJob.PowerServer) -> Unit
) {
    @Volatile
    private var running = true

    private var worker: Thread? = null

    fun start() {
        if (worker?.isAlive == true) return
        running = true
        worker = thread(name = "Redis-JobConsumer") { loop() }
    }

    fun stop() {
        running = false
        worker?.interrupt()
    }

    private fun loop() {
        var backoff = 3000L
        while (running) {
            try {
                logger("JobConsumer connecting to Redis at $address:$port (jobs=$jobsChannel, legacy=$legacyChannel)", error = false)
                Jedis(address, port).use { jedis ->
                    val subscriber = RedisSubscriber { channel, message -> handle(channel, message) }
                    // Blocks until connection drops or stop() is called
                    jedis.subscribe(subscriber, jobsChannel, legacyChannel)
                }
                backoff = 3000L
            } catch (e: Exception) {
                if (!running) return
                logger("JobConsumer disconnected: ${e.message} — retrying in ${backoff / 1000}s", error = true)
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return
                }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun handle(channel: String, message: String) {
        val parsedType = try {
            JSONObject(message).optString("type")
        } catch (e: Exception) {
            null
        }

        // Legacy channel: keep old producers alive for create/power, log the rest (smp-like messages)
        if (channel == legacyChannel && parsedType !in setOf("create_server", "power_server")) {
            logger("[legacy/$channel] non-job message ignored: $message", error = false)
            return
        }

        when (val job = JobParser.parse(message)) {
            is CoreJob.CreateServer -> {
                logger("Accepted create_server job request_id=${job.requestId} software=${job.settings.software} v=${job.settings.version}", error = false)
                onCreate(job)
            }
            is CoreJob.PowerServer -> {
                logger("Accepted power_server job server_id=${job.serverId} action=${job.action}", error = false)
                onPower(job)
            }
            null -> logger("Discarding invalid/unknown job message from [$channel]: $message", error = true)
        }
    }
}