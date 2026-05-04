package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openmrs")
public record OpenMrsProperties(
        RestProperties rest,
        FhirProperties fhir,
        AuthProperties auth,
        IdentifierProperties identifier,
        long capabilityRefreshMs
) {
    public record RestProperties(String baseUrl, int timeout) {}
    public record FhirProperties(String baseUrl, int timeout) {}
    public record AuthProperties(String type, String username, String password, OAuth2Properties oauth2) {
        public record OAuth2Properties(String tokenUrl, String clientId, String clientSecret, String scope) {}
    }
    public record IdentifierProperties(String locationUuid, String idgenSourceUuid, String typeName) {}
}
