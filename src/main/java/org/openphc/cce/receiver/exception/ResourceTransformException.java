package org.openphc.cce.receiver.exception;

public class ResourceTransformException extends RuntimeException {
    public ResourceTransformException(String message) {
        super(message);
    }

    public ResourceTransformException(String message, Throwable cause) {
        super(message, cause);
    }
}
