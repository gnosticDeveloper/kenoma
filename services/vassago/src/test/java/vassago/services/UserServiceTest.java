package vassago.services;

import common.utils.RolesUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import vassago.db.VassagoDbService;
import vassago.dto.UserRequestDTO;

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

    // -----------------------------------------------------------------------
    // createUser — password validation
    // -----------------------------------------------------------------------

    @Test
    void createUser_rejectsNullPassword() {
        UserRequestDTO dto = validRequest();
        dto.setPassword(null);

        StepVerifier.create(userService.createUser(dto))
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException &&
                                e.getMessage().equals("Password does not meet requirements"))
                .verify();

        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void createUser_rejectsTooShortPassword() {
        UserRequestDTO dto = validRequest();
        dto.setPassword("Sh0rt!"); // under 12 chars

        StepVerifier.create(userService.createUser(dto))
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void createUser_rejectsPasswordWithNoUppercase() {
        UserRequestDTO dto = validRequest();
        dto.setPassword("nocaps123!abc");

        StepVerifier.create(userService.createUser(dto))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void createUser_rejectsPasswordWithNoDigit() {
        UserRequestDTO dto = validRequest();
        dto.setPassword("NoDigits!Pass");

        StepVerifier.create(userService.createUser(dto))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void createUser_rejectsPasswordWithNoSpecialChar() {
        UserRequestDTO dto = validRequest();
        dto.setPassword("NoSpecial1Pass");

        StepVerifier.create(userService.createUser(dto))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void createUser_validPassword_delegatesToDbService() {
        UserRequestDTO dto = validRequest();
        DatabaseClient client = mockClientReturning(rowFor(USER_ID));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));
        when(encoder.encode(anyString())).thenReturn("hashed-password");

        StepVerifier.create(userService.createUser(dto))
                .assertNext(response -> {
                    assertThat(response.getName()).isEqualTo("Jane");
                    assertThat(response.getEmail()).isEqualTo("jane@example.com");
                })
                .verifyComplete();

        verify(vassagoDbService, times(1)).getClient(ORG_ID);
    }

    // -----------------------------------------------------------------------
    // updateUser — password validation
    // -----------------------------------------------------------------------

    @Test
    void updateUser_withInvalidPassword_rejectsEarly() {
        UserRequestDTO dto = validRequest();
        dto.setPassword("bad");

        StepVerifier.create(userService.updateUser(ORG_ID, USER_ID, dto))
                .expectError(IllegalArgumentException.class)
                .verify();

        verifyNoInteractions(vassagoDbService);
    }

    @Test
    void updateUser_withNullPassword_doesNotEncodePassword() {
        UserRequestDTO dto = validRequest();
        dto.setPassword(null);
        DatabaseClient client = mockClientReturning(rowFor(USER_ID));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(userService.updateUser(ORG_ID, USER_ID, dto))
                .assertNext(response -> assertThat(response).isNotNull())
                .verifyComplete();

        verifyNoInteractions(encoder);
    }

    // -----------------------------------------------------------------------
    // getUserById — delegates to db service
    // -----------------------------------------------------------------------

    @Test
    void getUserById_delegatesToDbService() {
        DatabaseClient client = mockClientReturning(rowFor(USER_ID));
        when(vassagoDbService.getClient(ORG_ID)).thenReturn(Mono.just(client));

        StepVerifier.create(userService.getUserById(ORG_ID, USER_ID))
                .assertNext(response -> assertThat(response.getId()).isEqualTo(USER_ID))
                .verifyComplete();

        verify(vassagoDbService, times(1)).getClient(ORG_ID);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UserRequestDTO validRequest() {
        UserRequestDTO dto = new UserRequestDTO();
        dto.setOrgId(ORG_ID);
        dto.setName("Jane");
        dto.setLastName("Doe");
        dto.setEmail("jane@example.com");
        dto.setUsername("janedoe");
        dto.setPassword("Str0ng!Pass1");
        dto.setRoles(List.of("USER"));
        return dto;
    }

    private Map<String, Object> rowFor(UUID id) {
        return Map.of(
                "id", id,
                "name", "Jane",
                "last_name", "Doe",
                "email", "jane@example.com",
                "username", "janedoe",
                "roles", RolesUtils.serialize(List.of("USER"))
        );
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