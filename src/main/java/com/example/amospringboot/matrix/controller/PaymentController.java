package com.example.amospringboot.matrix.controller;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class PaymentController {

    private static final String CONTAINER = "matrices";
    
    private static final String FALLBACK_MATRIX = "initial-matrix.b64";

    private final MatrixApiClient matrixApi;

    public PaymentController(MatrixApiClient matrixApi) {
        this.matrixApi = matrixApi;
    }

    /**
     * POST /api/payment – JSON endpoint (no view). Enforces authoritative values:
     * - node_a from UPN (local-part)
     * - blob_name & out_base from latest blob in container
     * - node_b fixed to "matrices"
     */
    @PostMapping(path = "/api/payment", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> makePayment(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal OAuth2User oauth2User,
            @Valid @RequestBody PaymentRequest body) {

        String upn = resolveUpn(oidcUser, oauth2User);
        String nodeA = localPart(upn);
        String latest = safeLatest();

        body.setBlob_name(latest);
        body.setOut_base(latest);
        body.setNode_a(nodeA);

        // node_b comes from user (required @NotBlank in DTO)
        if (body.getContainer() == null || body.getContainer().isBlank()) {
            body.setContainer("matrices");
        }

        return matrixApi.payment(body);
    }


    /* -------------------- helpers -------------------- */

    private String safeLatest() {
        try {
            return matrixApi.latestBlob(CONTAINER, FALLBACK_MATRIX);
        } catch (Exception e) {
            return FALLBACK_MATRIX;
        }
    }

    private static String resolveUpn(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
            String v = firstNonBlank(
                    oidc.getClaimAsString("upn"),
                    oidc.getClaimAsString("preferred_username"),
                    oidc.getEmail(),
                    oidc.getName()
            );
            if (v != null) return v;
        }
        if (oauth2 != null) {
            String v = firstNonBlank(
                    (String) oauth2.getAttributes().get("upn"),
                    (String) oauth2.getAttributes().get("preferred_username"),
                    (String) oauth2.getAttributes().get("email"),
                    oauth2.getName()
            );
            if (v != null) return v;
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null ? a.getName() : "unknown";
    }

    private static String localPart(String s) {
        if (s == null) return "unknown";
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
