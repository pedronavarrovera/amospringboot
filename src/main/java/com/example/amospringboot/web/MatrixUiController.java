// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/matrix/cycle")
public class MatrixUiController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixUiController.class);

    private static final String CONTAINER = "matrices";
    private static final String FALLBACK  = "initial-matrix.b64";
    private static final Pattern TS_TAIL  = Pattern.compile("(-\\d{8}-\\d{6})+$");

    private final WebClient matrixWebClient;

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    /** Prevent tampering with authoritative fields. */
    @InitBinder("cycleForm")
    public void disallowAuthoritative(WebDataBinder binder) {
        binder.setDisallowedFields("blob_name", "out_base", "container", "node_a");
    }

    @GetMapping("/find/ui")
    public String show(Model model,
                       @AuthenticationPrincipal OidcUser oidc,
                       @AuthenticationPrincipal OAuth2User oauth2,
                       @RequestParam(value = "blob", required = false) String blobOverride) {

        CycleFindRequest form = (CycleFindRequest) model.getAttribute("cycleForm");
        if (form == null) form = new CycleFindRequest();

        // authoritative values
        form.setContainer(CONTAINER);
        form.setNode_a(localPart(resolveUpn(oidc, oauth2)));

        // blob/out_base defaults
        String latest = (blobOverride != null && !blobOverride.isBlank()) ? blobOverride : safeLatestBlob();
        form.setBlob_name(latest);
        form.setOut_base(normalizeOutBase(latest));

        model.addAttribute("cycleForm", form);
        return "matrix/cycle-find";
    }

    @PostMapping(value = "/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@Valid @ModelAttribute("cycleForm") CycleFindRequest form,
                         BindingResult binding,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        // authoritative reassertion
        form.setContainer(CONTAINER);
        form.setNode_a(localPart(resolveUpn(oidc, oauth2)));
        String latest = safeLatestBlob();
        form.setBlob_name(latest);
        form.setOut_base(normalizeOutBase(latest));

        if (binding.hasErrors()) {
            model.addAttribute("error", "Please correct the highlighted fields.");
            return "matrix/cycle-find";
        }
        if (form.getNode_b() == null || form.getNode_b().isBlank()) {
            model.addAttribute("error", "Please enter Node B.");
            return "matrix/cycle-find";
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("blob_name", form.getBlob_name());
            payload.put("node_a", form.getNode_a());
            payload.put("node_b", form.getNode_b());
            payload.put("container", form.getContainer());
            if (form.getApply_settlement() != null)
                payload.put("apply_settlement", form.getApply_settlement());
            payload.put("out_base", form.getOut_base());

            Map<String, Object> result = matrixWebClient.post()
                    .uri("/matrix/cycle/find")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            model.addAttribute("result", result);
        } catch (WebClientResponseException ex) {
            LOG.error("Cycle find failed: {} {}", ex.getStatusCode().value(), ex.getResponseBodyAsString(), ex);
            model.addAttribute("error",
                    "Search failed: " + ex.getStatusCode().value() +
                    (ex.getResponseBodyAsString() != null ? " — " + ex.getResponseBodyAsString() : ""));
        } catch (Exception ex) {
            LOG.error("Cycle find failed", ex);
            model.addAttribute("error",
                    "Search failed: " + (ex.getMessage() != null ? ex.getMessage() : "See server logs."));
        }

        return "matrix/cycle-find";
    }

    /* ===================== helpers ===================== */

    /** Calls /matrix/blobs?container=matrices and returns the latest timestamped blob. */
    private String safeLatestBlob() {
        try {
            Object body = matrixWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/matrix/blobs")
                            .queryParam("container", CONTAINER)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(Object.class)
                    .block();

            List<String> names = extractBlobNames(body);
            if (names.isEmpty()) return FALLBACK;

            names.sort((a, b) -> Long.compare(extractSortableTs(b), extractSortableTs(a)));
            String best = names.get(0);
            return (best == null || best.isBlank()) ? FALLBACK : best;
        } catch (Exception e) {
            LOG.debug("safeLatestBlob(): fallback due to {}", e.toString());
            return FALLBACK;
        }
    }

    /** Normalize out_base from blob name. */
    private static String normalizeOutBase(String blobName) {
        if (blobName == null || blobName.isBlank()) return "matrix-update";
        String base = blobName.endsWith(".b64")
                ? blobName.substring(0, blobName.length() - 4)
                : blobName;
        return TS_TAIL.matcher(base).replaceAll("");
    }

    /** Extract local part of an email/UPN. */
    private static String localPart(String s) {
        if (s == null) return "unknown";
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }

    /** Resolve UPN/email reliably. */
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
            Map<String, Object> a = oauth2.getAttributes();
            String v = firstNonBlank(
                    asString(a.get("upn")),
                    asString(a.get("preferred_username")),
                    asString(a.get("email")),
                    oauth2.getName());
            if (v != null) return v;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private static String asString(Object o) {
        return (o != null) ? o.toString() : null;
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals)
            if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Extract blob names from either a List<String> or a Map<String, Object> with "blobs". */
    private static List<String> extractBlobNames(Object body) {
        if (body == null) return Collections.emptyList();

        if (body instanceof List<?>) {
            return ((List<?>) body).stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }

        if (body instanceof Map<?, ?> map) {
            Object blobs = map.get("blobs");
            if (blobs instanceof List<?>) {
                return ((List<?>) blobs).stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .toList();
            }
        }
        return Collections.emptyList();
    }

    /** Parse sortable timestamp from blob name like initial-matrix-YYYYMMDD-HHMMSS.b64. */
    private static long extractSortableTs(String name) {
        if (name == null) return 0L;
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0 ? name.substring(0, dot) : name);

        int lastDash = stem.lastIndexOf('-');
        if (lastDash < 0) return 0L;
        String tail = stem.substring(lastDash + 1);
        if (tail.matches("\\d{6}")) {
            int prevDash = stem.lastIndexOf('-', lastDash - 1);
            if (prevDash > 0) {
                String ymd = stem.substring(prevDash + 1, lastDash);
                if (ymd.matches("\\d{8}")) {
                    try {
                        return Long.parseLong(ymd + tail);
                    } catch (NumberFormatException ignore) {
                        return 0L;
                    }
                }
            }
        }
        if (stem.endsWith("-latest")) return 1L;
        return 0L;
    }
}


