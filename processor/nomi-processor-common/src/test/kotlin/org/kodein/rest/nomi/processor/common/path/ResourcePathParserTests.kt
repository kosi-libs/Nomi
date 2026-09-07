package org.kodein.rest.nomi.processor.common.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ResourcePathParserTests {

    private fun ok(result: PathResult): ResourcePath = (assertIs<PathResult.Ok>(result)).path
    private fun errors(result: PathResult): List<PathError> = (assertIs<PathResult.Failed>(result)).errors

    // --- derivation ------------------------------------------------------------------------------

    @Test
    fun `derives the lowercased simple name as a single literal segment`() {
        val path = ok(derivedResourcePath("Articles", emptyList()))
        assertEquals("articles", path.toString())
    }

    @Test
    fun `lowercases only the first letter of the simple name`() {
        val path = ok(derivedResourcePath("ArticleComments", emptyList()))
        assertEquals("articleComments", path.toString())
    }

    @Test
    fun `appends one bare placeholder segment per own parameter, in order`() {
        val path = ok(derivedResourcePath("Post", listOf("userId", "postId")))
        assertEquals("post/{userId}/{postId}", path.toString())
        assertEquals(listOf("userId", "postId"), path.parameterNames)
    }

    @Test
    fun `keeps dashes and underscores in the simple name`() {
        assertEquals("my-name", ok(derivedResourcePath("my-name", emptyList())).toString())
        assertEquals("my_name", ok(derivedResourcePath("my_name", emptyList())).toString())
    }

    @Test
    fun `rejects a simple name unusable as a path segment`() {
        for (bad in listOf("Article\$s", "Articlé", "1Article", "")) {
            val e = errors(derivedResourcePath(bad, emptyList()))
            assertEquals(listOf(PathError.UndeclarableSimpleName(bad)), e)
        }
    }

    @Test
    fun `a derived path always has at least one segment`() {
        val path = ok(derivedResourcePath("X", emptyList()))
        assertTrue(path.segments.isNotEmpty())
    }

    // --- explicit paths: happy cases --------------------------------------------------------------

    @Test
    fun `accepts a plain literal path`() {
        assertEquals(ResourcePath(listOf(PathSegment("u", null, ""))), ok(parseResourcePath("u", emptyList())))
    }

    @Test
    fun `accepts a multi-segment literal path`() {
        val path = ok(parseResourcePath("a/b/c", emptyList()))
        assertEquals("a/b/c", path.toString())
    }

    @Test
    fun `accepts a bare placeholder segment`() {
        val path = ok(parseResourcePath("{id}", listOf("id")))
        assertEquals(listOf("id"), path.parameterNames)
    }

    @Test
    fun `accepts a literal prefix before a placeholder`() {
        val path = ok(parseResourcePath("p:{postId}", listOf("postId")))
        assertEquals("p:{postId}", path.toString())
    }

    @Test
    fun `accepts a literal suffix after a placeholder`() {
        val path = ok(parseResourcePath("{id}.json", listOf("id")))
        assertEquals("{id}.json", path.toString())
    }

    @Test
    fun `accepts a bare colon with no placeholder`() {
        ok(parseResourcePath("p:q", emptyList()))
    }

    // --- explicit paths: structural errors ----------------------------------------------------------

    @Test
    fun `rejects a blank path`() {
        assertEquals(listOf(PathError.Blank), errors(parseResourcePath("   ", emptyList())))
    }

    @Test
    fun `rejects a leading slash`() {
        assertTrue(PathError.LeadingSlash in errors(parseResourcePath("/a", emptyList())))
    }

    @Test
    fun `rejects a trailing slash`() {
        assertTrue(PathError.TrailingSlash in errors(parseResourcePath("a/", emptyList())))
    }

    @Test
    fun `rejects an empty segment`() {
        assertTrue(PathError.EmptySegment in errors(parseResourcePath("a//b", emptyList())))
    }

    @Test
    fun `rejects each forbidden character and a percent sign`() {
        for (c in listOf('?', '#', '&', '=', ' ')) {
            val e = errors(parseResourcePath("a${c}b", emptyList()))
            assertTrue(PathError.ForbiddenCharacter(c) in e, "expected '$c' to be forbidden, got $e")
        }
        assertTrue(PathError.PercentSign in errors(parseResourcePath("a%20b", emptyList())))
    }

    @Test
    fun `rejects a scheme and authority while accepting a bare colon`() {
        assertTrue(PathError.SchemeOrAuthority in errors(parseResourcePath("http://x/y", emptyList())))
    }

    // --- explicit paths: placeholder syntax -------------------------------------------------------

    @Test
    fun `rejects the ktor wildcard segment`() {
        assertTrue(errors(parseResourcePath("*", emptyList())).any { it is PathError.KtorSyntax })
    }

    @Test
    fun `rejects the ktor tailcard and optional syntaxes`() {
        for (bad in listOf("{...}", "{name...}", "{name?}")) {
            val e = errors(parseResourcePath(bad, listOf("name")))
            assertTrue(e.any { it is PathError.KtorSyntax }, "expected '$bad' to be rejected as ktor syntax, got $e")
        }
    }

    @Test
    fun `rejects two placeholders in one segment`() {
        assertTrue(errors(parseResourcePath("{a}-{b}", listOf("a", "b"))).any { it is PathError.MultiplePlaceholders })
    }

    @Test
    fun `rejects a nested placeholder`() {
        assertTrue(errors(parseResourcePath("{{a}}", listOf("a"))).any { it is PathError.NestedPlaceholder })
    }

    @Test
    fun `rejects an unbalanced placeholder`() {
        assertTrue(errors(parseResourcePath("{id", emptyList())).any { it is PathError.UnbalancedPlaceholder })
        assertTrue(errors(parseResourcePath("id}", emptyList())).any { it is PathError.UnbalancedPlaceholder })
    }

    @Test
    fun `rejects an empty placeholder`() {
        assertTrue(errors(parseResourcePath("{}", emptyList())).any { it is PathError.EmptyPlaceholderName })
    }

    // --- explicit paths: parameter cross-checks ---------------------------------------------------

    @Test
    fun `rejects a placeholder naming an unknown or inherited parameter`() {
        val e = errors(parseResourcePath("{userId}", emptyList()))
        assertEquals(listOf(PathError.UnknownParameter("userId")), e)
    }

    @Test
    fun `rejects a missing own parameter`() {
        val e = errors(parseResourcePath("fixed", listOf("id")))
        assertEquals(listOf(PathError.MissingParameter("id")), e)
    }

    @Test
    fun `rejects a repeated placeholder`() {
        val e = errors(parseResourcePath("{id}/{id}", listOf("id")))
        assertEquals(listOf(PathError.RepeatedParameter("id")), e)
    }

    @Test
    fun `reports several errors from one path in a single result`() {
        val e = errors(parseResourcePath("/a//{id}?", listOf("other")))
        assertTrue(PathError.LeadingSlash in e)
        assertTrue(PathError.EmptySegment in e)
        assertTrue(PathError.ForbiddenCharacter('?') in e)
        assertTrue(PathError.UnknownParameter("id") in e)
        assertTrue(PathError.MissingParameter("other") in e)
    }
}
