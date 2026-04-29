package org.openphc.cce.receiver.config;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class DiscoveredConfig {

    private volatile String locationUuid;
    private volatile String identifierTypeName;
    private volatile String identifierTypeUuid;
    private volatile String idgenSourceUuid;
    private volatile String visitTypeUuid;
    private final List<String> sourceIdentifierTypes = new CopyOnWriteArrayList<>();
    private final Map<String, String> identifierTypeUuids = new ConcurrentHashMap<>();
    private final Map<String, String> encounterTypeCache = new ConcurrentHashMap<>();
    private volatile String phoneNumberAttributeTypeUuid;
    private volatile String emailAttributeTypeUuid;
    private volatile String severeSeverityConceptUuid;
    private volatile String moderateSeverityConceptUuid;
    private volatile String mildSeverityConceptUuid;

    public String getLocationUuid() { return locationUuid; }
    public void setLocationUuid(String locationUuid) { this.locationUuid = locationUuid; }

    public String getIdentifierTypeName() { return identifierTypeName; }
    public void setIdentifierTypeName(String identifierTypeName) { this.identifierTypeName = identifierTypeName; }

    public String getIdentifierTypeUuid() { return identifierTypeUuid; }
    public void setIdentifierTypeUuid(String identifierTypeUuid) { this.identifierTypeUuid = identifierTypeUuid; }

    public String getIdgenSourceUuid() { return idgenSourceUuid; }
    public void setIdgenSourceUuid(String idgenSourceUuid) { this.idgenSourceUuid = idgenSourceUuid; }

    public String getVisitTypeUuid() { return visitTypeUuid; }
    public void setVisitTypeUuid(String visitTypeUuid) { this.visitTypeUuid = visitTypeUuid; }

    public List<String> getSourceIdentifierTypes() { return sourceIdentifierTypes; }

    public Map<String, String> getIdentifierTypeUuids() { return identifierTypeUuids; }

    public Map<String, String> getEncounterTypeCache() { return encounterTypeCache; }

    public synchronized void addSourceIdentifierType(String typeName, String typeUuid) {
        if (!sourceIdentifierTypes.contains(typeName)) {
            sourceIdentifierTypes.add(typeName);
        }
        identifierTypeUuids.put(typeName, typeUuid);
    }

    public String getPhoneNumberAttributeTypeUuid() { return phoneNumberAttributeTypeUuid; }
    public void setPhoneNumberAttributeTypeUuid(String uuid) { this.phoneNumberAttributeTypeUuid = uuid; }

    public String getEmailAttributeTypeUuid() { return emailAttributeTypeUuid; }
    public void setEmailAttributeTypeUuid(String uuid) { this.emailAttributeTypeUuid = uuid; }

    public String getSevereSeverityConceptUuid() { return severeSeverityConceptUuid; }
    public void setSevereSeverityConceptUuid(String uuid) { this.severeSeverityConceptUuid = uuid; }

    public String getModerateSeverityConceptUuid() { return moderateSeverityConceptUuid; }
    public void setModerateSeverityConceptUuid(String uuid) { this.moderateSeverityConceptUuid = uuid; }

    public String getMildSeverityConceptUuid() { return mildSeverityConceptUuid; }
    public void setMildSeverityConceptUuid(String uuid) { this.mildSeverityConceptUuid = uuid; }
}
