package org.openphc.cce.receiver.service;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InboundProcessingService {

    private static final Logger log = LoggerFactory.getLogger(InboundProcessingService.class);

    private final BundleSplitter bundleSplitter;
    private final ResourceRouter resourceRouter;

    public InboundProcessingService(BundleSplitter bundleSplitter, ResourceRouter resourceRouter) {
        this.bundleSplitter = bundleSplitter;
        this.resourceRouter = resourceRouter;
    }

    public ProcessingResponse process(String payload, String correlationId, String sourceSystem) {
        log.info("Processing inbound request - correlationId: {}, source: {}", correlationId, sourceSystem);

        // Split bundle into individual resources
        List<ResourceEntry> entries = bundleSplitter.split(payload);
        log.info("Split payload into {} resource entries", entries.size());

        if (entries.isEmpty()) {
            return ProcessingResponse.of(correlationId, List.of());
        }

        // Route and process all entries
        List<RoutingResult> results = resourceRouter.routeAll(entries);

        ProcessingResponse response = ProcessingResponse.of(correlationId, results);
        log.info("Processing complete - total: {}, success: {}, failures: {}",
                response.totalResources(), response.successCount(), response.failureCount());

        return response;
    }
}
