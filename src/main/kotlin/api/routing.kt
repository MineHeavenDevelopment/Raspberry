package api

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

// chizi felan ezafe nakardam serfan daram test mikonam !
fun Application.configureRouting(){
}
    fun Application.module() {
        install(ContentNegotiation) {
            json()
        }

    routing {
        get("/") {
            call.respondText("Hello, World!")
        }
    }
}