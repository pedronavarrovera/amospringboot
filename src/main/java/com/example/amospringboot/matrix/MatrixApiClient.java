package com.example.amospringboot.matrix;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MatrixApiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<String>> LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Matches one or more "-YYYYMMDD-HHMMSS" groups immediately before the extension.
     * Example: "initial-matrix-20251018-091137-20251021-195554.b64"
     */
    private static final Pattern MULTI_TS_BEFORE_EXT =
            Pattern.compile("(-\\d{8}-\\d{6})+(?=\\.[^.]+$)");

    /**
     * Captures the last YYYYMMDD-HHMMSS immediately before the extension.
     * We’ll use the last match when files have multiple stamps.
     */
    private static final Pattern LAST_TS_BEFORE_EXT =
            Pattern.compile("(\\d{8}-\\d{6})(?=\\.[^.]+$)");

    private final WebClient webClient;

    public MatrixApiClient(WebClient matrixWebClient) {
        this.webClient = matrixWebClient;
    }

    /** GET /matrix/analyze?blob_name=...&container=... */
    public Map<String, Object> analyze(String blobName, String container) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("blob_name", blobName);
            if (container != null && !container.isBlank()) params.add("container", container);

            return webClient.get()
                    .uri(uri -> uri.path("/matrix/analyze").queryParams(params).build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .block();
        } catch (WebClientResponseException ex) {
            throw new MatrixRemoteException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception e) {
            throw new MatrixRemoteException(HttpStatus.BAD_GATEWAY, "Matrix API unreachable", e);
        }
    }

    /** POST /matrix/cycle/find */
    public Map<String, Object> findCycle(CycleFindRequest req) {
        try {
            return webClient.post()
                    .uri("/matrix/cycle/find")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .block();
        } catch (WebClientResponseException ex) {
            throw new MatrixRemoteException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception e) {
            throw new MatrixRemoteException(HttpStatus.BAD_GATEWAY, "Matrix API unreachable", e);
        }
    }

    /** POST /matrix/payment */
    public Map<String, Object> payment(PaymentRequest req) {
        try {
            return webClient.post()
                    .uri("/matrix/payment")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)
                    .block();
        } catch (WebClientResponseException ex) {
            throw new MatrixRemoteException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception e) {
            throw new MatrixRemoteException(HttpStatus.BAD_GATEWAY, "Matrix API unreachable", e);
        }
    }

    /** Alias for convenience/compat: some callers expect applyPayment(...) */
    public Map<String, Object> applyPayment(PaymentRequest req) {
        return payment(req);
    }

    /* ===================== BLOBS ===================== */

    /** GET /matrix/blobs?container=... -> returns list of filenames */
    public List<String> listBlobs(String container) {
        try {
            return webClient.get()
                    .uri(uri -> uri.path("/matrix/blobs")
                                   .queryParam("container", container)
                                   .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(LIST_TYPE)
                    .block();
        } catch (WebClientResponseException ex) {
            // If the API returns 404 when there are no blobs, don't explode—return empty list.
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new ArrayList<>();
            }
            throw new MatrixRemoteException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception e) {
            throw new MatrixRemoteException(HttpStatus.BAD_GATEWAY, "Matrix API unreachable", e);
        }
    }

    /**
     * Returns the *.b64 file with the newest timestamp found as the LAST occurrence
     * of pattern YYYYMMDD-HHMMSS right before the extension (e.g., 20251015-082727 in
     * payment-User-20251015-082727.b64). Falls back to the provided default if none found.
     * If no filenames contain a timestamp, pick the lexicographically last *.b64 as a heuristic.
     */
    public String latestBlob(String container, String fallback) {
        List<String> files = listBlobs(container);
        if (files == null || files.isEmpty()) return fallback;

        String byTimestamp = files.stream()
                .filter(n -> n != null && n.endsWith(".b64"))
                .map(n -> new String[]{ n, extractLastTimestampCompact(n) }) // [name, yyyymmddHHmmss or null]
                .filter(arr -> arr[1] != null)
                .max(Comparator.comparing((String[] arr) -> arr[1]).thenComparing(arr -> arr[0]))
                .map(arr -> arr[0])
                .orElse(null);

        if (byTimestamp != null) return byTimestamp;

        // Fallback: pick lexicographically last *.b64
        return files.stream()
                .filter(n -> n != null && n.endsWith(".b64"))
                .max(String::compareTo)
                .orElse(fallback);
    }

    /** Convenience overload with a sensible default fallback. */
    public String latestBlob(String container) {
        return latestBlob(container, "initial-matrix.b64");
    }

    /* ===================== FILENAME HELPERS (NEW) ===================== */

    /**
     * Strip any number of "-YYYYMMDD-HHMMSS" groups that appear immediately before the extension.
     * Example:
     *   "initial-matrix-20251018-091137-20251021-195554.b64" -> "initial-matrix.b64"
     */
    public String normalizeBase(String blobName) {
        if (blobName == null) return null;
        return MULTI_TS_BEFORE_EXT.matcher(blobName).replaceAll("");
    }

    /**
     * Build a single, clean timestamped filename from a base (which may already have stamps).
     * Keeps the original extension (".b64" or whatever is present).
     * Example:
     *   base="initial-matrix-20251018-091137.b64" -> "initial-matrix-20251021-200105.b64"
     */
    public String nextTimestampedName(String base) {
        return nextTimestampedName(base, LocalDateTime.now());
    }

    /** For testability/injection */
    public String nextTimestampedName(String base, LocalDateTime now) {
        String normalized = normalizeBase(base);
        if (normalized == null || normalized.isBlank()) {
            normalized = "initial-matrix.b64";
        }
        int dot = normalized.lastIndexOf('.');
        String ext = dot >= 0 ? normalized.substring(dot) : "";
        String stem = dot >= 0 ? normalized.substring(0, dot) : normalized;

        return stem + "-" + now.format(TS_FMT) + ext;
    }

    /**
     * Extract the *last* "YYYYMMDD-HHMMSS" immediately before the extension and return it
     * compacted as "yyyyMMddHHmmss" for lexicographic comparison. Returns null if absent.
     */
    private String extractLastTimestampCompact(String name) {
        if (name == null) return null;
        Matcher m = LAST_TS_BEFORE_EXT.matcher(name);
        String last = null;
        while (m.find()) last = m.group(1); // take the last timestamp before extension
        if (last == null) return null;
        return last.replace("-", ""); // yyyyMMddHHmmss
    }
}
