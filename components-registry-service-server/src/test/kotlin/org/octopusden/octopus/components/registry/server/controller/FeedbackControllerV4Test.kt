package org.octopusden.octopus.components.registry.server.controller

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.octopusden.cloud.commons.security.client.AuthServerClient
import org.octopusden.octopus.components.registry.server.ComponentRegistryServiceApplication
import org.octopusden.octopus.components.registry.server.repository.FeedbackRepository
import org.octopusden.octopus.components.registry.server.support.adminJwt
import org.octopusden.octopus.components.registry.server.support.editorJwt
import org.octopusden.octopus.components.registry.server.support.viewerJwt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * SYS-062: feedback submit (any authenticated user) + admin triage (IMPORT_DATA).
 * Exercises the full security stack and the attachment magic-byte validation.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ComponentRegistryServiceApplication::class],
)
@ActiveProfiles("common", "ft-db")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(180)
@Tag("integration")
class FeedbackControllerV4Test {
    @MockBean
    @Suppress("UnusedPrivateProperty")
    private lateinit var authServerClient: AuthServerClient

    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var repository: FeedbackRepository

    init {
        // The `common` profile resolves components-registry.groovy-path against this dir.
        val testResourcesPath =
            java.nio.file.Paths.get(FeedbackControllerV4Test::class.java.getResource("/expected-data")!!.toURI()).parent
        System.setProperty("COMPONENTS_REGISTRY_SERVICE_TEST_DATA_DIR", testResourcesPath.toString())
    }

    @BeforeEach
    fun clean() {
        repository.deleteAll()
    }

    @Test
    @DisplayName("SYS-062 anonymous submit is rejected (401)")
    fun `SYS-062 submit requires authentication`() {
        mvc
            .perform(post("/rest/api/4/feedback").contentType(MediaType.APPLICATION_JSON).content(body(TYPE_BUG, "boom")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @DisplayName("SYS-062 authenticated submit records the JWT username as submitter")
    fun `SYS-062 submit stores submitter from jwt`() {
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(TYPE_BUG, "something broke")),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.type").value("BUG"))
            .andExpect(jsonPath("$.status").value("NEW"))
            .andExpect(jsonPath("$.submittedBy").value("bob"))
    }

    @Test
    @DisplayName("SYS-062 submit accepts a valid PNG screenshot")
    fun `SYS-062 submit stores a valid png attachment`() {
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithAttachment(TYPE_BUG, "see screenshot", "shot.png", PNG_1x1_BASE64)),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.attachments.length()").value(1))
            .andExpect(jsonPath("$.attachments[0].contentType").value("image/png"))
    }

    @Test
    @DisplayName("SYS-062 submit rejects a non-image attachment (400)")
    fun `SYS-062 submit rejects non image attachment`() {
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithAttachment(TYPE_BUG, "bad file", "notes.txt", NON_IMAGE_BASE64)),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-062 submit rejects more than the allowed attachment count (400)")
    fun `SYS-062 submit rejects too many attachments`() {
        val four = (1..4).joinToString(",") { attachmentJson("s$it.png", PNG_1x1_BASE64) }
        val payload = """{"type":"$TYPE_BUG","message":"lots","attachments":[$four]}"""
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload),
            ).andExpect(status().isBadRequest)
    }

    @Test
    @DisplayName("SYS-062 admin list requires IMPORT_DATA — viewer/editor forbidden, admin ok")
    fun `SYS-062 admin list is import gated`() {
        submitAs(editorJwt(), TYPE_IDEA, "nice to have")

        mvc.perform(get("/rest/api/4/admin/feedback").with(viewerJwt())).andExpect(status().isForbidden)
        mvc.perform(get("/rest/api/4/admin/feedback").with(editorJwt())).andExpect(status().isForbidden)
        mvc
            .perform(get("/rest/api/4/admin/feedback").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].message").value("nice to have"))
    }

    @Test
    @DisplayName("SYS-062 admin can fetch details and screenshot bytes with nosniff + inline disposition")
    fun `SYS-062 admin fetches attachment bytes`() {
        submitWithAttachmentAs(editorJwt(), "shot.png", PNG_1x1_BASE64)
        val feedbackId = adminFirstId()
        val attachmentId = adminFirstAttachmentId(feedbackId)

        mvc
            .perform(get("/rest/api/4/admin/feedback/$feedbackId/attachments/$attachmentId").with(adminJwt()))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_PNG))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
    }

    @Test
    @DisplayName("SYS-062 fetching an attachment under the wrong feedback id is 404")
    fun `SYS-062 attachment scoped to its feedback`() {
        submitWithAttachmentAs(editorJwt(), "shot.png", PNG_1x1_BASE64)
        val feedbackId = adminFirstId()
        val attachmentId = adminFirstAttachmentId(feedbackId)

        mvc
            .perform(get("/rest/api/4/admin/feedback/${feedbackId + 999}/attachments/$attachmentId").with(adminJwt()))
            .andExpect(status().isNotFound)
    }

    @Test
    @DisplayName("SYS-062 admin status change stamps updated_by and requires IMPORT_DATA")
    fun `SYS-062 admin updates status`() {
        submitAs(editorJwt(), TYPE_BUG, "please fix")
        val feedbackId = adminFirstId()

        mvc
            .perform(
                put("/rest/api/4/admin/feedback/$feedbackId/status")
                    .with(editorJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"status":"IN_PROGRESS"}"""),
            ).andExpect(status().isForbidden)

        mvc
            .perform(
                put("/rest/api/4/admin/feedback/$feedbackId/status")
                    .with(adminJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"status":"RESOLVED"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESOLVED"))
            .andExpect(jsonPath("$.updatedBy").value("alice"))
    }

    // ---- helpers ------------------------------------------------------------

    private fun body(
        type: String,
        message: String,
    ) = """{"type":"$type","message":"$message"}"""

    private fun attachmentJson(
        filename: String,
        dataBase64: String,
    ) = """{"filename":"$filename","contentType":"image/png","dataBase64":"$dataBase64"}"""

    private fun bodyWithAttachment(
        type: String,
        message: String,
        filename: String,
        dataBase64: String,
    ) = """{"type":"$type","message":"$message","attachments":[${attachmentJson(filename, dataBase64)}]}"""

    private fun submitAs(
        jwt: org.springframework.test.web.servlet.request.RequestPostProcessor,
        type: String,
        message: String,
    ) {
        mvc
            .perform(post("/rest/api/4/feedback").with(jwt).contentType(MediaType.APPLICATION_JSON).content(body(type, message)))
            .andExpect(status().isCreated)
    }

    private fun submitWithAttachmentAs(
        jwt: org.springframework.test.web.servlet.request.RequestPostProcessor,
        filename: String,
        dataBase64: String,
    ) {
        mvc
            .perform(
                post("/rest/api/4/feedback")
                    .with(jwt)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithAttachment(TYPE_BUG, "with shot", filename, dataBase64)),
            ).andExpect(status().isCreated)
    }

    private fun adminFirstId(): Long =
        mvc
            .perform(get("/rest/api/4/admin/feedback").with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
            .let { Regex(""""id":(\d+)""").find(it)!!.groupValues[1].toLong() }

    private fun adminFirstAttachmentId(feedbackId: Long): Long =
        mvc
            .perform(get("/rest/api/4/admin/feedback/$feedbackId").with(adminJwt()))
            .andExpect(status().isOk)
            .andReturn()
            .response.contentAsString
            .let { Regex(""""attachments":\[\{"id":(\d+)""").find(it)!!.groupValues[1].toLong() }

    companion object {
        private const val TYPE_BUG = "BUG"
        private const val TYPE_IDEA = "IDEA"

        // 1x1 transparent PNG (magic bytes 89 50 4E 47 …).
        private const val PNG_1x1_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

        // base64 of "hello world" — valid base64, but not a PNG/JPEG payload.
        private const val NON_IMAGE_BASE64 = "aGVsbG8gd29ybGQ="
    }
}
