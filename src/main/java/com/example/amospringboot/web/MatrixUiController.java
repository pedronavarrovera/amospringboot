// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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

import java.util.HashMap;
import java.util.Map;

@Controller
public class MatrixUiController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixUiController.class);

    private final WebClient matrixWebClient;

    // Deploy-time defaults (override in application.yml / env)
    @Value("${amo.matrix.blob-name:initial-matrix-latest.b64}")
    private String defaultBlobName;

    @Value("${amo.matrix.out-base:initial-matrix-latest}")
    private String defaultOutBase;

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    @GetMapping("/matrix/cycle/find/ui")
    public String show(Model model,
                       @AuthenticationPrincipal OidcUser oidc,
                       @AuthenticationPrincipal OAuth2User oauth2) {

        CycleFindRequest form = (CycleFindRequest) model.getAttribute("cycleForm");
        if (form == null) {
            form = new CycleFindRequest();
        }

        // Authoritative server-side values (never rely on the browser to send hidden inputs)
        form.setContainer("matrices");
        form.setBlob_name(defaultBlobName);
        form.setOut_base(defaultOutBase);
        form.setNode_a(resolveNodeA(oidc, oauth2));

        model.addAttribute("cycleForm", form);
        return "matrix/cycle-find";
    }

    @PostMapping(value = "/matrix/cycle/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@ModelAttribute("cycleForm") CycleFindRequest form,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        // Re-assert authoritative values on POST (ignore any client-provided values)
        form.setContainer("matrices");
        form.setBlob_name(defaultBlobName);
        form.setOut_base(defaultOutBase);
        form.setNode_a(resolveNodeA(oidc, oauth2));

        // Minimal UI validation: only Node B is typed by the user here
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

            // Call REST backend: POST /matrix/cycle/find (JSON)
            Map<String, Object> result = matrixWebClient.post()
                    .uri("/matrix/cycle/find")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            model.addAttribute("result", result);
        } catch (Exception ex) {
            LOG.error("Cycle find via UI failed", ex);
            model.addAttribute("error", "Search failed: " +
                    (ex.getMessage() != null ? ex.getMessage() : "See server logs."));
        }

        return "matrix/cycle-find";
    }

    /** Derive a stable local identifier for node_a from the authenticated principal */
    private String resolveNodeA(OidcUser oidc, OAuth2User oauth2) {
        if (oidc != null) {
            String preferred = oidc.getPreferredUsername();
            if (preferred != null && !preferred.isBlank()) return preferred;
            String email = oidc.getEmail();
            if (email != null && email.contains("@")) return email.substring(0, email.indexOf('@'));
        }
        if (oauth2 != null) {
            Object preferred = oauth2.getAttributes().get("preferred_username");
            if (preferred instanceof String s && !s.isBlank()) return s;

            Object email = oauth2.getAttributes().get("email");
            if (email instanceof String s && s.contains("@")) return s.substring(0, s.indexOf('@'));

            Object login = oauth2.getAttributes().get("login"); // e.g., GitHub
            if (login instanceof String s && !s.isBlank()) return s;
        }
        return "amo";
    }
}
