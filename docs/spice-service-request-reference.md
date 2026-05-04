# SPICE ServiceRequest — Field Reference

> **Document Purpose:** Defines every field in the FHIR `ServiceRequest` resource that SPICE's fhir-mapper generates for a referral, what each field means in real-world clinical terms, how the CCE Receiver Adaptor processes it, and whether it currently maps correctly to OpenMRS.
>
> **Source:** Derived from `FhirAssessmentMapper.createReferralTicket()` in SPICE fhir-mapper and `FhirToRestTransformer.transformServiceRequest()` in the receiver adaptor.
>
> **Related documents:**
> - [`spice-openmrs-referral-use-case.md`](./spice-openmrs-referral-use-case.md) — Architecture overview
> - [`referral-use-case-api-walkthrough.md`](./referral-use-case-api-walkthrough.md) — Step-by-step API walkthrough

---

## Table of Contents

1. [Full Annotated Example](#1-full-annotated-example)
2. [Field-by-Field Reference](#2-field-by-field-reference)
3. [What the Receiver Adaptor Uses](#3-what-the-receiver-adaptor-uses)
4. [Known Gaps and Fixes Required](#4-known-gaps-and-fixes-required)
5. [ReferenceResolver Behaviour per Field](#5-referenceresolver-behaviour-per-field)

---

## 1. Full Annotated Example

```jsonc
{
  // ═══════════════════════════════════════════════════════════
  // FHIR RESOURCE IDENTITY
  // ═══════════════════════════════════════════════════════════

  "resourceType": "ServiceRequest",
  // Real world: The referral slip — a formal clinical order requesting
  // that a specific patient receive care at a health facility.
  // Equivalent to a paper referral form written by a CHW/clinician.

  "id": "1838",
  // Auto-assigned by HAPI FHIR when stored. SPICE does not set this —
  // fhir-mapper uses PUT /fhir/ServiceRequest/{encounterId} so the
  // HAPI ID equals the SPICE encounter ID.

  // ═══════════════════════════════════════════════════════════
  // SPICE STATUS IDENTIFIERS  [REQUIRED by SPICE fhir-mapper]
  // Standard FHIR "code" field is NOT used by SPICE.
  // All SPICE-specific semantic data is encoded as identifiers.
  // ═══════════════════════════════════════════════════════════

  "identifier": [
    {
      "system": "http://host.docker.internal:8090/fhir/patient-status",
      "value": "REFERRED"
      // Real world: The patient's overall programme status in SPICE.
      // This drives the ServiceRequest.status value:
      //   "REFERRED"   → status = "active"   (open referral)
      //   "RECOVERED"  → status = "completed" (patient recovered, referral closed)
      //   anything else → status = "on-hold"
      // Possible values: ENROLLED | REFERRED | VISITED | RECOVERED
    },
    {
      "system": "http://host.docker.internal:8090/fhir/patient-current-status",
      "value": "REFERRED"
      // Real world: The most recent status — may differ from patient-status
      // during transitions (e.g. patient-status=REFERRED, current=VISITED
      // while status update is in flight).
    },
    {
      "system": "http://host.docker.internal:8090/fhir/category",
      "value": "NCD"
      // ★ CRITICAL — This is the ONLY source for the OpenMRS concept field.
      //
      // Real world: The disease programme this referral belongs to.
      // A patient with uncontrolled hypertension → "NCD" referral.
      // Possible values:
      //   NCD           = Non-Communicable Disease (hypertension, diabetes)
      //   ICCM          = Integrated Community Case Management (child illness)
      //   MENTAL_HEALTH = Mental health programme
      //   HIV           = HIV/AIDS programme
      //   MATERNAL_HEALTH = ANC / PNC / pregnancy
      //   TB            = Tuberculosis
      //
      // SPICE does NOT send a "code.coding" field. This identifier is the
      // only way to know what type of referral this is.
      // The receiver adaptor MUST read this and map it to an OpenMRS
      // concept UUID (current gap — transformer reads fhir.path("code")
      // which is always absent for SPICE resources).
    },
    {
      "system": "http://host.docker.internal:8090/fhir/encounter-type",
      "value": "NCD_MEDICAL_REVIEW"
      // Real world: The specific clinical encounter type being requested
      // at the referral facility.
      // "NCD_MEDICAL_REVIEW" = the patient needs a full NCD consultation
      // with a clinician (BP check, medication review, lab tests).
      // Informational — not currently mapped by the receiver adaptor.
    }
  ],

  // ═══════════════════════════════════════════════════════════
  // CORE FHIR ORDER FIELDS  [REQUIRED]
  // ═══════════════════════════════════════════════════════════

  "status": "active",
  // Real world: Whether the referral is open, completed, or cancelled.
  //   active    = referral open, patient not yet seen at facility
  //   on-hold   = referral paused (e.g. patient enrolled but not referred)
  //   completed = patient visited the facility / recovered
  //   revoked   = referral cancelled
  // Set automatically by fhir-mapper based on patient-status identifier.

  "intent": "order",
  // Always "order" for referrals.
  // Means this is a binding clinical order, not a proposal (plan/proposal).

  "priority": "urgent",
  // Real world: How urgently the patient needs to be seen.
  //   urgent → maps to urgency = STAT  in the OpenMRS TestOrder
  //   routine → maps to urgency = ROUTINE
  // SPICE always sets "urgent" for referred patients.

  // ═══════════════════════════════════════════════════════════
  // PATIENT  [REQUIRED for OpenMRS TestOrder]
  // ═══════════════════════════════════════════════════════════

  "subject": {
    "reference": "Patient/1198503150001234"
    // Real world: The patient being referred.
    // The person who the CHW identified as needing facility-level care.
    //
    // ★ The value after "Patient/" MUST be the patient's National ID (NID)
    // — NOT the HAPI FHIR integer resource ID.
    //
    // Why this matters:
    //   ReferenceResolver calls → GET /patient?identifier=1198503150001234
    //   OpenMRS returns the matching patient UUID
    //   That UUID becomes "patient" in the TestOrder
    //
    // If SPICE sends "Patient/616" (HAPI integer ID) instead:
    //   GET /patient?identifier=616 → no results → patient not found
    //   → TestOrder cannot be created
    //
    // SPICE stores NID in HAPI Patient resource as:
    //   identifier[system=.../national-id].value = "NID-1774256338"
    // That value should be what appears here.
  },

  // ═══════════════════════════════════════════════════════════
  // SOURCE ORGANIZATION  [Set by SPICE — NOT used as orderer]
  // ═══════════════════════════════════════════════════════════

  "requester": {
    "reference": "Organization/580"
    // Real world: The organization ORIGINATING this referral.
    // "Waterloo CHC is referring this patient" — the sending facility.
    //
    // ★ CURRENT MISMATCH with receiver adaptor:
    // The transformer reads "requester" and uses it as "orderer" in
    // the OpenMRS TestOrder. But "orderer" must be a Provider UUID,
    // not an Organization UUID.
    //
    // extractUuidFromRef("Organization/580") → "580" → not a UUID → null
    // → orderer is never set → OpenMRS rejects the order (400).
    //
    // Fix required: transformer must read performer[0] (Practitioner)
    // for orderer, not requester (Organization).
  },

  // ═══════════════════════════════════════════════════════════
  // PERFORMERS  [REQUIRED by SPICE fhir-mapper — always 2 or 3]
  // ═══════════════════════════════════════════════════════════

  "performer": [
    {
      "reference": "Practitioner/1198503150001234"
      // Real world: The CHW or clinician who is writing this referral.
      // "Community Health Worker Hana Bekele is referring this patient."
      // This is performer[0] and is ALWAYS present.
      //
      // When referredClinicianId is set in SPICE: uses that clinician's FHIR ID.
      // When not set: falls back to provenance.userId (the logged-in user's FHIR ID).
      //
      // ★ This is the correct field to use as "orderer" in OpenMRS.
      // ReferenceResolver calls → GET /provider?q=1198503150001234
      // Returns the matching Provider UUID → used as "orderer".
      //
      // Prerequisite: A Provider must exist in OpenMRS with
      // identifier = <same ID as used in this reference>.
    },
    {
      "reference": "RelatedPerson/614"
      // Real world: The patient's household membership record in SPICE.
      // RelatedPerson represents the community-enrolled household member
      // (different from the Patient FHIR resource which holds demographics).
      // SPICE uses this for linking the referral back to the household.
      // performer[1] — always the RelatedPerson (memberId).
      // NOT used by the receiver adaptor.
    },
    {
      "reference": "Organization/580"
      // Real world: The TARGET facility the patient is being referred TO.
      // "Please see this patient at Waterloo CHC."
      // performer[2] — only present when referred = true.
      //
      // Ideally this should map to an OpenMRS Location for the visit.
      // Currently the receiver adaptor does not resolve this to a Location.
      // Would require an OrganizationResolver component.
    }
  ],

  // ═══════════════════════════════════════════════════════════
  // SOURCE ENCOUNTER IN SPICE  [OPTIONAL]
  // ═══════════════════════════════════════════════════════════

  "encounter": {
    "reference": "Encounter/1825"
    // Real world: The clinical encounter in SPICE (e.g. a household visit
    // BP assessment) during which the CHW decided to refer the patient.
    // Links the referral back to the originating assessment.
    //
    // This is a HAPI FHIR encounter ID — it does NOT exist in OpenMRS.
    // ReferenceResolver calls → GET /encounter?q=1825 → not found
    // Result: VisitManager creates a brand-new OpenMRS visit + encounter.
    // This is expected and correct — the OpenMRS encounter is the
    // facility consultation, not the community assessment.
  },

  // ═══════════════════════════════════════════════════════════
  // REFERRAL TICKET TYPE  [OPTIONAL]
  // ═══════════════════════════════════════════════════════════

  "requisition": {
    "system": "http://host.docker.internal:8090/fhir/ticket-type",
    "value": "MEDICAL_REVIEW"
    // Real world: The specific appointment type at the facility.
    // "MEDICAL_REVIEW" = patient needs a full NCD clinician consultation.
    // Set to the referral type (e.g. MEDICAL_REVIEW, INVESTIGATION).
    // Not currently used by the receiver adaptor.
  },

  // ═══════════════════════════════════════════════════════════
  // SCHEDULED DATE  [OPTIONAL — only when follow-up date is known]
  // ═══════════════════════════════════════════════════════════

  "occurrenceDateTime": "2026-05-01T09:00:00+00:00",
  // Real world: The date the patient is expected at the referral facility.
  // Only set when SPICE has a nextVisitDate.
  // Not currently mapped by receiver adaptor.

  // ═══════════════════════════════════════════════════════════
  // REFERRAL REASON / NOTES  [OPTIONAL but clinically important]
  // ═══════════════════════════════════════════════════════════

  "patientInstruction": "Elevated blood pressure requiring specialist consultation",
  // Real world: Free-text reason for referral written by the CHW.
  // This is what the receiving clinician reads to understand why
  // the patient was sent to the facility.
  // Maps from ReferralDetailsDTO.referredReason in SPICE.
  //
  // Currently NOT mapped to any OpenMRS field by the transformer.
  // Could be stored as an obs using concept:
  //   164359AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA (Reason for referral — text)

  // ═══════════════════════════════════════════════════════════
  // AUTO-REFERRAL FLAG  [OPTIONAL — system-generated referrals only]
  // ═══════════════════════════════════════════════════════════

  "note": [
    {
      "text": "AUTO_REFERRAL_TICKET"
      // Real world: Indicates this referral was automatically triggered
      // by SPICE's risk algorithm — not manually created by the CHW.
      // Example: patient with BP > 180/110 mmHg → auto-referred to facility.
      // Only present when autoReferral = true in SPICE.
      // Not used by receiver adaptor.
    }
  ]
}
```

---

## 2. Field-by-Field Reference

### Required Fields

| Field | SPICE Source (Java) | Real-World Meaning | Example Value |
|-------|--------------------|--------------------|---------------|
| `resourceType` | Hardcoded | Document type | `"ServiceRequest"` |
| `status` | Derived from `patientStatus` | Is referral open/closed? | `"active"` |
| `intent` | Hardcoded `ORDER` | Binding clinical order | `"order"` |
| `priority` | Hardcoded `URGENT` | How fast patient needs care | `"urgent"` |
| `subject.reference` | `referralDetailsDTO.patientReference` | Which patient is being referred | `"Patient/NID-1774256338"` |
| `requester.reference` | `provenance.organizationId` | Which facility is sending the referral | `"Organization/580"` |
| `identifier[patient-status]` | `referralDetailsDTO.patientStatus` | Overall patient programme status | `"REFERRED"` |
| `identifier[patient-current-status]` | `referralDetailsDTO.currentPatientStatus` | Latest status | `"REFERRED"` |
| `identifier[category]` | `referralDetailsDTO.category` | Disease programme | `"NCD"` |
| `identifier[encounter-type]` | `referralDetailsDTO.encounterType` | Type of appointment needed | `"NCD_MEDICAL_REVIEW"` |
| `performer[0]` | `referredClinicianId` or `provenance.userId` | CHW/clinician making the referral | `"Practitioner/<fhir-id>"` |
| `performer[1]` | `referralDetailsDTO.memberId` | Household member record | `"RelatedPerson/614"` |
| `requisition` | Hardcoded `MEDICAL_REVIEW` when referred | Ticket/appointment type | `"MEDICAL_REVIEW"` |

### Optional Fields

| Field | SPICE Source (Java) | Real-World Meaning | Present When |
|-------|--------------------|--------------------|-------------|
| `encounter.reference` | `referralDetailsDTO.encounterId` | SPICE assessment that triggered referral | Always (if encounterId not null) |
| `performer[2]` | `referralDetailsDTO.referredSiteId` | Target facility for referral | `referred = true` only |
| `occurrenceDateTime` | `referralDetailsDTO.nextVisitDate` | Scheduled appointment date | Only when next visit date is set |
| `patientInstruction` | `referralDetailsDTO.referredReason` | Free-text referral reason | Always set by CHW |
| `note[0].text` | `autoReferral` flag | System-generated referral flag | `autoReferral = true` only |

---

## 3. What the Receiver Adaptor Uses

When the receiver adaptor transforms a `ServiceRequest` into an OpenMRS `TestOrder`, it reads only these fields:

```
ServiceRequest                         OpenMRS TestOrder
─────────────────────────────────────────────────────────────────
subject.reference     ──────────────→  patient    (UUID via identifier search)
requester.reference   ──────────────→  orderer    (UUID — BROKEN: reads Organization)
code.coding[0].code   ──────────────→  concept    (UUID — BROKEN: field absent in SPICE)
encounter.reference   ──────────────→  encounter  (UUID — always null, VisitManager creates)
priority              ──────────────→  urgency    (STAT / ROUTINE)
[hardcoded]           ──────────────→  type       = "testorder"
[hardcoded]           ──────────────→  action     = "NEW"
[hardcoded]           ──────────────→  careSetting = "OUTPATIENT"
```

Fields the adaptor **ignores** (not read by transformer):
- `identifier[category]` — contains the concept source but transformer reads `code` instead
- `identifier[encounter-type]` — not mapped
- `performer[]` — not read (orderer should come from here)
- `patientInstruction` — not stored as obs
- `requisition` — not mapped
- `performer[2]` (target Organization) — not mapped to Location

---

## 4. Known Gaps and Fixes Required

### Gap 1 — `concept` field is always null

**Why:** SPICE never sets `ServiceRequest.code.coding`. The referral category is in `identifier[system=.../category].value`.

**Transformer reads:**
```java
String conceptUuid = extractConceptUuid(fhir.path("code"));  // → null always
```

**Fix needed:** Fall back to reading `identifier[category]` when `code` is absent and map the value to an OpenMRS concept UUID via a configurable category-to-concept map:

| SPICE category | OpenMRS concept |
|----------------|----------------|
| `NCD` | `dc98ea48-66e9-4b2a-8533-3833f8200d06` (Internal facility transfer/referral) or custom "NCD Referral" concept |
| `ICCM` | To be defined |
| `MENTAL_HEALTH` | To be defined |
| `HIV` | To be defined |
| `MATERNAL_HEALTH` | To be defined |
| `TB` | To be defined |

---

### Gap 2 — `orderer` field is always null

**Why:** Transformer reads `requester` which SPICE sets to `Organization/<siteId>`. That is not a UUID → `extractUuidFromRef` returns null.

**Transformer reads:**
```java
String ordererUuid = extractUuidFromRef(
    fhir.path("requester").path("reference").asText(""));  // "Organization/580" → "580" → not UUID → null
```

**Fix needed:** Read `performer[0]` (always a `Practitioner`) instead:
```java
// performer[0] is always the Practitioner (CHW/referring clinician)
String ordererUuid = extractFirstPractitionerFromPerformer(fhir);
```

**Prerequisite:** A `Provider` must exist in OpenMRS with `identifier` matching the value in `Practitioner/<value>`.

---

### Gap 3 — Patient reference carries HAPI integer ID (not NID)

**Current SPICE behaviour:** `subject.reference = "Patient/616"` (HAPI integer resource ID).

**Why it fails:** ReferenceResolver calls `GET /patient?identifier=616` — no OpenMRS patient has identifier `"616"`.

**Fix needed (SPICE side):** fhir-mapper should set `patientReference` to the NID value from `bioData.nationalId` (e.g. `Patient/NID-1774256338`) instead of the HAPI integer ID.

**Alternative (adaptor side):** When patient resolution fails by integer ID, fetch the HAPI resource `GET /fhir/Patient/{id}`, extract `identifier[system=.../national-id].value`, then retry OpenMRS search with the NID. Requires a HAPI FHIR client in the adaptor.

---

## 5. ReferenceResolver Behaviour per Field

The `ReferenceResolver` walks every `"reference"` field in the JSON. For each:
1. If value after `/` **is a UUID** → skip (already resolved)
2. If value is **not a UUID** → search OpenMRS REST

| Reference in ServiceRequest | Not UUID? | OpenMRS REST call | Expected result |
|-----------------------------|-----------|-------------------|-----------------|
| `Patient/NID-1774256338` | ✅ non-UUID | `GET /patient?identifier=NID-1774256338` | ✅ Returns patient UUID |
| `Patient/616` | ✅ non-UUID | `GET /patient?identifier=616` | ❌ No match — wrong identifier |
| `Organization/580` | ✅ non-UUID | `GET /location?q=580` | ❌ No location named "580" |
| `Practitioner/1198503150001234` | ✅ non-UUID | `GET /provider?q=1198503150001234` | ✅ If provider with that identifier exists |
| `Practitioner/some-uuid` | UUID → skipped | (no call) | Passed through as-is |
| `RelatedPerson/614` | ✅ non-UUID | `GET /person?q=614` | ❌ Person search is by name, not number |
| `Encounter/1825` | ✅ non-UUID | `GET /encounter?q=1825` | ❌ SPICE encounter not in OpenMRS |

**Important:** Failed resolutions are **silently skipped** — the original reference string is left unchanged. The transformer then calls `extractUuidFromRef()` on it — if the part after `/` is not a UUID, the field is omitted from the OpenMRS payload entirely.
