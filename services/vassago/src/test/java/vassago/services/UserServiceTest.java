package vassago.services;

import common.utils.RolesUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import vassago.db.VassagoDbService;
import vassago.dto.CreateUserResponseDTO;
import vassago.dto.UserRequestDTO;
import vassago.security.VassagoAuthentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private VassagoDbService vassagoDbService;

    @Mock
    private PasswordEncoder encoder;

    @InjectMocks
    private UserService userService;

    private static final UUID ORG_ID  = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    private static final Map<String, List<String>> ADMIN_ROLES = Map.of(
            "vassago", List.of("ADMIN", "USER")
    );

    private static final Map<String, List<String>> USER_ROLES = Map.of(
            "vassago", List.of("USER")
    );

    // -------------------------------------------------------------------------
    // createUser
    // -------------------------------------------------------------------------

    @Test
    void createUser_callerCanAssignSubsetOfOwnRoles() {
        UserRequestDTO dto = validRequest(USER_ROLES);
        DatabaseClient client = mockClientReturning(rowFor(USER_ID, USER_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.encode(anyString())).thenReturn("hashed");

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(ADMIN_ROLES))
                )
                .assertNext(response -> {
                    assertThat(response).isInstanceOf(CreateUserResponseDTO.class);
                    assertThat(response.getName()).isEqualTo("Jane");
                    assertThat(((CreateUserResponseDTO) response).getTemporaryPassword()).isNotBlank();
                })
                .verifyComplete();
    }

    @Test
    void createUser_callerCanAssignExactOwnRoles() {
        UserRequestDTO dto = validRequest(ADMIN_ROLES);
        DatabaseClient client = mockClientReturning(rowFor(USER_ID, ADMIN_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.encode(anyString())).thenReturn("hashed");

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(ADMIN_ROLES))
                )
                .assertNext(response -> assertThat(response.getRoles()).isEqualTo(ADMIN_ROLES))
                .verifyComplete();
    }

    @Test
    void createUser_rejects_rolesCallerDoesNotHold() {
        UserRequestDTO dto = validRequest(ADMIN_ROLES);

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(USER_ROLES))
                )
                .expectErrorMatches(e ->
                        e instanceof org.springframework.web.server.ResponseStatusException &&
                                ((org.springframework.web.server.ResponseStatusException) e)
                                        .getStatusCode().value() == 403)
                .verify();

        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void createUser_rejects_roleForServiceCallerHasNoAccessTo() {
        Map<String, List<String>> foreignRoles = Map.of("inventory", List.of("MANAGER"));
        UserRequestDTO dto = validRequest(foreignRoles);

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(USER_ROLES))
                )
                .expectErrorMatches(e ->
                        e instanceof org.springframework.web.server.ResponseStatusException &&
                                ((org.springframework.web.server.ResponseStatusException) e)
                                        .getStatusCode().value() == 403)
                .verify();

        verifyNoInteractions(vassagoDbService);
    }

    // -------------------------------------------------------------------------
    // getUserById
    // -------------------------------------------------------------------------

    @Test
    void getUserById_delegatesToDbService() {
        DatabaseClient client = mockClientReturning(rowFor(USER_ID, USER_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(userService.getUserById(ORG_ID, USER_ID))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID))
                .verifyComplete();

        verify(vassagoDbService, times(1)).getClient(ORG_ID);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UserRequestDTO validRequest(Map<String, List<String>> roles) {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setOrgId(ORG_ID);
        dto.setName("Jane");
        dto.setLastName("Doe");
        dto.setEmail("jane@example.com");
        dto.setUsername("janedoe");
        dto.setRoles(roles);
        return dto;
    }

    private Map<String, Object> rowFor(UUID id, Map<String, List<String>> roles) {
        return Map.of(
                "id", id,
                "name", "Jane",
                "last_name", "Doe",
                "email", "jane@example.com",
                "username", "janedoe",
                "roles", RolesUtils.serialize(roles)
        );
    }

    private reactor.util.context.Context withCaller(Map<String, List<String>> roles) {
        VassagoAuthentication auth = new VassagoAuthentication(ORG_ID, "janedoe", roles);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        return ReactiveSecurityContextHolder.withSecurityContext(Mono.just(ctx));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientReturning(Map<String, Object> row) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec execSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec fetchSpec = mock(FetchSpec.class);
        when(client.sql(anyString())).thenReturn(execSpec);
        when(execSpec.bind(anyString(), any())).thenReturn(execSpec);
        when(execSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.just(row));
        return client;
    }
}