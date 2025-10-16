package com.example.amospringboot.matrix.controller;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

@Controller
public class PaymentController {

    private static final String CONTAINER = "matrices";
    private static final String NODE_B = "matrices";
    private static final String FALLBACK_MATRIX = "initial-matrix.b64";

    private final MatrixApiClient matrixApi;

    public PaymentController(MatrixApiClient matrixApi) {
        this.matrixApi = matrixApi;
    }

    @InitBinder("form")
    public void disallowClientControlledFields(WebDataBinder binder) {
        // Never accept these from the browser
        binder.setDisallowedFields("blob_name", "node_a", "node_b", "out_base");
    }

    @GetMapping("/payment")
    public String paymentForm(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal OAuth2User oauth2User,
            Model model) {

        String upn = resolveUpn(oidcUser, oauth2User);
        String nodeA = localPart(upn);
        String latest = safeLatest();

        PaymentRequest form = new PaymentRequest();
        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setNode_a(nodeA);
        form.setNode_b(NODE_B);

        model.addAttribute("form", form);
        return "payment";
    }

    @PostMapping("/payment")
    public String makePayment(
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal OAuth2User oauth2User,
            @Valid @ModelAttribute("form") PaymentRequest form,
            BindingResult errors,
            Model model) {

        if (errors.hasErrors()) return "payment";

        // Authoritative values
        String upn = resolveUpn(oidcUser, oauth2User);
        String nodeA = localPart(upn);
        String latest = safeLatest();

        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setNode_a(nodeA);
        form.setNode_b(NODE_B);

        // Call your backend payment endpoint
        var result = matrixApi.payment(form);

        model.addAttribute("success", true);
        model.addAttribute("result", result);
        return "payment";
    }

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

