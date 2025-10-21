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
 * /payment UI: out_base MUST always equal blob_name.
 * Amount MUST be a positive integer (>0), never decimal.
 */
@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentUiController.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("payment.audit");

    private static final String VIEW = "payment";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";

    private final WebClient matrixWebClient;
    private final ObjectMapper objectMapper;
    private final String paymentPath;
    private final String blobsPath;
    private final String container;

    public PaymentUiController(
            WebClient matrixWebClient,
            ObjectMapper objectMapper,
            @Value("${matrix.api.payment-path:/payment}") String paymentPath,
            @Value("${matrix.api.blobs-path:/blobs}") String blobsPath,
            @Value("${matrix.container:matrices}") String container
    ) {
        this.matrixWebClient = matrixWebClient;
        this.objectMapper = objectMapper;
        this.paymentPath = paymentPath;
        this.blobsPath = blobsPath;
        this.container = container;
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

            form.setContainer(container);

            // Pick blob: ?blob=… > latest > fallback
            String chosenBlob = (blob != null && !blob.isBlank())
                    ? blob
                    : getLatestBlobName().orElse(FALLBACK_BLOB);
            form.setBlob_name(chosenBlob);

            // out_base mirrors blob_name
            form.setOut_base(chosenBlob);

            // node A = local-part of current user
            form.setNode_a(resolveCurrentUser(oidcUser, oauth2User));

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

        // Reassert authoritative fields server-side
        form.setContainer(container);
        if (form.getBlob_name() == null || form.getBlob_name().isBlank()) {
            form.setBlob_name(getLatestBlobName().orElse(FALLBACK_BLOB));
        }
        form.setOut_base(form.getBlob_name()); // ALWAYS equal
        form.setNode_a(principalId);

        // Jakarta validation errors?
        if (br.hasErrors()) {
            ra.addFlashAttribute("error", "Please fix the highlighted errors and try again.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }

        // Custom amount validation: positive integer only
        if (!isPositiveInteger(form.getAmount())) {
            ra.addFlashAttribute("error", "Amount must be a positive integer (no decimals).");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }

        // Node B required
        if (form.getNode_b() == null || form.getNode_b().isBlank()) {
            ra.addFlashAttribute("error", "Node B is required.");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("validation_error", correlationId, principalId, form, null, null);
            return "redirect:/payment";
        }

        try {
            // Call matrix API via WebClient
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

            Map body = resp.getBody();
            String json = objectToJsonSafe(body);
            ra.addFlashAttribute("success", "Payment submitted successfully.");
            ra.addFlashAttribute("resultJson", json);
            audit("success", correlationId, principalId, form,
                    resp.getStatusCode().value(), body);

        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            ra.addFlashAttribute("error", "Payment failed: " + msg);
            ra.addFlashAttribute("resultJson",
                    "{\"status\":\"error\",\"note\":\"client_exception\"}");
            ra.addFlashAttribute("form", scrubForFlash(form));
            audit("exception", correlationId, principalId, form,
                    null,
                    Map.of("exception", ex.getClass().getSimpleName(), "message", msg));
        }

        return "redirect:/payment";
    }

    // ---------- Helpers ----------

    /** Positive integer check for BigDecimal. */
    private static boolean isPositiveInteger(BigDecimal amt) {
        if (amt == null) return false;
        if (amt.signum() <= 0) return false;       // > 0
        return amt.stripTrailingZeros().scale() <= 0; // no fractional part
    }

    /** Try to fetch latest blob name from the Matrix API. */
    private Optional<String> getLatestBlobName() {
        try {
            ResponseEntity<Object> resp = matrixWebClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path(blobsPath)
                            .queryParam("container", container)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(Object.class)
                    .block();

            if (resp == null || resp.getBody() == null) return Optional.empty();

            Object body = resp.getBody();

            // Case 1: array of strings
            if (body instanceof List<?> list && !list.isEmpty()) {
                if (list.get(0) instanceof String) {
                    // assume newest last
                    return list.stream().map(o -> (String) o).reduce((a, b) -> b);
                }
                // Case 2: array of objects { name, lastModified | last_modified }
                if (list.get(0) instanceof Map) {
                    return list.stream()
                            .map(it -> (Map<?, ?>) it)
                            .sorted((a, b) -> {
                                Instant ia = parseInstantOrNull(a.get("lastModified"), a.get("last_modified"));
                                Instant ib = parseInstantOrNull(b.get("lastModified"), b.get("last_modified"));
                                if (ia == null && ib == null) return 0;
                                if (ia == null) return 1;
                                if (ib == null) return -1;
                                return ib.compareTo(ia); // newest first
                            })
                            .map(m -> Objects.toString(m.get("name"), null))
                            .filter(Objects::nonNull)
                            .findFirst();
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not fetch latest blob: {}", e.toString());
        }
        return Optional.empty();
    }

    private static Instant parseInstantOrNull(Object... candidates) {
        for (Object c : candidates) {
            if (c == null) continue;
            try { return Instant.parse(c.toString()); } catch (Exception ignored) {}
        }
        return null;
    }

    /** Audit log: one JSON line per payment attempt */
    private void audit(String outcome,
                       String correlationId,
                       String principal,
                       PaymentRequest form,
                       Integer upstreamStatus,
                       Object upstreamBody) {
        try {
            String resultStatus = null, resultNote = null;
            if (upstreamBody instanceof Map<?, ?> map) {
                Object s = map.get("status"); if (s != null) resultStatus = String.valueOf(s);
                Object n = map.get("note");   if (n != null) resultNote   = String.valueOf(n);
            }

            Map<String, Object> evt = new LinkedHashMap<>();
            evt.put("ts", Instant.now().toString());
            evt.put("correlationId", correlationId);
            evt.put("outcome", outcome);
            evt.put("principal", principal);

            if (form != null) {
                safePut(evt, "nodeA", form.getNode_a());
                safePut(evt, "nodeB", form.getNode_b());
                if (form.getAmount() != null)
                    safePut(evt, "amount", form.getAmount().toPlainString());
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

    /**
     * Prefer preferred_username/email, then strip domain part (everything after '@').
     * Falls back to name/subject; returns "unknown-user" if none available.
     */
    private String resolveCurrentUser(OidcUser oidc, OAuth2User oauth2) {
        String raw = null;

        if (oidc != null) {
            Object preferred = oidc.getClaims().get("preferred_username");
            if (preferred instanceof String s && !s.isBlank()) raw = s;
            else if (oidc.getEmail() != null && !oidc.getEmail().isBlank()) raw = oidc.getEmail();
            else if (oidc.getFullName() != null && !oidc.getFullName().isBlank()) raw = oidc.getFullName();
            else if (oidc.getSubject() != null && !oidc.getSubject().isBlank()) raw = oidc.getSubject();
        }

        if ((raw == null || raw.isBlank()) && oauth2 != null) {
            Object preferred = oauth2.getAttributes().get("preferred_username");
            if (preferred instanceof String s && !s.isBlank()) raw = s;
            else {
                Object email = oauth2.getAttributes().get("email");
                if (email instanceof String s && !s.isBlank()) raw = s;
                else {
                    Object name = oauth2.getAttributes().get("name");
                    if (name instanceof String s && !s.isBlank()) raw = s;
                }
            }
        }

        if (raw == null || raw.isBlank()) return "unknown-user";

        int at = raw.indexOf('@');
        if (at > 0) raw = raw.substring(0, at);
        return raw.trim();
    }

    private PaymentRequest scrubForFlash(PaymentRequest form) {
        PaymentRequest copy = new PaymentRequest();
        copy.setBlob_name(form.getBlob_name());
        copy.setContainer(container);
        copy.setNode_a(form.getNode_a());
        copy.setOut_base(form.getBlob_name()); // mirror in flash too
        copy.setNode_b(form.getNode_b());
        copy.setAmount(form.getAmount());
        return copy;
    }
}

