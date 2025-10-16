package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final String CONTAINER = "matrices";
    private static final String NODE_B   = "matrices";
    private static final String FALLBACK = "initial-matrix.b64";

    private final MatrixApiClient client;

    public PaymentUiController(MatrixApiClient client) {
        this.client = client;
    }

    /** Disallow binding for authoritative fields (prevent tampering via request params). */
    @InitBinder("form")
    public void disallowClientControlledFields(WebDataBinder binder) {
        binder.setDisallowedFields("blob_name", "node_a", "node_b", "out_base");
    }

    /** GET /payment – render form pre-populated with authoritative values. */
    @GetMapping
    public String page(Model model,
                       @AuthenticationPrincipal OidcUser oidcUser,
                       @AuthenticationPrincipal OAuth2User oauth2User) {

        String upn    = resolveUpn(oidcUser, oauth2User);
        String nodeA  = localPart(upn);
        String latest = safeLatest();

        Form form = new Form();
        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setNode_a(nodeA);
        form.setNode_b(NODE_B);
        // Optional default container:
        // form.setContainer(CONTAINER);

        model.addAttribute("form", form);
        return "payment";
    }

    /** POST /payment – enforce authoritative values and submit to Matrix API. */
    @PostMapping
    public String submit(@Valid @ModelAttribute("form") Form form,
                         BindingResult errors,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidcUser,
                         @AuthenticationPrincipal OAuth2User oauth2User) {

        if (errors.hasErrors()) {
            return "payment";
        }
        if (form.getAmount() == null || form.getAmount().signum() <= 0) {
            model.addAttribute("error", "Amount must be greater than 0");
            return "payment";
        }

        // Authoritative server-side values (ignore anything from client for these)
        String upn    = resolveUpn(oidcUser, oauth2User);
        String nodeA  = localPart(upn);
        String latest = safeLatest();

        PaymentRequest req = new PaymentRequest();
        req.setBlob_name(latest);
        req.setOut_base(latest);
        req.setNode_a(nodeA);
        req.setNode_b(NODE_B);
        req.setAmount(form.getAmount());
        req.setContainer(form.getContainer());

        Map<String, Object> result = client.payment(req);
        model.addAttribute("result", result);

        // Also re-show the enforced values in the form after submit
        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setNode_a(nodeA);
        form.setNode_b(NODE_B);
        model.addAttribute("form", form);

        return "payment";
    }

    /* -------------------- helpers -------------------- */

    private String safeLatest() {
        try {
            // Uses your extended MatrixApiClient
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

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** Form backing bean for server-rendered page */
    public static class Form {
        @NotBlank private String blob_name; // server-enforced
        @NotBlank private String node_a;    // server-enforced (UPN local-part)
        @NotBlank private String node_b;    // server-enforced ("matrices")
        @NotNull  private BigDecimal amount;
        private String out_base;            // server-enforced (latest blob)
        private String container;           // optional

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }
}
