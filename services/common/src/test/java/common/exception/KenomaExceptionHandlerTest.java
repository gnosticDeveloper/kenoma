package common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class KenomaExceptionHandlerTest {

    private final KenomaExceptionHandler handler = new KenomaExceptionHandler();

    @Test
    void handleKenomaException_badRequest_returns400() {
        var response = handler.handleKenomaException(new BadRequestException("invalid input"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).satisfies(body -> {
            assertThat(body.status()).isEqualTo(400);
            assertThat(body.error()).isEqualTo("BAD_REQUEST");
            assertThat(body.message()).isEqualTo("invalid input");
        });
    }

    @Test
    void handleKenomaException_unauthorized_returns401() {
        var response = handler.handleKenomaException(new UnauthorizedException("not allowed"));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void handleKenomaException_forbidden_returns403() {
        var response = handler.handleKenomaException(new ForbiddenException("access denied"));
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().error()).isEqualTo("FORBIDDEN");
    }

    @Test
    void handleKenomaException_notFound_returns404() {
        var response = handler.handleKenomaException(new NotFoundException("not here"));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("not here");
    }

    @Test
    void handleKenomaException_conflict_returns409() {
        var response = handler.handleKenomaException(new ConflictException("already exists"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo("CONFLICT");
    }

    @Test
    void handleResponseStatusException_withReason_usesReason() {
        var ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "resource gone");
        var response = handler.handleResponseStatusException(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
        assertThat(response.getBody().message()).isEqualTo("resource gone");
    }

    @Test
    void handleResponseStatusException_withoutReason_fallsBackToMessage() {
        var ex = new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var response = handler.handleResponseStatusException(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().message()).isNotBlank();
    }

    @Test
    void handleResponseStatusException_nonStandardCode_usesCodeAsString() {
        var ex = new ResponseStatusException(HttpStatusCode.valueOf(999), "custom error");
        var response = handler.handleResponseStatusException(ex);
        assertThat(response.getStatusCode().value()).isEqualTo(999);
        assertThat(response.getBody().error()).isEqualTo("999");
        assertThat(response.getBody().message()).isEqualTo("custom error");
    }

    @Test
    void handleGenericException_alwaysReturns500_withGenericMessage() {
        var response = handler.handleGenericException(new RuntimeException("something exploded"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("INTERNAL_SERVER_ERROR");
        assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    }
}
