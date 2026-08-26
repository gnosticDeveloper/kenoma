package bime;

import bime.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMetadataIT extends BaseIT {

    @LocalServerPort
    int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(15))
                .build();
        mockAdminJwt();
    }

    @Test
    void createMetadata_persistsToDatabase() {
        ProductMetadataResponseDTO response = createMetadata("Color");

        assertThat(response.getId()).isNotNull();
        assertThat(response.getName()).isEqualTo("Color");
        assertThat(response.getOrgId()).isEqualTo(ORG_ID);
        assertThat(response.getOptions()).isEmpty();
    }

    @Test
    void getAllMetadata_returnsList() {
        createMetadata("Size");
        createMetadata("Material");

        client.get().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ProductMetadataResponseDTO.class)
                .hasSize(2);
    }

    @Test
    void getMetadataById_returnsWithOptions() {
        ProductMetadataResponseDTO meta = createMetadata("Weight");
        addOption(meta.getId(), "Light");
        addOption(meta.getId(), "Heavy");

        ProductMetadataResponseDTO response = client.get().uri("/metadata/{id}", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(meta.getId());
        assertThat(response.getOptions()).hasSize(2);
        assertThat(response.getOptions()).extracting(MetadataOptionResponseDTO::getValue)
                .containsExactlyInAnyOrder("Light", "Heavy");
    }

    @Test
    void addOption_appendsToMetadata() {
        ProductMetadataResponseDTO meta = createMetadata("Style");

        MetadataOptionResponseDTO option = addOption(meta.getId(), "Classic");

        assertThat(option.getId()).isNotNull();
        assertThat(option.getValue()).isEqualTo("Classic");
        assertThat(option.getMetadataId()).isEqualTo(meta.getId());
    }

    @Test
    void addOption_derivesCodeWhenOmitted() {
        ProductMetadataResponseDTO meta = createMetadata("Color-" + UUID.randomUUID());

        MetadataOptionResponseDTO option = addOption(meta.getId(), "Extra Large");

        assertThat(option.getCode()).isEqualTo("EXTRALARGE");
    }

    @Test
    void addOption_honorsExplicitCode() {
        ProductMetadataResponseDTO meta = createMetadata("Size-" + UUID.randomUUID());

        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue("Extra Large");
        dto.setCode("XL");
        MetadataOptionResponseDTO option = client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(option).isNotNull();
        assertThat(option.getCode()).isEqualTo("XL");
    }

    @Test
    void addOption_nonAsciiValueWithoutExplicitCode_returns400() {
        ProductMetadataResponseDTO meta = createMetadata("Size-" + UUID.randomUUID());

        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue("Größe");

        client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void addOption_nonAsciiValueWithExplicitCode_succeeds() {
        ProductMetadataResponseDTO meta = createMetadata("Size-" + UUID.randomUUID());

        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue("Größe");
        dto.setCode("XL-DE");

        MetadataOptionResponseDTO option = client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(option).isNotNull();
        assertThat(option.getValue()).isEqualTo("Größe");
        assertThat(option.getCode()).isEqualTo("XL-DE");
    }

    @Test
    void addOption_duplicateDerivedCode_returns409() {
        ProductMetadataResponseDTO meta = createMetadata("Duplicates-" + UUID.randomUUID());
        addOption(meta.getId(), "Red!");

        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue("Red?");

        client.post().uri("/metadata/{id}/options", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void removeOption_deletesFromMetadata() {
        ProductMetadataResponseDTO meta = createMetadata("Finish");
        MetadataOptionResponseDTO option = addOption(meta.getId(), "Matte");

        client.delete().uri("/metadata/{id}/options/{optionId}", meta.getId(), option.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        ProductMetadataResponseDTO after = client.get().uri("/metadata/{id}", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(after).isNotNull();
        assertThat(after.getOptions()).isEmpty();
    }

    @Test
    void deleteMetadata_returns204() {
        ProductMetadataResponseDTO meta = createMetadata("ToDelete");

        client.delete().uri("/metadata/{id}", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/metadata/{id}", meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void assignMetadata_toProduct() {
        ProductMetadataResponseDTO meta = createMetadata("Flavor");
        MetadataOptionResponseDTO opt = addOption(meta.getId(), "Sweet");

        ProductResponseDTO product = createProduct();

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(opt.getId()));

        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNoContent();

        ProductResponseDTO updated = client.get().uri("/products/{id}", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(updated).isNotNull();
        assertThat(updated.getMetadata()).hasSize(1);
        assertThat(updated.getMetadata().get(0).getMetadataId()).isEqualTo(meta.getId());
        assertThat(updated.getMetadata().get(0).getSelectedOptions()).hasSize(1);
    }

    @Test
    void patchOptions_addsAndRemovesOptions() {
        ProductMetadataResponseDTO meta = createMetadata("Grade");
        MetadataOptionResponseDTO opt1 = addOption(meta.getId(), "A");
        MetadataOptionResponseDTO opt2 = addOption(meta.getId(), "B");

        ProductResponseDTO product = createProduct();

        ProductMetadataAssignmentItemDTO item = new ProductMetadataAssignmentItemDTO();
        item.setMetadataId(meta.getId());
        item.setOptionIds(List.of(opt1.getId()));

        client.put().uri("/products/{id}/metadata", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(item))
                .exchange()
                .expectStatus().isNoContent();

        MetadataOptionPatchDTO patch = new MetadataOptionPatchDTO();
        patch.setAdd(List.of(opt2.getId()));
        patch.setRemove(List.of(opt1.getId()));

        client.patch().uri("/products/{id}/metadata/{metadataId}/options", product.getId(), meta.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(patch)
                .exchange()
                .expectStatus().isNoContent();

        ProductResponseDTO after = client.get().uri("/products/{id}", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(after).isNotNull();
        List<UUID> selectedIds = after.getMetadata().get(0).getSelectedOptions()
                .stream().map(MetadataOptionResponseDTO::getId).toList();
        assertThat(selectedIds).containsExactly(opt2.getId());
        assertThat(selectedIds).doesNotContain(opt1.getId());
    }

    @Test
    void createMetadata_returns409_forDuplicateName() {
        createMetadata("Unique");

        ProductMetadataRequestDTO dto = new ProductMetadataRequestDTO();
        dto.setName("Unique");
        client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void addOption_returns404_forUnknownMetadata() {
        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue("Orphan");
        client.post().uri("/metadata/{id}/options", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void removeOption_returns404_forUnknownOption() {
        ProductMetadataResponseDTO meta = createMetadata("ForRemoval");

        client.delete().uri("/metadata/{id}/options/{optionId}", meta.getId(), UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createMetadata_returns403_withUserRole() {
        mockUserJwt();

        ProductMetadataRequestDTO dto = new ProductMetadataRequestDTO();
        dto.setName("Blocked");
        client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isForbidden();
    }

    ProductMetadataResponseDTO createMetadata(String name) {
        ProductMetadataRequestDTO dto = new ProductMetadataRequestDTO();
        dto.setName(name);
        ProductMetadataResponseDTO response = client.post().uri("/metadata")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductMetadataResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    MetadataOptionResponseDTO addOption(UUID metadataId, String value) {
        MetadataOptionRequestDTO dto = new MetadataOptionRequestDTO();
        dto.setValue(value);
        MetadataOptionResponseDTO response = client.post().uri("/metadata/{id}/options", metadataId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MetadataOptionResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private ProductResponseDTO createProduct() {
        ProductRequestDTO dto = new ProductRequestDTO();
        dto.setSku("META-PROD-" + UUID.randomUUID());
        dto.setName("Metadata Test Product");
        dto.setIsActive(true);
        ProductResponseDTO response = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }
}
