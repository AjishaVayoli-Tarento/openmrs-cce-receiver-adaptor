package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.exception.OpenMrsClientException;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
public class OpenMrsRestClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMrsRestClient.class);
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenMrsRestClient(@Qualifier("openmrsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Retryable(retryFor = HttpServerErrorException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public RoutingResult create(String endpoint, String payload, String resourceType) {
        try {
            log.debug("POST {} - payload length: {}", endpoint, payload.length());

            String response = restClient.post()
                    .uri(endpoint)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);

            log.info("Created {} via REST {}: {}", resourceType, endpoint, uuid);
            return RoutingResult.success(resourceType, uuid, "REST:" + endpoint, 201, response);
        } catch (HttpServerErrorException e) {
            log.warn("Server error on POST {}: {} - retrying", endpoint, e.getStatusCode());
            throw e;
        } catch (Exception e) {
            log.error("Failed to POST {} to {}: {}", resourceType, endpoint, e.getMessage());
            return RoutingResult.failure(resourceType, "REST:" + endpoint, 502, e.getMessage());
        }
    }

    @Retryable(retryFor = HttpServerErrorException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2))
    public RoutingResult update(String endpoint, String uuid, String payload, String resourceType) {
        try {
            log.debug("PUT {}/{} - payload length: {}", endpoint, uuid, payload.length());

            String response = restClient.put()
                    .uri("{endpoint}/{uuid}", endpoint, uuid)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            log.info("Updated {} via REST {}/{}", resourceType, endpoint, uuid);
            return RoutingResult.updated(resourceType, uuid, "REST:" + endpoint, 200, response);
        } catch (HttpServerErrorException e) {
            log.warn("Server error on PUT {}/{}: {} - retrying", endpoint, uuid, e.getStatusCode());
            throw e;
        } catch (Exception e) {
            log.error("Failed to PUT {} at {}/{}: {}", resourceType, endpoint, uuid, e.getMessage());
            return RoutingResult.failure(resourceType, "REST:" + endpoint, 502, e.getMessage());
        }
    }

    public boolean exists(String endpoint, String uuid) {
        try {
            restClient.get()
                    .uri("{endpoint}/{uuid}", endpoint, uuid)
                    .retrieve()
                    .body(String.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String searchByIdentifier(String endpoint, String identifier) {
        try {
            String response = restClient.get()
                    .uri("{endpoint}?identifier={id}&v=default", endpoint, identifier)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                return results.get(0).path("uuid").asText(null);
            }
        } catch (Exception e) {
            log.debug("Identifier search failed: {}", e.getMessage());
        }
        return null;
    }
}
