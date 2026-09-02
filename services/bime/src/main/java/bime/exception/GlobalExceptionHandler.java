package bime.exception;

import common.exception.ErrorResponse;
import common.exception.KenomaExceptionHandler;
import io.r2dbc.spi.R2dbcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler extends KenomaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse(403, "FORBIDDEN", "Access denied"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKeyException(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(409, "CONFLICT", "A resource with the same unique identifier already exists"));
    }

    /**
     * A value the request supplied broke a database integrity constraint the service layer's own
     * checks didn't catch first (Postgres SQLState class 23). Client error, not a server fault → 400.
     * {@link DuplicateKeyException} is a subclass but has its own handler above, which still wins.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Request rejected on a database constraint", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "BAD_REQUEST",
                        "A field in the request is invalid or out of the allowed range"));
    }

    /**
     * The r2dbc-postgres driver reports data-exception SQL states (class 22 — string too long for
     * its column, numeric field overflow, bad date, ...) as a bad-grammar exception rather than a
     * {@link DataIntegrityViolationException}. When the request supplied an out-of-range value that
     * the service didn't bound-check, that is a 400. A genuine SQL error (any other SQL state) still
     * falls through to the generic 500.
     */
    @ExceptionHandler(BadSqlGrammarException.class)
    public ResponseEntity<ErrorResponse> handleBadSqlGrammar(BadSqlGrammarException ex) {
        Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
        String sqlState = root instanceof R2dbcException r2dbc ? r2dbc.getSqlState() : null;
        if (sqlState != null && sqlState.startsWith("22")) {
            log.warn("Request rejected on an out-of-range value (SQLState {})", sqlState);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "BAD_REQUEST",
                            "A field in the request is invalid or out of the allowed range"));
        }
        log.error("Unhandled SQL error", ex);
        return ResponseEntity.internalServerError()
                .body(new ErrorResponse(500, "INTERNAL_SERVER_ERROR", "An unexpected error occurred"));
    }
}
