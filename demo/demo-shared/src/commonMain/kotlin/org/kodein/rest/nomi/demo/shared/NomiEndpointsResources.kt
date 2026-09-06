package org.kodein.rest.nomi.demo.shared

import org.kodein.rest.nomi.EndpointService
import org.kodein.rest.nomi.InternalNomiAPI
import kotlin.uuid.Uuid


// GENERATED

@InternalNomiAPI data object NomiArticles : Articles
@InternalNomiAPI data object NomiArticlesNew : Articles.New
@InternalNomiAPI data class NomiArticle(override val articleId: Uuid) : Article
@InternalNomiAPI data class NomiArticleComments(override val articleId: Uuid) : Article.Comments

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
fun Articles(): Articles = NomiArticles

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
fun Articles.New(): Articles.New = NomiArticlesNew

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
fun Article(articleId: Uuid): Article = NomiArticle(articleId)

@OptIn(InternalNomiAPI::class)
context(_: EndpointService)
fun Article.Comments(): Article.Comments = NomiArticleComments(articleId)
