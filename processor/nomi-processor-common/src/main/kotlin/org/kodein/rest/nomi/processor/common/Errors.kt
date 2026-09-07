package org.kodein.rest.nomi.processor.common

import org.kodein.rest.nomi.processor.common.path.PathError

/**
 * Every diagnostic message the analysis in this module can produce, as functions rather than
 * inline strings - so a test can assert `NomiErrors.propertyIsVar("id")` without freezing prose
 * at the call site, and so a wording change happens in exactly one place.
 */
public object NomiErrors {

    // --- General -----------------------------------------------------------------------------

    public fun notPublicOrInternal(name: String): String =
        "'$name' must be public or internal"

    public fun isLocal(name: String): String =
        "'$name' must not be a local declaration"

    public fun isExpectOrActual(name: String): String =
        "'$name' must not be expect/actual"

    public fun isExternal(name: String): String =
        "'$name' must not be external"

    public fun isInner(name: String): String =
        "'$name' must not be an inner class"

    public fun hasTypeParameters(name: String): String =
        "'$name' must not declare type parameters"

    public fun notVisibleEnough(used: String, by: String): String =
        "'$used' is less visible than '$by', which uses it"

    // --- Resource declarations -----------------------------------------------------------------

    public fun resourceNotAnInterface(name: String, kind: String): String =
        "@Endpoint.Resource '$name' must be an interface, not a $kind"

    public fun resourceIsFunInterface(name: String): String =
        "@Endpoint.Resource '$name' must not be a fun interface"

    public fun resourceRootHasSupertype(name: String, supertype: String): String =
        "root resource '$name' must have no supertype, found '$supertype'"

    public fun resourceNestedWrongSupertype(name: String, expectedParent: String): String =
        "nested resource '$name' must directly extend its enclosing resource '$expectedParent'"

    public fun resourceExtraSupertype(name: String, supertype: String): String =
        "resource '$name' must have no supertype other than its enclosing resource, found '$supertype'"

    public fun resourceExtendsEndpointService(name: String): String =
        "resource '$name' must not extend EndpointService"

    public fun resourceEnclosedByResource(name: String, enclosing: String): String =
        "root resource '$name' is nested inside resource '$enclosing' without directly extending it"

    public fun resourceAlsoAnnotatedService(name: String): String =
        "resource '$name' must not also be annotated @Endpoint.Service"

    public fun nestedDeclarationNotResource(parent: String, nested: String): String =
        "'$nested', nested in resource '$parent', must be annotated @Endpoint.Resource"

    public fun nestedDeclarationForbidden(parent: String, nested: String, kind: String): String =
        "resource '$parent' must not declare a nested $kind ('$nested')"

    public fun resourceHasFunction(resource: String, function: String): String =
        "resource '$resource' must declare no functions, found '$function'"

    public fun propertyIsVar(name: String): String =
        "resource property '$name' must be val, not var"

    public fun propertyNotAbstract(name: String): String =
        "resource property '$name' must be abstract (no initializer, getter body, or delegate)"

    public fun propertyIsExtension(name: String): String =
        "resource property '$name' must not be an extension property"

    public fun propertyHasTypeParameters(name: String): String =
        "resource property '$name' must not declare type parameters"

    public fun propertyOverrides(name: String): String =
        "resource property '$name' must not override anything"

    public fun propertyNameInvalid(name: String): String =
        "resource property name '$name' must match [A-Za-z_][A-Za-z0-9_]*"

    public fun propertyShadowsAncestor(name: String, ancestor: String): String =
        "resource property '$name' shadows a property already declared by ancestor resource '$ancestor'"

    public fun propertyHasJvmAnnotation(name: String, annotation: String): String =
        "resource property '$name' must not carry @$annotation"

    public fun propertyConstOrLateinit(name: String): String =
        "resource property '$name' must not be const or lateinit"

    public fun useSiteTargetMovesAnnotation(annotation: String, target: String): String =
        "@$annotation must not use the '$target:' use-site target"

    // --- Path parameter types ------------------------------------------------------------------

    public fun pathParamNullable(name: String): String =
        "path parameter '$name' must not be nullable"

    public fun pathParamTypeParameter(name: String): String =
        "path parameter '$name' must not be a type parameter"

    public fun pathParamStarProjection(name: String): String =
        "path parameter '$name' must not use a star projection"

    public fun pathParamFunctionType(name: String): String =
        "path parameter '$name' must not be a functional type"

    public fun pathParamArray(name: String): String =
        "path parameter '$name' must not be an array"

    public fun pathParamForbiddenType(name: String, type: String): String =
        "path parameter '$name' must not be $type"

    public fun pathParamStringedOnString(name: String): String =
        "path parameter '$name' is String-typed and must not carry @Endpoint.Stringed"

    public fun pathParamUseStringerRegistersString(name: String): String =
        "path parameter '$name' is String-typed, but a @Endpoint.UseStringer in scope registers String"

    public fun pathParamParameterizedNeedsStringed(name: String): String =
        "path parameter '$name' has a parameterized type and can only be served a Stringer via @Endpoint.Stringed"

    // --- Resource paths (PathError -> message) ---------------------------------------------------

    public fun path(error: PathError): String = when (error) {
        PathError.Blank -> "path must not be blank"
        PathError.LeadingSlash -> "path must not start with '/'"
        PathError.TrailingSlash -> "path must not end with '/'"
        PathError.EmptySegment -> "path must not contain an empty segment ('//')"
        is PathError.ForbiddenCharacter -> "path must not contain '${error.char}'"
        PathError.PercentSign -> "path must not contain '%' - literal text is percent-encoded by the runtime"
        PathError.SchemeOrAuthority -> "path must name path segments only, not a scheme or authority"
        is PathError.UnbalancedPlaceholder -> "unbalanced '{'/'}' in path segment '${error.segment}'"
        is PathError.NestedPlaceholder -> "nested placeholder in path segment '${error.segment}'"
        is PathError.EmptyPlaceholderName -> "empty placeholder in path segment '${error.segment}'"
        is PathError.KtorSyntax -> "'${error.syntax}' is ktor routing syntax and not supported by Nomi (segment '${error.segment}')"
        is PathError.MultiplePlaceholders -> "at most one placeholder is allowed per path segment ('${error.segment}')"
        is PathError.UnknownParameter -> "path placeholder '{${error.name}}' does not name one of this resource's own path parameters"
        is PathError.MissingParameter -> "path parameter '${error.name}' must appear as a placeholder in the path"
        is PathError.RepeatedParameter -> "path parameter '${error.name}' must appear exactly once in the path"
        is PathError.UndeclarableSimpleName -> "'${error.simpleName}' is not usable as a path segment as-is; give this resource an explicit path"
        PathError.NoSegment -> "a resource must contribute at least one path segment"
    }

    // --- Stringers -----------------------------------------------------------------------------

    public fun stringerWithNotObject(name: String, kind: String): String =
        "Stringer '$name' must be an object, not a $kind"

    public fun stringerNotVisibleEnough(name: String, user: String): String =
        "Stringer '$name' is less visible than '$user', which uses it"

    public fun stringerNotImplementingStringer(name: String): String =
        "'$name' must implement Stringer<T> for a concrete T"

    public fun stringerRawOrTypeParam(name: String): String =
        "'$name' must implement Stringer<T> for a concrete T, not a star projection or its own type parameter"

    public fun stringerTypeMismatch(name: String, expected: String, actual: String): String =
        "'$name' implements Stringer<$actual>, but $expected was expected exactly"

    public fun useStringerRegistersString(): String =
        "@Endpoint.UseStringer must not register a Stringer for String"

    public fun useStringerTypeHasTypeParameters(type: String): String =
        "@Endpoint.UseStringer's type '$type' must not declare type parameters"

    public fun useStringerTypeForbidden(type: String): String =
        "@Endpoint.UseStringer's type must not be $type"

    public fun stringerAmbiguous(type: String, candidates: List<String>): String =
        "ambiguous Stringer for $type: ${candidates.joinToString(", ")} are all registered at the same distance and depth"

    public fun stringerUnresolved(type: String, property: String): String =
        "no Stringer resolves for '$property' of type $type"

    public fun carrierAnnotationBadRetention(name: String, retention: String): String =
        "carrier annotation '$name' must have BINARY or RUNTIME retention, found $retention"

    // --- Generated-name collisions ---------------------------------------------------------------

    public fun implNameCollision(a: String, b: String, generated: String): String =
        "'$a' and '$b' both generate the implementation name '$generated'"

    public fun factoryCollision(receiver: String?, name: String): String =
        "two generated factory functions collide on ${receiver?.let { "receiver '$it', " }.orEmpty()}name '$name'"

    public fun collidesWithUserDeclaration(generated: String): String =
        "generated declaration '$generated' collides with an existing declaration in this package"

    public fun fileNameCollision(a: String, b: String, generated: String): String =
        "source files '$a' and '$b' both generate the file name '$generated'"
}
