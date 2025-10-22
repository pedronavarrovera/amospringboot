// src/main/java/com/example/amospringboot/web/MatrixCycleController.java
package com.example.amospringboot.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/matrix/cycle")
@Validated
public class MatrixCycleController {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixCycleController.class);

    private final MatrixCycleService matrixCycleService;

    public MatrixCycleController(MatrixCycleService matrixCycleService) {
        this.matrixCycleService = matrixCycleService;
    }

    @PostMapping("/find")
    public ResponseEntity<CycleFindResponse> findCycle(@Valid @RequestBody CycleFindRequest req) {
        LOG.info("POST /matrix/cycle/find container={} blob={} nodes={} edges={}",
                req.container(), req.blob(),
                req.nodes() != null ? req.nodes().size() : 0,
                req.edges() != null ? req.edges().size() : 0);

        CycleFindResponse result = matrixCycleService.findCycle(req);

        LOG.info("Cycle find result: found={} cycleLength={} detailsKeys={}",
                result.found(),
                result.cycle() != null ? result.cycle().size() : 0,
                result.details() != null ? result.details().keySet() : "none");

        return ResponseEntity.ok(result);
    }

    public record CycleFindRequest(
            @Nullable @Size(min = 1, message = "container must not be blank if provided")
            String container,
            @Nullable @Size(min = 1, message = "blob must not be blank if provided")
            String blob,
            @NotNull @NotEmpty List<String> nodes,
            @NotNull @NotEmpty List<Edge> edges,
            @Nullable Map<String, Object> options
    ) {}

    public record Edge(
            @NotNull String from,
            @NotNull String to,
            @NotNull BigDecimal weight
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CycleFindResponse(
            boolean found,
            @Nullable List<String> cycle,
            @Nullable Map<String, Object> details
    ) {}
}

