package org.kodein.rest.nomi.demo.client

import org.kodein.rest.nomi.client.ClientEndpointServiceFactory
import org.kodein.rest.nomi.demo.shared.ArticlesService


@ClientEndpointServiceFactory(ArticlesService::class, "articles")
object APIServices
