package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

@Controller
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentUiController {

    private static final String VIEW = "payment";
    private static final String CONTAINER = "matrices";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";
    private static final String OUT_BASE = "payment-update";

    private final MatrixApiClient client;
    private final ObjectMapper objectMapper;

    /**
     * Prevent client-side tampering of authoritative fields (readonly in the UI).
     */
    @InitBinder("form")
    void initBinder(WebDataBinder binder) {
        binder.setDisallowedFields("node_a", "container", "blob_name", "out_base");
    }

    @GetMapping
    public String page(
            @RequestParam(value = "blob", required = false) String blob,
            Model model,
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal OAuth2User oauth2User
    ) {
        // If flash already provided "form", reuse it; otherwise build defaults
        if (!model.containsAttribute("form")) {
            PaymentRequest form = new PaymentRequest();
            form.setBlob_name(blob != null && !blob.isBlank() ? blob : FALLBACK_BLOB);
            form.setContainer(CONTAINER);
            form.setNode_a(resolveCurrentUser(oidcUser, oauth2User));
            form.setOut_base(OUT_BASE);
            // Leave node_b and amount empty for user input
            model.addAttribute("form", form);
        }
        return VIEW;
    }

    @PostMapping
    public String submit(
            @Valid @ModelAttribute("form") PaymentRequest form,
            BindingResult br,
            RedirectAttributes ra,
            @AuthenticationPrincipal OidcUser oidcUser,
            @AuthenticationPrincipal OAuth2User oauth2User
    ) {
        // Reassert authoritative server-side values (ignore anything posted for these fields)
        form.setContainer(CONTAINER);
        form.setOut_base(OUT_BASE);
        if (form.getBlob_name() == null || form.getBlob_name().isBlank()) {
            form.setBlob_name(FALLBACK_BLOB);
        }
        form.setNode_a(resolveCurrentUser(oidcUser, oauth2User));

        // Validation
        if (br.hasErrors()) {
            ra.addFlashAttribute("error", "Please fix the highlighted errors and try again.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            return "redirect:/payment";
        }
        if (form.getNode_b() == null || form.getNode_b().isBlank()) {
            ra.addFlashAttribute("error", "Node B is required.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            return "redirect:/payment";
        }
        if (form.getAmount() == null || form.getAmount().compareTo(new BigDecimal("0.00")) <= 0) {
            ra.addFlashAttribute("error", "Amount must be greater than 0.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            return "redirect:/payment";
        }

        try {
            // Call downstream service
            // Adjust the return type to whatever your client uses (e.g., Map<String,Object>, ResponseEntity<...>, etc.)
            Object result = invokePayment(form);

            // Serialize result as JSON string for the template's <script type="application/json">
            String json = objectMapper.writeValueAsString(result);
            ra.addFlashAttribute("success", "Payment submitted successfully.");
            ra.addFlashAttribute("resultJson", json);

        } catch (Exception ex) {
            ra.addFlashAttribute("error", "Payment failed: " + ex.getMessage());
            // Include a small machine-readable note as well
            ra.addFlashAttribute("resultJson", "{\"status\":\"error\",\"note\":\"client_exception\"}");
            ra.addFlashAttribute("form", scrubForFlash(form));
        }

        // PRG pattern
        return "redirect:/payment";
    }

    /**
     * Centralize how we call the Matrix API client so you can adapt it to your client signature.
     */
    private Object invokePayment(PaymentRequest form) {
        // Example 1: client returns a Map payload directly
        // return client.applyPayment(form);

        // Example 2: client returns ResponseEntity<Map<String, Object>>
        ResponseEntity<Map<String, Object>> resp = client.applyPayment(form);
        if (!resp.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Upstream returned " + resp.getStatusCode());
        }
        return Objects.requireNonNullElse(resp.getBody(), Map.of("status", "ok"));
    }

    /**
     * Derive a stable identifier for Node A from the authenticated principal.
     */
    private String resolveCurrentUser(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
            // prefer email, fall back to name/preferred_username/sub
            String email = oidc.getEmail();
            if (email != null && !email.isBlank()) return email;
            Object preferred = oidc.getClaims().get("preferred_username");
            if (preferred instanceof String s && !s.isBlank()) return s;
            String name = oidc.getFullName();
            if (name != null && !name.isBlank()) return name;
            String sub = oidc.getSubject();
            if (sub != null && !sub.isBlank()) return sub;
        }
        if (oauth2 != null) {
            Object email = oauth2.getAttributes().get("email");
            if (email instanceof String s && !s.isBlank()) return s;
            Object preferred = oauth2.getAttributes().get("preferred_username");
            if (preferred instanceof String s && !s.isBlank()) return s;
            Object name = oauth2.getAttributes().get("name");
            if (name instanceof String s && !s.isBlank()) return s;
        }
        return "unknown-user";
    }

    /**
     * Remove/neutralize fields we reassert anyway, to avoid surprising echoes after redirect.
     */
    private PaymentRequest scrubForFlash(PaymentRequest form) {
        PaymentRequest copy = new PaymentRequest();
        copy.setBlob_name(form.getBlob_name()); // keep visible context
        copy.setContainer(CONTAINER);           // visible but server-controlled
        copy.setNode_a(form.getNode_a());       // visible but server-controlled
        copy.setOut_base(OUT_BASE);             // visible but server-controlled
        copy.setNode_b(form.getNode_b());       // user input
        copy.setAmount(form.getAmount());       // user input
        return copy;
    }
}
