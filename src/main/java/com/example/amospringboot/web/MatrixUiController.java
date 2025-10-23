// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
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

    private static final String VIEW = "matrix/cycle-find";
    private static final String CONTAINER = "matrices";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";

    private static final Pattern TS_TAIL = Pattern.compile("(-\\d{8}-\\d{6})$");

    private final WebClient matrixWebClient;

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    @InitBinder("cycleForm")
    public void disallowAuthoritative(WebDataBinder binder) {
        binder.setDisallowedFields("blob_name", "out_base", "container", "node_a");
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

    /** POST submit */
    @PostMapping(value = "/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@Valid @ModelAttribute("cycleForm") CycleFindRequest form,
                         BindingResult binding,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        String blob = safeLatestBlob();
        String outBase = normalizeOutBase(blob);
        String nodeA = localPart(resolveUpn(oidc, oauth2));

        form.setBlob_name(blob);
        form.setOut_base(outBase);
        form.setContainer(CONTAINER);
        form.setNode_a(nodeA);

        if (binding.hasErrors()) {
            model.addAttribute("error", "Please correct the highlighted fields.");
            return VIEW;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
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
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            model.addAttribute("result", result);
            model.addAttribute("error", null);

        } catch (WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            LOG.warn("Cycle find failed: HTTP {}", code, wcre);
            model.addAttribute("error", "Search failed: HTTP " + code);
            model.addAttribute("result", null);

        } catch (Exception ex) {
            LOG.error("Cycle find via UI failed", ex);
            model.addAttribute("error",
                    "Search failed: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            model.addAttribute("result", null);
        }

        model.addAttribute("cycleForm", form);
        return VIEW;
    }

    // ===== helpers =====

    /** Pick newest timestamped blob; else use "*-latest.b64"; else FALLBACK. */
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
}




