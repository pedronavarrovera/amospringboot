package com.example.amospringboot.matrix;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class MatrixExceptionHandler {
    @ExceptionHandler(MatrixRemoteException.class)
    public ResponseEntity<Map<String,Object>> handleRemote(MatrixRemoteException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of(
                    "error", "matrix_api_error",
                    "status", ex.getStatusCode().value(),
                    "message", ex.getRemoteBody()
                ));
    }
}
