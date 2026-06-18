package raum.exception;

import common.exception.KenomaExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler extends KenomaExceptionHandler {}
