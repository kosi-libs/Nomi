package org.kodein.rest.nomi.processor.common

/** Fully-qualified names of every Nomi declaration the processors read, as KSP expects them: dotted. */
public object NomiFq {
    public const val ENDPOINT: String = "org.kodein.rest.nomi.Endpoint"
    public const val RESOURCE: String = "org.kodein.rest.nomi.Endpoint.Resource"
    public const val SERVICE: String = "org.kodein.rest.nomi.Endpoint.Service"
    public const val QUERY: String = "org.kodein.rest.nomi.Endpoint.Query"
    public const val USE_STRINGER: String = "org.kodein.rest.nomi.Endpoint.UseStringer"
    public const val STRINGED: String = "org.kodein.rest.nomi.Endpoint.Stringed"
    public const val MULTIPART_FORM_DATA: String = "org.kodein.rest.nomi.Endpoint.MultipartFormData"
    public const val PART: String = "org.kodein.rest.nomi.Endpoint.MultipartFormData.Part"
    public const val ENDPOINT_SERVICE: String = "org.kodein.rest.nomi.EndpointService"
    public const val MULTIPART: String = "org.kodein.rest.nomi.EndpointService.Multipart"
    public const val ENDPOINT_FILE: String = "org.kodein.rest.nomi.EndpointFile"
    public const val INTERNAL_API: String = "org.kodein.rest.nomi.InternalNomiAPI"
    public const val STRINGER: String = "org.kodein.rest.nomi.Stringer"
    public const val BOOLEAN_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.BooleanStringer"
    public const val INT_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.IntStringer"
    public const val LONG_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.LongStringer"
    public const val STRING_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.StringStringer"
    public const val EITHER: String = "org.kodein.rest.nomi.Either"
    public const val ERROR: String = "org.kodein.rest.nomi.Error"
    public const val NO_FAIL: String = "org.kodein.rest.nomi.NoFail"

    /** The builtin `Stringer` for each type it covers, keyed by that type's FQN. */
    public val BUILTIN_STRINGERS: Map<String, String> = mapOf(
        "kotlin.Boolean" to BOOLEAN_STRINGER,
        "kotlin.Int" to INT_STRINGER,
        "kotlin.Long" to LONG_STRINGER,
        "kotlin.String" to STRING_STRINGER,
    )
}
