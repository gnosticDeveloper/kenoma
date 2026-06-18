package common.exception;

import org.springframework.http.HttpStatus;

public abstract class KenomaServerException extends KenomaException {

    protected KenomaServerException(HttpStatus status, String message) {
        super(status, message);
    }
}
