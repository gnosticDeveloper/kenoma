package raum.exception;

import common.exception.KenomaServerException;
import org.springframework.http.HttpStatus;

public class PricingConfigurationException extends KenomaServerException {

    public PricingConfigurationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
