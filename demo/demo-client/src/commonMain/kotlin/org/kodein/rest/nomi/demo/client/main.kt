package org.kodein.rest.nomi.demo.client

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import org.kodein.rest.nomi.client.call
import org.kodein.rest.nomi.demo.shared.Articles
import org.kodein.rest.nomi.demo.shared.SharedJson


suspend fun main() {

    val client = HttpClient {
        install(ContentNegotiation) {
            json(SharedJson)
        }
        defaultRequest {
            url("http://localhost:8080")
        }
    }

    println("Articles")
    val result = APIServices.articles(client).call { Articles().get() }
    println(result)
}
