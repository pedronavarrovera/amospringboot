// src/main/java/com/example/amospringboot/web/GlobalErrorHandler.java
package com.example.amospringboot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.net.URI;

@RestControllerAdvice
public class GlobalErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalErrorHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        pd.setTitle("Invalid request");
        pd.setType(URI.create("about:blank#validation-error"));
        pd.setProperty("errors", ex.getBindingResult().getAllErrors());
        LOG.warn("400 Validation failed: {}", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(BindException.class)
    public ProblemDetail handleBind(BindException ex) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Binding failed");
        pd.setTitle("Invalid request");
        pd.setType(URI.create("about:blank#bind-error"));
        pd.setProperty("errors", ex.getAllErrors());
        LOG.warn("400 Binding failed: {}", ex.getMessage());
        return pd;
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ProblemDetail handleErrorResponse(ErrorResponseException ex) {
        LOG.error("Handled ErrorResponseException", ex);
        return ex.getBody();
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        LOG.error("500 Internal error", ex);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
        pd.setTitle("Unexpected error");
        pd.setType(URI.create("about:blank#internal"));
        return pd;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        pd.setTitle("Not found");
        pd.setDetail("Endpoint or resource not found");
        pd.setType(URI.create("about:blank#not-found"));
        return pd;
    }
    // (While here) return 405 instead of 500 for wrong method:
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        var pd = ProblemDetail.forStatus(HttpStatus.METHOD_NOT_ALLOWED);
        pd.setTitle("Method not allowed");
        pd.setDetail(ex.getMessage());
        pd.setType(URI.create("about:blank#method-not-allowed"));
        return pd;
    }
}
