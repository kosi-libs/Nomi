package org.kodein.rest.nomi.processor.common

/** Fully-qualified names of every Nomi declaration the processors read, as KSP expects them: dotted. */
public object NomiFq {
    public const val ENDPOINT: String = "org.kodein.rest.nomi.Endpoint"
    public const val RESOURCE: String = "org.kodein.rest.nomi.Endpoint.Resource"
    public const val SERVICE: String = "org.kodein.rest.nomi.Endpoint.Service"
    public const val QUERY: String = "org.kodein.rest.nomi.Endpoint.Query"
    public const val USE_STRINGER: String = "org.kodein.rest.nomi.Endpoint.UseStringer"
    public const val STRINGED: String = "org.kodein.rest.nomi.Endpoint.Stringed"
    public const val ENDPOINT_SERVICE: String = "org.kodein.rest.nomi.EndpointService"
    public const val INTERNAL_API: String = "org.kodein.rest.nomi.InternalNomiAPI"
    public const val STRINGER: String = "org.kodein.rest.nomi.Stringer"
    public const val BOOLEAN_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.BooleanStringer"
    public const val INT_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.IntStringer"
    public const val LONG_STRINGER: String = "org.kodein.rest.nomi.Stringer.Builtins.LongStringer"

    /** The builtin `Stringer` for each type it covers, keyed by that type's FQN. */
    public val BUILTIN_STRINGERS: Map<String, String> = mapOf(
        "kotlin.Boolean" to BOOLEAN_STRINGER,
        "kotlin.Int" to INT_STRINGER,
        "kotlin.Long" to LONG_STRINGER,
    )
}
