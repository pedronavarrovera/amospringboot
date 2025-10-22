// src/main/java/com/example/amospringboot/web/MatrixCycleService.java
package com.example.amospringboot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MatrixCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixCycleService.class);

    private final WebClient matrixWebClient;

    public MatrixCycleService(WebClient matrixWebClient) {
        this.matrixWebClient = matrixWebClient;
    }

    public MatrixCycleController.CycleFindResponse findCycle(MatrixCycleController.CycleFindRequest req) {

        Map<String, Object> payload = Map.of(
                "container", req.container(),
                "blob", req.blob(),
                "nodes", req.nodes(),
                "edges", req.edges(),
                "options", req.options()
        );

        try {
            Map<String, Object> backend =
                    matrixWebClient.post()
                            .uri("/matrix/cycle/find")
                            .bodyValue(payload)
                            .retrieve()
                            .onStatus(
                                    status -> status.is4xxClientError(),
                                    response -> response.bodyToMono(String.class)
                                            .defaultIfEmpty("Bad request to backend")
                                            .flatMap(msg -> {
                                                LOG.warn("Backend 4xx: {}", msg);
                                                var pd = ProblemDetail.forStatusAndDetail(
                                                        HttpStatus.BAD_GATEWAY,
                                                        "Backend rejected request: " + msg);
                                                return Mono.error(new ErrorResponseException(
                                                        HttpStatus.BAD_GATEWAY, pd, null));
                                            })
                            )
                            .onStatus(
                                    status -> status.is5xxServerError(),
                                    response -> response.bodyToMono(String.class)
                                            .defaultIfEmpty("Backend error")
                                            .flatMap(msg -> {
                                                LOG.error("Backend 5xx: {}", msg);
                                                var pd = ProblemDetail.forStatusAndDetail(
                                                        HttpStatus.BAD_GATEWAY,
                                                        "Backend failed: " + msg);
                                                return Mono.error(new ErrorResponseException(
                                                        HttpStatus.BAD_GATEWAY, pd, null));
                                            })
                            )
                            .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                            .block(Duration.ofSeconds(30));

            if (backend == null) {
                throw new ErrorResponseException(
                        HttpStatus.BAD_GATEWAY,
                        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No response from matrix backend"),
                        null
                );
            }

            validateSchema(backend);

            boolean found = getBool(backend.get("found"));
            List<String> cycle = asStringList(backend.get("cycle"));
            Map<String, Object> details = asMap(backend.get("details"));

            return new MatrixCycleController.CycleFindResponse(found, cycle, details);

        } catch (ErrorResponseException e) {
            throw e; // handled globally
        } catch (Exception ex) {
            LOG.error("Cycle search failed", ex);
            var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Cycle search failed");
            throw new ErrorResponseException(HttpStatus.INTERNAL_SERVER_ERROR, pd, ex);
        }
    }

    /** --- SCHEMA VALIDATION --- **/
    private void validateSchema(Map<String, Object> backend) {
        Set<String> validKeys = Set.of("found", "cycle", "details");
        for (String key : backend.keySet()) {
            if (!validKeys.contains(key)) {
                String msg = "Unexpected key in backend response: " + key +
                        " (expected only " + validKeys + ")";
                LOG.warn(msg);
                var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, msg);
                throw new ErrorResponseException(HttpStatus.BAD_GATEWAY, pd, null);
            }
        }

        if (!backend.containsKey("found")) {
            var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                    "Backend response missing 'found' field");
            throw new ErrorResponseException(HttpStatus.BAD_GATEWAY, pd, null);
        }
    }

    /** --- UTILITY CASTS --- **/
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map<?, ?> m) ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        if (o instanceof List<?> list) {
            return (List<String>) list.stream().map(String::valueOf).toList();
        }
        return null;
    }

    private static boolean getBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        if (o instanceof Number n) return n.intValue() != 0;
        return false;
    }
}
