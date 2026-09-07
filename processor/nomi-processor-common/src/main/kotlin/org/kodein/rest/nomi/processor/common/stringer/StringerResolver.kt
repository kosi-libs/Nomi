package org.kodein.rest.nomi.processor.common.stringer

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import org.kodein.rest.nomi.processor.common.NomiErrors
import org.kodein.rest.nomi.processor.common.NomiFq
import org.kodein.rest.nomi.processor.common.Reporter
import org.kodein.rest.nomi.processor.common.general.GeneralChecks
import org.kodein.rest.nomi.processor.common.ksp.annotationsNamed
import org.kodein.rest.nomi.processor.common.ksp.classArg
import org.kodein.rest.nomi.processor.common.ksp.fq
import org.kodein.rest.nomi.processor.common.ksp.fqName
import org.kodein.rest.nomi.processor.common.ksp.retention
import org.kodein.rest.nomi.processor.common.ksp.typeArg

/** Where a resolved `Stringer` came from - a direct `@Endpoint.Stringed`/`@Endpoint.UseStringer`
 * registration, or the runtime's builtin fallback for `Boolean`/`Int`/`Long`. */
public sealed interface StringerRef {
    public val declaration: KSClassDeclaration

    public data class Declared(override val declaration: KSClassDeclaration) : StringerRef
    public data class Builtin(override val declaration: KSClassDeclaration) : StringerRef
}

private val ARRAY_FQ_NAMES = setOf(
    "kotlin.Array",
    "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.CharArray", "kotlin.DoubleArray",
    "kotlin.FloatArray", "kotlin.IntArray", "kotlin.LongArray", "kotlin.ShortArray",
    "kotlin.UByteArray", "kotlin.UIntArray", "kotlin.ULongArray", "kotlin.UShortArray",
)

private val FORBIDDEN_STRINGER_TYPE_FQ_NAMES = setOf("kotlin.Unit", "kotlin.Nothing", "kotlin.Any")

/** Only annotations outside `kotlin.*`/`java.*` can plausibly be a Nomi carrier; skips stdlib
 * meta-annotations (`@Deprecated`, `@Suppress`, ...) so their own SOURCE-retention siblings never
 * cause a false "carrier must have BINARY or RUNTIME retention" report. */
private fun isLikelyUserAnnotation(fqName: String?): Boolean =
    fqName != null && !fqName.startsWith("kotlin.") && !fqName.startsWith("java.")

public class StringerResolver(private val resolver: Resolver, private val reporter: Reporter) {

    /** One scope per interface in [scopeChain], nearest (the declaring interface) first. */
    public fun scopesOf(scopeChain: List<KSClassDeclaration>): List<List<Registration<String, KSClassDeclaration>>> =
        scopeChain.map { registrationsOf(it) }

    private fun registrationsOf(scope: KSClassDeclaration): List<Registration<String, KSClassDeclaration>> {
        val found = collectFromAnnotationTree(
            roots = scope.annotations.toList(),
            identityOf = { it.fqName() },
            childrenOf = { ann ->
                if (!isLikelyUserAnnotation(ann.fqName())) emptyList()
                else (ann.annotationType.resolve().declaration as? KSClassDeclaration)?.annotations?.toList().orEmpty()
            },
            valuesOf = { ann ->
                if (ann.fqName() != NomiFq.USE_STRINGER) emptyList()
                else {
                    val type = ann.typeArg("type")
                    val with = ann.classArg("with")
                    if (type == null || with == null) {
                        emptyList()
                    } else {
                        // Every registration's own shape is validated here, at discovery, regardless
                        // of whether anything in scope ever matches it (SPECS.md only excuses an
                        // UNUSED registration from scrutiny, not an intrinsically malformed one).
                        checkUseStringerType(type, ann)
                        checkStringerObject(with, type, ann, scope)
                        val typeFq = type.declaration.qualifiedName?.asString()
                        if (typeFq != null) listOf(typeFq to with) else emptyList()
                    }
                }
            },
            onVisit = { ann, _ ->
                if (isLikelyUserAnnotation(ann.fqName())) {
                    val declaration = ann.annotationType.resolve().declaration as? KSClassDeclaration
                    val ret = declaration?.retention()
                    if (ret != null && ret != AnnotationRetention.BINARY && ret != AnnotationRetention.RUNTIME) {
                        reporter.error(ann, NomiErrors.carrierAnnotationBadRetention(declaration.fq(), ret.name))
                    }
                }
            },
        )
        return found.map { (pair, depth) -> Registration(pair.first, pair.second, depth) }
    }

    /**
     * Resolves a `Stringer` for [ownerName]'s [type] (already typealias-expanded and non-nullable),
     * in [scopes] (nearest first, see [scopesOf]) - `@Endpoint.Stringed` on [owner], if present,
     * wins outright. [user] is the declaration whose visibility the Stringer must be at least as
     * visible as. Returns `null` when nothing resolves, having already reported why. [owner] is
     * only the diagnostic's location - a resource path parameter (a [KSDeclaration]) and an
     * endpoint query parameter (a [com.google.devtools.ksp.symbol.KSValueParameter], which is not
     * one) share this one resolution path, so the caller supplies [ownerName] itself rather than
     * this function deriving it via [KSDeclaration.fq].
     */
    public fun resolveFor(
        owner: KSNode,
        ownerName: String,
        type: KSType,
        stringed: KSAnnotation?,
        scopes: List<List<Registration<String, KSClassDeclaration>>>,
        user: KSDeclaration,
    ): StringerRef? {
        if (stringed != null) {
            val with = stringed.classArg("with") ?: return null
            return if (checkStringerObject(with, type, stringed, user)) StringerRef.Declared(with) else null
        }

        val typeFq = type.declaration.qualifiedName?.asString()
        if (typeFq == null) {
            reporter.error(owner, NomiErrors.stringerUnresolved("?", ownerName))
            return null
        }

        val resolution = resolveStringer(scopes, typeFq, sameStringer = { a, b -> a.fq() == b.fq() })
        return when (resolution) {
            // The registration's own shape was already fully validated when discovered (see
            // registrationsOf) - trust it here rather than reporting the same problem twice.
            is StringerResolution.Resolved -> StringerRef.Declared(resolution.stringer)
            is StringerResolution.Ambiguous -> {
                reporter.error(owner, NomiErrors.stringerAmbiguous(typeFq, resolution.candidates.map { it.fq() }))
                null
            }
            is StringerResolution.Unresolved -> {
                val builtin = NomiFq.BUILTIN_STRINGERS[typeFq]?.let { resolver.getClassDeclarationByName(it) }
                if (builtin != null) {
                    StringerRef.Builtin(builtin)
                } else {
                    reporter.error(owner, NomiErrors.stringerUnresolved(typeFq, ownerName))
                    null
                }
            }
        }
    }

    /** SPECS.md § Stringers - rules on the `with =` object itself. */
    public fun checkStringerObject(candidate: KSClassDeclaration, forType: KSType, at: KSNode, user: KSDeclaration): Boolean {
        if (candidate.classKind != ClassKind.OBJECT) {
            reporter.error(at, NomiErrors.stringerWithNotObject(candidate.fq(), candidate.classKind.name.lowercase()))
            return false
        }
        if (!GeneralChecks.checkReadableDeclaration(candidate, reporter)) return false
        if (!GeneralChecks.checkAtLeastAsVisible(candidate, user, reporter)) return false

        val stringerSuperType = candidate.getAllSuperTypes()
            .firstOrNull { it.declaration.qualifiedName?.asString() == NomiFq.STRINGER }
        if (stringerSuperType == null) {
            reporter.error(at, NomiErrors.stringerNotImplementingStringer(candidate.fq()))
            return false
        }

        val typeArg = stringerSuperType.arguments.getOrNull(0)
        val tType = typeArg?.type?.resolve()
        if (typeArg?.type == null || tType == null || tType.declaration is KSTypeParameter) {
            reporter.error(at, NomiErrors.stringerRawOrTypeParam(candidate.fq()))
            return false
        }

        val expected = forType.makeNotNullable()
        val actual = tType.makeNotNullable()
        if (!(expected.isAssignableFrom(actual) && actual.isAssignableFrom(expected))) {
            reporter.error(
                at,
                NomiErrors.stringerTypeMismatch(
                    candidate.fq(),
                    expected.declaration.qualifiedName?.asString() ?: "?",
                    actual.declaration.qualifiedName?.asString() ?: "?",
                ),
            )
            return false
        }
        return true
    }

    /** SPECS.md § Stringers - rules on the `type =` argument of a `@Endpoint.UseStringer` registration. */
    public fun checkUseStringerType(type: KSType, at: KSNode): Boolean {
        val fqName = type.declaration.qualifiedName?.asString()
        if (fqName in FORBIDDEN_STRINGER_TYPE_FQ_NAMES || fqName in ARRAY_FQ_NAMES) {
            reporter.error(at, NomiErrors.useStringerTypeForbidden(fqName ?: "?"))
            return false
        }
        if (type.isFunctionType) {
            reporter.error(at, NomiErrors.useStringerTypeForbidden("a functional type"))
            return false
        }
        val decl = type.declaration as? KSClassDeclaration
        if (decl?.classKind == ClassKind.ANNOTATION_CLASS) {
            reporter.error(at, NomiErrors.useStringerTypeForbidden("an annotation class"))
            return false
        }
        if (decl != null && decl.typeParameters.isNotEmpty()) {
            reporter.error(at, NomiErrors.useStringerTypeHasTypeParameters(fqName ?: "?"))
            return false
        }
        return true
    }
}
