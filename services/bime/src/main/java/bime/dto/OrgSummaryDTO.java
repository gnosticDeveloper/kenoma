package bime.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** An organization's id and display name, as returned by raum's {@code GET /orgs/{id}/summary}. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrgSummaryDTO {
    private UUID id;
    private String name;
}
