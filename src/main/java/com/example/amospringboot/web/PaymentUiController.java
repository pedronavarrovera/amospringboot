package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.PaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Handles the /payment UI form and posts payments to the matrix API.
 * Logs every confirmed payment (success/fail/exception) to payment.audit.
 */
@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentUiController.class);
    /** Dedicated audit logger routed to payment-audit.log via logback-spring.xml */
    private static final Logger AUDIT = LoggerFactory.getLogger("payment.audit");

    private static final String VIEW = "payment";
    private static final String CONTAINER = "matrices";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";
    private static final String OUT_BASE = "payment-update";

    private final WebClient matrixWebClient;
    private final ObjectMapper objectMapper;
    private final String paymentPath;

    public PaymentUiController(
            WebClient matrixWebClient,
            ObjectMapper objectMapper,
            @Value("${matrix.api.payment-path:/payment}") String paymentPath
    ) {
        this.matrixWebClient = matrixWebClient;
        this.objectMapper = objectMapper;
        this.paymentPath = paymentPath;
    }

    /** Prevent tampering of authoritative fields (readonly in UI). */
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
        if (!model.containsAttribute("form")) {
            PaymentRequest form = new PaymentRequest();
            form.setBlob_name(blob != null && !blob.isBlank() ? blob : FALLBACK_BLOB);
            form.setContainer(CONTAINER);
            form.setNode_a(resolveCurrentUser(oidcUser, oauth2User));
            form.setOut_base(OUT_BASE);
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
        final String correlationId = UUID.randomUUID().toString();
        final String principalId = resolveCurrentUser(oidcUser, oauth2User);

        // Reassert authoritative fields
        form.setContainer(CONTAINER);
        form.setOut_base(OUT_BASE);
        if (form.getBlob_name() == null || form.getBlob_name().isBlank()) {
            form.setBlob_name(FALLBACK_BLOB);
        }
        form.setNode_a(principalId);

        // --- Validation ---
        if (br.hasErrors()) {
            ra.addFlashAttribute("error", "Please fix the highlighted errors and try again.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }
        if (form.getNode_b() == null || form.getNode_b().isBlank()) {
            ra.addFlashAttribute("error", "Node B is required.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }
        if (form.getAmount() == null || form.getAmount().compareTo(new BigDecimal("0.00")) <= 0) {
            ra.addFlashAttribute("error", "Amount must be greater than 0.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }

        // --- Call upstream ---
        try {
            ResponseEntity<Map> resp = matrixWebClient
                    .post()
                    .uri(paymentPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(form)
                    .retrieve()
                    .toEntity(Map.class)
                    .onErrorResume(e -> Mono.error(new IllegalStateException("Upstream error", e)))
                    .block();

            if (resp == null) throw new IllegalStateException("Upstream returned null response");

            if (!resp.getStatusCode().is2xxSuccessful()) {
                String note = "Upstream returned " + resp.getStatusCode();
                ra.addFlashAttribute("error", "Payment failed: " + note);
                ra.addFlashAttribute("resultJson",
                        "{\"status\":\"error\",\"note\":\"" + escape(note) + "\"}");
                ra.addFlashAttribute("form", scrubForFlash(form));
                audit("failure", correlationId, principalId, form,
                        resp.getStatusCode().value(), resp.getBody());
                return "redirect:/payment";
            }

            // Success
            Map body = resp.getBody();
            String json = objectToJsonSafe(body);
            ra.addFlashAttribute("success", "Payment submitted successfully.");
            ra.addFlashAttribute("resultJson", json);
            audit("success", correlationId, principalId, form,
                    resp.getStatusCode().value(), body);

        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            ra.addFlashAttribute("error", "Payment failed: " + msg);
            ra.addFlashAttribute("resultJson", "{\"status\":\"error\",\"note\":\"client_exception\"}");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("exception", correlationId, principalId, form,
                    null, Map.of("exception", ex.getClass().getSimpleName(), "message", msg));
        }

        return "redirect:/payment"; // PRG
    }

    /** Write a compact JSON line to the payment.audit logger. */
    private void audit(String outcome,
                       String correlationId,
                       String principal,
                       PaymentRequest form,
                       Integer upstreamStatus,
                       Object upstreamBody) {
        try {
            // Extract short hints from upstream
            String resultStatus = null, resultNote = null;
            if (upstreamBody instanceof Map<?, ?> map) {
                Object s = map.get("status"); if (s != null) resultStatus = String.valueOf(s);
                Object n = map.get("note");   if (n != null) resultNote   = String.valueOf(n);
            }

            // Build ordered map safely
            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("ts", Instant.now().toString());
            evt.put("correlationId", correlationId);
            evt.put("outcome", outcome); // success | failure | exception | validation_error
            evt.put("principal", principal);

            if (form != null) {
                safePut(evt, "nodeA", form.getNode_a());
                safePut(evt, "nodeB", form.getNode_b());
                if (form.getAmount() != null) {
                    safePut(evt, "amount", form.getAmount().toPlainString());
                }
                safePut(evt, "blob", form.getBlob_name());
                safePut(evt, "container", form.getContainer());
                safePut(evt, "outBase", form.getOut_base());
            }

            if (upstreamStatus != null) safePut(evt, "upstreamStatus", upstreamStatus);
            safePut(evt, "resultStatus", resultStatus);
            safePut(evt, "resultNote", resultNote);

            AUDIT.info(objectToJsonSafe(evt));
        } catch (Exception e) {
            LOG.warn("Audit logging failed: {}", e.toString());
        }
    }

    private static void safePut(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private String objectToJsonSafe(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { return "{\"_json\":\"error\"}"; }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }

    private String resolveCurrentUser(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
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

    private PaymentRequest scrubForFlash(PaymentRequest form) {
        PaymentRequest copy = new PaymentRequest();
        copy.setBlob_name(form.getBlob_name());
        copy.setContainer(CONTAINER);
        copy.setNode_a(form.getNode_a());
        copy.setOut_base(OUT_BASE);
        copy.setNode_b(form.getNode_b());
        copy.setAmount(form.getAmount());
        return copy;
    }
}

