package common.exception;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends KenomaClientException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, message);
    }
}
