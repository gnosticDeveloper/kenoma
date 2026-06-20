package common.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class StringUtilsTest {

    @Test
    void isValidPassword_returnsFalse_forNull() {
        assertThat(StringUtils.isValidPassword(null)).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenTooShort() {
        assertThat(StringUtils.isValidPassword("Sh0rt!")).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenTooLong() {
        String tooLong = "Aa1!".repeat(19); // 76 chars
        assertThat(StringUtils.isValidPassword(tooLong)).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenMissingUppercase() {
        assertThat(StringUtils.isValidPassword("nouppercase1!")).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenMissingLowercase() {
        assertThat(StringUtils.isValidPassword("NOLOWERCASE1!")).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenMissingDigit() {
        assertThat(StringUtils.isValidPassword("NoDigitsHere!")).isFalse();
    }

    @Test
    void isValidPassword_returnsFalse_whenMissingSpecialChar() {
        assertThat(StringUtils.isValidPassword("NoSpecial1234")).isFalse();
    }

    @Test
    void isValidPassword_returnsTrue_forValidPassword() {
        assertThat(StringUtils.isValidPassword("V@lidPass1234")).isTrue();
    }

    @Test
    void isValidPassword_returnsTrue_atMinimumLength() {
        assertThat(StringUtils.isValidPassword("V@lid1234567")).isTrue(); // exactly 12 chars
    }

    @Test
    void isValidPassword_returnsTrue_atMaximumLength() {
        // 72 chars: 18 repetitions of "Aa1!" = 72
        String maxLength = "Aa1!".repeat(18);
        assertThat(StringUtils.isValidPassword(maxLength)).isTrue();
    }

    @Test
    void isValidPassword_returnsFalse_atExactlyOneBeyondMax() {
        String overMax = "Aa1!".repeat(18) + "A"; // 73 chars
        assertThat(StringUtils.isValidPassword(overMax)).isFalse();
    }

    @Test
    void privateConstructor_isCallableViaReflection() throws Exception {
        Constructor<StringUtils> c = StringUtils.class.getDeclaredConstructor();
        c.setAccessible(true);
        c.newInstance();
    }
}
