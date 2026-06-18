package common.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedException extends KenomaClientException {

    public UnauthorizedException(String message) {
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
