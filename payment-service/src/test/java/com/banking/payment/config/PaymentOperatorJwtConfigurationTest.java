package com.banking.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOperatorJwtConfigurationTest {
    private static final String ISSUER = "https://identity.example.test/issuer";
    private static final String JWK_SET =
            "https://identity.example.test/.well-known/jwks.json";
    private static final String AUDIENCE = "finbank-payment-operations";

    private final PaymentRecoverySecurityConfiguration configuration =
            new PaymentRecoverySecurityConfiguration();

    @Test
    void jwtMode_createsNoLocalFallbackUserAndRejectsMixedBasicCredentials() {
        UserDetailsService users = configuration.paymentRecoveryUsers(
                jwtAuthentication(),
                "",
                "",
                true,
                "none"
        );

        assertThatThrownBy(() -> users.loadUserByUsername("external-operator"))
                .isInstanceOf(UsernameNotFoundException.class);
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers(
                jwtAuthentication(),
                "local-operator",
                "{bcrypt}$2a$10$" + "a".repeat(53),
                true,
                "none"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Local operator credentials must be blank in JWT mode");
    }

    @Test
    void basicMode_rejectsSilentlyIgnoredJwtSettings() {
        PaymentOperatorAuthenticationProperties properties =
                new PaymentOperatorAuthenticationProperties(
                        PaymentOperatorAuthenticationProperties.Mode.BASIC,
                        new PaymentOperatorAuthenticationProperties.ExternalJwt(
                                ISSUER,
                                JWK_SET,
                                AUDIENCE
                        )
                );

        assertThatThrownBy(() -> configuration.paymentRecoveryUsers(
                properties,
                "",
                "",
                false,
                "none"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT settings must be blank in Basic authentication mode");
    }

    @Test
    void jwtMode_requiresDirectTlsAndCompleteHttpsSettings() {
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers(
                jwtAuthentication(),
                "",
                "",
                false,
                "none"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("JWT operator authentication requires server SSL to be enabled");
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers(
                jwtAuthentication(),
                "",
                "",
                true,
                "framework"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Operator authentication requires server.forward-headers-strategy=none"
                );

        assertInvalidJwtSettings("", JWK_SET, AUDIENCE, "JWT issuer URI");
        assertInvalidJwtSettings("http://identity.example.test", JWK_SET, AUDIENCE,
                "JWT issuer URI must be an absolute HTTPS URI");
        assertInvalidJwtSettings(ISSUER, "", AUDIENCE, "JWT JWK set URI");
        assertInvalidJwtSettings(ISSUER, JWK_SET + "?tenant=one", AUDIENCE,
                "JWT JWK set URI must be an absolute HTTPS URI");
        assertInvalidJwtSettings(ISSUER, JWK_SET, "", "JWT audience must contain");
        assertInvalidJwtSettings(ISSUER, JWK_SET, "audience with spaces",
                "JWT audience must contain");
    }

    @Test
    void jwtDecoder_isRs256NimbusDecoderWithoutStartupNetworkDiscovery() {
        assertThat(configuration.paymentOperatorJwtDecoder(
                jwtAuthentication(),
                true
        )).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void jwtValidator_requiresIssuerAudienceTimeAndBoundedVisibleSubject() {
        OAuth2TokenValidator<Jwt> validator = configuration
                .paymentOperatorJwtValidator(jwtAuthentication(), true);

        assertThat(validator.validate(jwt(
                ISSUER,
                AUDIENCE,
                "external-operator",
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(
                "https://wrong-issuer.example.test",
                AUDIENCE,
                "external-operator",
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isTrue();
        Jwt missingSubject = Jwt.withTokenValue("missing-subject")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build();
        assertThat(validator.validate(missingSubject).hasErrors()).isTrue();
        Jwt missingExpiry = Jwt.withTokenValue("missing-expiry")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("external-operator")
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(30))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build();
        assertThat(validator.validate(missingExpiry).hasErrors()).isTrue();
        Jwt missingIssuedAt = Jwt.withTokenValue("missing-issued-at")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("external-operator")
                .audience(List.of(AUDIENCE))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build();
        assertThat(validator.validate(missingIssuedAt).hasErrors()).isTrue();
        Jwt futureIssuedAt = Jwt.withTokenValue("future-issued-at")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("external-operator")
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().plusSeconds(120))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build();
        assertThat(validator.validate(futureIssuedAt).hasErrors()).isTrue();
        Jwt futureNotBefore = Jwt.withTokenValue("future-not-before")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("external-operator")
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(30))
                .notBefore(Instant.now().plusSeconds(120))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scope", PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE)
                .build();
        assertThat(validator.validate(futureNotBefore).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(
                ISSUER,
                "wrong-audience",
                "external-operator",
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(
                ISSUER,
                AUDIENCE,
                " ",
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(
                ISSUER,
                AUDIENCE,
                "a".repeat(101),
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(
                ISSUER,
                AUDIENCE,
                "external-operator",
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(120),
                PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
        )).hasErrors()).isTrue();
    }

    @Test
    void jwtConverter_mapsOnlyExactOperatorScopesAndUsesSubjectAsPrincipal() {
        Authentication authentication = configuration
                .paymentOperatorJwtAuthenticationConverter()
                .convert(jwt(
                        ISSUER,
                        AUDIENCE,
                        "external-operator",
                        Instant.now().minusSeconds(30),
                        Instant.now().plusSeconds(300),
                        PaymentRecoverySecurityConfiguration.RECOVERY_SCOPE
                                + " "
                                + PaymentRecoverySecurityConfiguration
                                        .HANDOFF_INSPECTION_SCOPE
                                + " unrelated.scope"
                ));

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("external-operator");
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactlyInAnyOrder(
                        PaymentRecoverySecurityConfiguration.RECOVERY_AUTHORITY,
                        PaymentRecoverySecurityConfiguration.HANDOFF_INSPECTION_AUTHORITY
                );
    }

    @Test
    void jwtConverter_acceptsStandardScpCollectionClaim() {
        Jwt jwt = Jwt.withTokenValue("scp-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("inspection-operator")
                .audience(List.of(AUDIENCE))
                .issuedAt(Instant.now().minusSeconds(30))
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("scp", List.of(
                        PaymentRecoverySecurityConfiguration.HANDOFF_INSPECTION_SCOPE
                ))
                .build();

        Authentication authentication = configuration
                .paymentOperatorJwtAuthenticationConverter()
                .convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting("authority")
                .containsExactly(
                        PaymentRecoverySecurityConfiguration.HANDOFF_INSPECTION_AUTHORITY
                );
    }

    private void assertInvalidJwtSettings(
            String issuer,
            String jwkSet,
            String audience,
            String messageFragment
    ) {
        PaymentOperatorAuthenticationProperties properties =
                new PaymentOperatorAuthenticationProperties(
                        PaymentOperatorAuthenticationProperties.Mode.JWT,
                        new PaymentOperatorAuthenticationProperties.ExternalJwt(
                                issuer,
                                jwkSet,
                                audience
                        )
                );
        assertThatThrownBy(() -> configuration.paymentRecoveryUsers(
                properties,
                "",
                "",
                true,
                "none"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messageFragment);
    }

    private PaymentOperatorAuthenticationProperties jwtAuthentication() {
        return new PaymentOperatorAuthenticationProperties(
                PaymentOperatorAuthenticationProperties.Mode.JWT,
                new PaymentOperatorAuthenticationProperties.ExternalJwt(
                        ISSUER,
                        JWK_SET,
                        AUDIENCE
                )
        );
    }

    private Jwt jwt(
            String issuer,
            String audience,
            String subject,
            Instant issuedAt,
            Instant expiresAt,
            String scopes
    ) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject(subject)
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", scopes)
                .build();
    }
}
