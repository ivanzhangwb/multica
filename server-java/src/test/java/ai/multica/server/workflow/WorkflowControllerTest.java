package ai.multica.server.workflow;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class WorkflowControllerTest {
    private static final String WORKSPACE = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "22222222-2222-2222-2222-222222222222";
    private static final String AGENT = "33333333-3333-3333-3333-333333333333";
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Test
    void healthEndpointsPreserveGoCompatibility() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
        mvc.perform(get("/healthz"))
            .andExpect(status().is(isIn(java.util.List.of(200, 503))))
            .andExpect(jsonPath("$.status", isIn(java.util.List.of("ready", "not_ready"))));
        mvc.perform(get("/readyz"))
            .andExpect(status().is(isIn(java.util.List.of(200, 503))))
            .andExpect(jsonPath("$.status", isIn(java.util.List.of("ready", "not_ready"))));
    }

    @Test
    void issueLifecycleUsesGoJsonShape() throws Exception {
        String projectId = createProject("Java migration");
        String labelId = createLabel("api", "3B82F6");
        String attachmentId = "44444444-4444-4444-4444-444444444444";

        MvcResult created = mvc.perform(post("/api/issues")
                .param("workspace_id", WORKSPACE)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "title", "Port issue API",
                    "description", "contract slice",
                    "project_id", projectId,
                    "attachment_ids", java.util.List.of(attachmentId)
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.workspace_id").value(WORKSPACE))
            .andExpect(jsonPath("$.identifier", startsWith("MUL-")))
            .andExpect(jsonPath("$.creator_type").value("member"))
            .andExpect(jsonPath("$.creator_id").value(USER))
            .andExpect(jsonPath("$.metadata").isMap())
            .andExpect(jsonPath("$.attachments", hasSize(1)))
            .andExpect(jsonPath("$.attachments[0].download_url").value("/api/attachments/" + attachmentId + "/download"))
            .andReturn();

        String issueId = read(created, "id");

        mvc.perform(post("/api/issues/{id}/labels/{labelId}", issueId, labelId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(true));

        mvc.perform(put("/api/issues/{id}/metadata/{key}", issueId, "pipeline_status")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("value", "green"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.metadata.pipeline_status").value("green"));

        mvc.perform(get("/api/issues/{id}", issueId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.labels", hasSize(1)))
            .andExpect(jsonPath("$.labels[0].name").value("api"))
            .andExpect(jsonPath("$.metadata.pipeline_status").value("green"))
            .andExpect(jsonPath("$.project_id").value(projectId));

        mvc.perform(put("/api/issues/{id}", issueId)
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", "done", "priority", "high"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("done"))
            .andExpect(jsonPath("$.priority").value("high"));

        mvc.perform(get("/api/projects/{id}", projectId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.issue_count").value(1))
            .andExpect(jsonPath("$.done_count").value(1))
            .andExpect(jsonPath("$.resource_count").value(0));

        mvc.perform(delete("/api/issues/{id}", issueId).param("workspace_id", WORKSPACE))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/issues/{id}", issueId).param("workspace_id", WORKSPACE))
            .andExpect(status().isNotFound());
    }

    @Test
    void commentSubscribersAndAttachmentPathsMatchGoSurface() throws Exception {
        String issueId = createIssue("Comment route");
        String attachmentId = "55555555-5555-5555-5555-555555555555";

        MvcResult created = mvc.perform(post("/api/issues/{id}/comments", issueId)
                .param("workspace_id", WORKSPACE)
                .header("X-Agent-ID", AGENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of(
                    "content", "implemented the Java comment route",
                    "attachment_ids", java.util.List.of(attachmentId)
                ))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.issue_id").value(issueId))
            .andExpect(jsonPath("$.author_type").value("agent"))
            .andExpect(jsonPath("$.author_id").value(AGENT))
            .andExpect(jsonPath("$.reactions", hasSize(0)))
            .andExpect(jsonPath("$.attachments", hasSize(1)))
            .andReturn();

        String commentId = read(created, "id");

        mvc.perform(get("/api/issues/{id}/comments", issueId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(commentId));

        mvc.perform(put("/api/comments/{id}", commentId)
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("content", "edited"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").value("edited"));

        mvc.perform(post("/api/issues/{id}/subscribers", issueId)
                .param("workspace_id", WORKSPACE)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("user_type", "member", "user_id", USER))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subscribed").value(true));

        mvc.perform(get("/api/issues/{id}/subscribers", issueId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].user_type").value("member"))
            .andExpect(jsonPath("$[0].reason").value("manual"));

        mvc.perform(get("/api/attachments/{id}", attachmentId).param("workspace_id", WORKSPACE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.comment_id").value(commentId))
            .andExpect(jsonPath("$.download_url").value("/api/attachments/" + attachmentId + "/download"));
    }

    @Test
    void validationMatchesWorkflowContract() throws Exception {
        String issueId = createIssue("Validation route");

        mvc.perform(post("/api/labels")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "bad", "color", "javascript:alert(1)"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("color must be a 6-digit hex value like #3b82f6"));

        mvc.perform(put("/api/issues/{id}/metadata/{key}", issueId, "bad key")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("value", "nope"))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("key must match ^[a-zA-Z_][a-zA-Z0-9_.-]{0,63}$"));

        mvc.perform(put("/api/issues/{id}/metadata/{key}", issueId, "nested")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":{\"not\":\"primitive\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("value must be a primitive: string, number, or bool"));
    }

    private String createIssue(String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/issues")
                .param("workspace_id", WORKSPACE)
                .header("X-User-ID", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn();
        return read(result, "id");
    }

    private String createProject(String title) throws Exception {
        MvcResult result = mvc.perform(post("/api/projects")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn();
        return read(result, "id");
    }

    private String createLabel(String name, String color) throws Exception {
        MvcResult result = mvc.perform(post("/api/labels")
                .param("workspace_id", WORKSPACE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", name, "color", color))))
            .andExpect(status().isCreated())
            .andReturn();
        return read(result, "id");
    }

    private String read(MvcResult result, String field) throws Exception {
        JsonNode root = mapper.readTree(result.getResponse().getContentAsByteArray());
        return root.get(field).asText();
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }
}
