package ai.multica.server.daemon;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
class DaemonControllerTest {
    @Autowired
    MockMvc mvc;

    @Autowired
    DaemonRepository repository;

    @Test
    void registerHeartbeatAndWorkspaceReposUseDaemonContractShape() throws Exception {
        String workspaceId = "ws-daemon-contract";
        DaemonTestFixtures.seedWorkspaceResources(repository, workspaceId,
                List.of(new DaemonModels.RepoData("git@github.com:multica-ai/multica.git", "main repo")),
                Map.of("timezone", "Asia/Shanghai"));

        MvcResult register = mvc.perform(post("/api/daemon/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspace_id": "ws-daemon-contract",
                                  "daemon_id": "daemon-a",
                                  "cli_version": "0.1.0",
                                  "runtimes": [{"name": "Codex", "type": "codex", "version": "1.0", "status": "online"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runtimes", hasSize(1)))
                .andExpect(jsonPath("$.runtimes[0].provider").value("codex"))
                .andExpect(jsonPath("$.repos[0].url").value("git@github.com:multica-ai/multica.git"))
                .andExpect(jsonPath("$.repos_version").isString())
                .andReturn();

        String runtimeId = JsonTest.read(register, "$.runtimes[0].id");
        mvc.perform(post("/api/daemon/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runtime_id\":\"" + runtimeId + "\",\"supports_batch_import\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.runtime_gone").value(false));

        mvc.perform(get("/api/daemon/workspaces/{workspaceId}/repos", workspaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspace_id").value(workspaceId))
                .andExpect(jsonPath("$.repos", hasSize(1)))
                .andExpect(jsonPath("$.settings.timezone").value("Asia/Shanghai"));
    }

    @Test
    void taskClaimLifecycleMessagesUsageAndTerminalCallbacksMatchDaemonClient() throws Exception {
        String runtimeId = registerRuntime("ws-task-contract", "daemon-task");
        TaskRecord task = DaemonTestFixtures.seedQueuedTask(repository, runtimeId, "ws-task-contract", "issue-1", "agent-1");

        mvc.perform(post("/api/daemon/runtimes/{runtimeId}/tasks/claim", runtimeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(task.id()))
                .andExpect(jsonPath("$.task.agent_id").value("agent-1"))
                .andExpect(jsonPath("$.task.runtime_id").value(runtimeId))
                .andExpect(jsonPath("$.task.issue_id").value("issue-1"))
                .andExpect(jsonPath("$.task.workspace_id").value("ws-task-contract"))
                .andExpect(jsonPath("$.task.auth_token").value("mat_test"));

        mvc.perform(post("/api/daemon/tasks/{taskId}/start", task.id()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/daemon/tasks/{taskId}/status", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));

        mvc.perform(post("/api/daemon/tasks/{taskId}/progress", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"working\",\"step\":1,\"total\":3}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/daemon/tasks/{taskId}/session", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"sess-1\",\"work_dir\":\"/tmp/work\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/daemon/tasks/{taskId}/usage", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"usage":[{"provider":"codex","model":"gpt-5","input_tokens":10,"output_tokens":20,
                                "cache_read_tokens":3,"cache_write_tokens":4}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post("/api/daemon/tasks/{taskId}/messages", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"seq":1,"type":"tool_use","tool":"exec_command",
                                "input":{"cmd":"git status"}},{"seq":2,"type":"tool_result","output":"clean"}]}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/daemon/tasks/{taskId}/messages", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tool").value("exec_command"))
                .andExpect(jsonPath("$[1].output").value("clean"));

        mvc.perform(post("/api/daemon/tasks/{taskId}/complete", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"output":"done","branch_name":"agent/task","session_id":"sess-2","work_dir":"/tmp/final"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(get("/api/daemon/tasks/{taskId}/status", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"));
        mvc.perform(get("/api/daemon/tasks/{taskId}/gc-check", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.completed_at").isString());
    }

    @Test
    void waitingLocalDirectoryAndFailPathsAreCovered() throws Exception {
        String runtimeId = registerRuntime("ws-wait-local", "daemon-wait");
        TaskRecord task = DaemonTestFixtures.seedQueuedTask(repository, runtimeId, "ws-wait-local", "issue-2", "agent-2");

        mvc.perform(post("/api/daemon/runtimes/{runtimeId}/tasks/claim", runtimeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/daemon/tasks/{taskId}/wait-local-directory", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"/Users/dev/project\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/daemon/tasks/{taskId}/status", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("waiting_local_directory"));
        mvc.perform(post("/api/daemon/tasks/{taskId}/fail", task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"error\":\"agent failed\",\"failure_reason\":\"agent_error\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/daemon/tasks/{taskId}/status", task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"));
    }

    private String registerRuntime(String workspaceId, String daemonId) throws Exception {
        MvcResult result = mvc.perform(post("/api/daemon/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspace_id": "%s",
                                  "daemon_id": "%s",
                                  "runtimes": [{"name": "Codex", "type": "codex", "status": "online"}]
                                }
                                """.formatted(workspaceId, daemonId)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonTest.read(result, "$.runtimes[0].id");
    }
}
