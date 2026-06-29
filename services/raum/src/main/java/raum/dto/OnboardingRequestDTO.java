package raum.dto;

import lombok.Data;
import raum.onboarding.BimePreset;

@Data
public class OnboardingRequestDTO {
    private String name;
    private String lastName;
    private String email;
    private String username;
    private BimePreset bimePreset;
}
