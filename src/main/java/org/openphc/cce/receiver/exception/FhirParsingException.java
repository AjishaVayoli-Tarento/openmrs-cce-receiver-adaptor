package org.openphc.cce.receiver.exception;

public class FhirParsingException extends RuntimeException {
    public FhirParsingException(String message) {
        super(message);
    }

    public FhirParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
