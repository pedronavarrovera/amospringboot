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

        Pattern p = Pattern.compile("(\\d{8}-\\d{6})(?=\\.[^.]+$)");

        // First try timestamp-aware max
        String byTimestamp = files.stream()
                .filter(n -> n != null && n.endsWith(".b64"))
                .map(n -> {
                    Matcher m = p.matcher(n);
                    String last = null;
                    while (m.find()) last = m.group(1); // last occurrence
                    if (last == null) return null;
                    String ts = last.replace("-", "");  // e.g. 20251015082727
                    return new String[]{ n, ts };
                })
                .filter(arr -> arr != null)
                .max(Comparator.comparing(arr -> arr[1])) // lexicographic on YYYYMMDDHHMMSS
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
}
