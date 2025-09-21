// Run this Spring Boot application with Maven using:
// Default / PROD (DigitalOcean)
// SPRING_PROFILES_ACTIVE=prod (or leave unset; default block is fine)
// mvn spring-boot:run
// alternative profile Activate with:  SPRING_PROFILES_ACTIVE=local-ssl
// Run with SPRING_PROFILES_ACTIVE=local-ssl and set KEYSTORE_PASSWORD
// $env:SPRING_PROFILES_ACTIVE="local-ssl"
// mvn spring-boot:run
// AZURE_CLIENT_ID=88608b1d-fc02-43ff-a7eb-df25173cbf1b
// AZURE_CLIENT_SECRET=<put secret here>
// AZURE_TENANT_ID=51f69a44-f7c1-449a-ab03-37c6980a3a9f
// (If DB) JDBC_URL, DB_USER, DB_PASS
// In Azure Entra ID → App registrations for this client:
// Add Redirect URI: https://app.amo.onl/login/oauth2/code/azure
//
// Once running, access it at:
// https://localhost:8443
//
// Note: Since the app uses a self-signed certificate in development,
// the browser will show a warning about an untrusted certificate.
// You can safely click "Proceed" to continue in this test environment.

package com.example.amospringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication is a convenience annotation that bundles:
// - @Configuration: marks the class as a source of bean definitions
// - @EnableAutoConfiguration: enables Spring Boot’s auto-configuration
// - @ComponentScan: scans the package for components, configurations, and services
@SpringBootApplication
public class AmospringbootApplication {

    // The entry point of the Spring Boot application.
    // SpringApplication.run() bootstraps the app, starting the embedded server
    // (e.g., Tomcat) and initializing the Spring context.
    public static void main(String[] args) {
        SpringApplication.run(AmospringbootApplication.class, args);
    }
}
