package org.kodein.rest.nomi.demo.shared

import kotlinx.serialization.Serializable
import org.kodein.rest.nomi.Error
import kotlin.uuid.Uuid


@Serializable
data class ArticleSummary(
    val id: Uuid,
    val title: String,
    val author: String,
)

@Serializable
data class ArticleContent(
    val title: String,
    val author: String,
    val content: String,
)

@Serializable
data class ArticleComment(
    val author: String,
    val content: String,
)

@Serializable
sealed interface ArticleCreationFailure : Error {
    data object InvalidTitle : ArticleCreationFailure
    data object InvalidAuthor : ArticleCreationFailure
    data object InvalidContent : ArticleCreationFailure
}

@Serializable
sealed interface ArticleCommentFailure : Error {
    data object InvalidAuthor : ArticleCommentFailure
    data object InvalidContent : ArticleCommentFailure
}
