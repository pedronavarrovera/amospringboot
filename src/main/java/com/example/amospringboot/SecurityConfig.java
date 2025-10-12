package com.example.amospringboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        // ---- OIDC logout: sends user to Entra end_session_endpoint with id_token_hint ----
        LogoutSuccessHandler oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        ((OidcClientInitiatedLogoutSuccessHandler) oidcLogout).setPostLogoutRedirectUri("{baseUrl}/");

        http
            // ---------- Enforce HTTPS so cookies are Secure and redirect_uri is https ----------
            .requiresChannel(ch -> ch.anyRequest().requiresSecure())

            // ---------- Authorization ----------
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/public/**", "/health",
                                 "/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico")
                .permitAll()
                .anyRequest().authenticated()
            )

            // ---------- OAuth2 / OIDC Login ----------
            // Keep defaults, but make success/failure explicit to avoid ambiguous flows.
            .oauth2Login(oauth -> oauth
                .defaultSuccessUrl("/home", true)
                .failureUrl("/login?error")
            )

            // ---------- Sessions (MUST be stateful for OAuth2) ----------
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(s -> s.migrateSession())
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false)
                .sessionRegistry(sessionRegistry())
            )

            // ---------- CSRF ----------
            // Keep CSRF on for browser; ignore pure JSON APIs.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))

            // ---------- Logout (OIDC back-channel) ----------
            .logout(logout -> logout
                .logoutUrl("/logout")                 // POST /logout
                .logoutSuccessHandler(oidcLogout)     // Entra end_session
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                // Keep JSESSIONID deletion (safe here) – the issue is during login, not logout.
                .deleteCookies("JSESSIONID")
            )

            // ---------- API entry point (401 instead of HTML redirect) ----------
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    (request, response, authException) -> response.sendError(401),
                    new AntPathRequestMatcher("/api/**")
                )
            )

            // ---------- Security headers ----------
            .headers(headers -> {
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                    "font-src 'self' https://fonts.gstatic.com; " +
                    "img-src 'self' data:; " +
                    "connect-src 'self' https://api.amo.onl; " +
                    "frame-ancestors 'none';"
                ));
                headers.referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER));
                headers.permissionsPolicy(pp -> pp.policy("geolocation=(), microphone=(), camera=(), payment=()"));
                headers.frameOptions(frame -> frame.deny());
                headers.httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .preload(true)
                    .maxAgeInSeconds(31536000)
                );
            });

        return http.build();
    }

    // Required for maximumSessions(...) tracking
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }
}
