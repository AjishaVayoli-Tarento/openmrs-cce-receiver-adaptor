package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the SPICE referral-specific flow.
 *
 * When a SPICE FHIR ServiceRequest is detected as a referral (see
 * {@code FhirToRestTransformer.isReferral}), the adaptor uses these UUIDs to
 * create the dedicated Outpatient Clinic visit and "Referral In" encounter.
 *
 * If {@link #enabled} is {@code false}, the adaptor falls back to the generic
 * visit/encounter logic for all ServiceRequests (legacy behaviour).
 */
@Component
@ConfigurationProperties(prefix = "openmrs.referral")
public class ReferralProperties {

    private boolean enabled = true;
    private String locationUuid;
    private String visitTypeUuid;
    private String encounterTypeUuid;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getLocationUuid() { return locationUuid; }
    public void setLocationUuid(String locationUuid) { this.locationUuid = locationUuid; }

    public String getVisitTypeUuid() { return visitTypeUuid; }
    public void setVisitTypeUuid(String visitTypeUuid) { this.visitTypeUuid = visitTypeUuid; }

    public String getEncounterTypeUuid() { return encounterTypeUuid; }
    public void setEncounterTypeUuid(String encounterTypeUuid) { this.encounterTypeUuid = encounterTypeUuid; }
}
