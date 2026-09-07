package org.kodein.rest.nomi.processor.common.resource

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility
import org.kodein.rest.nomi.processor.common.path.ResourcePath
import org.kodein.rest.nomi.processor.common.stringer.StringerRef

/** One resolved path parameter, own or inherited. */
public class PathParam(
    public val declaration: KSPropertyDeclaration,
    public val name: String,
    public val type: KSType,
    public val stringer: StringerRef,
)

/** A fully validated `@Endpoint.Resource` interface - never constructed for an invalid one. */
public class AnalyzedResource(
    public val declaration: KSClassDeclaration,
    public val parent: AnalyzedResource?,
    /** Enclosing plain (non-resource) declarations of the *root* of this chain, outermost first. */
    public val enclosing: List<KSClassDeclaration>,
    public val ownParams: List<PathParam>,
    public val path: ResourcePath,
    public val visibility: Visibility,
) {
    public val packageName: String get() = declaration.packageName.asString()

    /** Outermost resource ... this resource - the declaration chain. */
    public val chain: List<AnalyzedResource>
        get() {
            val list = mutableListOf<AnalyzedResource>()
            var current: AnalyzedResource? = this
            while (current != null) {
                list += current
                current = current.parent
            }
            return list.asReversed()
        }

    /** Every path parameter, parents-first, own last - the constructor/factory-call order. */
    public val allParams: List<PathParam> get() = parent?.allParams.orEmpty() + ownParams

    // `enclosing` only ever holds data on the ROOT of a chain (see ResourceAnalyzer) - the plain
    // declarations it's nested in, which the whole chain's generated name is prefixed with.
    public val implSimpleName: String
        get() = nomiImplName(chain.first().enclosing.map { it.simpleName.asString() } + chain.map { it.declaration.simpleName.asString() })

    public val factoryName: String get() = declaration.simpleName.asString()

    public val containingFile: KSFile? get() = declaration.containingFile
}
