package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResourceRouter {

    private static final Logger log = LoggerFactory.getLogger(ResourceRouter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ReferenceResolver referenceResolver;
    private final ConceptResolver conceptResolver;
    private final RequiredFieldEnricher requiredFieldEnricher;
    private final PatientIdentifierEnricher patientIdentifierEnricher;
    private final VisitManager visitManager;
    private final FhirToRestTransformer fhirToRestTransformer;
    private final OpenMrsRestClient openMrsRestClient;
    private final OpenMrsFhirClient openMrsFhirClient;

    private static final Set<String> FHIR_ONLY_TYPES = Set.of(
            "Task", "DiagnosticReport", "Procedure",
            "MedicationDispense", "MedicationAdministration", "Consent"
    );

    private static final Map<String, Integer> DEPENDENCY_ORDER = Map.of(
            "Patient", 1,
            "Practitioner", 2,
            "Location", 3,
            "Medication", 4,
            "Encounter", 5
    );

    public ResourceRouter(ReferenceResolver referenceResolver,
                          ConceptResolver conceptResolver,
                          RequiredFieldEnricher requiredFieldEnricher,
                          PatientIdentifierEnricher patientIdentifierEnricher,
                          VisitManager visitManager,
                          FhirToRestTransformer fhirToRestTransformer,
                          OpenMrsRestClient openMrsRestClient,
                          OpenMrsFhirClient openMrsFhirClient) {
        this.referenceResolver = referenceResolver;
        this.conceptResolver = conceptResolver;
        this.requiredFieldEnricher = requiredFieldEnricher;
        this.patientIdentifierEnricher = patientIdentifierEnricher;
        this.visitManager = visitManager;
        this.fhirToRestTransformer = fhirToRestTransformer;
        this.openMrsRestClient = openMrsRestClient;
        this.openMrsFhirClient = openMrsFhirClient;
    }

    public List<RoutingResult> routeAll(List<ResourceEntry> entries) {
        // Sort by dependency order
        List<ResourceEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> DEPENDENCY_ORDER.getOrDefault(e.resourceType(), 10)));

        SourceIdMappingStore mappingStore = new SourceIdMappingStore();
        Map<String, String> perRequestVisitCache = new ConcurrentHashMap<>();
        List<RoutingResult> results = new ArrayList<>();

        for (ResourceEntry entry : sorted) {
            try {
                RoutingResult result = processEntry(entry, mappingStore, perRequestVisitCache);
                results.add(result);

                // Store mapping for cross-references within Bundle
                if (result.resourceId() != null && !"failed".equals(result.status())) {
                    mappingStore.put(entry.fullUrl(), entry.resourceType(),
                            extractId(entry.resourceJson()), result.resourceId());
                }
            } catch (Exception e) {
                log.error("Failed to process {} entry: {}", entry.resourceType(), e.getMessage(), e);
                results.add(RoutingResult.failure(entry.resourceType(), "N/A", 500, e.getMessage()));
            }
        }

        return results;
    }

    private RoutingResult processEntry(ResourceEntry entry, SourceIdMappingStore mappingStore,
                                       Map<String, String> perRequestVisitCache) throws Exception {
        String resourceType = entry.resourceType();
        ObjectNode resourceNode = (ObjectNode) objectMapper.readTree(entry.resourceJson());

        // Enrichment Pipeline
        // [1] Resolve references
        referenceResolver.resolveReferences(resourceNode, mappingStore);

        // [2] Resolve concepts (done during transform for REST path)
        // ConceptResolver is called by FhirToRestTransformer

        // [3] Required field enrichment
        requiredFieldEnricher.enrich(resourceNode, resourceType);

        // [4] Patient identifier enrichment
        if ("Patient".equals(resourceType)) {
            patientIdentifierEnricher.enrich(resourceNode);
        }

        // [5] Visit management for Encounters
        if ("Encounter".equals(resourceType)) {
            visitManager.ensureVisit(resourceNode, perRequestVisitCache);
        }

        // Ensure encounter for standalone orders
        if ("ServiceRequest".equals(resourceType) || "MedicationRequest".equals(resourceType)) {
            String encounterUuid = visitManager.ensureEncounterForOrder(resourceNode, perRequestVisitCache);
            if (encounterUuid != null) {
                ObjectNode encounterRef = objectMapper.createObjectNode();
                encounterRef.put("reference", "Encounter/" + encounterUuid);
                resourceNode.set("encounter", encounterRef);
            }
        }

        // Route to REST or FHIR path
        if (FHIR_ONLY_TYPES.contains(resourceType)) {
            return routeViaFhir(resourceNode, resourceType);
        } else {
            return routeViaRest(resourceNode, resourceType, entry);
        }
    }

    private RoutingResult routeViaRest(ObjectNode resourceNode, String resourceType, ResourceEntry entry) {
        // [6] Transform FHIR → REST payload
        FhirToRestTransformer.TransformResult transformResult =
                fhirToRestTransformer.transform(resourceNode, resourceType);

        // Determine create vs update
        String existingUuid = resolveExistingUuid(resourceNode, resourceType, entry);

        if (existingUuid != null) {
            return openMrsRestClient.update(transformResult.endpoint(), existingUuid,
                    transformResult.payload(), resourceType);
        } else {
            return openMrsRestClient.create(transformResult.endpoint(), transformResult.payload(), resourceType);
        }
    }

    private RoutingResult routeViaFhir(ObjectNode resourceNode, String resourceType) {
        String payload = resourceNode.toString();

        // Check for existing resource by ID
        String id = resourceNode.path("id").asText(null);
        if (id != null && !id.isBlank()) {
            // For FHIR path, attempt update
            return openMrsFhirClient.update(resourceType, id, payload);
        }

        return openMrsFhirClient.create(resourceType, payload);
    }

    private String resolveExistingUuid(ObjectNode resourceNode, String resourceType, ResourceEntry entry) {
        String id = resourceNode.path("id").asText(null);
        if (id == null || id.isBlank()) return null;

        // UUID pattern check
        if (id.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            String endpoint = getRestEndpoint(resourceType);
            if (endpoint != null && openMrsRestClient.exists(endpoint, id)) {
                return id;
            }
        }

        // For Patient, try identifier-based search
        if ("Patient".equals(resourceType)) {
            JsonNode identifiers = resourceNode.path("identifier");
            if (identifiers.isArray()) {
                for (JsonNode idNode : identifiers) {
                    String value = idNode.path("value").asText(idNode.path("identifier").asText(""));
                    if (!value.isBlank()) {
                        String found = openMrsRestClient.searchByIdentifier("/patient", value);
                        if (found != null) return found;
                    }
                }
            }
        }

        return null;
    }

    private String getRestEndpoint(String resourceType) {
        return switch (resourceType) {
            case "Patient" -> "/patient";
            case "Encounter" -> "/encounter";
            case "Observation" -> "/obs";
            case "Condition" -> "/condition";
            case "AllergyIntolerance" -> "/allergy";
            case "ServiceRequest", "MedicationRequest" -> "/order";
            case "Location" -> "/location";
            case "Practitioner" -> "/provider";
            case "Medication" -> "/drug";
            case "Immunization" -> "/obs";
            default -> null;
        };
    }

    private String extractId(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
