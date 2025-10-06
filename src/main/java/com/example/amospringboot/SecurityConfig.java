package com.example.amospringboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        // OIDC logout -> Microsoft Entra end_session_endpoint with id_token_hint + post_logout_redirect_uri
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/");

        http
            // ---------- Authorization ----------
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/public/**", "/health",
                    "/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico"
                ).permitAll()
                .anyRequest().authenticated()
            )

            // ---------- OAuth2 / OIDC Login ----------
            .oauth2Login(Customizer.withDefaults())

            // ---------- Sessions ----------
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sessionFixation -> sessionFixation.migrateSession())
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false) // replace old session on new login
                .sessionRegistry(sessionRegistry())
            )

            // ---------- CSRF ----------
            // Enable for browser; ignore pure JSON APIs
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")
            )

            // ---------- Logout ----------
            .logout(logout -> logout
                // Keep your <a href="/logout"> working by allowing GET.
                // (Best practice is POST with CSRF token; switch later if you can.)
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "GET"))
                .logoutSuccessHandler(oidcLogout)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )

            // ---------- API entry point (401 instead of redirect) ----------
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    (request, response, authException) -> response.sendError(401),
                    new AntPathRequestMatcher("/api/**")
                )
            )

            // ---------- Security headers ----------
            .headers(headers -> {
                // Content Security Policy (CSP)
                headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; " +
                    "script-src 'self' 'unsafe-inline'; " +                 //
                    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; " +
                    "font-src 'self' https://fonts.gstatic.com; " +
                    "img-src 'self' data:; " +
                    "connect-src 'self'; " +
                    "frame-ancestors 'none';"
                ));

                // Referrer-Policy
                headers.referrerPolicy(ref -> ref.policy(ReferrerPolicy.NO_REFERRER));

                // Permissions-Policy (supported in Spring Security 6.2.x)
                headers.permissionsPolicy(pp -> pp.policy(
                    "geolocation=(), microphone=(), camera=(), payment=()"
                ));

                // X-Frame-Options (defense in depth; align with frame-ancestors)
                headers.frameOptions(frame -> frame.deny());

                // HSTS (enable only if ALL subdomains are HTTPS)
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

