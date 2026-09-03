package raum.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "An organization's id and display name, with no other detail")
public record OrgSummaryResponseDTO(UUID id, String name) {}
