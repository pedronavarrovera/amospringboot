package com.example.amospringboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        // Builds an OIDC logout handler that sends users to the Microsoft logout endpoint
        // with id_token_hint and a dynamic post_logout_redirect_uri = {baseUrl}/
        var oidcLogout = new OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/");

        http
            // Authorize everything by default; you can open up public paths here if needed
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/public/**", "/health").permitAll()
                .anyRequest().authenticated()
            )

            // OAuth2 login with defaults (uses your application.yml)
            .oauth2Login(Customizer.withDefaults())

            // Use HTTP session (JSESSIONID) to persist Authentication
            .sessionManagement(sm -> sm
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(sessionFixation -> sessionFixation.migrateSession())
                // optional: limit concurrent sessions per user
                //.maximumSessions(1)
            )

            // CSRF is recommended for browser apps.
            // If you expose pure JSON APIs under /api/**, you can exclude them:
            .csrf(csrf -> csrf
                //.ignoringRequestMatchers("/api/**")
                .disable() // <-- enable if you have forms; leave disabled if it's an API-only UI
            )

            // Proper OIDC logout
            .logout(logout -> logout
                .logoutSuccessHandler(oidcLogout)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }
}
