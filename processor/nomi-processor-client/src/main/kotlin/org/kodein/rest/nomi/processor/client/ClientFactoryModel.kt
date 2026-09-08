package org.kodein.rest.nomi.processor.client

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration

/** `@ClientEndpointServiceFactory`'s fully-qualified name, as KSP expects it: dotted. Client-only, so
 * it does not belong in `nomi-processor-common`'s `NomiFq`. */
internal const val CLIENT_ENDPOINT_SERVICE_FACTORY_FQ = "org.kodein.rest.nomi.client.ClientEndpointServiceFactory"

/** One `@ClientEndpointServiceFactory` registration on an annotated object - already validated,
 * never constructed for an invalid one. */
public class ClientFactoryRegistration(
    public val annotation: KSAnnotation,
    public val serviceClass: KSClassDeclaration,
    public val name: String,
)

/** SPECS.adoc § Generated-name collisions: "A client implementation, named `Nomi<Object><Service>Client`,
 * must be unique per package." Only the object's own *simple* name is used - unlike a resource's
 * generated impl, which concatenates its whole enclosing chain - so two differently-nested objects
 * sharing a simple name is exactly the collision [ClientRoundChecks] must catch. */
public fun clientImplName(objectSimpleName: String, serviceSimpleName: String): String =
    "Nomi$objectSimpleName${serviceSimpleName}Client"
