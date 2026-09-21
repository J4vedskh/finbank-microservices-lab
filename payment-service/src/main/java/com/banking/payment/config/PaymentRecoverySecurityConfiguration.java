package com.banking.payment.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
@EnableConfigurationProperties(PaymentOperatorAuthenticationProperties.class)
public class PaymentRecoverySecurityConfiguration {
    public static final String RECOVERY_AUTHORITY = "PAYMENT_OUTBOX_RECOVERY";
    public static final String HANDOFF_INSPECTION_AUTHORITY =
            "PAYMENT_OUTBOX_HANDOFF_INSPECTION";
    public static final String RECOVERY_SCOPE = "payment.outbox.recovery";
    public static final String HANDOFF_INSPECTION_SCOPE =
            "payment.outbox.handoff-inspection";
    private static final Pattern BCRYPT_HASH = Pattern.compile(
            "^\\{bcrypt}\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$"
    );

    @Bean
    SecurityFilterChain paymentSecurityFilterChain(
            HttpSecurity http,
            PaymentOperatorAuthenticationProperties authenticationProperties,
            ApplicationContext applicationContext
    ) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .requiresChannel(channels -> channels
                        .requestMatchers("/internal/payment-outbox/**")
                        .requiresSecure())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/payments", "/payments/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/payments").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/metrics"
                        ).permitAll()
                        .requestMatchers(
                                HttpMethod.GET,
                                "/internal/payment-outbox/dead-letter-handoffs"
                        ).hasAuthority(HANDOFF_INSPECTION_AUTHORITY)
                        .requestMatchers(
                                HttpMethod.POST,
                                "/internal/payment-outbox/*/recovery"
                        ).hasAuthority(RECOVERY_AUTHORITY)
                        .anyRequest().denyAll())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        if (authenticationProperties.mode()
                == PaymentOperatorAuthenticationProperties.Mode.BASIC) {
            http.httpBasic(Customizer.withDefaults());
        } else {
            JwtDecoder jwtDecoder = applicationContext.getBean(
                    "paymentOperatorJwtDecoder",
                    JwtDecoder.class
            );
            http
                    .httpBasic(AbstractHttpConfigurer::disable)
                    .oauth2ResourceServer(oauth2 -> oauth2
                            .authenticationEntryPoint(this::bearerAuthenticationFailure)
                            .jwt(jwt -> jwt
                                    .decoder(jwtDecoder)
                                    .jwtAuthenticationConverter(
                                            paymentOperatorJwtAuthenticationConverter()
                                    )));
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "payment.recovery.authentication",
            name = "mode",
            havingValue = "jwt"
    )
    JwtDecoder paymentOperatorJwtDecoder(
            PaymentOperatorAuthenticationProperties authenticationProperties,
            @Value("${server.ssl.enabled:false}") boolean serverSslEnabled
    ) {
        ValidatedJwtSettings settings = requireJwtSettings(
                authenticationProperties,
                serverSslEnabled
        );
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(settings.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(paymentOperatorJwtValidator(settings));
        return decoder;
    }

    @Bean
    PasswordEncoder paymentPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService paymentRecoveryUsers(
            PaymentOperatorAuthenticationProperties authenticationProperties,
            @Value("${payment.recovery.operator.username:}") String username,
            @Value("${payment.recovery.operator.password-hash:}") String passwordHash,
            @Value("${server.ssl.enabled:false}") boolean serverSslEnabled,
            @Value("${server.forward-headers-strategy:none}") String forwardHeadersStrategy
    ) {
        if (authenticationProperties.mode()
                == PaymentOperatorAuthenticationProperties.Mode.JWT) {
            if (hasText(username) || hasText(passwordHash)) {
                throw new IllegalStateException(
                        "Local operator credentials must be blank in JWT mode"
                );
            }
            requireForwardHeadersDisabled(forwardHeadersStrategy);
            requireJwtSettings(authenticationProperties, serverSslEnabled);
            return new InMemoryUserDetailsManager();
        }
        rejectJwtSettingsInBasicMode(authenticationProperties.externalJwt());
        boolean usernameConfigured = hasText(username);
        boolean passwordConfigured = hasText(passwordHash);
        if (usernameConfigured != passwordConfigured) {
            throw new IllegalStateException(
                    "Recovery operator username and password hash must be configured together"
            );
        }
        if (!usernameConfigured) {
            return new InMemoryUserDetailsManager();
        }
        requireForwardHeadersDisabled(forwardHeadersStrategy);
        if (!serverSslEnabled) {
            throw new IllegalStateException(
                    "Recovery operator credentials require server SSL to be enabled"
            );
        }

        String normalizedUsername = username.strip();
        if (!isValidUsername(normalizedUsername)) {
            throw new IllegalStateException(
                    "Recovery operator username must contain 1 to 100 visible ASCII characters without a colon"
            );
        }
        String normalizedPasswordHash = passwordHash.strip();
        Matcher matcher = BCRYPT_HASH.matcher(normalizedPasswordHash);
        if (!matcher.matches() || !isValidBcryptCost(matcher.group(1))) {
            throw new IllegalStateException("Recovery operator password must be a BCrypt hash");
        }

        return new InMemoryUserDetailsManager(
                User.withUsername(normalizedUsername)
                        .password(normalizedPasswordHash)
                        .authorities(RECOVERY_AUTHORITY, HANDOFF_INSPECTION_AUTHORITY)
                        .build()
        );
    }

    JwtAuthenticationConverter paymentOperatorJwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter =
                new JwtGrantedAuthoritiesConverter();
        scopeConverter.setAuthorityPrefix("");

        JwtAuthenticationConverter authenticationConverter =
                new JwtAuthenticationConverter();
        authenticationConverter.setPrincipalClaimName("sub");
        authenticationConverter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> scopes = scopeConverter.convert(jwt);
            LinkedHashSet<GrantedAuthority> authorities = new LinkedHashSet<>();
            if (scopes != null) {
                for (GrantedAuthority scope : scopes) {
                    if (RECOVERY_SCOPE.equals(scope.getAuthority())) {
                        authorities.add(new SimpleGrantedAuthority(RECOVERY_AUTHORITY));
                    } else if (HANDOFF_INSPECTION_SCOPE.equals(scope.getAuthority())) {
                        authorities.add(new SimpleGrantedAuthority(
                                HANDOFF_INSPECTION_AUTHORITY
                        ));
                    }
                }
            }
            return authorities;
        });
        return authenticationConverter;
    }

    OAuth2TokenValidator<Jwt> paymentOperatorJwtValidator(
            PaymentOperatorAuthenticationProperties authenticationProperties,
            boolean serverSslEnabled
    ) {
        return paymentOperatorJwtValidator(requireJwtSettings(
                authenticationProperties,
                serverSslEnabled
        ));
    }

    private OAuth2TokenValidator<Jwt> paymentOperatorJwtValidator(
            ValidatedJwtSettings settings
    ) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators
                .createDefaultWithIssuer(settings.issuerUri());
        OAuth2TokenValidator<Jwt> audience = jwt ->
                jwt.getAudience().contains(settings.audience())
                        ? OAuth2TokenValidatorResult.success()
                        : invalidToken("JWT audience is not accepted");
        OAuth2TokenValidator<Jwt> subject = jwt ->
                isVisibleAscii(jwt.getSubject(), 100)
                        ? OAuth2TokenValidatorResult.success()
                        : invalidToken("JWT subject is invalid");
        OAuth2TokenValidator<Jwt> requiredTimes = jwt -> {
            Instant issuedAt = jwt.getIssuedAt();
            Instant expiresAt = jwt.getExpiresAt();
            if (issuedAt == null || expiresAt == null) {
                return invalidToken("JWT issued-at and expiry claims are required");
            }
            if (issuedAt.isAfter(Instant.now().plusSeconds(60))) {
                return invalidToken("JWT issued-at claim is in the future");
            }
            if (!expiresAt.isAfter(issuedAt)) {
                return invalidToken("JWT expiry must be after issued-at");
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(
                defaults,
                audience,
                subject,
                requiredTimes
        );
    }

    private ValidatedJwtSettings requireJwtSettings(
            PaymentOperatorAuthenticationProperties authenticationProperties,
            boolean serverSslEnabled
    ) {
        if (!serverSslEnabled) {
            throw new IllegalStateException(
                    "JWT operator authentication requires server SSL to be enabled"
            );
        }
        PaymentOperatorAuthenticationProperties.ExternalJwt externalJwt =
                authenticationProperties.externalJwt();
        String issuerUri = requireHttpsUri(externalJwt.issuerUri(), "issuer URI");
        String jwkSetUri = requireHttpsUri(externalJwt.jwkSetUri(), "JWK set URI");
        String audience = externalJwt.audience() == null
                ? ""
                : externalJwt.audience().strip();
        if (!isVisibleAscii(audience, 200)) {
            throw new IllegalStateException(
                    "JWT audience must contain 1 to 200 visible ASCII characters"
            );
        }
        return new ValidatedJwtSettings(issuerUri, jwkSetUri, audience);
    }

    private String requireHttpsUri(String value, String label) {
        String normalized = value == null ? "" : value.strip();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute()
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalStateException(
                        "JWT " + label + " must be an absolute HTTPS URI without user info, query, or fragment"
                );
            }
            return uri.toString();
        } catch (URISyntaxException failure) {
            throw new IllegalStateException("JWT " + label + " is invalid", failure);
        }
    }

    private void rejectJwtSettingsInBasicMode(
            PaymentOperatorAuthenticationProperties.ExternalJwt externalJwt
    ) {
        if (hasText(externalJwt.issuerUri())
                || hasText(externalJwt.jwkSetUri())
                || hasText(externalJwt.audience())) {
            throw new IllegalStateException(
                    "JWT settings must be blank in Basic authentication mode"
            );
        }
    }

    private OAuth2TokenValidatorResult invalidToken(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                description,
                null
        ));
    }

    private void requireForwardHeadersDisabled(String strategy) {
        if (strategy == null || !"none".equalsIgnoreCase(strategy.strip())) {
            throw new IllegalStateException(
                    "Operator authentication requires server.forward-headers-strategy=none"
            );
        }
    }

    private void bearerAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException failure
    ) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isValidUsername(String username) {
        return !username.isEmpty()
                && username.length() <= 100
                && username.chars().allMatch(character ->
                        character >= 0x21 && character <= 0x7e && character != ':');
    }

    private boolean isValidBcryptCost(String costText) {
        int cost = Integer.parseInt(costText);
        return cost >= 4 && cost <= 31;
    }

    private boolean isVisibleAscii(String value, int maximumLength) {
        return value != null
                && !value.isEmpty()
                && value.length() <= maximumLength
                && value.chars().allMatch(character ->
                        character >= 0x21 && character <= 0x7e);
    }

    private record ValidatedJwtSettings(
            String issuerUri,
            String jwkSetUri,
            String audience
    ) {
    }
}
