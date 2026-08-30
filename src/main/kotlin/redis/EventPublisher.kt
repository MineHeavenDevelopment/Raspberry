package redis

import org.json.JSONObject
import utils.logger

// Publishes core events to the canonical events channel with the exact
// contract consumed by "luminous - go/internal/bus/bus.go":
//   {"type":"server_build_success","request_id":"..","ip":"..","port":25565}
//   {"type":"server_build_failed","request_id":"..","reason":".."}
//   {"type":"server_power_success","server_id":"..","action":"start|stop"}
class EventPublisher(
    private val publisher: RedisPublisher,
    private val eventsChannel: String
) {
    fun buildSuccess(requestId: String, ip: String, port: Int) {
        publish(JSONObject().apply {
            put("type", "server_build_success")
            put("request_id", requestId)
            put("ip", ip)
            put("port", port)
        })
    }

    fun buildFailed(requestId: String, reason: String) {
        publish(JSONObject().apply {
            put("type", "server_build_failed")
            put("request_id", requestId)
            put("reason", reason)
        })
    }

    fun powerSuccess(serverId: String, action: String) {
        publish(JSONObject().apply {
            put("type", "server_power_success")
            put("server_id", serverId)
            put("action", action)
        })
    }

    private fun publish(json: JSONObject) {
        publisher.publish(json.toString(), eventsChannel)
    }
}