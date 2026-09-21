package org.kodein.rest.nomi.processor.common.resource

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedNameTests {

    @Test
    fun `concatenates the enclosing chain with no separator, prefixed Nomi`() {
        assertEquals("NomiArticlesNew", nomiImplName(listOf("Articles", "New")))
        assertEquals("NomiArticleComments", nomiImplName(listOf("Article", "Comments")))
    }

    @Test
    fun `a single name still gets the Nomi prefix`() {
        assertEquals("NomiUsers", nomiImplName(listOf("Users")))
    }

    @Test
    fun `includes plain enclosing declarations, not just resource interfaces`() {
        // object Api { @Endpoint.Resource interface Users } -> NomiApiUsers, per the
        // Generated-name-collisions reading (see the plan's naming decision).
        assertEquals("NomiApiUsers", nomiImplName(listOf("Api", "Users")))
    }

    @Test
    fun `derives the generated file name from the source file name`() {
        assertEquals("NomiEndpointsResources", generatedResourcesFileName("endpoints.kt"))
    }

    @Test
    fun `a differently-cased source file name produces the same generated name`() {
        // This is exactly the collision RoundChecks must catch: endpoints.kt and Endpoints.kt.
        assertEquals(
            generatedResourcesFileName("endpoints.kt"),
            generatedResourcesFileName("Endpoints.kt"),
        )
    }

    @Test
    fun `sanitizes non-identifier characters out of the source file name`() {
        assertEquals("NomiMyendpointsResources", generatedResourcesFileName("my-endpoints.kt"))
    }

    @Test
    fun `multipartBuilderName prefixes the part type's simple names with multipart`() {
        assertEquals("multipartToolsPostData", multipartBuilderName(listOf("ToolsPostData")))
    }

    @Test
    fun `multipartBuilderName concatenates enclosing names outermost first`() {
        assertEquals("multipartOuterUpload", multipartBuilderName(listOf("Outer", "Upload")))
    }

    @Test
    fun `generatedMultipartFileName wraps the object name`() {
        assertEquals("NomiAPIServicesMultipart", generatedMultipartFileName("APIServices"))
    }
}
