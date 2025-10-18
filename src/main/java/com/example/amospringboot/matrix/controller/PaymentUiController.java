package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

import java.util.Map;

@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final String CONTAINER = "matrices";
    private static final String FALLBACK = "initial-matrix.b64";

    private final MatrixApiClient client;
    private final ObjectMapper objectMapper;

    public PaymentUiController(MatrixApiClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /** Disallow binding for authoritative fields (prevent tampering via request params). */
    @InitBinder("form")
    public void disallowClientControlledFields(WebDataBinder binder) {
        // These are always enforced server-side
        binder.setDisallowedFields("blob_name", "node_a", "out_base", "container");
    }

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
        form.setContainer(CONTAINER);   // authoritative default
        // node_b intentionally left blank for user input

        model.addAttribute("form", form);
        return "payment";
    }

    @PostMapping
    public String submit(@Valid @ModelAttribute("form") Form form,
                         BindingResult errors,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidcUser,
                         @AuthenticationPrincipal OAuth2User oauth2User) {

        if (errors.hasErrors()) return "payment";

        if (form.getAmount() == null || form.getAmount() < 1) {
            model.addAttribute("error", "Amount must be greater than 0");
            return "payment";
        }
        if (isBlank(form.getNode_b())) {
            model.addAttribute("error", "Node B is required");
            return "payment";
        }

        String upn    = resolveUpn(oidcUser, oauth2User);
        String nodeA  = localPart(upn);
        String latest = safeLatest();

        // Build request with authoritative values
        PaymentRequest req = new PaymentRequest();
        req.setBlob_name(latest);
        req.setOut_base(latest);
        req.setNode_a(nodeA);
        req.setNode_b(form.getNode_b());      // user-entered
        req.setContainer(CONTAINER);          // enforce default
        req.setAmount(form.getAmount());      // Integer; if your DTO expects BigDecimal, adjust here

        Map<String, Object> result = client.payment(req);

        // Serialize JSON for the template <script id="paymentResult">
        model.addAttribute("resultJson", safeJson(result));

        // Optional: drive banners your template already supports
        Object status = result.get("status");
        if ("ok".equals(String.valueOf(status))) {
            model.addAttribute("success", "Payment applied successfully.");
        } else {
            model.addAttribute("errorMessage", String.valueOf(result.getOrDefault("note", status)));
        }

        // Re-show enforced values after submit
        form.setBlob_name(latest);
        form.setOut_base(latest);
        form.setNode_a(nodeA);
        form.setContainer(CONTAINER);
        model.addAttribute("form", form);

        return "payment";
    }

    /* -------------------- helpers -------------------- */

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

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"status\":\"error\",\"note\":\"serialization_failed\"}";
        }
    }

    /** Form backing bean for server-rendered page */
    public static class Form {
        @NotBlank private String blob_name; // server-enforced
        @NotBlank private String node_a;    // server-enforced (UPN local-part)

        @NotBlank private String node_b;    // user-entered (recipient)

        @NotNull @Min(1)
        private Integer amount;             // use Integer; if you prefer BigDecimal, convert in submit()

        private String out_base;            // server-enforced (latest blob)
        private String container;           // server-enforced "matrices"

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public Integer getAmount() { return amount; }
        public void setAmount(Integer amount) { this.amount = amount; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }
}
