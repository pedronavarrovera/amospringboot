package com.example.amospringboot.matrix;

import org.springframework.http.HttpStatusCode;

public class MatrixRemoteException extends RuntimeException {
    private final HttpStatusCode statusCode;
    private final String remoteBody;

    public MatrixRemoteException(HttpStatusCode statusCode, String remoteBody, Throwable cause) {
        super("Matrix API error: " + statusCode + " " + remoteBody, cause);
        this.statusCode = statusCode;
        this.remoteBody = remoteBody;
    }

    public HttpStatusCode getStatusCode() { return statusCode; }
    public String getRemoteBody() { return remoteBody; }
}

