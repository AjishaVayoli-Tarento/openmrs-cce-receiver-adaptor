package org.openphc.cce.receiver.controller;

import org.openphc.cce.receiver.model.ProcessingResponse;
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
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystem) {

        log.info("Received inbound FHIR payload - correlationId: {}, source: {}, size: {} bytes",
                correlationId, sourceSystem, payload != null ? payload.length() : 0);

        ProcessingResponse response = processingService.process(payload, correlationId, sourceSystem);

        HttpStatus status;
        if (response.failureCount() == 0) {
            status = HttpStatus.ACCEPTED;
        } else if (response.successCount() > 0) {
            status = HttpStatus.MULTI_STATUS;
        } else {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
        }

        return ResponseEntity.status(status).body(response);
    }
}
