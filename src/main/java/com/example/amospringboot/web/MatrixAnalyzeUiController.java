package com.example.amospringboot.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/matrix")
public class MatrixAnalyzeUiController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixAnalyzeUiController.class);

    private static final String VIEW = "analyze";         // matches analyze.html
    private static final String CONTAINER = "matrices";
    private static final String FALLBACK_BLOB = "initial-matrix.b64";
    private static final Pattern TS_TAIL = Pattern.compile("(-\\d{8}-\\d{6})$");

    private final WebClient matrixWebClient;
    private final ObjectMapper objectMapper;

    public MatrixAnalyzeUiController(WebClient matrixWebClient, ObjectMapper objectMapper) {
        this.matrixWebClient = matrixWebClient;
        this.objectMapper = objectMapper;
    }

    /** Show analyze page with authoritative defaults */
    @GetMapping("/analyze")
    public String showAnalyze(Model model, @RequestParam(value = "blob", required = false) String blobOverride) {
        if (!model.containsAttribute("form")) {
            AnalyzeForm form = new AnalyzeForm();
            String chosenBlob = (blobOverride != null && !blobOverride.isBlank())
                    ? blobOverride
                    : safeLatestBlob();
            form.setBlob_name(chosenBlob);
            form.setContainer(CONTAINER);
            model.addAttribute("form", form);
        }
        model.addAttribute("error", null);
        model.addAttribute("result", null);
        model.addAttribute("resultJson", "{}");
        return VIEW;
    }

    /** Handle Analyze submit from analyze.html */
    @PostMapping(value = "/analyze", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public String submitAnalyze(@ModelAttribute("form") AnalyzeForm form, Model model) {
        // Recompute authoritative fields server-side
        String blob = safeLatestBlob();
        form.setBlob_name(blob);
        form.setContainer(CONTAINER);

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("blob_name", form.getBlob_name());
            payload.put("container", form.getContainer());

            // Call matrix service API
            Map<String, Object> result = matrixWebClient.post()
                    .uri("/matrix/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();

            model.addAttribute("result", result);
            model.addAttribute("error", null);
            model.addAttribute("resultJson", toJsonSafe(result));

        } catch (WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            LOG.warn("Analyze failed: HTTP {}", code, wcre);
            model.addAttribute("error", "Analyze failed: HTTP " + code);
            model.addAttribute("result", null);
            model.addAttribute("resultJson", toJsonSafe(errorMap("status", "error", "http_code", code)));

        } catch (Exception ex) {
            LOG.error("Analyze via UI failed", ex);
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            model.addAttribute("error", "Analyze failed: " + msg);
            model.addAttribute("result", null);
            model.addAttribute("resultJson", toJsonSafe(errorMap("status", "error", "message", msg)));
        }

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

    private String toJsonSafe(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { return "{\"error\":\"json-serialize-failed\"}"; }
    }

    private static Map<String, Object> errorMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    /** Backing bean for analyze.html (blob_name + container). */
    public static class AnalyzeForm {
        private String blob_name;
        private String container;
        public String getBlob_name() { return blob_name; }
        public void setBlob_name(String blob_name) { this.blob_name = blob_name; }
        public String getContainer() { return container; }
        public void setContainer(String container) { this.container = container; }
    }
}
