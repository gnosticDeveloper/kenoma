package bime;

import bime.dto.BarcodeLookupResponseDTO;
import bime.dto.BarcodeSource;
import bime.dto.BarcodeSymbology;
import bime.dto.OrgBarcodeSettingsRequestDTO;
import bime.dto.OrgBarcodeSettingsResponseDTO;
import bime.dto.OrgUnitRequestDTO;
import bime.dto.ProductRequestDTO;
import bime.dto.ProductResponseDTO;
import bime.dto.ProductVariantRequestDTO;
import bime.dto.ProductVariantResponseDTO;
import bime.dto.UomConversionRequestDTO;
import bime.dto.VariantBarcodeIssueRequestDTO;
import bime.dto.VariantBarcodeRequestDTO;
import bime.dto.VariantBarcodeResponseDTO;
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

class BarcodeIT extends BaseIT {

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
    void link_validEan13_persistsAndAppearsOnVariant() {
        Ctx ctx = newVariant();
        VariantBarcodeResponseDTO linked = link(ctx, "9780201379624", BarcodeSymbology.EAN13, false);

        assertThat(linked.getBarcode()).isEqualTo("9780201379624");
        assertThat(linked.getSymbology()).isEqualTo(BarcodeSymbology.EAN13);
        assertThat(linked.getSource()).isEqualTo(BarcodeSource.PROVIDER);

        ProductVariantResponseDTO variant = client.get()
                .uri("/products/{p}/variants/{v}", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();
        assertThat(variant.getBarcodes()).extracting(VariantBarcodeResponseDTO::getBarcode)
                .containsExactly("9780201379624");
    }

    @Test
    void link_invalidCheckDigit_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379625");
        dto.setSymbology(BarcodeSymbology.EAN13);

        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void link_duplicateWithinOrg_returns409() {
        Ctx a = newVariant();
        Ctx b = newVariant();
        link(a, "9780201379624", BarcodeSymbology.EAN13, false);

        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379624");
        dto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", b.productId, b.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void link_upcA_isStoredAsEquivalentEan13() {
        Ctx ctx = newVariant();
        VariantBarcodeResponseDTO linked = link(ctx, "036000291452", BarcodeSymbology.UPC_A, false);

        assertThat(linked.getBarcode()).isEqualTo("0036000291452");
        assertThat(linked.getSymbology()).isEqualTo(BarcodeSymbology.EAN13);

        // a bare 12-digit UPC-A scan still resolves to the same variant
        BarcodeLookupResponseDTO hit = lookup("036000291452");
        assertThat(hit.getVariant().getId()).isEqualTo(ctx.variantId);
    }

    @Test
    void issue_generatesValidEan13_andAdvancesSequence() {
        Ctx ctx = newVariant();

        VariantBarcodeResponseDTO first = issue(ctx, false);
        VariantBarcodeResponseDTO second = issue(ctx, false);

        assertThat(first.getBarcode()).hasSize(13).startsWith("20");
        assertThat(first.getSource()).isEqualTo(BarcodeSource.ISSUED);
        assertThat(second.getBarcode()).isNotEqualTo(first.getBarcode());

        OrgBarcodeSettingsResponseDTO settings = client.get().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgBarcodeSettingsResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.getNextSequence()).isEqualTo(3L);

        // the issued value round-trips through lookup
        BarcodeLookupResponseDTO hit = lookup(first.getBarcode());
        assertThat(hit.getVariant().getId()).isEqualTo(ctx.variantId);
    }

    @Test
    void issue_withGs1Prefix_usesThatPrefix() {
        putSettings("5012345");
        Ctx ctx = newVariant();

        VariantBarcodeResponseDTO issued = issue(ctx, false);

        assertThat(issued.getBarcode()).hasSize(13).startsWith("5012345");
    }

    @Test
    void primaryFlag_movesToTheLatestPrimary() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        link(ctx, "4006381333931", BarcodeSymbology.EAN13, true);

        List<VariantBarcodeResponseDTO> barcodes = client.get()
                .uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VariantBarcodeResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(barcodes).hasSize(2);
        assertThat(barcodes).filteredOn(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .extracting(VariantBarcodeResponseDTO::getBarcode)
                .containsExactly("4006381333931");
    }

    @Test
    void patchPrimary_promotesOneAndDemotesTheOther() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        link(ctx, "4006381333931", BarcodeSymbology.EAN13, false);

        client.patch().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "4006381333931")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"isPrimary\":true}")
                .exchange()
                .expectStatus().isOk();

        List<VariantBarcodeResponseDTO> barcodes = client.get()
                .uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VariantBarcodeResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(barcodes).filteredOn(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .extracting(VariantBarcodeResponseDTO::getBarcode)
                .containsExactly("4006381333931");
    }

    @Test
    void lookup_unknownBarcode_returns404() {
        client.get().uri("/barcodes/lookup?code={b}", "9999999999994")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void lookup_blankOrMissingCode_returns400() {
        client.get().uri("/barcodes/lookup?code={b}", "   ")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
        client.get().uri("/barcodes/lookup")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void issue_withIsPrimary_demotesExistingPrimary() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);

        issue(ctx, true);

        assertThat(listBarcodes(ctx))
                .filteredOn(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .extracting(VariantBarcodeResponseDTO::getSource)
                .containsExactly(BarcodeSource.ISSUED);
    }

    @Test
    void link_withoutIsPrimary_leavesNoPrimary_untilOneIsChosen() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, false);
        link(ctx, "4006381333931", BarcodeSymbology.EAN13, false);

        assertThat(listBarcodes(ctx)).noneMatch(b -> Boolean.TRUE.equals(b.getIsPrimary()));

        client.patch().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"isPrimary\":true}")
                .exchange().expectStatus().isOk();

        assertThat(listBarcodes(ctx)).filteredOn(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .extracting(VariantBarcodeResponseDTO::getBarcode).containsExactly("9780201379624");
    }

    @Test
    void patchPrimary_withFalse_clearsTheFlag() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);

        client.patch().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"isPrimary\":false}")
                .exchange().expectStatus().isOk();

        assertThat(listBarcodes(ctx)).noneMatch(b -> Boolean.TRUE.equals(b.getIsPrimary()));
    }

    @Test
    void remove_primaryBarcode_doesNotAutoPromoteAnother() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        link(ctx, "4006381333931", BarcodeSymbology.EAN13, false);

        client.delete().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNoContent();

        List<VariantBarcodeResponseDTO> left = listBarcodes(ctx);
        assertThat(left).extracting(VariantBarcodeResponseDTO::getBarcode).containsExactly("4006381333931");
        assertThat(left).noneMatch(b -> Boolean.TRUE.equals(b.getIsPrimary()));
    }

    @Test
    void link_sameBarcodeTwiceOnSameVariant_returns409() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, false);

        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379624");
        dto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void link_code39_invalidCharacter_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("BAD@CHAR");
        dto.setSymbology(BarcodeSymbology.CODE39);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(dto)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void issuanceSequence_isPerOrg() {
        Ctx a = newVariant();
        issue(a, false);
        issue(a, false);

        mockAdminJwtForOrg(ORG_ID_B);
        Ctx b = newVariant();
        VariantBarcodeResponseDTO firstForB = issue(b, false);

        // Org B's first issued code is sequence 1 in the restricted range, unaffected by org A.
        assertThat(firstForB.getBarcode()).isEqualTo("2000000000015");
        OrgBarcodeSettingsResponseDTO settingsB = client.get().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(OrgBarcodeSettingsResponseDTO.class).returnResult().getResponseBody();
        assertThat(settingsB).isNotNull();
        assertThat(settingsB.getNextSequence()).isEqualTo(2L);
    }

    @Test
    void getSettings_freshOrg_returnsDefaults() {
        OrgBarcodeSettingsResponseDTO settings = client.get().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(OrgBarcodeSettingsResponseDTO.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        assertThat(settings.getGs1Prefix()).isNull();
        assertThat(settings.getNextSequence()).isEqualTo(1L);
    }

    @Test
    void barcodeLabels_whichAll_emitsMoreThanPrimaryOnly() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        issue(ctx, false);
        issue(ctx, false);

        byte[] primary = client.get().uri("/products/{p}/barcode-labels?which=primary", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        byte[] all = client.get().uri("/products/{p}/barcode-labels?which=all", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();

        assertThat(primary).isNotNull();
        assertThat(all).isNotNull();
        assertThat(all.length).isGreaterThan(primary.length);
    }

    @Test
    void lookup_isScopedToCallerOrg() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, false);

        mockAdminJwtForOrg(ORG_ID_B);
        client.get().uri("/barcodes/lookup?code={b}", "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void remove_unlinksBarcode_thenReturns404() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, false);

        client.delete().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNoContent();

        client.get().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VariantBarcodeResponseDTO.class)
                .hasSize(0);

        client.delete().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void settings_setAndClearGs1Prefix() {
        OrgBarcodeSettingsResponseDTO afterSet = putSettings("5012345");
        assertThat(afterSet.getGs1Prefix()).isEqualTo("5012345");

        OrgBarcodeSettingsResponseDTO afterClear = putSettings(null);
        assertThat(afterClear.getGs1Prefix()).isNull();
    }

    @Test
    void barcodeLabels_returnsPdf() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        issue(ctx, false);

        byte[] pdf = client.get().uri("/products/{p}/barcode-labels?which=all&columns=2&copies=2", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectBody(byte[].class)
                .returnResult().getResponseBody();

        assertThat(pdf).isNotNull();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(500);
    }

    @Test
    void barcodeLabels_noBarcodes_returns400() {
        Ctx ctx = newVariant();
        client.get().uri("/products/{p}/barcode-labels", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void barcodeLabels_unknownProduct_returns404() {
        client.get().uri("/products/{p}/barcode-labels", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void settings_invalidPrefix_returns400() {
        OrgBarcodeSettingsRequestDTO dto = new OrgBarcodeSettingsRequestDTO();
        dto.setGs1Prefix("12a4");
        client.put().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ── RBAC ──

    @Test
    void mutations_asViewer_areForbidden() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        mockViewerJwt();

        VariantBarcodeRequestDTO linkDto = new VariantBarcodeRequestDTO();
        linkDto.setBarcode("4006381333931");
        linkDto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(linkDto).exchange().expectStatus().isForbidden();

        client.post().uri("/products/{p}/variants/{v}/barcodes/issue", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isForbidden();

        client.patch().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"isPrimary\":true}").exchange().expectStatus().isForbidden();

        client.delete().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isForbidden();

        client.put().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"gs1Prefix\":\"5012345\"}").exchange().expectStatus().isForbidden();
    }

    @Test
    void reads_asViewer_areAllowed() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        mockViewerJwt();

        client.get().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isOk();
        client.get().uri("/barcodes/lookup?code={b}", "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isOk();
        client.get().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isOk();
        client.get().uri("/products/{p}/barcode-labels", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isOk();
    }

    @Test
    void newRoutes_withoutJwt_areUnauthorized() {
        client.get().uri("/barcodes/lookup?code={b}", "9780201379624").exchange().expectStatus().isUnauthorized();
        client.get().uri("/barcodes/settings").exchange().expectStatus().isUnauthorized();
    }

    // ── Cross-tenant isolation ──

    @Test
    void crossOrg_cannotTouchAnotherOrgsVariant() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        mockAdminJwtForOrg(ORG_ID_B);

        client.get().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isNotFound();

        VariantBarcodeRequestDTO linkDto = new VariantBarcodeRequestDTO();
        linkDto.setBarcode("4006381333931");
        linkDto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(linkDto).exchange().expectStatus().isNotFound();

        client.patch().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"isPrimary\":true}").exchange().expectStatus().isNotFound();

        client.delete().uri("/products/{p}/variants/{v}/barcodes?barcode={b}", ctx.productId, ctx.variantId, "9780201379624")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isNotFound();

        client.get().uri("/products/{p}/barcode-labels", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").exchange().expectStatus().isNotFound();
    }

    @Test
    void sameBarcode_canBeLinkedInDifferentOrgs() {
        Ctx a = newVariant();
        link(a, "9780201379624", BarcodeSymbology.EAN13, false);

        mockAdminJwtForOrg(ORG_ID_B);
        Ctx b = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379624");
        dto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", b.productId, b.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isOk();
    }

    // ── Non-EAN13 symbologies ──

    @Test
    void link_ean8_roundTrips() {
        Ctx ctx = newVariant();
        VariantBarcodeResponseDTO r = link(ctx, "96385074", BarcodeSymbology.EAN8, true);
        assertThat(r.getBarcode()).isEqualTo("96385074");
        assertThat(r.getSymbology()).isEqualTo(BarcodeSymbology.EAN8);
        assertThat(lookup("96385074").getVariant().getId()).isEqualTo(ctx.variantId);
    }

    @Test
    void link_code128AndCode39_roundTrip_andNormalizeCase() {
        Ctx ctx = newVariant();
        link(ctx, "ABC-123/45", BarcodeSymbology.CODE128, false);
        link(ctx, "part 42", BarcodeSymbology.CODE39, false);

        assertThat(lookup("ABC-123/45").getVariant().getId()).isEqualTo(ctx.variantId);
        assertThat(lookup("part 42").getVariant().getId()).isEqualTo(ctx.variantId);
        assertThat(lookup("PART 42").getVariant().getId()).isEqualTo(ctx.variantId);
    }

    @Test
    void link_ean8_badCheckDigit_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("96385075");
        dto.setSymbology(BarcodeSymbology.EAN8);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isBadRequest();
    }

    @Test
    void barcodeLabels_withNonEanSymbology_returnsPdf() {
        Ctx ctx = newVariant();
        link(ctx, "WAREHOUSE-42", BarcodeSymbology.CODE128, true);

        byte[] pdf = client.get().uri("/products/{p}/barcode-labels", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class)
                .returnResult().getResponseBody();
        assertThat(pdf).isNotNull();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    // ── Malformed link requests ──

    @Test
    void link_nullSymbology_returns400() {
        Ctx ctx = newVariant();
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"barcode\":\"9780201379624\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void link_blankBarcode_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("   ");
        dto.setSymbology(BarcodeSymbology.EAN13);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isBadRequest();
    }

    @Test
    void link_code128TooLong_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("X".repeat(65));
        dto.setSymbology(BarcodeSymbology.CODE128);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isBadRequest();
    }

    // ── Barcodes embedded in variant responses ──

    @Test
    void variantListAndSearch_includeBarcodes() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);

        List<ProductVariantResponseDTO> list = client.get()
                .uri("/products/{p}/variants", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(ProductVariantResponseDTO.class).returnResult().getResponseBody();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBarcodes()).extracting(VariantBarcodeResponseDTO::getBarcode)
                .containsExactly("9780201379624");

        List<ProductVariantResponseDTO> found = client.get()
                .uri("/products/variants/search?sku={s}", list.get(0).getSku())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBodyList(ProductVariantResponseDTO.class).returnResult().getResponseBody();
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getBarcodes()).extracting(VariantBarcodeResponseDTO::getBarcode)
                .containsExactly("9780201379624");
    }

    @Test
    void lookup_deactivatedVariant_stillResolves_butMarkedInactive() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);

        client.delete().uri("/products/{p}/variants/{v}", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isNoContent();

        BarcodeLookupResponseDTO hit = lookup("9780201379624");
        assertThat(hit.getVariant().getId()).isEqualTo(ctx.variantId);
        assertThat(hit.getVariant().getIsActive()).isFalse();
    }

    // ── Label sheet parameter hardening ──

    @Test
    void barcodeLabels_toleratesOutOfRangeParams() {
        Ctx ctx = newVariant();
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);

        for (String qs : List.of("?columns=99&copies=0", "?columns=0&copies=250",
                "?pageSize=nonsense", "?which=whatever", "?columns=-3&copies=-1")) {
            client.get().uri("/products/{p}/barcode-labels" + qs, ctx.productId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentType(MediaType.APPLICATION_PDF);
        }
    }

    @Test
    void barcodeLabels_variantIdFromAnotherProduct_returns400() {
        Ctx a = newVariant();
        link(a, "9780201379624", BarcodeSymbology.EAN13, true);
        Ctx b = newVariant();

        client.get().uri("/products/{p}/barcode-labels?variantId={v}", a.productId, b.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isBadRequest();
    }

    // ── Per-unit barcodes ──

    @Test
    void link_omittedUom_defaultsToBaseUnit() {
        Ctx ctx = newVariant();
        VariantBarcodeResponseDTO r = link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        assertThat(r.getUom()).isEqualTo("units");
        assertThat(r.getFactor()).isEqualByComparingTo("1");
    }

    @Test
    void link_toConfiguredPackUnit_carriesFactorAndPackPrice() {
        Ctx ctx = pricedVariantWithCase(new java.math.BigDecimal("2.00"), 24);

        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379624");
        dto.setSymbology(BarcodeSymbology.EAN13);
        dto.setUom("case");
        dto.setIsPrimary(true);
        VariantBarcodeResponseDTO linked = client.post()
                .uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isOk()
                .expectBody(VariantBarcodeResponseDTO.class).returnResult().getResponseBody();
        assertThat(linked).isNotNull();
        assertThat(linked.getUom()).isEqualTo("case");
        assertThat(linked.getFactor()).isEqualByComparingTo("24");

        BarcodeLookupResponseDTO hit = lookup("9780201379624");
        assertThat(hit.getUom()).isEqualTo("case");
        assertThat(hit.getFactor()).isEqualByComparingTo("24");
        assertThat(hit.getPackPrice()).isEqualByComparingTo("48.00");
    }

    @Test
    void link_withUnconfiguredUnit_returns400() {
        Ctx ctx = newVariant();
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode("9780201379624");
        dto.setSymbology(BarcodeSymbology.EAN13);
        dto.setUom("pallet");
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isBadRequest();
    }

    @Test
    void primary_isPerUnit_notPerVariant() {
        Ctx ctx = pricedVariantWithCase(new java.math.BigDecimal("2.00"), 24);
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true); // base unit, primary

        VariantBarcodeRequestDTO caseDto = new VariantBarcodeRequestDTO();
        caseDto.setBarcode("4006381333931");
        caseDto.setSymbology(BarcodeSymbology.EAN13);
        caseDto.setUom("case");
        caseDto.setIsPrimary(true);
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(caseDto).exchange().expectStatus().isOk();

        assertThat(listBarcodes(ctx)).filteredOn(b -> Boolean.TRUE.equals(b.getIsPrimary()))
                .extracting(VariantBarcodeResponseDTO::getUom)
                .containsExactlyInAnyOrder("units", "case");
    }

    @Test
    void issue_forPackUnit_carriesFactor() {
        Ctx ctx = pricedVariantWithCase(new java.math.BigDecimal("2.00"), 12);
        VariantBarcodeIssueRequestDTO dto = new VariantBarcodeIssueRequestDTO();
        dto.setUom("case");
        VariantBarcodeResponseDTO issued = client.post()
                .uri("/products/{p}/variants/{v}/barcodes/issue", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto).exchange().expectStatus().isOk()
                .expectBody(VariantBarcodeResponseDTO.class).returnResult().getResponseBody();
        assertThat(issued).isNotNull();
        assertThat(issued.getUom()).isEqualTo("case");
        assertThat(issued.getFactor()).isEqualByComparingTo("12");
    }

    @Test
    void barcodeLabels_perUnit_rendersPackCaption() {
        Ctx ctx = pricedVariantWithCase(new java.math.BigDecimal("2.00"), 24);
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);
        VariantBarcodeRequestDTO caseDto = new VariantBarcodeRequestDTO();
        caseDto.setBarcode("4006381333931");
        caseDto.setSymbology(BarcodeSymbology.EAN13);
        caseDto.setUom("case");
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(caseDto).exchange().expectStatus().isOk();

        byte[] pdf = client.get().uri("/products/{p}/barcode-labels?which=all", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(pdf).isNotNull();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void barcodeLabels_uomFilter_restrictsToThatUnit() {
        Ctx ctx = pricedVariantWithCase(new java.math.BigDecimal("2.00"), 24);
        link(ctx, "9780201379624", BarcodeSymbology.EAN13, true);           // base unit
        VariantBarcodeRequestDTO caseDto = new VariantBarcodeRequestDTO();
        caseDto.setBarcode("4006381333931");
        caseDto.setSymbology(BarcodeSymbology.EAN13);
        caseDto.setUom("case");
        client.post().uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(caseDto).exchange().expectStatus().isOk();

        byte[] casePdf = client.get().uri("/products/{p}/barcode-labels?uom=case&which=all", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        byte[] allPdf = client.get().uri("/products/{p}/barcode-labels?which=all", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(casePdf).isNotNull();
        assertThat(allPdf).isNotNull();
        assertThat(new String(casePdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(casePdf.length).isLessThan(allPdf.length);

        // a unit with no barcode on this product -> nothing to print
        client.get().uri("/products/{p}/barcode-labels?uom=pallet", ctx.productId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange().expectStatus().isBadRequest();
    }

    // --- helpers ---

    private record Ctx(UUID productId, UUID variantId) {}

    /** A variant priced at {@code price} USD with a "case" pack conversion of {@code factor} base units. */
    private Ctx pricedVariantWithCase(java.math.BigDecimal price, int factor) {
        OrgUnitRequestDTO unit = new OrgUnitRequestDTO();
        unit.setName("case");
        client.post().uri("/units").header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(unit).exchange();

        ProductRequestDTO prod = new ProductRequestDTO();
        prod.setSku("BCU-" + UUID.randomUUID());
        prod.setName("Pack Barcode Product");
        prod.setIsActive(true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(prod).exchange().expectStatus().isOk()
                .expectBody(ProductResponseDTO.class).returnResult().getResponseBody();
        assertThat(product).isNotNull();

        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        varDto.setPrice(price);
        varDto.setPriceCurrency("USD");
        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(varDto).exchange().expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class).returnResult().getResponseBody();
        assertThat(variant).isNotNull();

        UomConversionRequestDTO conv = new UomConversionRequestDTO();
        conv.setUomName("case");
        conv.setFactor(java.math.BigDecimal.valueOf(factor));
        client.put().uri("/variants/{v}/uom-conversions", variant.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(conv).exchange().expectStatus().isOk();

        return new Ctx(product.getId(), variant.getId());
    }

    private Ctx newVariant() {
        ProductRequestDTO prod = new ProductRequestDTO();
        prod.setSku("BC-" + UUID.randomUUID());
        prod.setName("Barcode Product");
        prod.setIsActive(true);
        ProductResponseDTO product = client.post().uri("/products")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(prod)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(product).isNotNull();

        ProductVariantRequestDTO varDto = new ProductVariantRequestDTO();
        varDto.setOptionIds(List.of());
        ProductVariantResponseDTO variant = client.post().uri("/products/{p}/variants", product.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(varDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProductVariantResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(variant).isNotNull();
        return new Ctx(product.getId(), variant.getId());
    }

    private VariantBarcodeResponseDTO link(Ctx ctx, String barcode, BarcodeSymbology symbology, boolean primary) {
        VariantBarcodeRequestDTO dto = new VariantBarcodeRequestDTO();
        dto.setBarcode(barcode);
        dto.setSymbology(symbology);
        dto.setIsPrimary(primary);
        VariantBarcodeResponseDTO response = client.post()
                .uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(VariantBarcodeResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private VariantBarcodeResponseDTO issue(Ctx ctx, boolean primary) {
        VariantBarcodeIssueRequestDTO dto = new VariantBarcodeIssueRequestDTO();
        dto.setIsPrimary(primary);
        VariantBarcodeResponseDTO response = client.post()
                .uri("/products/{p}/variants/{v}/barcodes/issue", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(VariantBarcodeResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private BarcodeLookupResponseDTO lookup(String barcode) {
        BarcodeLookupResponseDTO response = client.get().uri("/barcodes/lookup?code={b}", barcode)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody(BarcodeLookupResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }

    private List<VariantBarcodeResponseDTO> listBarcodes(Ctx ctx) {
        List<VariantBarcodeResponseDTO> body = client.get()
                .uri("/products/{p}/variants/{v}/barcodes", ctx.productId, ctx.variantId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(VariantBarcodeResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private OrgBarcodeSettingsResponseDTO putSettings(String prefix) {
        OrgBarcodeSettingsRequestDTO dto = new OrgBarcodeSettingsRequestDTO();
        dto.setGs1Prefix(prefix);
        OrgBarcodeSettingsResponseDTO response = client.put().uri("/barcodes/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(dto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(OrgBarcodeSettingsResponseDTO.class)
                .returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response;
    }
}
