package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentRecoverySecurityConfigurationTest {
    private static final String PASSWORD = "correct-horse-battery-staple";

    private final PaymentRecoverySecurityConfiguration configuration =
            new PaymentRecoverySecurityConfiguration();

    @Test
    void paymentRecoveryUsers_noCredentialsConfiguredCreatesNoFallbackUser() {
        UserDetailsService users = configuration.paymentRecoveryUsers("", "", false);

        assertThatThrownBy(() -> users.loadUserByUsername("admin"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void paymentRecoveryUsers_bcryptCredentialCreatesOnlyRecoveryAuthority() {
        String passwordHash = "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);

        UserDetails user = configuration
                .paymentRecoveryUsers("recovery-operator", passwordHash, true)
                .loadUserByUsername("recovery-operator");

        assertThat(user.getUsername()).isEqualTo("recovery-operator");
        assertThat(user.getPassword()).isEqualTo(passwordHash);
        assertThat(user.getAuthorities())
                .extracting("authority")
                .containsExactly(PaymentRecoverySecurityConfiguration.RECOVERY_AUTHORITY);
    }

    @Test
    void paymentRecoveryUsers_partialCredentialsFailStartup() {
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers("recovery-operator", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator username and password hash must be configured together");
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers("", validHash(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator username and password hash must be configured together");
    }

    @Test
    void paymentRecoveryUsers_credentialsWithoutServerSslFailStartup() {
        assertThatThrownBy(() -> configuration
                .paymentRecoveryUsers("recovery-operator", validHash(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator credentials require server SSL to be enabled");
    }

    @Test
    void paymentRecoveryUsers_cleartextOrNoopPasswordIsRejected() {
        assertThatThrownBy(() -> configuration
                .paymentRecoveryUsers("recovery-operator", "cleartext-password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator password must be a BCrypt hash");
        assertThatThrownBy(() -> configuration
                .paymentRecoveryUsers("recovery-operator", "{noop}password", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator password must be a BCrypt hash");
    }

    @Test
    void paymentRecoveryUsers_unsupportedBcryptVersionOrCostIsRejected() {
        assertInvalidHash("{bcrypt}$2x$10$" + "a".repeat(53));
        assertInvalidHash("{bcrypt}$2a$03$" + "a".repeat(53));
        assertInvalidHash("{bcrypt}$2a$32$" + "a".repeat(53));
    }

    @Test
    void paymentRecoveryUsers_invalidUsernameIsRejected() {
        assertThatThrownBy(() -> configuration
                .paymentRecoveryUsers("operator:name", validHash(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator username must contain 1 to 100 visible ASCII characters without a colon");
    }

    private String validHash() {
        return "{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD);
    }

    private void assertInvalidHash(String passwordHash) {
        assertThatThrownBy(() -> configuration
                .paymentRecoveryUsers("recovery-operator", passwordHash, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Recovery operator password must be a BCrypt hash");
    }
}
