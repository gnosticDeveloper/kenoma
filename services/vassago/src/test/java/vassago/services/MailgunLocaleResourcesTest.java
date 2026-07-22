package vassago.services;

import org.junit.jupiter.api.Test;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

class MailgunLocaleResourcesTest {

    @Test
    void englishBundle_hasAllKeysAndFormatsLink() {
        ResourceBundle bundle = ResourceBundle.getBundle("email", Locale.ENGLISH);
        assertThat(bundle.getString("verify.subject")).isEqualTo("Confirm your account");
        assertThat(MessageFormat.format(bundle.getString("verify.body"), "http://x/verify"))
                .contains("http://x/verify");
        assertThat(bundle.getString("reset.subject")).isEqualTo("Reset your password");
        assertThat(MessageFormat.format(bundle.getString("reset.body"), "http://x/verify"))
                .contains("http://x/verify");
    }

    @Test
    void spanishBundle_hasAllKeysAndFormatsLink() {
        ResourceBundle bundle = ResourceBundle.getBundle("email", Locale.forLanguageTag("es"));
        assertThat(bundle.getString("verify.subject")).isNotBlank().isNotEqualTo("Confirm your account");
        assertThat(MessageFormat.format(bundle.getString("verify.body"), "http://x/verify"))
                .contains("http://x/verify");
        assertThat(bundle.getString("reset.subject")).isNotBlank().isNotEqualTo("Reset your password");
        assertThat(MessageFormat.format(bundle.getString("reset.body"), "http://x/verify"))
                .contains("http://x/verify");
    }
}
