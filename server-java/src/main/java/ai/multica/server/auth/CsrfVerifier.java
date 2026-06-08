package ai.multica.server.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class CsrfVerifier {
    public boolean verify(HttpServletRequest request, String authToken) {
        if (HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String token = request.getHeader("X-CSRF-Token");
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) {
            return false;
        }
        try {
            byte[] nonce = HexFormat.of().parseHex(parts[0]);
            byte[] expected = HexFormat.of().parseHex(parts[1]);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(nonce), expected);
        } catch (Exception ignored) {
            return false;
        }
    }
}
