package common.exception;

import org.springframework.http.HttpStatus;

public class BadRequestException extends KenomaClientException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, message);
    }
}
