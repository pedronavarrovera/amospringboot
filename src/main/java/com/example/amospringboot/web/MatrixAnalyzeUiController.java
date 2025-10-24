// src/main/java/com/example/amospringboot/web/MatrixAnalyzeUiController.java
package com.example.amospringboot.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.*;
import java.util.regex.Pattern;

@Controller
@RequestMapping("/matrix/analyze")
public class MatrixAnalyzeUiController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixAnalyzeUiController.class);

    // Must match src/main/resources/templates/matrix/analyze.html
    private static final String VIEW = "matrix/analyze";
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
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String showAnalyze(Model model,
                              @RequestParam(value = "blob", required = false) String blobOverride) {
        String chosenBlob = (blobOverride != null && !blobOverride.isBlank()) ? blobOverride : safeLatestBlob();
        if (!model.containsAttribute("form")) {
            AnalyzeForm form = new AnalyzeForm();
            form.setBlob_name(chosenBlob);
            form.setContainer(CONTAINER);
            model.addAttribute("form", form);
        }
        model.addAttribute("error", null);
        model.addAttribute("result", null);
        model.addAttribute("resultJson", "{}");
        LOG.info("ANALYZE_UI_GET container={} blob={} override={}", CONTAINER, chosenBlob, (blobOverride == null ? "null" : blobOverride));
        return VIEW;
    }

    /** Handle Analyze submit from analyze.html */
    @PostMapping(consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String submitAnalyze(@ModelAttribute("form") AnalyzeForm form, Model model) {
        // Recompute authoritative values
        String blob = safeLatestBlob();
        form.setBlob_name(blob);
        form.setContainer(CONTAINER);

        LOG.info("ANALYZE_ATTEMPT container={} blob={}", form.getContainer(), form.getBlob_name());

        Map<String, Object> payload = Map.of(
                "blob_name", form.getBlob_name(),
                "container", form.getContainer()
        );

        try {
            Map<String, Object> result = tryAnalyze(payload);

            String status = (result != null) ? String.valueOf(result.getOrDefault("status", "unknown")) : "null";
            LOG.info("ANALYZE_SUCCESS container={} blob={} status={} keys={}",
                    form.getContainer(), form.getBlob_name(), status,
                    (result == null ? 0 : result.keySet().size()));

            model.addAttribute("result", result);
            model.addAttribute("error", null);
            model.addAttribute("resultJson", toJsonSafe(result));

        } catch (WebClientResponseException wcre) {
            int code = wcre.getStatusCode().value();
            LOG.warn("ANALYZE_FAILURE_HTTP container={} blob={} httpCode={} body={}",
                    form.getContainer(), form.getBlob_name(), code,
                    truncate(wcre.getResponseBodyAsString(), 1000));

            model.addAttribute("error", "Analyze failed: HTTP " + code);
            model.addAttribute("result", null);
            model.addAttribute("resultJson", toJsonSafe(Map.of("status","error","http_code",code)));

        } catch (Exception ex) {
            String msg = (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            LOG.error("ANALYZE_FAILURE_EX container={} blob={} errorClass={} message={}",
                    form.getContainer(), form.getBlob_name(), ex.getClass().getName(), truncate(msg, 1000), ex);

            model.addAttribute("error", "Analyze failed: " + msg);
            model.addAttribute("result", null);
            model.addAttribute("resultJson", toJsonSafe(Map.of("status","error","message",msg)));
        }

        return VIEW;
    }

    /**
     * Call backend analyze: try POST first; if 405 Method Not Allowed, retry as GET with query params.
     */
    private Map<String, Object> tryAnalyze(Map<String, Object> payload) {
        try {
            // Primary: POST /matrix/analyze (JSON)
            return matrixWebClient.post()
                    .uri("/matrix/analyze")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        } catch (WebClientResponseException.MethodNotAllowed mna) {
            // Fallback: GET /matrix/analyze?container=...&blob_name=...
            String container = String.valueOf(payload.get("container"));
            String blobName  = String.valueOf(payload.get("blob_name"));

            LOG.info("ANALYZE_RETRY_GET container={} blob={} because=405", container, blobName);

            return matrixWebClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/matrix/analyze")
                            .queryParam("container", container)
                            .queryParam("blob_name", blobName)
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp -> resp.createException())
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
        }
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
            String bestTs = null;
            long bestVal = 0L;

            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                if (n.endsWith("-latest.b64")) latestAlias = n;
                long v = extractSortableTs(n);
                if (v > bestVal) { bestVal = v; bestTs = n; }
            }

            if (bestTs != null) {
                LOG.info("safeLatestBlob(): selected timestamped {}", bestTs);
                return bestTs;
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

        try { return Long.parseLong(yyyymmdd + hhmmss); }
        catch (NumberFormatException ignore) { return 0L; }
    }

    private String toJsonSafe(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { return "{\"error\":\"json-serialize-failed\"}"; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max) + "...(truncated)";
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


