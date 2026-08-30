package config

import managers.ConfigManager.configFile

fun saveDefaultConfig() {
    val defaultConfigText = """
            # Raspberry Config

            [node]
            name = "node-1"
            public_host = "127.0.0.1"   # IP/hostname returned to clients in server_build_success events
            start_port = 25565
            end_port = 26000
            max_servers = 10

            [runtime]
            mode = "incus"    # incus (container, product default) | process (direct java on host, dev)

            [incus]
            image = "images:ubuntu/24.04/cloud"   # image must ship a JRE 21+
            remote = "local"
            remote_key = ""                       # optional key for incus remotes
            idmap_isolated = true                 # P0: unprivileged containers only (never privileged)
            runtime_version = 1                   # stamped as user.luminous.runtime_version

            [api]
            core_token = "change-me-core-admin-token"   # P0: canonical secret; prefer env LUMINOUS_CORE_TOKEN
            # admin_token = "..."                       # legacy alias (still accepted)
            http_port = 8081
            max_upload_mb = 200                         # File API upload cap

            [download]
            auto_download = true
            max_concurrent = 3

            [paths]
            versions_dir = "./versions"

            [discord]
            token = "put your bot token here"
            title = "Alert"
            color = 0xeb34b7
            guildID = "put your guild id here"
            description = <nil>

            [redis]
            address = "localhost"
            port = 6379
    ##        password = ""  // not work rn
            channel = "raspberry"                 # legacy channel, still consumed
            jobs_channel = "luminous:core:jobs"   # canonical jobs channel (web panel + luminous-go)
            events_channel = "luminous:core:events"  # canonical events channel

        """.trimIndent()

    configFile.writeText(defaultConfigText)
}
