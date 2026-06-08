package ai.multica.server.common;

import ai.multica.server.workflow.NotFoundException;
import ai.multica.server.workflow.ValidationException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({
        ValidationException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<Map<String, String>> badRequest(Exception exception) {
        String message = exception.getMessage() == null ? "invalid request body" : exception.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
