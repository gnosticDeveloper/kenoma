package common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class KenomaServerExceptionTest {

    static class ConcreteServerException extends KenomaServerException {
        ConcreteServerException(String message) {
            super(HttpStatus.INTERNAL_SERVER_ERROR, message);
        }
    }

    @Test
    void constructor_setsStatusAndMessage() {
        var ex = new ConcreteServerException("something went wrong on the server");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ex.getMessage()).isEqualTo("something went wrong on the server");
    }
}
