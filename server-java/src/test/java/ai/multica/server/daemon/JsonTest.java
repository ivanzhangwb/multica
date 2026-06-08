package ai.multica.server.daemon;

import com.jayway.jsonpath.JsonPath;
import org.springframework.test.web.servlet.MvcResult;

final class JsonTest {
    private JsonTest() {}

    static String read(MvcResult result, String path) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
