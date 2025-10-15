package com.example.amospringboot.matrix;

import com.example.amospringboot.matrix.dto.CycleFindRequest;
import com.example.amospringboot.matrix.dto.PaymentRequest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Component
public class MatrixApiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
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
                    .retrieve()
                    .bodyToMono(MAP_TYPE)   // ✅ type-safe
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
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)   // ✅ type-safe
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
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(MAP_TYPE)   // ✅ type-safe
                    .block();
        } catch (WebClientResponseException ex) {
            throw new MatrixRemoteException(ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        } catch (Exception e) {
            throw new MatrixRemoteException(HttpStatus.BAD_GATEWAY, "Matrix API unreachable", e);
        }
    }
}
