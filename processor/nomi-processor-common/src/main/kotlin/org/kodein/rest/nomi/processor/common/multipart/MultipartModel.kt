package org.kodein.rest.nomi.processor.common.multipart

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import org.kodein.rest.nomi.processor.common.stringer.StringerRef

/** How many times a part may occur. Constant names match `Endpoint.MultipartFormData.PartPresence`'s
 * exactly, so the annotation's argument name resolves with a plain `valueOf` - the same trick
 * [org.kodein.rest.nomi.processor.common.service.HttpMethod] plays on `Endpoint.Method`. */
public enum class PartPresence {
    Optional,
    Required,
    RepeatedOptional,
    RepeatedRequired;

    /** Whether the part must occur at least once. */
    public val isRequired: Boolean get() = this == Required || this == RepeatedRequired

    /** Whether the part may occur more than once. */
    public val isRepeated: Boolean get() = this == RepeatedOptional || this == RepeatedRequired
}

/** What a part carries: a file, or a value encoded through a `Stringer` (SPECS.adoc § Multipart
 * part types - "`EndpointFile` makes it a *file part*; any other type makes it a *value part*"). */
public sealed interface PartPayload {
    public object File : PartPayload
    public data class Value(val stringer: StringerRef) : PartPayload
}

/** One part: a direct, nested subtype of the part type, already validated. [parameterName] is the
 * subtype's simple name lowercased, which the generated builder uses; [wireName] is the effective
 * part name on the wire. The two differ whenever `@Part(name)` is given. */
public class MultipartPart(
    public val declaration: KSClassDeclaration,
    public val property: KSPropertyDeclaration,
    public val parameterName: String,
    public val wireName: String,
    public val presence: PartPresence,
    public val payloadType: KSType,
    public val payload: PartPayload,
)

/** A fully validated `@Endpoint.MultipartFormData` interface - never constructed for an invalid one. */
public class AnalyzedMultipart(
    public val declaration: KSClassDeclaration,
    public val parts: List<MultipartPart>,
) {
    public val packageName: String get() = declaration.packageName.asString()
}
