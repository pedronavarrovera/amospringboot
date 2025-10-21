package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentUiController.class);

    // Keep these consistent with MatrixUiController
    private static final String CONTAINER = "matrices";
    private static final String FALLBACK  = "initial-matrix.b64";
    private static final String VIEW      = "payment";

    private final MatrixApiClient client;
    private final ObjectMapper objectMapper;

    public PaymentUiController(MatrixApiClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /** Prevent binding of authoritative fields (they’re readonly in the UI). */
    @InitBinder("form")
    public void disallowAuthoritative(WebDataBinder binder) {
        binder.setDisallowedFields("blob_name", "out_base", "container", "node_a");
    }

    /** GET /payment — prefill authoritative fields, mirror out_base=blob_name. */
    @GetMapping
    public String page(Model model,
                       @AuthenticationPrincipal OidcUser oidcUser,
                       @AuthenticationPrincipal OAuth2User oauth2User,
                       @RequestParam(value = "blob", required = false) String blob) {

        if (!model.containsAttribute("form")) {
            PaymentForm form = new PaymentForm();

            String latest = (blob != null && !blob.isBlank())
                    ? blob
                    : safeLatest();

            form.setBlob_name(latest);     // authoritative
            form.setOut_base(latest);      // authoritative (must equal blob_name)
            form.setContainer(CONTAINER);  // authoritative
            form.setNode_a(localPart(resolveUpn(oidcUser, oauth2User))); // authoritative

            model.addAttribute("form", form);
        }
        return VIEW;
    }

    /** POST /payment — reassert authoritative values, validate, call API, render result. */
    @PostMapping
    public String submit(@Valid @ModelAttribute("form") PaymentForm form,
                         BindingResult br,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidcUser,
                         @AuthenticationPrincipal OAuth2User oauth2User) {

        // Re-enforce authoritative values regardless of client input
        String latest = safeLatest();
        String nodeA  = localPart(resolveUpn(oidcUser, oauth2User));

        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setContainer(CONTAINER);
        form.setNode_a(nodeA);

        // Field-level validation (Bean + custom)
        if (br.hasErrors()) {
            model.addAttribute("error", "Please fix the highlighted errors and try again.");
            model.addAttribute("form", form);
            return VIEW;
        }

        if (!isPositiveInteger(form.getAmount())) {
            model.addAttribute("error", "Amount must be a positive integer (no decimals).");
            model.addAttribute("form", form);
            return VIEW;
        }

        if (isBlank(form.getNode_b())) {
            model.addAttribute("error", "Node B is required.");
            model.addAttribute("form", form);
            return VIEW;
        }

        try {
            // Map to DTO expected by the Matrix API
            PaymentRequest req = new PaymentRequest();
            req.setBlob_name(form.getBlob_name());
            req.setOut_base(form.getOut_base());           // must equal blob_name
            req.setContainer(form.getContainer());
            req.setNode_a(form.getNode_a());
            req.setNode_b(form.getNode_b());
            req.setAmount(form.getAmount());

            Map<String, Object> result = client.applyPayment(req);
            model.addAttribute("success", "Payment submitted.");
            model.addAttribute("result", result);
            model.addAttribute("resultJson", toJson(result));
        } catch (Exception e) {
            LOG.warn("Payment failed: {}", e.toString());
            model.addAttribute("error", "Payment failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        // Keep showing enforced values and user inputs after submit
        model.addAttribute("form", form);
        return VIEW;
    }

    // ============== helpers (kept stylistically consistent with MatrixUiController) ==============

    private String safeLatest() {
        try {
            return client.latestBlob(CONTAINER, FALLBACK);
        } catch (Exception e) {
            return FALLBACK;
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

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Amount must be >0 and have no fractional part. */
    private static boolean isPositiveInteger(BigDecimal amt) {
        if (amt == null) return false;
        if (amt.signum() <= 0) return false;
        return amt.stripTrailingZeros().scale() <= 0;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "null";
        }
    }

    // --- form object (kept local for UI binding, mirrors PaymentRequest fields we use) ---
    public static class PaymentForm {
        @NotBlank private String blob_name; // authoritative
        @NotBlank private String node_a;    // authoritative (UPN local-part)
        @NotBlank private String node_b;    // user-entered
        @NotBlank private String container; // authoritative
        @NotBlank private String out_base;  // authoritative (must equal blob_name)
        private BigDecimal amount;          // user-entered (must be integer >0)

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}


