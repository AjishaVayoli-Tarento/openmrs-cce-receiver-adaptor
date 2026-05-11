package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional SPICE FHIR lookup used when provisioning a
 * placeholder patient — fetches the upstream SPICE patient demographics so the
 * created OpenMRS patient mirrors the source instead of using "Unknown".
 */
@ConfigurationProperties(prefix = "spice.fhir")
public record SpiceProperties(
        boolean enabled,
        String baseUrl,
        String token,
        String client,
        String lookupResource,
        String identifierSystem,
        int timeout
) {
    public SpiceProperties {
        if (timeout <= 0) timeout = 30000;
        // Default the FHIR client header used by SPICE to "job" — dev/EC2
        // tokens are minted for the job suite. Override per-environment if needed.
        if (client == null || client.isBlank()) client = "job";
        // SPICE stores national-id on the RelatedPerson resource (not Patient),
        // so the inbound subject.reference (which is the national-id) is looked
        // up against RelatedPerson by default.
        if (lookupResource == null || lookupResource.isBlank()) lookupResource = "RelatedPerson";
    }
}
