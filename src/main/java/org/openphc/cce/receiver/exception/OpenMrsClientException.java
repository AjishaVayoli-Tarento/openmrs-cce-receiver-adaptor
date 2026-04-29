package org.openphc.cce.receiver.exception;

public class OpenMrsClientException extends RuntimeException {

    private final int statusCode;

    public OpenMrsClientException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public OpenMrsClientException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
