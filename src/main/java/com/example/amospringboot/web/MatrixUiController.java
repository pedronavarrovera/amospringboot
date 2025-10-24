// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/matrix/cycle")
public class MatrixUiController {

    private static final Logger LOG   = LoggerFactory.getLogger(MatrixUiController.class);
    /** Audit channel analogous to PaymentUiController's AUDIT logger */
    private static final Logger AUDIT = LoggerFactory.getLogger("cycle.audit");

    private static final String VIEW          = "matrix/cycle-find";
    private static final String CONTAINER     = "matrices";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";
    private static final Pattern TS_TAIL      = Pattern.compile("(-\\d{8}-\\d{6})$");

    private final WebClient matrixWebClient;

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    @InitBinder("cycleForm")
    public void disallowAuthoritative(WebDataBinder binder) {
        // Users must not post these; we compute them server-side.
        binder.setDisallowedFields("blob_name", "out_base", "container", "node_a");
    }

    /** Default for /matrix/cycle -> take users to the UI page */
    @GetMapping
    public String cycleRootRedirect() {
        return "redirect:/matrix/cycle/find/ui";
    }

    /** GET page */
    @GetMapping("/find/ui")
    public String show(Model model,
                       @AuthenticationPrincipal OidcUser oidc,
                       @AuthenticationPrincipal OAuth2User oauth2,
                       @RequestParam(value = "blob", required = false) String blobOverride) {

        if (!model.containsAttribute("cycleForm")) {
            CycleFindRequest form = new CycleFindRequest();

            String chosenBlob = (blobOverride != null && !blobOverride.isBlank())
                    ? blobOverride
                    : safeLatestBlob();

            form.setBlob_name(chosenBlob);
            form.setOut_base(normalizeOutBase(chosenBlob));
            form.setContainer(CONTAINER);
            form.setNode_a(localPart(resolveUpn(oidc, oauth2)));

            model.addAttribute("cycleForm", form);
        }

        model.addAttribute("error", null);
        model.addAttribute("result", null);
        return VIEW;
    }

    /** POST submit (no @Valid: we set authoritative fields first, then check node_b manually) */
    @PostMapping(value = "/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@ModelAttribute("cycleForm") CycleFindRequest form,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        // Re-compute authoritative values
        String blob    = safeLatestBlob();
        String outBase = normalizeOutBase(blob);
        String nodeA   = localPart(resolveUpn(oidc, oauth2));

        form.setBlob_name(blob);
        form.setOut_base(outBase);
        form.setContainer(CONTAINER);
        form.setNode_a(nodeA);

        // Minimal validation for user-entered field(s)
        String nodeB = form.getNode_b();
        if (nodeB == null || nodeB.isBlank() || !nodeB.matches("^[A-Za-z0-9_\\-]{1,64}$")) {
            model.addAttribute("error", "Please correct the highlighted fields.");
            return VIEW;
        }

        // ===== Payment-style structured logging =====
        final String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long durationMs = 0L;

        Boolean applySettlementRequested = form.getApply_settlement();

        LOG.info("CYCLE_ATTEMPT traceId={} container={} blob={} out={} node_a={} node_b={} apply_settlement={}",
                traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                safe(form.getNode_a()), safe(form.getNode_b()),
                String.valueOf(applySettlementRequested));
        AUDIT.info("CYCLE_ATTEMPT traceId={} container={} blob={} out={} node_a={} node_b={} apply_settlement={}",
                traceId, form.getContainer(), form.getBlob_name(), form.getOut_base(),
                safe(form.getNode_a()), safe(form.getNode_b()),
                String.valueOf(applySettlementRequested));

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("blob_name", form.getBlob_name());
            payload.put("node_a", form.getNode_a());
            payload.put("node_b", form.getNode_b());
            payload.put("container", form.getContainer());
            if (applySettlementRequested != null) {
                payload.put("apply_settlement", applySettlementRequested);
            }
            payload.put("out_base", form.getOut_base());

            long t0 = System.nanoTime();
            Map<String, Object> result = matrixWebClient.post()
                    .uri("/matrix/cycle/find")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            // Extract common fields
            String  status            = asString(result != null ? result.get("status") : null);
            String  writtenBlob       = asString(result != null ? result.get("written_blob") : null);
            Boolean settlementApplied = asBooleanOrNull(result != null ? result.get("settlement_applied") : null);

            // Some backends may wrap inside "data"
            if (result != null && result.get("data") instanceof Map<?,?> data) {
                if (status == null)            status = asString(data.get("status"));
                if (writtenBlob == null)       writtenBlob = asString(data.get("written_blob"));
                if (settlementApplied == null) settlementApplied = asBooleanOrNull(data.get("settlement_applied"));
            }

            boolean ok = "ok".equalsIgnoreCase(status)
                      || (writtenBlob != null && !writtenBlob.isBlank());

            LOG.info("CYCLE_SUCCESS traceId={} durationMs={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} settlement_applied={} status={} written_blob={}",
                    traceId, durationMs,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested), String.valueOf(settlementApplied),
                    safe(status), safe(writtenBlob));
            AUDIT.info("CYCLE_SUCCESS traceId={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} settlement_applied={} status={} written_blob={}",
                    traceId,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested), String.valueOf(settlementApplied),
                    safe(status), safe(writtenBlob));

            model.addAttribute("result", result);
            model.addAttribute("error", ok ? null : ("Search failed: status=" + safe(status)));

        } catch (WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            String body = wcre.getResponseBodyAsString();
            String msg = (body != null && !body.isBlank()) ? truncate(body, 800) : ("HTTP " + code);

            LOG.warn("CYCLE_FAILURE traceId={} durationMs={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} http_status={} errorBody={}",
                    traceId, durationMs,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested), code, msg, wcre);
            AUDIT.info("CYCLE_FAILURE traceId={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} http_status={} error={}",
                    traceId,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested), code, truncate(msg, 400));

            model.addAttribute("error", "Search failed: HTTP " + code);
            model.addAttribute("result", null);

        } catch (Exception ex) {
            String msg = (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());

            LOG.warn("CYCLE_FAILURE traceId={} durationMs={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} errorClass={} error={}",
                    traceId, durationMs,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested),
                    ex.getClass().getName(), truncate(msg, 800), ex);
            AUDIT.info("CYCLE_FAILURE traceId={} container={} blob={} out={} node_a={} node_b={} apply_settlement={} error={}",
                    traceId,
                    form.getContainer(), form.getBlob_name(), form.getOut_base(),
                    safe(form.getNode_a()), safe(form.getNode_b()),
                    String.valueOf(applySettlementRequested), truncate(msg, 400));

            model.addAttribute("error", "Search failed: " + msg);
            model.addAttribute("result", null);
        } finally {
            MDC.clear();
        }

        model.addAttribute("cycleForm", form);
        return VIEW;
    }

    // ===== helpers =====

    /** Pick newest timestamped blob; else "*-latest.b64"; else FALLBACK. */
    private String safeLatestBlob() {
        try {
            List<String> names = matrixWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/matrix/blobs")
                            .queryParam("container", CONTAINER)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<List<String>>() {})
                    .block();

            if (names == null || names.isEmpty()) {
                LOG.warn("safeLatestBlob(): empty/null list, using FALLBACK={}", FALLBACK_BLOB);
                return FALLBACK_BLOB;
            }

            String latestAlias = null;
            List<String> ts = new ArrayList<>();

            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                if (n.endsWith("-latest.b64")) {
                    latestAlias = n;
                } else if (extractSortableTs(n) > 0L) {
                    ts.add(n);
                }
            }

            if (!ts.isEmpty()) {
                ts.sort((a, b) -> Long.compare(extractSortableTs(b), extractSortableTs(a)));
                String best = ts.get(0);
                LOG.info("safeLatestBlob(): selected timestamped {}", best);
                return best;
            }

            if (latestAlias != null) {
                LOG.info("safeLatestBlob(): no timestamped entries; using alias {}", latestAlias);
                return latestAlias;
            }

            LOG.warn("safeLatestBlob(): neither timestamped nor alias found; using FALLBACK={}", FALLBACK_BLOB);
            return FALLBACK_BLOB;

        } catch (Exception e) {
            LOG.warn("safeLatestBlob(): exception -> {}, using FALLBACK={}", e.toString(), FALLBACK_BLOB);
            return FALLBACK_BLOB;
        }
    }

    /** Parse YYYYMMDD-HHMMSS right before .b64; returns 0 if absent/bad. */
    private static long extractSortableTs(String name) {
        if (name == null) return 0L;
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name);

        int lastDash = stem.lastIndexOf('-');
        if (lastDash < 0) return 0L;

        String hhmmss = stem.substring(lastDash + 1);
        if (!hhmmss.matches("\\d{6}")) return 0L;

        int prevDash = stem.lastIndexOf('-', lastDash - 1);
        if (prevDash < 0) return 0L;

        String yyyymmdd = stem.substring(prevDash + 1, lastDash);
        if (!yyyymmdd.matches("\\d{8}")) return 0L;

        try {
            return Long.parseLong(yyyymmdd + hhmmss);
        } catch (NumberFormatException ignore) {
            return 0L;
        }
    }

    /** Strip timestamp & .b64 → base name (e.g., "initial-matrix"). */
    private static String normalizeOutBase(String blobName) {
        if (blobName == null || blobName.isBlank()) return "initial-matrix";
        String base = blobName.endsWith(".b64") ? blobName.substring(0, blobName.length() - 4) : blobName;
        base = TS_TAIL.matcher(base).replaceAll("");
        return base;
    }

    private static String resolveUpn(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
            String v = firstNonBlank(
                    oidc.getClaimAsString("upn"),
                    oidc.getClaimAsString("preferred_username"),
                    oidc.getEmail(),
                    oidc.getName());
            if (v != null) return v;
        }
        if (oauth2 != null) {
            String v = firstNonBlank(
                    asString(oauth2.getAttributes().get("upn")),
                    asString(oauth2.getAttributes().get("preferred_username")),
                    asString(oauth2.getAttributes().get("email")),
                    oauth2.getName());
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

    private static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    private static Boolean asBooleanOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            if ("true".equalsIgnoreCase(s))  return Boolean.TRUE;
            if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        }
        return null;
    }

    private static String safe(String s) {
        return s == null ? "null" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max) + "...(truncated)";
    }
}





