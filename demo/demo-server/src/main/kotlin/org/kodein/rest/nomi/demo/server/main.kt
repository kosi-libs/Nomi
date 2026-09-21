package org.kodein.rest.nomi.demo.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import org.kodein.rest.nomi.demo.shared.SharedJson


fun main() {
    embeddedServer(
        factory = CIO,
        port = 8080,
    ) {
        install(ContentNegotiation) {
            json(SharedJson)
        }

        routing {
            articles(ServerArticlesService())
            tools(ServerToolsService())
        }

    }.start(wait = true)
}

