package config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppConfig(
    val node: NodeConfig = NodeConfig(),
    val runtime: RuntimeConfig = RuntimeConfig(),
    val incus: IncusConfig = IncusConfig(),
    val api: ApiConfig = ApiConfig(),
    val download: DownloadConfig = DownloadConfig(),
    val paths: PathsConfig = PathsConfig(),
    val discord: DiscordConfig = DiscordConfig(),
    val redis: RedisConfig = RedisConfig()
)

// How server runtimes are started on this node: "incus" runs every Minecraft
// server inside its own Incus container (product default, mandatory path);
// "process" runs java directly on the host (dev mode).
@Serializable
data class RuntimeConfig(
    val mode: String = "incus"
)

@Serializable
data class IncusConfig(
    // Image must ship a JRE 21+ (preparing the image is the node operator's job)
    val image: String = "images:ubuntu/24.04/cloud",

    // Optional key for incus remotes (reserved; unused while remote = "local")
    @SerialName("remote_key")
    val remoteKey: String = "",

    // Incus remote the containers are created on
    val remote: String = "local",

    // P0: unprivileged isolation for every server container (never privileged).
    @SerialName("idmap_isolated")
    val idmapIsolated: Boolean = true,

    // Metadata stamped on every container for reconciliation after restarts.
    @SerialName("runtime_version")
    val runtimeVersion: Int = 1
)

// Node identity + capacity: what the panel sees and how many servers may run at once
@Serializable
data class NodeConfig(
    val name: String = "node-1",

    @SerialName("public_host")
    val publicHost: String = "127.0.0.1",

    @SerialName("start_port")
    val startPort: Int = 25565,

    @SerialName("end_port")
    val endPort: Int = 26000,

    @SerialName("max_servers")
    val maxServers: Int = 10
)

// Core HTTP API (Ktor) authentication and limits
@Serializable
data class ApiConfig(
    // P0: canonical token key; admin_token remains a legacy alias.
    @SerialName("core_token")
    val coreToken: String = "",

    // Legacy alias of core_token (kept so old config.toml files keep loading).
    @SerialName("admin_token")
    val adminToken: String = "change-me-core-admin-token",

    @SerialName("http_port")
    val httpPort: Int = 8081,

    // P0: upload size cap for the File API (MB).
    @SerialName("max_upload_mb")
    val maxUploadMb: Int = 200
)

@Serializable
data class DownloadConfig(
    @SerialName("auto_download")
    val autoDownload: Boolean = true,

    @SerialName("max_concurrent")
    val maxConcurrent: Int = 3
)

@Serializable
data class PathsConfig(
    @SerialName("versions_dir")
    val versionsDir: String = "./versions"
)
@Serializable
data class DiscordConfig(
    @SerialName("token")
    val discordToken: String = "put your bot token here",

    @SerialName("guildID")
    val guildID: String = "put your guild id here",

    @SerialName("description")
    val description: String = "<nil>",

    @SerialName("title")
    val title: String = "Alert",

    @SerialName("color")
    val color: Int = 0xeb34b7
)

@Serializable
data class RedisConfig(
    @SerialName("address")
    val address: String = "localhost",

    @SerialName("port")
    val port: Int = 6379,

    // Legacy single-channel name kept for backward compatibility (old producers
    // used to publish raw jobs on "raspberry"). It is still consumed by JobConsumer.
    @SerialName("channel")
    val channel: String = "raspberry",

    // Canonical contract channels shared with luminous-go and the web panel
    @SerialName("jobs_channel")
    val jobsChannel: String = "luminous:core:jobs",

    @SerialName("events_channel")
    val eventsChannel: String = "luminous:core:events"
)
