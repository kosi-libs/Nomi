package org.kodein.rest.nomi.demo.shared

import org.kodein.rest.nomi.Either
import org.kodein.rest.nomi.Endpoint
import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.NoFail


@Endpoint.Resource
interface Tools {

    @Endpoint.Resource
    interface Headers : Tools


}

@Endpoint.Service
interface ToolsService : EndpointService {

    suspend fun Tools.Headers.get(): Either<List<Pair<String, List<String>>>, NoFail>

}
