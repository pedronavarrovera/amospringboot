package com.example.amospringboot.web;

import com.example.amospringboot.matrix.MatrixApiClient;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern as RePattern; // <-- if this alias confuses your IDE, remove and use java.util.regex.Pattern fully-qualified

@Controller
@RequestMapping("/payment")
public class PaymentUiController {

    private static final Logger LOG   = LoggerFactory.getLogger(PaymentUiController.class);
    /** Matches <logger name="payment.audit"> in logback-spring.xml */
    private static final Logger AUDIT = LoggerFactory.getLogger("payment.audit");

    private static final String CONTAINER = "matrices";
    private static final String FALLBACK  = "initial-matrix.b64";
    private static final String VIEW      = "payment";

    // UPDATED: regex to strip one or more trailing -YYYYMMDD-HHMMSS sequences
    private static final java.util.regex.Pattern TS_TAIL =
            java.util.regex.Pattern.compile("(-\\d{8}-\\d{6})+$");

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

    /** GET /payment — prefill authoritative fields; out_base derived from blob_name (no old ts). */
    @GetMapping
    public String page(Model model,
                       @AuthenticationPrincipal OidcUser oidcUser,
                       @AuthenticationPrincipal OAuth2User oauth2User,
                       @RequestParam(value = "blob", required = false) String blob) {

        if (!model.containsAttribute("form")) {
            PaymentForm form = new PaymentForm();

            String latest = (blob != null && !blob.isBlank()) ? blob : safeLatest();

            form.setBlob_name(latest);                 // authoritative
            form.setOut_base(normalizeOutBase(latest)); // UPDATED: derive clean base
            form.setContainer(CONTAINER);              // authoritative
            form.setNode_a(localPart(resolveUpn(oidcUser, oauth2User))); // authoritative

            model.addAttribute("form", form);
        }
        return VIEW;
    }

    /** POST /payment — log attempt + result with a traceId; no deprecated APIs used. */
    @PostMapping
    public String submit(@Valid @ModelAttribute("form") PaymentForm form,
                         BindingResult br,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidcUser,
                         @AuthenticationPrincipal OAuth2User oauth2User) {

        final String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);

        // Re-enforce authoritative values regardless of client input
        String latest = safeLatest();
        String nodeA  = localPart(resolveUpn(oidcUser, oauth2User));

        form.setBlob_name(latest);
        form.setOut_base(normalizeOutBase(latest)); // UPDATED: ensure clean base every submit
        form.setContainer(CONTAINER);
        form.setNode_a(nodeA);

        // Bean validation only checks user inputs (node_b, amount)
        if (br.hasErrors()) {
            model.addAttribute("error", "Please fix the highlighted errors and try again.");
            model.addAttribute("form", form);
            LOG.info("PAYMENT_FAILURE traceId={} reason=validation container={} blob={} out={} node_a={} node_b={} amount={} errors={}",
                    traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount(), br.getErrorCount());
            AUDIT.info("PAYMENT_FAILURE traceId={} reason=validation container={} blob={} out={} node_a={} node_b={} amount={}",
                    traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount());
            MDC.clear();
            return VIEW;
        }

        // Extra guard: amount must be a positive integer (no decimals)
        if (!isPositiveInteger(form.getAmount())) {
            model.addAttribute("error", "Amount must be a positive integer (no decimals).");
            model.addAttribute("form", form);
            LOG.info("PAYMENT_FAILURE traceId={} reason=nonIntegerAmount container={} blob={} out={} node_a={} node_b={} amount={}",
                    traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount());
            AUDIT.info("PAYMENT_FAILURE traceId={} reason=nonIntegerAmount container={} blob={} out={} node_a={} node_b={} amount={}",
                    traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount());
            MDC.clear();
            return VIEW;
        }

        long durationMs = 0L;
        try {
            PaymentRequest req = new PaymentRequest();
            req.setBlob_name(form.getBlob_name());
            req.setOut_base(form.getOut_base());
            req.setContainer(form.getContainer());
            req.setNode_a(form.getNode_a());
            req.setNode_b(form.getNode_b());
            req.setAmount(form.getAmount());

            LOG.info("PAYMENT_ATTEMPT traceId={} container={} blob={} out={} node_a={} node_b={} amount={}",
                    traceId, req.getContainer(), req.getBlob_name(), req.getOut_base(),
                    safe(req.getNode_a()), safe(req.getNode_b()), req.getAmount());
            AUDIT.info("PAYMENT_ATTEMPT traceId={} container={} blob={} out={} node_a={} node_b={} amount={}",
                    traceId, req.getContainer(), req.getBlob_name(), req.getOut_base(),
                    safe(req.getNode_a()), safe(req.getNode_b()), req.getAmount());

            long t0 = System.nanoTime();
            Map<String, Object> result = client.applyPayment(req);
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            String resultJson = toJson(result);
            String status = String.valueOf(result.getOrDefault("status", "unknown"));
            String writtenBlob = String.valueOf(result.getOrDefault("written_blob", ""));

            LOG.info("PAYMENT_SUCCESS traceId={} durationMs={} container={} blob={} out={} node_a={} node_b={} amount={} status={} written_blob={} result={}",
                    traceId, durationMs,
                    req.getContainer(), req.getBlob_name(), req.getOut_base(),
                    safe(req.getNode_a()), safe(req.getNode_b()), req.getAmount(),
                    status, writtenBlob, truncate(resultJson, 4000));
            AUDIT.info("PAYMENT_SUCCESS traceId={} container={} blob={} out={} node_a={} node_b={} amount={} status={} written_blob={}",
                    traceId, req.getContainer(), req.getBlob_name(), req.getOut_base(),
                    safe(req.getNode_a()), safe(req.getNode_b()), req.getAmount(),
                    status, writtenBlob);

            model.addAttribute("success", "Payment submitted.");
            model.addAttribute("result", result);
            model.addAttribute("resultJson", resultJson);
        } catch (Exception e) {
            String msg = (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            LOG.warn("PAYMENT_FAILURE traceId={} durationMs={} container={} blob={} out={} node_a={} node_b={} amount={} error={} class={}",
                    traceId, durationMs,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount(),
                    truncate(msg, 2000), e.getClass().getName(), e);
            AUDIT.info("PAYMENT_FAILURE traceId={} container={} blob={} out={} node_a={} node_b={} amount={} error={}",
                    traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()), form.getAmount(),
                    truncate(msg, 1000));

            model.addAttribute("error", "Payment failed: " + msg);
        } finally {
            MDC.clear();
        }

        model.addAttribute("form", form);
        return VIEW;
    }

    // ===== helpers =====

    private String safeLatest() {
        try {
            return client.latestBlob(CONTAINER, FALLBACK);
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    // UPDATED: convert "initial-matrix-20251018-091137.b64" -> "initial-matrix"
    private static String normalizeOutBase(String blobName) {
        if (blobName == null || blobName.isBlank()) return "payment-update";
        String base = blobName.endsWith(".b64") ? blobName.substring(0, blobName.length() - 4) : blobName;
        base = TS_TAIL.matcher(base).replaceAll(""); // strip trailing timestamp(s)
        return base;
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

    /** Avoid logging full UPN; we already use local part. Keep simple for logs. */
    private static String safe(String s) {
        return s == null ? "null" : s;
    }

    /** Keep logs bounded to prevent huge payloads. */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max) + "...(truncated)";
    }

    // ---- UI form class: VALIDATE ONLY USER INPUTS ----
    public static class PaymentForm {
        // authoritative fields (no bean validation here; they’re reasserted server-side)
        private String blob_name;
        private String node_a;
        private String container;
        private String out_base;

        // user-entered fields (validated)
        @NotBlank(message = "Node B is required")
        @Pattern(regexp = "^[A-Za-z0-9_\\-]{1,64}$",
                 message = "Node must be 1–64 chars, letters/digits/_/- only")
        private String node_b;

        @NotNull(message = "Amount is required")
        @Positive(message = "Amount must be greater than 0")
        @Digits(integer = 12, fraction = 0, message = "Amount must be an integer (no decimals)")
        private BigDecimal amount;

        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getNode_a() { return node_a; }
        public void setNode_a(String node_a) { this.node_a = node_a; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
        public String getOut_base() { return out_base; }
        public void setOut_base(String out_base) { this.out_base = out_base; }
        public String getNode_b() { return node_b; }
        public void setNode_b(String node_b) { this.node_b = node_b; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}


