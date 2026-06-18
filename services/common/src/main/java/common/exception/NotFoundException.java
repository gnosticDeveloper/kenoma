package common.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends KenomaClientException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, message);
    }
}
