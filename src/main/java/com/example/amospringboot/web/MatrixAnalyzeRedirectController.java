// src/main/java/com/example/amospringboot/web/MatrixAnalyzeRedirectController.java
package com.example.amospringboot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/matrix/analyze")
public class MatrixAnalyzeRedirectController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixAnalyzeRedirectController.class);
    private static final String TARGET = "/matrix/cycle/find/ui";

    @GetMapping
    public String analyzeGet(@AuthenticationPrincipal OidcUser oidc,
                             @AuthenticationPrincipal OAuth2User oauth2,
                             Authentication auth) {
        String who = who(oidc, oauth2, auth);
        LOG.info("ANALYZE_ROUTE_GET invoked by {}", who);
        // Redirect to the supported UI instead of 500/whitelabel
        return "redirect:" + TARGET;
    }

    @PostMapping
    public String analyzePost(@AuthenticationPrincipal OidcUser oidc,
                              @AuthenticationPrincipal OAuth2User oauth2,
                              Authentication auth) {
        String who = who(oidc, oauth2, auth);
        LOG.info("ANALYZE_ROUTE_POST invoked by {}", who);
        return "redirect:" + TARGET;
    }

    private static String who(OidcUser oidc, OAuth2User oauth2, Authentication auth) {
        if (oidc != null) {
            String upn = firstNonBlank(
                oidc.getClaimAsString("upn"),
                oidc.getClaimAsString("preferred_username"),
                oidc.getEmail(),
                oidc.getName()
            );
            if (upn != null) return upn;
        }
        if (oauth2 != null) {
            Object upn = oauth2.getAttributes().get("upn");
            Object pref = oauth2.getAttributes().get("preferred_username");
            Object email = oauth2.getAttributes().get("email");
            String v = firstNonBlank(asString(upn), asString(pref), asString(email), oauth2.getName());
            if (v != null) return v;
        }
        return (auth != null ? auth.getName() : "anonymous");
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
