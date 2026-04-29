package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class RequiredFieldEnricher {

    private static final Logger log = LoggerFactory.getLogger(RequiredFieldEnricher.class);
    private static final DateTimeFormatter OPENMRS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    public ObjectNode enrich(ObjectNode resourceNode, String resourceType) {
        String now = ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);

        switch (resourceType) {
            case "Observation" -> enrichObservation(resourceNode, now);
            case "Encounter" -> enrichEncounter(resourceNode, now);
            case "Condition" -> enrichCondition(resourceNode, now);
            case "AllergyIntolerance" -> enrichAllergyIntolerance(resourceNode, now);
            case "Immunization" -> enrichImmunization(resourceNode, now);
            case "DiagnosticReport" -> enrichDiagnosticReport(resourceNode, now);
            case "ServiceRequest" -> enrichServiceRequest(resourceNode, now);
            case "MedicationRequest" -> enrichMedicationRequest(resourceNode, now);
            case "Procedure" -> enrichProcedure(resourceNode, now);
            case "MedicationDispense" -> enrichMedicationDispense(resourceNode, now);
            case "MedicationAdministration" -> enrichMedicationAdministration(resourceNode, now);
            case "Task" -> enrichTask(resourceNode, now);
            case "Consent" -> enrichConsent(resourceNode, now);
        }

        return resourceNode;
    }

    private void enrichObservation(ObjectNode node, String now) {
        setIfMissing(node, "effectiveDateTime", now);
        setIfMissing(node, "status", "final");
    }

    private void enrichEncounter(ObjectNode node, String now) {
        if (node.path("period").isMissingNode()) {
            ObjectNode period = node.putObject("period");
            period.put("start", now);
        } else {
            ObjectNode period = (ObjectNode) node.get("period");
            if (period.path("start").isMissingNode() || period.path("start").asText("").isBlank()) {
                period.put("start", now);
            }
        }
        setIfMissing(node, "status", "finished");
    }

    private void enrichCondition(ObjectNode node, String now) {
        setIfMissing(node, "recordedDate", now);
        if (node.path("clinicalStatus").isMissingNode()) {
            ObjectNode cs = node.putObject("clinicalStatus");
            cs.putArray("coding").addObject()
                    .put("system", "http://terminology.hl7.org/CodeSystem/condition-clinical")
                    .put("code", "active");
        }
    }

    private void enrichAllergyIntolerance(ObjectNode node, String now) {
        setIfMissing(node, "recordedDate", now);
        setIfMissing(node, "type", "allergy");
    }

    private void enrichImmunization(ObjectNode node, String now) {
        setIfMissing(node, "occurrenceDateTime", now);
        setIfMissing(node, "status", "completed");
    }

    private void enrichDiagnosticReport(ObjectNode node, String now) {
        setIfMissing(node, "issued", now);
        setIfMissing(node, "status", "final");
    }

    private void enrichServiceRequest(ObjectNode node, String now) {
        setIfMissing(node, "authoredOn", now);
        setIfMissing(node, "status", "active");
        setIfMissing(node, "intent", "order");
    }

    private void enrichMedicationRequest(ObjectNode node, String now) {
        setIfMissing(node, "authoredOn", now);
        setIfMissing(node, "status", "active");
        setIfMissing(node, "intent", "order");
    }

    private void enrichProcedure(ObjectNode node, String now) {
        setIfMissing(node, "performedDateTime", now);
        setIfMissing(node, "status", "completed");
    }

    private void enrichMedicationDispense(ObjectNode node, String now) {
        setIfMissing(node, "whenHandedOver", now);
        setIfMissing(node, "status", "completed");
    }

    private void enrichMedicationAdministration(ObjectNode node, String now) {
        setIfMissing(node, "effectiveDateTime", now);
        setIfMissing(node, "status", "completed");
    }

    private void enrichTask(ObjectNode node, String now) {
        setIfMissing(node, "authoredOn", now);
        setIfMissing(node, "status", "requested");
        setIfMissing(node, "intent", "order");
    }

    private void enrichConsent(ObjectNode node, String now) {
        setIfMissing(node, "dateTime", now);
        setIfMissing(node, "status", "active");
    }

    private void setIfMissing(ObjectNode node, String field, String value) {
        if (node.path(field).isMissingNode() || node.path(field).asText("").isBlank()) {
            node.put(field, value);
            log.debug("Auto-filled missing field '{}' with '{}'", field, value);
        }
    }
}
