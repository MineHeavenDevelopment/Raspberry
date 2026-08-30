package redis

import utils.logger
import redis.clients.jedis.JedisPubSub

// Real pub/sub subscriber (replaces the old TODO stub).
// The handler receives every (channel, message) pair; JobConsumer decides
// whether it is a job to process or a legacy message that only gets logged.
class RedisSubscriber(private val handler: (channel: String, message: String) -> Unit) : JedisPubSub() {

    override fun onMessage(channel: String?, message: String?) {
        if (channel == null || message == null) return
        logger("Received message from [$channel]: $message", error = false)
        try {
            handler(channel, message)
        } catch (e: Exception) {
            logger("Error while handling message from [$channel]: ${e.message}", error = true)
        }
    }

    override fun onSubscribe(channel: String?, subscribedChannels: Int) {
        logger("Subscribed to channel: $channel", error = false)
    }

    override fun onUnsubscribe(channel: String?, subscribedChannels: Int) {
        logger("Unsubscribed from channel: $channel", error = false)
    }
}