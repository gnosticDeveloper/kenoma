package raum.openbao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenBaoTokenRenewalSchedulerTest {

    @Mock
    private OpenBaoService openBaoService;
    @Mock
    private OpenBaoProvisioner openBaoProvisioner;

    @Test
    void renewToken_fallsBackToReProvisioning_whenRenewalFailsAfterRetries() {
        when(openBaoService.renewSelf()).thenReturn(Mono.error(new RuntimeException("renewSelf failed: lease is not renewable")));
        when(openBaoProvisioner.provisionOnce()).thenReturn(Mono.empty());

        new OpenBaoTokenRenewalScheduler(openBaoService, openBaoProvisioner).renewToken();

        verify(openBaoProvisioner).provisionOnce();
    }

    @Test
    void renewToken_doesNotReProvision_whenRenewalSucceeds() {
        when(openBaoService.renewSelf()).thenReturn(Mono.empty());

        new OpenBaoTokenRenewalScheduler(openBaoService, openBaoProvisioner).renewToken();

        verify(openBaoProvisioner, never()).provisionOnce();
    }
}
