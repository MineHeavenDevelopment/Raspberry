package job

import org.json.JSONArray
import org.json.JSONObject

// Canonical contract with luminous-go and the web panel.
// Jobs arrive on redis channel "luminous:core:jobs" (legacy "raspberry" still accepted):
//   {"type":"create_server","request_id":"<unique>","player_uuid":"..","player_name":"..","settings":{...}}
//   {"type":"power_server","server_id":"..","action":"start|stop|restart"}
// settings keys (all optional): version, software, world_type, hardcore, max_players,
// motd, ram_mb, online_mode, plugins:[{name,url}], mods:[{name,url}]

data class PluginFile(val name: String, val url: String)

// P0: single source of truth for supported server software. Every module must
// validate against this list.
val SUPPORTED_SOFTWARE = setOf("paper", "purpur", "vanilla", "fabric", "forge")
val SUPPORTED_WORLD_TYPES = setOf("default", "flat", "amplified")

data class ServerSettings(
    val version: String = "1.21",
    val software: String = "paper",
    val worldType: String = "default",
    val hardcore: Boolean = false,
    val maxPlayers: Int = 20,
    val motd: String = "A Luminous Server",
    val ramMb: Int = 2048,
    val onlineMode: Boolean = false,
    val plugins: List<PluginFile> = emptyList(),
    val mods: List<PluginFile> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("software", software)
        put("world_type", worldType)
        put("hardcore", hardcore)
        put("max_players", maxPlayers)
        put("motd", motd)
        put("ram_mb", ramMb)
        put("online_mode", onlineMode)
        put("plugins", JSONArray(plugins.map { JSONObject().put("name", it.name).put("url", it.url) }))
        put("mods", JSONArray(mods.map { JSONObject().put("name", it.name).put("url", it.url) }))
    }

    companion object {
        fun fromJson(s: JSONObject?): ServerSettings {
            s ?: return ServerSettings()
            fun files(key: String): List<PluginFile> {
                val arr = s.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    PluginFile(
                        name = o.optString("name", "file-${i + 1}"),
                        url = o.optString("url", "")
                    ).takeIf { it.url.isNotBlank() }
                }
            }
            // P0 canonical naming: memory_mb is authoritative; legacy ram_mb is
            // accepted as an alias (memory_mb wins when both are present).
            val memoryMb = when {
                s.has("memory_mb") && !s.isNull("memory_mb") -> s.optInt("memory_mb", 2048)
                s.has("ram_mb") && !s.isNull("ram_mb") -> s.optInt("ram_mb", 2048)
                else -> 2048
            }.coerceIn(512, 65536)

            // Canonical software whitelist (also validated at provision time).
            val software = s.optString("software", "paper").ifBlank { "paper" }.lowercase().trim()
            val worldType = s.optString("world_type", "default").ifBlank { "default" }.lowercase().trim()

            return ServerSettings(
                version = s.optString("version", "1.21").ifBlank { "1.21" },
                software = software,
                worldType = worldType,
                hardcore = s.optBoolean("hardcore", false),
                maxPlayers = s.optInt("max_players", 20).coerceIn(2, 100000),
                motd = s.optString("motd", "A Luminous Server").ifBlank { "A Luminous Server" },
                ramMb = memoryMb,
                onlineMode = s.optBoolean("online_mode", false),
                plugins = files("plugins"),
                mods = files("mods")
            )
        }
    }
}

sealed class CoreJob {
    data class CreateServer(
        val requestId: String,
        val playerUuid: String?,
        val playerName: String?,
        val settings: ServerSettings
    ) : CoreJob()

    data class PowerServer(
        val serverId: String,
        val action: String
    ) : CoreJob()
}

object JobParser {
    fun parse(raw: String): CoreJob? {
        val json = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return null
        }
        return when (json.optString("type")) {
            "create_server" -> {
                val requestId = json.optString("request_id").trim()
                if (requestId.isEmpty()) null
                else CoreJob.CreateServer(
                    requestId = requestId,
                    playerUuid = json.optString("player_uuid").takeIf { it.isNotBlank() },
                    playerName = json.optString("player_name").takeIf { it.isNotBlank() },
                    settings = ServerSettings.fromJson(json.optJSONObject("settings"))
                )
            }
            "power_server" -> {
                // server_id identifies the server (request_id accepted as alias)
                val serverId = (json.optString("server_id") + json.optString("request_id")).trim()
                val action = json.optString("action", "stop").trim().lowercase()
                if (serverId.isEmpty() || action !in setOf("start", "stop", "restart")) null
                else CoreJob.PowerServer(serverId, action)
            }
            else -> null
        }
    }
}
