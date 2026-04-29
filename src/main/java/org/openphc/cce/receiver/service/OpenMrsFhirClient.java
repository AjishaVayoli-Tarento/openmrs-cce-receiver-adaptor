package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
public class OpenMrsFhirClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsFhirClient.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenMrsFhirClient(@Qualifier("fhirRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Retryable(retryFor = HttpServerErrorException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public RoutingResult create(String resourceType, String fhirJson) {
        try {
            log.debug("POST FHIR /{} - payload length: {}", resourceType, fhirJson.length());

            String response = restClient.post()
                    .uri("/{resourceType}", resourceType)
                    .body(fhirJson)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String id = root.path("id").asText(null);

            log.info("Created {} via FHIR: {}", resourceType, id);
            return RoutingResult.success(resourceType, id, "FHIR:/" + resourceType, 201, response);
        } catch (HttpServerErrorException e) {
            log.warn("Server error on FHIR POST /{}: {} - retrying", resourceType, e.getStatusCode());
            throw e;
        } catch (Exception e) {
            log.error("Failed to POST FHIR {}: {}", resourceType, e.getMessage());
            return RoutingResult.failure(resourceType, "FHIR:/" + resourceType, 502, e.getMessage());
        }
    }

    @Retryable(retryFor = HttpServerErrorException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public RoutingResult update(String resourceType, String id, String fhirJson) {
        try {
            log.debug("PUT FHIR /{}/{}", resourceType, id);

            String response = restClient.put()
                    .uri("/{resourceType}/{id}", resourceType, id)
                    .body(fhirJson)
                    .retrieve()
                    .body(String.class);

            log.info("Updated {} via FHIR: {}", resourceType, id);
            return RoutingResult.updated(resourceType, id, "FHIR:/" + resourceType, 200, response);
        } catch (HttpServerErrorException e) {
            log.warn("Server error on FHIR PUT /{}/{}: {} - retrying", resourceType, id, e.getStatusCode());
            throw e;
        } catch (Exception e) {
            log.error("Failed to PUT FHIR {}/{}: {}", resourceType, id, e.getMessage());
            return RoutingResult.failure(resourceType, "FHIR:/" + resourceType, 502, e.getMessage());
        }
    }
}
