package common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

public class KenomaExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(KenomaExceptionHandler.class);

    @ExceptionHandler(KenomaException.class)
    public ResponseEntity<ErrorResponse> handleKenomaException(KenomaException ex) {
        HttpStatus status = ex.getStatus();
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.name(), ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        int code = ex.getStatusCode().value();
        HttpStatus status = HttpStatus.resolve(code);
        String error = status != null ? status.name() : String.valueOf(code);
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        return ResponseEntity.status(code).body(new ErrorResponse(code, error, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        logger.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(500, "INTERNAL_SERVER_ERROR", "An unexpected error occurred"));
    }
}
