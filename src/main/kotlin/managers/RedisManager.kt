package managers

import config.AppConfig
import job.CoreJob
import job.JobConsumer
import redis.EventPublisher
import redis.RedisPublisher
import utils.logger

class RedisManager(
    private val config: AppConfig,
    private val onCreate: (CoreJob.CreateServer) -> Unit = { _ -> },
    private val onPower: (CoreJob.PowerServer) -> Unit = { _ -> }
) {
    private val redisConfig = config.redis

    val publisher = RedisPublisher(redisConfig.address, redisConfig.port, redisConfig.channel)
    val events = EventPublisher(publisher, redisConfig.eventsChannel)

    private val consumer = JobConsumer(
        address = redisConfig.address,
        port = redisConfig.port,
        jobsChannel = redisConfig.jobsChannel,
        legacyChannel = redisConfig.channel,
        onCreate = onCreate,
        onPower = onPower
    )

    fun startConsumer() {
        logger("Connecting to Redis at ${redisConfig.address}:${redisConfig.port}...", error = false)
        consumer.start()
    }

    fun stopConsumer() {
        consumer.stop()
    }
}