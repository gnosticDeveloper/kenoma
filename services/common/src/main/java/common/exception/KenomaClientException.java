package common.exception;

import org.springframework.http.HttpStatus;

public abstract class KenomaClientException extends KenomaException {

    protected KenomaClientException(HttpStatus status, String message) {
        super(status, message);
    }
}
