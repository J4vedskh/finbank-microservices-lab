package com.banking.payment.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration
public class PaymentRecoverySecurityConfiguration {
    public static final String RECOVERY_AUTHORITY = "PAYMENT_OUTBOX_RECOVERY";
    private static final Pattern BCRYPT_HASH = Pattern.compile(
            "^\\{bcrypt}\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$"
    );

    @Bean
    SecurityFilterChain paymentSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .requiresChannel(channels -> channels
                        .requestMatchers(
                                HttpMethod.POST,
                                "/internal/payment-outbox/*/recovery"
                        ).requiresSecure())
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
                                HttpMethod.POST,
                                "/internal/payment-outbox/*/recovery"
                        ).hasAuthority(RECOVERY_AUTHORITY)
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable());
        return http.build();
    }

    @Bean
    PasswordEncoder paymentPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService paymentRecoveryUsers(
            @Value("${payment.recovery.operator.username:}") String username,
            @Value("${payment.recovery.operator.password-hash:}") String passwordHash,
            @Value("${server.ssl.enabled:false}") boolean serverSslEnabled
    ) {
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
                        .authorities(RECOVERY_AUTHORITY)
                        .build()
        );
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
}
