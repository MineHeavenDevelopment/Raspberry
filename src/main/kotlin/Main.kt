package ir.nayragames

import api.module
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import managers.ConfigManager
import utils.logger

fun main(args: Array<String>) {
    val config = ConfigManager.loadConfig()
    logger("Starting Raspberry core (Engine: Netty, admin HTTP port: ${config.api.httpPort})...", error = false)
    // The HTTP port comes from config.toml -> [api].http_port
    embeddedServer(Netty, port = config.api.httpPort, module = Application::module)
        .start(wait = true)
}
