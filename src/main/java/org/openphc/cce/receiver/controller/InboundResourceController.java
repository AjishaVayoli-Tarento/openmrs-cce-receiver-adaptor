package org.openphc.cce.receiver.controller;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InboundResourceController {

    private static final Logger log = LoggerFactory.getLogger(InboundResourceController.class);

    private final InboundProcessingService processingService;

    public InboundResourceController(InboundProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping(value = "/openmrs/fhir", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<ProcessingResponse> receiveBundle(
            @RequestBody String payload,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystem,
            @RequestHeader(value = "X-CCE-Intelligence-Delivery-Id", required = false) String intelligenceDeliveryId,
            @RequestHeader(value = "X-CCE-Intelligence-Event-Id", required = false) String intelligenceEventId) {

        log.info("Received inbound FHIR payload - correlationId: {}, source: {}, " +
                        "intelligenceDeliveryId: {}, intelligenceEventId: {}, size: {} bytes",
                correlationId, sourceSystem, intelligenceDeliveryId, intelligenceEventId,
                payload != null ? payload.length() : 0);

        ProcessingResponse response = processingService.process(payload, correlationId, sourceSystem);

        HttpStatus status;
        if (response.failureCount() == 0) {
            // All resources processed successfully.
            status = HttpStatus.ACCEPTED;
        } else if (response.successCount() > 0) {
            // Partial success — surface as 207 so callers can inspect per-resource results.
            status = HttpStatus.MULTI_STATUS;
        } else if (hasRetryableFailure(response)) {
            // All failed and at least one looks transient (downstream 5xx / timeout / connection
            // error). Return 503 so the caller (e.g. cce-intelligence-service) retries with backoff
            // instead of treating it as a permanent rejection.
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else {
            // All failed due to validation / mapping issues — non-retryable.
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        }

        return ResponseEntity.status(status).body(response);
    }

    /**
     * A failure is treated as retryable only when the underlying call indicates a transient
     * server-side or network problem. Definitive client errors (4xx with status, validation
     * rejections) are NOT retried, even if their stack-trace bodies happen to mention
     * "connection" or similar.
     *
     * Retryable conditions:
     *  - HTTP 5xx, 408 Request Timeout, 429 Too Many Requests
     *  - HTTP status 0 (no response received at all — i.e. socket/network exception) AND
     *    the error message hints at a transport-level failure.
     */
    private boolean hasRetryableFailure(ProcessingResponse response) {
        if (response.results() == null) return false;
        for (RoutingResult r : response.results()) {
            if (!"failed".equals(r.status())) continue;
            int code = r.httpStatus();
            if (code >= 500 && code <= 599) return true;
            if (code == 408 || code == 429) return true;
            if (code == 0) {
                String msg = r.errorMessage();
                if (msg == null) return true; // unknown transport failure — retry
                String lower = msg.toLowerCase();
                if (lower.contains("timeout")
                        || lower.contains("timed out")
                        || lower.contains("unavailable")
                        || lower.contains("unreachable")
                        || lower.contains("refused")
                        || lower.contains("connect")) {
                    return true;
                }
            }
        }
        return false;
    }
}
