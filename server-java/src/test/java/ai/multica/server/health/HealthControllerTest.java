package ai.multica.server.health;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
    @Test
    void healthReturnsOk() {
        HealthController controller = new HealthController(new JdbcTemplate());

        Map<String, String> body = controller.health();

        assertThat(body.get("status")).isEqualTo("ok");
        assertThat(body.get("timestamp")).isNotBlank();
    }

    @Test
    void readyChecksDatabase() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        HealthController controller = new HealthController(jdbc);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ready");
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void readyReturnsUnavailableWhenDatabaseFails() {
        JdbcTemplate jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("db down"));
        HealthController controller = new HealthController(jdbc);

        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).containsEntry("status", "not_ready");
    }
}
