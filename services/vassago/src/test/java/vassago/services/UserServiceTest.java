package vassago.services;

import common.exception.BadRequestException;
import common.exception.ForbiddenException;
import common.exception.NotFoundException;
import common.exception.UnauthorizedException;
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
import vassago.dto.PasswordChangeRequestDTO;
import vassago.dto.UserRequestDTO;
import vassago.dto.UserResponseDTO;
import vassago.security.VassagoAuthentication;
import vassago.security.VassagoRole;

import java.time.Instant;
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
    @Mock
    private MailgunService mailgunService;

    private static final UUID ORG_ID     = UUID.randomUUID();
    private static final UUID USER_ID    = UUID.randomUUID();
    private static final UUID SERVICE_ID = UUID.randomUUID();

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(vassagoDbService, encoder, SERVICE_ID, mailgunService);
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
        DatabaseClient client = mockClientReturningSelectThenInsert(rowFor(USER_ID, USER_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.encode(anyString())).thenReturn("hashed");
        when(mailgunService.sendVerificationEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                        userService.createUser(dto)
                                .contextWrite(withCaller(ADMIN_ROLES))
                )
                .assertNext(response -> {
                    assertThat(response).isInstanceOf(UserResponseDTO.class);
                    assertThat(response.getName()).isEqualTo("Jane");
                })
                .verifyComplete();
    }

    @Test
    void createUser_callerCanAssignExactOwnRoles() {
        UserRequestDTO dto = validRequest(ADMIN_ROLES);
        DatabaseClient client = mockClientReturningSelectThenInsert(rowFor(USER_ID, ADMIN_ROLES));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.encode(anyString())).thenReturn("hashed");
        when(mailgunService.sendVerificationEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

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
                .expectErrorMatches(e -> e instanceof ForbiddenException)
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
                .expectErrorMatches(e -> e instanceof ForbiddenException)
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
                .expectErrorMatches(e -> e instanceof BadRequestException)
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
                .expectErrorMatches(e -> e instanceof ForbiddenException)
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
                .expectErrorMatches(e -> e instanceof BadRequestException)
                .verify();
        verifyNoInteractions(vassagoDbService);
    }

    // --- changePassword tests ---

    @Test
    void changePassword_happyPath() {
        String storedHash = "$2a$10$hashedOldPassword";
        DatabaseClient client = mockClientReturningPasswordChange(
                Map.of("id", USER_ID, "password", storedHash, "email", "jane@example.com")
        );
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.matches("oldPass1!", storedHash)).thenReturn(true);
        when(mailgunService.sendPasswordResetEmail(anyString(), any(UUID.class), anyString()))
                .thenReturn(Mono.empty());

        PasswordChangeRequestDTO dto = new PasswordChangeRequestDTO("oldPass1!");

        StepVerifier.create(
                        userService.changePassword(dto)
                                .contextWrite(withCallerUsername("janedoe", USER_ROLES))
                )
                .verifyComplete();
    }

    @Test
    void changePassword_wrongOldPassword_returns401() {
        String storedHash = "$2a$10$hashedOldPassword";
        DatabaseClient client = mockClientReturning(
                Map.of("id", USER_ID, "password", storedHash, "email", "jane@example.com")
        );
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.matches("wrongPass!", storedHash)).thenReturn(false);

        PasswordChangeRequestDTO dto = new PasswordChangeRequestDTO("wrongPass!");

        StepVerifier.create(
                        userService.changePassword(dto)
                                .contextWrite(withCallerUsername("janedoe", USER_ROLES))
                )
                .expectErrorMatches(e -> e instanceof UnauthorizedException)
                .verify();
    }

    @Test
    void changePassword_unknownUser_returns404() {
        DatabaseClient client = mockClientReturningEmpty();
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        PasswordChangeRequestDTO dto = new PasswordChangeRequestDTO("oldPass1!");

        StepVerifier.create(
                        userService.changePassword(dto)
                                .contextWrite(withCallerUsername("ghost", USER_ROLES))
                )
                .expectErrorMatches(e -> e instanceof NotFoundException)
                .verify();
    }

    // --- helpers ---

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
        VassagoAuthentication auth = new VassagoAuthentication(ORG_ID, username, roles, SERVICE_ID,
                UUID.randomUUID().toString(), Instant.now().plusSeconds(300));
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
    private DatabaseClient mockClientReturningEmpty() {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec execSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec fetchSpec = mock(FetchSpec.class);
        when(client.sql(anyString())).thenReturn(execSpec);
        when(execSpec.bind(anyString(), any())).thenReturn(execSpec);
        when(execSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.one()).thenReturn(Mono.empty());
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
    private DatabaseClient mockClientReturningSequential(Map<String, Object> firstRow,
                                                         Map<String, Object> secondRow) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec firstSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec secondSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec firstFetch = mock(FetchSpec.class);
        FetchSpec secondFetch = mock(FetchSpec.class);
        when(client.sql(anyString()))
                .thenReturn(firstSpec)
                .thenReturn(secondSpec);
        when(firstSpec.bind(anyString(), any())).thenReturn(firstSpec);
        when(firstSpec.fetch()).thenReturn(firstFetch);
        when(firstFetch.one()).thenReturn(Mono.just(firstRow));
        when(secondSpec.bind(anyString(), any())).thenReturn(secondSpec);
        when(secondSpec.fetch()).thenReturn(secondFetch);
        when(secondFetch.one()).thenReturn(Mono.just(secondRow));
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientReturningSelectThenInsert(Map<String, Object> selectRow) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec selectSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec insertSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec selectFetch = mock(FetchSpec.class);
        FetchSpec insertFetch = mock(FetchSpec.class);
        when(client.sql(anyString()))
                .thenReturn(selectSpec)
                .thenReturn(insertSpec);
        when(selectSpec.bind(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.fetch()).thenReturn(selectFetch);
        when(selectFetch.one()).thenReturn(Mono.just(selectRow));
        when(insertSpec.bind(anyString(), any())).thenReturn(insertSpec);
        when(insertSpec.fetch()).thenReturn(insertFetch);
        when(insertFetch.rowsUpdated()).thenReturn(Mono.just(1L));
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DatabaseClient mockClientReturningPasswordChange(Map<String, Object> selectRow) {
        DatabaseClient client = mock(DatabaseClient.class);
        DatabaseClient.GenericExecuteSpec selectSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        DatabaseClient.GenericExecuteSpec insertSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        FetchSpec selectFetch = mock(FetchSpec.class);
        FetchSpec insertFetch = mock(FetchSpec.class);
        when(client.sql(anyString()))
                .thenReturn(selectSpec)
                .thenReturn(insertSpec);
        when(selectSpec.bind(anyString(), any())).thenReturn(selectSpec);
        when(selectSpec.fetch()).thenReturn(selectFetch);
        when(selectFetch.one()).thenReturn(Mono.just(selectRow));
        when(insertSpec.bind(anyString(), any())).thenReturn(insertSpec);
        when(insertSpec.fetch()).thenReturn(insertFetch);
        when(insertFetch.rowsUpdated()).thenReturn(Mono.just(1L));
        return client;
    }
}
