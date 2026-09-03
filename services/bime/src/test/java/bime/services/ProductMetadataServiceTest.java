package bime.services;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMetadataServiceTest {

    @Test
    void deriveCode_stripsNonAlphanumericAndUppercases() {
        assertThat(ProductMetadataService.deriveCode("Extra Large")).isEqualTo("EXTRALARGE");
        assertThat(ProductMetadataService.deriveCode("Red")).isEqualTo("RED");
    }

    @Test
    void deriveCode_nonAlphanumericOnlyValue_fallsBackToPlaceholder() {
        assertThat(ProductMetadataService.deriveCode("---")).isEqualTo("OPT");
        assertThat(ProductMetadataService.deriveCode("")).isEqualTo("OPT");
    }

    @Test
    void deriveCode_truncatesToColumnLimit() {
        String longValue = "A".repeat(60);
        assertThat(ProductMetadataService.deriveCode(longValue)).hasSize(50);
    }

    @Test
    void hasUnrepresentableCharacters_flagsAccentedCyrillicAndCjk() {
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Größe")).isTrue();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Café")).isTrue();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Молоко")).isTrue();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("红色")).isTrue();
    }

    @Test
    void hasUnrepresentableCharacters_allowsPlainAsciiAndSharpS() {
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Extra Large")).isFalse();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Red!")).isFalse();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters("Straße")).isFalse();
        assertThat(ProductMetadataService.hasUnrepresentableCharacters(null)).isFalse();
    }
}
