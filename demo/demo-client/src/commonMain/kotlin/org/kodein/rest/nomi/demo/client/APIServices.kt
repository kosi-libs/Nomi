package org.kodein.rest.nomi.demo.client

import org.kodein.rest.nomi.client.ClientEndpointServiceFactory
import org.kodein.rest.nomi.demo.shared.ArticlesService
import org.kodein.rest.nomi.demo.shared.ToolsService


@ClientEndpointServiceFactory(ArticlesService::class, "articles")
@ClientEndpointServiceFactory(ToolsService::class, "tools")
object APIServices
