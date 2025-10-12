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
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.filter.ForwardedHeaderFilter;
import org.springframework.http.HttpStatus;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        // --- OIDC logout to Entra end_session_endpoint with id_token_hint + post_logout_redirect_uri ---
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/");

        http
            // --- Honor X-Forwarded-* from DO proxy to reconstruct {baseUrl} correctly ---
            // (ForwardedHeaderFilter bean below actually applies the headers.)
            // --- Enforce HTTPS at the edge; ok to keep requiresSecure() here as well ---
            .requiresChannel(ch -> ch.anyRequest().requiresSecure())

            // --- Authorization rules ---
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/public/**", "/health", "/_health/**",
                                 "/css/**", "/js/**", "/images/**", "/webjars/**", "/favicon.ico")
                .permitAll()
                .anyRequest().authenticated()
            )

            // --- OAuth2 / OIDC login (keep session-based defaults) ---
            .oauth2Login(oauth -> oauth
                .defaultSuccessUrl("/home", true)
                .failureUrl("/login?error")
            )

            // --- Sessions MUST be stateful for OAuth2 (no STATELESS) ---
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(s -> s.migrateSession())
                .maximumSessions(1)
                .maxSessionsPreventsLogin(false)
                .sessionRegistry(sessionRegistry())
            )

            // --- CSRF: keep on for browser; ignore pure JSON APIs if needed ---
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))

            // --- Logout with OIDC back-channel to Entra ---
            .logout(logout -> logout
                .logoutUrl("/logout")                 // POST /logout
                .logoutSuccessHandler(oidcLogout)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            )

            // --- APIs should get 401 JSON instead of HTML redirect ---
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**"))
            )

            // --- Security headers ---
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

    // Critical when behind DigitalOcean/App Platform LB so {baseUrl} & cookies line up
    @Bean
    public ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
