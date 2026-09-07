package org.kodein.rest.nomi.processor.common.path

/** One path segment: an optional literal prefix/suffix around at most one `{parameter}` placeholder. */
data class PathSegment(val prefix: String, val parameter: String?, val suffix: String) {
    val isLiteral: Boolean get() = parameter == null

    override fun toString(): String = buildString {
        append(prefix)
        if (parameter != null) append('{').append(parameter).append('}')
        append(suffix)
    }
}

data class ResourcePath(val segments: List<PathSegment>) {
    val parameterNames: List<String> get() = segments.mapNotNull { it.parameter }

    override fun toString(): String = segments.joinToString("/")
}

// SPECS.md § Resource paths - one variant per distinct failure, so a result can report several at once.
sealed interface PathError {
    data object Blank : PathError
    data object LeadingSlash : PathError
    data object TrailingSlash : PathError
    data object EmptySegment : PathError
    data class ForbiddenCharacter(val char: Char) : PathError
    data object PercentSign : PathError
    data object SchemeOrAuthority : PathError
    data class UnbalancedPlaceholder(val segment: String) : PathError
    data class NestedPlaceholder(val segment: String) : PathError
    data class EmptyPlaceholderName(val segment: String) : PathError
    data class KtorSyntax(val segment: String, val syntax: String) : PathError
    data class MultiplePlaceholders(val segment: String) : PathError
    data class UnknownParameter(val name: String) : PathError
    data class MissingParameter(val name: String) : PathError
    data class RepeatedParameter(val name: String) : PathError
    data class UndeclarableSimpleName(val simpleName: String) : PathError
    data object NoSegment : PathError
}

sealed interface PathResult {
    data class Ok(val path: ResourcePath) : PathResult
    data class Failed(val errors: List<PathError>) : PathResult
}
