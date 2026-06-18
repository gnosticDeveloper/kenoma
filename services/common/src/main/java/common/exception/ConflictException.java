package common.exception;

import org.springframework.http.HttpStatus;

public class ConflictException extends KenomaClientException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
