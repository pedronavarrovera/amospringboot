// src/main/java/com/example/amospringboot/web/MatrixUiController.java
package com.example.amospringboot.web;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
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

    public MatrixUiController(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    @GetMapping("/matrix/cycle/find/ui")
    public String show(Model model,
                       @AuthenticationPrincipal OidcUser oidc,
                       @AuthenticationPrincipal OAuth2User oauth2) {

        if (!model.containsAttribute("cycleForm")) {
            // Pre-populate with sane defaults if needed
            var form = new CycleFindRequest();
            form.setContainer("matrices");
            // blob_name, node_a, out_base should already be set by your page flow
            model.addAttribute("cycleForm", form);
        }
        return "matrix/cycle-find";
    }

    @PostMapping(value = "/matrix/cycle/find/ui", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submit(@Valid @ModelAttribute("cycleForm") CycleFindRequest form,
                         BindingResult binding,
                         Model model,
                         @AuthenticationPrincipal OidcUser oidc,
                         @AuthenticationPrincipal OAuth2User oauth2) {

        if (binding.hasErrors()) {
            model.addAttribute("error", "Please correct the highlighted fields.");
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
            model.addAttribute("error", "Search failed: " + ex.getMessage());
        }

        return "matrix/cycle-find";
    }
}
