package com.example.amospringboot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * SecurityConfig configures Spring Security for the application.
 * 
 * It enforces authentication on all requests and integrates
 * OAuth2 login with Microsoft Entra ID.
 */
@Configuration
public class SecurityConfig {

    /**
     * Defines the security filter chain for handling HTTP requests.
     * 
     * @param http HttpSecurity object used to configure web security
     * @return The configured SecurityFilterChain
     * @throws Exception If security configuration fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 🔐 Require authentication for every request in the app.
            // If a user is not authenticated, they will be redirected
            // to the Microsoft Entra ID login page.
            .authorizeHttpRequests(authz -> authz
                .anyRequest().authenticated()
            )

            // ⚡ Enable OAuth2 login with default settings.
            // Spring Boot automatically uses application.yml/properties config
            // for Microsoft Entra ID (client-id, client-secret, etc.).
            .oauth2Login(Customizer.withDefaults())

            // 🚪 Configure logout behavior.
            // After logout, the user will be redirected to Microsoft’s logout endpoint,
            // which also clears their Entra ID session, then redirected back to localhost:8080.
            .logout(logout -> logout
                .logoutSuccessUrl(
                    "https://login.microsoftonline.com/common/oauth2/v2.0/logout?post_logout_redirect_uri=http://localhost:8080"
                )
                .invalidateHttpSession(true)   // Clear session data
                .clearAuthentication(true)     // Remove authentication info
                .deleteCookies("JSESSIONID")   // Remove session cookie
            );

        // ✅ Return the built security configuration
        return http.build();
    }
}
