package vassago.services;

import common.utils.RolesUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import vassago.security.VassagoRole;
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

    private static final UUID ORG_ID     = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(vassagoDbService, encoder, SERVICE_ID);
    }

    private static final Map<String, List<String>> ADMIN_ROLES = Map.of(
            SERVICE_ID.toString(), List.of(VassagoRole.VASSAGO_ADMIN.name(), VassagoRole.VASSAGO_USER.name())
    );
    private static final Map<String, List<String>> USER_ROLES = Map.of(
            SERVICE_ID.toString(), List.of(VassagoRole.VASSAGO_USER.name())
    );

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
        Map<String, List<String>> foreignRoles = Map.of(
                UUID.randomUUID().toString(), List.of(VassagoRole.VASSAGO_ADMIN.name())
        );
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

    @Test
    void createUser_rejects_unknownVassagoRole() {
        Map<String, List<String>> unknownRoles = Map.of(
                SERVICE_ID.toString(), List.of("VASSAGO_SUPERUSER")
        );
        UserRequestDTO dto = validRequest(unknownRoles);

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(Map.of(
                                        SERVICE_ID.toString(), List.of("VASSAGO_SUPERUSER")
                                )))
                )
                .expectErrorMatches(e ->
                        e instanceof org.springframework.web.server.ResponseStatusException &&
                                ((org.springframework.web.server.ResponseStatusException) e)
                                        .getStatusCode().value() == 400)
                .verify();
        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void getUserById_delegatesToDbService() {
        DatabaseClient client = mockClientReturning(rowFor(USER_ID, USER_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(
                        userService.getUserById(USER_ID)
                                .contextWrite(withCaller(USER_ROLES))
                )
                .assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID))
                .verifyComplete();
        verify(vassagoDbService, times(1)).getClient(ORG_ID);
    }

    @Test
    void updateUser_adminCanEditAnyUser() {
        DatabaseClient client = mockClientReturningSequential(
                Map.of("username", "janedoe"),
                rowFor(USER_ID, USER_ROLES)
        );
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(
                        userService.updateUser(USER_ID, validRequest(USER_ROLES))
                                .contextWrite(withCaller(ADMIN_ROLES))
                )
                .assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID))
                .verifyComplete();
    }

    @Test
    void updateUser_userCanEditThemselves() {
        DatabaseClient client = mockClientReturningSequential(
                Map.of("username", "janedoe"),
                rowFor(USER_ID, USER_ROLES)
        );
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(
                        userService.updateUser(USER_ID, validRequest(USER_ROLES))
                                .contextWrite(withCallerUsername("janedoe", USER_ROLES))
                )
                .assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID))
                .verifyComplete();
    }

    @Test
    void updateUser_userCannotEditOtherUsers() {
        DatabaseClient client = mockClientReturningSelectOnly(Map.of("username", "someoneelse"));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(
                        userService.updateUser(USER_ID, validRequest(USER_ROLES))
                                .contextWrite(withCallerUsername("janedoe", USER_ROLES))
                )
                .expectErrorMatches(e ->
                        e instanceof org.springframework.web.server.ResponseStatusException &&
                                ((org.springframework.web.server.ResponseStatusException) e)
                                        .getStatusCode().value() == 403)
                .verify();
    }

    @Test
    void updateUser_rejects_unknownVassagoRole() {
        Map<String, List<String>> unknownRoles = Map.of(
                SERVICE_ID.toString(), List.of("VASSAGO_SUPERUSER")
        );

        StepVerifier.create(
                        userService.updateUser(USER_ID, validRequest(unknownRoles))
                                .contextWrite(withCaller(ADMIN_ROLES))
                )
                .expectErrorMatches(e ->
                        e instanceof org.springframework.web.server.ResponseStatusException &&
                                ((org.springframework.web.server.ResponseStatusException) e)
                                        .getStatusCode().value() == 400)
                .verify();
        verifyNoInteractions(vassagoDbService);
    }

    private UserRequestDTO validRequest(Map<String, List<String>> roles) {
        UserRequestDTO dto = new UserRequestDTO();
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
        return withCallerUsername("janedoe", roles);
    }

    private reactor.util.context.Context withCallerUsername(String username, Map<String, List<String>> roles) {
        VassagoAuthentication auth = new VassagoAuthentication(ORG_ID, username, roles, SERVICE_ID);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientReturningSelectOnly(Map<String, Object> selectRow) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec execSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec fetchSpec = mock(FetchSpec.class);
        when(client.sql(anyString())).thenReturn(execSpec);
        when(execSpec.bind(anyString(), any())).thenReturn(execSpec);
        when(execSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.just(selectRow));
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientReturningSequential(Map<String, Object> selectRow,
                                                         Map<String, Object> updateRow) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec selectSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec updateSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec selectFetch = mock(FetchSpec.class);
        FetchSpec updateFetch = mock(FetchSpec.class);

        when(client.sql(anyString()))
                .thenReturn(selectSpec)
                .thenReturn(updateSpec);
        when(selectSpec.bind(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.fetch()).thenReturn(selectFetch);
        when(selectFetch.one()).thenReturn(Mono.just(selectRow));
        when(updateSpec.bind(anyString(), any())).thenReturn(updateSpec);
        when(updateSpec.fetch()).thenReturn(updateFetch);
        when(updateFetch.one()).thenReturn(Mono.just(updateRow));

        return client;
    }
}