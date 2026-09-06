package org.kodein.rest.nomi.demo.shared

import kotlinx.serialization.json.Json


val SharedJson = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
