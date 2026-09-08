package org.kodein.rest.nomi.processor.server

import org.kodein.rest.nomi.processor.common.resource.AnalyzedResource
import org.kodein.rest.nomi.processor.common.service.AnalyzedEndpoint

/**
 * One `route(){}` node in the generated function's tree. [resource] is `null` only for the
 * synthetic root, whose children sit directly in the function body. Keyed by resource *identity*
 * (a fully-qualified name), not by rendered path text, walking each endpoint's own
 * [AnalyzedResource.chain] - so a resource with no endpoint of its own but a nested one still gets
 * a node for that nested resource's sake, and two unrelated resources that happen to render the
 * same path text (SPECS.adoc § Resource paths: explicitly allowed) still get their own, separate
 * node, exactly as a resource's own `path.segments` become exactly one `route(){}` call, with a
 * nested resource's own contribution nested inside it - see SPECS.adoc § Server processor's example.
 */
public class RouteNode(public val resource: AnalyzedResource?) {
    public val children: LinkedHashMap<String, RouteNode> = LinkedHashMap()
    public val endpoints: MutableList<AnalyzedEndpoint> = mutableListOf()
}

/**
 * Folds [endpoints] into a [RouteNode] tree. Node order is each resource's first appearance across
 * [endpoints]; within one node, [RouteNode.endpoints] keep that same source order and are always
 * rendered before its children - SPECS.adoc § Server processor: "All of the HTTP method handlers of
 * a given path are siblings inside that path's `route {}` block: a handler block must never
 * contain another handler declaration."
 */
public fun buildRouteTree(endpoints: List<AnalyzedEndpoint>): RouteNode {
    val root = RouteNode(resource = null)
    for (endpoint in endpoints) {
        var node = root
        for (resource in endpoint.resource.chain) {
            val key = resource.declaration.qualifiedName?.asString() ?: resource.declaration.simpleName.asString()
            node = node.children.getOrPut(key) { RouteNode(resource) }
        }
        node.endpoints += endpoint
    }
    return root
}
