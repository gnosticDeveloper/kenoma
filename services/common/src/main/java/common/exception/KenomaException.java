package common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public abstract class KenomaException extends RuntimeException {

    private final HttpStatus status;

    protected KenomaException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

}
