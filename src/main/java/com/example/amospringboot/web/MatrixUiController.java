// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Controller
public class MatrixUiController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixUiController.class);

    private final WebClient matrixWebClient;

    // Fallback defaults if “latest” cannot be retrieved
    @Value("${amo.matrix.blob-name:initial-matrix-latest.b64}")
    private String fallbackBlobName;

    @Value("${amo.matrix.out-base:initial-matrix-latest}")
    private String fallbackOutBase;

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    @GetMapping("/matrix/cycle/find/ui")
    public String show(Model model,
                       @AuthenticationPrincipal OidcUser oidc,
                       @AuthenticationPrincipal OAuth2User oauth2) {

        CycleFindRequest form = (CycleFindRequest) model.getAttribute("cycleForm");
        if (form == null) form = new CycleFindRequest();

        // Authoritative server-side values
        form.setContainer("matrices");
        form.setNode_a(extractLocalUserId(oidc, oauth2));

        LatestMatrix latest = fetchLatestMatrix();
        form.setBlob_name(latest.blobName());
        form.setOut_base(latest.outBase());

        model.addAttribute("cycleForm", form);
        return "matrix/cycle-find";
    }

    @PostMapping(value = "/matrix/cycle/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@ModelAttribute("cycleForm") CycleFindRequest form,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        // Re-assert authoritative values (ignore client-provided hidden inputs)
        form.setContainer("matrices");
        form.setNode_a(extractLocalUserId(oidc, oauth2));

        LatestMatrix latest = fetchLatestMatrix();
        form.setBlob_name(latest.blobName());
        form.setOut_base(latest.outBase());

        // Minimal UI validation: only Node B is user-entered
        if (form.getNode_b() == null || form.getNode_b().isBlank()) {
            model.addAttribute("error", "Please enter Node B.");
            return "matrix/cycle-find";
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("blob_name", form.getBlob_name());
            payload.put("node_a", form.getNode_a());
            payload.put("node_b", form.getNode_b());
            if (form.getContainer() != null)        payload.put("container", form.getContainer());
            if (form.getApply_settlement() != null) payload.put("apply_settlement", form.getApply_settlement());
            if (form.getOut_base() != null)         payload.put("out_base", form.getOut_base());

            Map<String, Object> result = matrixWebClient.post()
                    .uri("/matrix/cycle/find")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            model.addAttribute("result", result);
        } catch (WebClientResponseException ex) {
            LOG.error("Cycle find via UI failed: {} {}", ex.getRawStatusCode(), ex.getResponseBodyAsString(), ex);
            model.addAttribute("error", "Search failed: " + ex.getStatusCode() +
                    (ex.getResponseBodyAsString() != null ? " — " + ex.getResponseBodyAsString() : ""));
        } catch (Exception ex) {
            LOG.error("Cycle find via UI failed", ex);
            model.addAttribute("error", "Search failed: " +
                    (ex.getMessage() != null ? ex.getMessage() : "See server logs."));
        }

        return "matrix/cycle-find";
    }

    /* -------------------- helpers -------------------- */

    /** Extract a stable local user id from the authenticated principal, stripping any domain. */
    private String extractLocalUserId(OidcUser oidc, OAuth2User oauth2) {
        String candidate = null;

        if (oidc != null) {
            candidate = firstNonBlank(
                    safe(oidc.getPreferredUsername()),
                    safe(oidc.getEmail()),
                    safeAttr(oidc, "upn"),
                    safeAttr(oidc, "unique_name"),
                    safeAttr(oidc, "name"),
                    safeAttr(oidc, "preferred_username")
            );
        }

        if (candidate == null && oauth2 != null) {
            Map<String, Object> a = oauth2.getAttributes();
            candidate = firstNonBlank(
                    safe(obj(a.get("preferred_username"))),
                    safe(obj(a.get("upn"))),
                    safe(obj(a.get("unique_name"))),
                    safe(obj(a.get("email"))),
                    safe(obj(a.get("login"))),
                    safe(obj(a.get("name"))),
                    safe(oauth2.getName())
            );
        }

        if (candidate == null || candidate.isBlank()) {
            return "amo";
        }

        return localPart(candidate);
    }

    /** If looks like an email/UPN, return local-part; else return as-is. */
    private String localPart(String v) {
        int at = v.indexOf('@');
        if (at > 0) return v.substring(0, at);
        return v;
    }

    private String safe(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String safeAttr(OidcUser user, String key) {
        if (user == null) return null;
        Object val = user.getClaims() != null ? user.getClaims().get(key) : null;
        return val == null ? null : safe(String.valueOf(val));
    }

    private String obj(Object o) {
        return o == null ? null : Objects.toString(o, null);
    }

    private String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /**
     * Try to fetch the "latest matrix" metadata (blob_name & out_base),
     * with safe fallbacks. Note: fixes compile errors by using HttpStatusCode::isError
     * and returning clientResponse.createException() directly.
     */
    private LatestMatrix fetchLatestMatrix() {
        String[] candidatePaths = new String[] {
                "/payment/template/matrix/latest",
                "/payment/template/latest",
                "/matrix/template/latest",
                "/matrix/latest",
                "/matrix/meta/latest"
        };

        for (String path : candidatePaths) {
            try {
                Map<String, Object> resp = matrixWebClient.get()
                        .uri(path)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.createException())
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .block();

                if (resp == null || resp.isEmpty()) continue;

                String blob = firstNonBlank(
                        obj(resp.get("blob_name")),
                        obj(resp.get("blobName")),
                        obj(resp.get("latest_blob_name")),
                        obj(resp.get("latestBlobName"))
                );
                String out = firstNonBlank(
                        obj(resp.get("out_base")),
                        obj(resp.get("outBase")),
                        obj(resp.get("latest_out_base")),
                        obj(resp.get("latestOutBase"))
                );

                if (blob != null && out != null) {
                    return new LatestMatrix(blob, out);
                }
            } catch (Exception ex) {
                LOG.debug("Could not fetch latest matrix from candidate {}: {}", path, ex.toString());
            }
        }

        // Fallback to configuration
        return new LatestMatrix(fallbackBlobName, fallbackOutBase);
    }

    private record LatestMatrix(String blobName, String outBase) {}
}

