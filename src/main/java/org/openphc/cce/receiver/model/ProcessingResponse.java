package org.openphc.cce.receiver.model;

import java.time.Instant;
import java.util.List;

public record ProcessingResponse(
        String bundleId,
        int totalResources,
        int successCount,
        int failureCount,
        Instant processedAt,
        List<RoutingResult> results
) {
    public static ProcessingResponse of(String bundleId, List<RoutingResult> results) {
        int success = (int) results.stream().filter(r -> !"failed".equals(r.status())).count();
        int failure = results.size() - success;
        return new ProcessingResponse(bundleId, results.size(), success, failure, Instant.now(), results);
    }
}
