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
    @Serializable data object InvalidTitle : ArticleCreationFailure
    @Serializable data object InvalidAuthor : ArticleCreationFailure
    @Serializable data object InvalidContent : ArticleCreationFailure
}

@Serializable
sealed interface ArticleCommentFailure : Error {
    @Serializable data object InvalidAuthor : ArticleCommentFailure
    @Serializable data object InvalidContent : ArticleCommentFailure
}
