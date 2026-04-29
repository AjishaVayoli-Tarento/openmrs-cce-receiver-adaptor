package org.openphc.cce.receiver.model;

public record ResourceEntry(
        String resourceType,
        String resourceJson,
        String method,
        String fullUrl
) {}
