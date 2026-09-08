package org.kodein.rest.nomi.demo.server

import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Either.Companion.value
import org.kodein.rest.nomi.NoFail
import org.kodein.rest.nomi.demo.shared.Tools
import org.kodein.rest.nomi.demo.shared.ToolsService
import org.kodein.rest.nomi.server.ServerEndpointService
import org.kodein.rest.nomi.server.routingCall


@ServerEndpointService
class ServerToolsService : ToolsService {
    override suspend fun Tools.Headers.get(): Either<List<Pair<String, List<String>>>, NoFail> {
        val headers = routingCall().request.headers.entries().map { it.key to it.value }.toList()
        return value(headers)
    }
}
