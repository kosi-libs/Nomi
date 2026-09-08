package org.kodein.rest.nomi.processor.common.path

// A path segment usable as-is, and the shape a derived resource's own simple name must fit.
public val SEGMENT_NAME_REGEX: Regex = Regex("[A-Za-z][A-Za-z0-9_-]*")

// A `{placeholder}` body, or an endpoint query parameter's effective name.
public val IDENTIFIER_REGEX: Regex = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val SCHEME_REGEX = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

/**
 * Resolves a resource's [PathResult] per SPECS.adoc § Resource paths: derives one from [simpleName]
 * and [ownParameters] when [declaredPath] is empty (the `@Endpoint.Resource` annotation's default),
 * otherwise parses [declaredPath] itself.
 */
public fun resourcePath(simpleName: String, declaredPath: String, ownParameters: List<String>): PathResult =
    if (declaredPath.isEmpty()) derivedResourcePath(simpleName, ownParameters)
    else parseResourcePath(declaredPath, ownParameters)

public fun derivedResourcePath(simpleName: String, ownParameters: List<String>): PathResult {
    if (!SEGMENT_NAME_REGEX.matches(simpleName)) {
        return PathResult.Failed(listOf(PathError.UndeclarableSimpleName(simpleName)))
    }
    val nameSegment = PathSegment(prefix = simpleName.replaceFirstChar { it.lowercaseChar() }, parameter = null, suffix = "")
    val paramSegments = ownParameters.map { PathSegment(prefix = "", parameter = it, suffix = "") }
    return PathResult.Ok(ResourcePath(listOf(nameSegment) + paramSegments))
}

public fun parseResourcePath(declared: String, ownParameters: List<String>): PathResult {
    if (declared.isBlank()) return PathResult.Failed(listOf(PathError.Blank))

    val errors = mutableListOf<PathError>()
    if (declared.startsWith("/")) errors += PathError.LeadingSlash
    if (declared.endsWith("/")) errors += PathError.TrailingSlash
    for (c in declared.toSet()) {
        when (c) {
            '?', '#', '&', '=' -> errors += PathError.ForbiddenCharacter(c)
            '%' -> errors += PathError.PercentSign
            else -> if (c.isWhitespace()) errors += PathError.ForbiddenCharacter(c)
        }
    }
    if (SCHEME_REGEX.containsMatchIn(declared)) errors += PathError.SchemeOrAuthority

    val segments = mutableListOf<PathSegment>()
    val placeholderNames = mutableListOf<String>()
    for (raw in declared.split("/")) {
        if (raw.isEmpty()) {
            errors += PathError.EmptySegment
            continue
        }
        val scan = scanSegment(raw)
        if (scan.error != null) {
            errors += scan.error
            continue
        }
        if (scan.placeholder != null) placeholderNames += scan.placeholder
        segments += PathSegment(scan.prefix, scan.placeholder, scan.suffix)
    }

    val nameCounts = placeholderNames.groupingBy { it }.eachCount()
    for ((name, count) in nameCounts) {
        if (name !in ownParameters) errors += PathError.UnknownParameter(name)
        else if (count > 1) errors += PathError.RepeatedParameter(name)
    }
    for (name in ownParameters) {
        if (name !in placeholderNames) errors += PathError.MissingParameter(name)
    }

    if (segments.isEmpty() && errors.isEmpty()) errors += PathError.NoSegment

    return if (errors.isEmpty()) PathResult.Ok(ResourcePath(segments)) else PathResult.Failed(errors)
}

private data class SegmentScan(val prefix: String, val placeholder: String?, val suffix: String, val error: PathError?)

private fun scanSegment(raw: String): SegmentScan {
    if (raw == "*") return SegmentScan("", null, "", PathError.KtorSyntax(raw, "*"))

    val firstOpen = raw.indexOf('{')
    if (firstOpen == -1) {
        if (raw.contains('}')) return SegmentScan("", null, "", PathError.UnbalancedPlaceholder(raw))
        return SegmentScan(raw, null, "", null)
    }

    val firstClose = raw.indexOf('}', firstOpen)
    if (firstClose == -1) return SegmentScan("", null, "", PathError.UnbalancedPlaceholder(raw))

    val prefix = raw.substring(0, firstOpen)
    val inner = raw.substring(firstOpen + 1, firstClose)
    val rest = raw.substring(firstClose + 1)

    if (prefix.contains('}')) return SegmentScan("", null, "", PathError.UnbalancedPlaceholder(raw))
    if (inner.contains('{')) return SegmentScan("", null, "", PathError.NestedPlaceholder(raw))
    if (rest.contains('{')) return SegmentScan("", null, "", PathError.MultiplePlaceholders(raw))
    if (rest.contains('}')) return SegmentScan("", null, "", PathError.UnbalancedPlaceholder(raw))

    if (inner.isEmpty()) return SegmentScan("", null, "", PathError.EmptyPlaceholderName(raw))
    if (inner == "..." || inner.endsWith("...") || inner.endsWith("?")) {
        return SegmentScan("", null, "", PathError.KtorSyntax(raw, "{$inner}"))
    }
    if (!IDENTIFIER_REGEX.matches(inner)) return SegmentScan("", null, "", PathError.UnknownParameter(inner))

    return SegmentScan(prefix, inner, rest, null)
}
