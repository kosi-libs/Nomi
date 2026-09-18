package org.kodein.rest.nomi.processor.common.path

/** One path segment: an optional literal prefix/suffix around at most one `{parameter}` placeholder. */
public data class PathSegment(val prefix: String, val parameter: String?, val suffix: String) {
    val isLiteral: Boolean get() = parameter == null

    override fun toString(): String = buildString {
        append(prefix)
        if (parameter != null) append('{').append(parameter).append('}')
        append(suffix)
    }
}

public data class ResourcePath(val segments: List<PathSegment>) {
    val parameterNames: List<String> get() = segments.mapNotNull { it.parameter }

    override fun toString(): String = segments.joinToString("/")
}

// SPECS.adoc § Resource paths - one variant per distinct failure, so a result can report several at once.
public sealed interface PathError {
    public data object Blank : PathError
    public data object LeadingSlash : PathError
    public data object TrailingSlash : PathError
    public data object EmptySegment : PathError
    public data class ForbiddenCharacter(val char: Char) : PathError
    public data object PercentSign : PathError
    public data object SchemeOrAuthority : PathError
    public data class UnbalancedPlaceholder(val segment: String) : PathError
    public data class NestedPlaceholder(val segment: String) : PathError
    public data class EmptyPlaceholderName(val segment: String) : PathError
    public data class KtorSyntax(val segment: String, val syntax: String) : PathError
    public data class MultiplePlaceholders(val segment: String) : PathError
    public data class UnknownParameter(val name: String) : PathError
    public data class MissingParameter(val name: String) : PathError
    public data class RepeatedParameter(val name: String) : PathError
    public data class UndeclarableSimpleName(val simpleName: String) : PathError
    public data object NoSegment : PathError
}

public sealed interface PathResult {
    public data class Ok(val path: ResourcePath) : PathResult
    public data class Failed(val errors: List<PathError>) : PathResult
}
