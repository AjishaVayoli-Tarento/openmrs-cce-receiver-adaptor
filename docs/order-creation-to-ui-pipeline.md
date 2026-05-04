# Complete Step-by-Step: Order Creation → Field Display Pipeline

This document explains how an order created by the CCE Receiver Adaptor flows from the SPICE FHIR `ServiceRequest` payload all the way to the column labels visible in the OpenMRS 3 (O3) patient chart Orders widget.

It is structured around three layers, with concrete examples for both **Test Order** (built-in OpenMRS subclass) and **Referral** (the auto-created OrderType used for SPICE referrals).

---

## Table of Contents

1. [Part 1 — When You Create a New OrderType, How Does It Map to UI Fields?](#part-1--when-you-create-a-new-ordertype-how-does-it-map-to-ui-fields)
2. [Part 2 — Order Base Model & Base Attributes](#part-2--order-base-model--base-attributes)
3. [Part 3 — How the Adaptor Maps Each Field](#part-3--how-the-adaptor-maps-each-field)
4. [Summary — The Three Layers Working Together](#summary--the-three-layers-working-together)
5. [Important Caveat About the O3 Display Layer](#important-caveat-about-the-o3-display-layer)

---

## Part 1 — When You Create a New OrderType, How Does It Map to UI Fields?

**Short answer: it doesn't map to UI fields at all.** The OrderType row creation has zero direct impact on what columns show in the UI. The UI mapping happens elsewhere (in the O3 frontend code). Here's the precise chain.

### Step 1.1 — POST to `/ordertype` Creates a Database Row

When the adaptor sends:
```bash
POST /ws/rest/v1/ordertype
{
  "name": "Referral",
  "description": "Order for patient referrals to other services or facilities",
  "javaClassName": "org.openmrs.Order",
  "conceptClasses": []
}
```

OpenMRS inserts **one row** into the `order_type` table:

| Column | Value |
|---|---|
| `order_type_id` | (auto, e.g., 5) |
| `uuid` | (auto-generated) |
| `name` | "Referral" |
| `description` | "Order for patient referrals..." |
| `java_class_name` | "org.openmrs.Order" |
| `parent` | NULL |
| `retired` | 0 |

And zero rows in `order_type_class_map` (because we sent `conceptClasses: []`).

**That's it. Nothing else happens. No UI metadata is created. No display schema is registered.**

### Step 1.2 — Why This Doesn't Affect the UI

The OpenMRS database knows:
- "Referral" is a name
- It uses the `org.openmrs.Order` Java class
- It accepts any concept class (because the conceptClasses list is empty)

The OpenMRS database does **NOT** know:
- Which columns to show when displaying a Referral order
- What labels to use for those columns
- How to format the values
- Which tab to put it under

**All that lives in the frontend (O3), which has no knowledge of your new OrderType row until a clinician loads a patient with a Referral order on it.**

### Step 1.3 — What Happens When O3 Loads That Patient

1. O3 calls `GET /ws/rest/v1/order?patient=...&v=full`
2. Backend returns all orders. For each, the JSON includes `orderType: { display: "Referral", uuid: "..." }`
3. The O3 Orders widget receives the array and groups them by `orderType.display`
4. **For each group, the widget asks itself: "Do I have a custom rendering schema for this OrderType name?"**

```javascript
// Conceptual lookup inside the O3 Orders widget
const schema = ORDER_TYPE_SCHEMAS[order.orderType.display]
            || ORDER_TYPE_SCHEMAS["_default_"];
```

5. The widget renders the row using whichever schema matches.

### Step 1.4 — Where the Schema Lookup Resolves For Each Type

| OrderType | What schema gets picked | Why |
|---|---|---|
| `"Test Order"` | Test Order schema in O3 | Built-in: O3 ships with explicit columns for tests |
| `"Drug Order"` | Drug Order schema in O3 | Built-in: O3 ships with explicit columns for drugs |
| `"Referral"` | Referral-specific labels (verified by inspection — see Caveat at end) | O3 has special handling for an OrderType named exactly "Referral" |
| `"Custom Vaccination Order"` (hypothetical) | The generic `_default_` schema | O3 has no knowledge of it; falls back to generic |

**This is critical:** the **OrderType name** is the joining key between backend and frontend. If the OrderType had been named "External Referral" instead of "Referral", O3 might NOT use the Referral labels — it would likely fall through to the generic schema and the column would say "Accession #" instead of "Referral reference number".

### Step 1.5 — The Full Picture for the Auto-Created OrderType

```
Adaptor                 OpenMRS DB              O3 Frontend
──────────────         ──────────────          ──────────────
POST /ordertype  ─►   INSERT INTO              (unaware until
{ "name":            order_type                a clinician loads
   "Referral",       VALUES (..,             a patient)
  "javaClass":       'Referral', ..)
   "org.openmrs.
    Order" }                                    GET /order?...
                                                 ▼
                                                Sees orderType.display="Referral"
                                                 ▼
                                                Looks up "Referral" in
                                                its schema map
                                                 ▼
                                                Renders 3-column expansion:
                                                "Referral", "Instructions",
                                                "Referral reference number"
```

**Key insight:** Creating the OrderType is purely a **data-classification** act. The visual experience is wholly determined by the frontend, with the OrderType **name** as the lookup key.

---

## Part 2 — Order Base Model & Base Attributes

### Step 2.1 — The Java Class Hierarchy in OpenMRS

OpenMRS has exactly three Order classes shipped in core:

```
org.openmrs.Order              (base — generic orders, including Referrals)
   ├── org.openmrs.TestOrder   (lab tests, procedures)
   └── org.openmrs.DrugOrder   (medications)
```

There's no `ReferralOrder` class (still open as TRUNK-6029). So the Referral OrderType uses the bare `org.openmrs.Order`.

### Step 2.2 — Fields on the Base `Order` Class

These are stored in the `orders` table and exist for **every** Order regardless of OrderType:

| Field (Java) | DB Column | Type | What it means |
|---|---|---|---|
| `orderId` | `order_id` | int (PK) | Internal ID |
| `uuid` | `uuid` | varchar(38) | Public UUID |
| `orderNumber` | `order_number` | varchar(50) | Human-readable (ORD-342) |
| `orderType` | `order_type_id` | FK → `order_type` | Test/Drug/Referral/etc. |
| `concept` | `concept_id` | FK → `concept` | The clinical thing |
| `patient` | `patient_id` | FK → `patient` | Who |
| `encounter` | `encounter_id` | FK → `encounter` | When/where |
| `orderer` | `orderer` | FK → `provider` | Who placed it |
| `instructions` | `instructions` | TEXT | Free-text instructions |
| `accessionNumber` | `accession_number` | varchar(255) | External reference |
| `commentToFulfiller` | `comment_to_fulfiller` | varchar(1024) | Note to receiver |
| `urgency` | `urgency` | varchar(50) | ROUTINE/STAT/ON_SCHEDULED_DATE |
| `dateActivated` | `date_activated` | datetime | When active |
| `autoExpireDate` | `auto_expire_date` | datetime | When auto-stops |
| `dateStopped` | `date_stopped` | datetime | When manually stopped |
| `careSetting` | `care_setting` | FK → `care_setting` | Outpatient/Inpatient |
| `previousOrder` | `previous_order_id` | FK self | For revisions |
| `action` | `order_action` | varchar(50) | NEW/REVISE/DISCONTINUE/RENEW |
| `orderReason` | `order_reason` | FK → `concept` | Coded reason |
| `orderReasonNonCoded` | `order_reason_non_coded` | varchar(255) | Free-text reason |
| `fulfillerStatus` | `fulfiller_status` | varchar(50) | RECEIVED/IN_PROGRESS/COMPLETED/EXCEPTION |
| `fulfillerComment` | `fulfiller_comment` | varchar(1024) | Note from receiver |
| `creator` | `creator` | FK → `users` | Audit |
| `dateCreated` | `date_created` | datetime | Audit |
| ...audit fields | ... | ... | retired/voided/etc. |

### Step 2.3 — Subclass-Specific Fields

`TestOrder` adds (in `test_order` table, joined to `orders` by `order_id`):
- `specimenSource`
- `laterality`
- `clinicalHistory`
- `frequency`
- `numberOfRepeats`

`DrugOrder` adds (in `drug_order` table):
- `drug` (FK)
- `dose`, `doseUnits`
- `frequency`
- `asNeeded`, `asNeededCondition`
- `quantity`, `quantityUnits`
- `numRefills`
- `dosingType` (SIMPLE/FREE_TEXT)
- `dosingInstructions`
- `route`
- `brandName`
- `dispenseAsWritten`
- `duration`, `durationUnits`

**A Referral order has NONE of these subclass fields** — only the base `Order` columns. That's why O3 can only display `concept`, `instructions`, and `accessionNumber` for it; there's literally no other column to show.

### Step 2.4 — Order Attributes (Optional Side Table)

For data not on the base/subclass models, OpenMRS provides a generic side table:

```
order_attribute_type      order_attribute
────────────────────      ────────────────
attribute_type_id (PK)    attribute_id (PK)
name                      order_id (FK)
description               attribute_type_id (FK)
datatype                  value_reference (string)
preferred_handler         ...
...
```

This is how you'd add custom fields like "Destination Facility" or "Referral Reason" to a Referral order without creating a new Java subclass.

### Step 2.5 — Fields Mapped from Base Model to UI for Each OrderType

Concrete view of which base/subclass fields the UI surfaces for each OrderType:

#### For **Test Order** (built-in)

| UI column label | Backend source | Path |
|---|---|---|
| "Test Order" | `Order.concept.display` | Base `orders.concept_id` → `concept.fully_specified_name` |
| "Result" | Linked **Obs** record | NOT on Order — `obs` table has `obs.order_id` FK; lab posts result here. UI joins. |
| "Reference range" | **Concept metadata** | NOT on Order — comes from `concept_numeric.low_absolute` / `hi_absolute` for the test concept |

#### For **Drug Order** (built-in)

| UI column label | Backend source | Path |
|---|---|---|
| "Drug" | `DrugOrder.drug.display` | `drug_order.drug_inventory_id` → `drug.name` |
| "Dose" | `DrugOrder.dose` + `doseUnits.display` | `drug_order.dose` + `dose_units` concept lookup |
| "Frequency" | `DrugOrder.frequency.display` | `drug_order.frequency` → `order_frequency.name` |
| "Duration" | `DrugOrder.duration` + `durationUnits.display` | `drug_order.duration` + `duration_units` lookup |

#### For **Referral** (auto-created custom OrderType)

| UI column label | Backend source | Path |
|---|---|---|
| "Referral" | `Order.concept.display` | Base `orders.concept_id` → `concept.fully_specified_name` |
| "Instructions" | `Order.instructions` | Base `orders.instructions` |
| "Referral reference number" | `Order.accessionNumber` | Base `orders.accession_number` |

Notice: **Referral uses 100% base-class fields**. Test Order pulls from the base + Concept metadata + linked Obs. Drug Order pulls from the base + the `drug_order` subclass table.

---

## Part 3 — How the Adaptor Maps Each Field

This is the SPICE → OpenMRS transformation chain, field by field.

### Step 3.1 — Adaptor's Decision Pipeline (Per ServiceRequest)

```
SPICE FHIR ServiceRequest
         │
         ▼
┌────────────────────────────────────────┐
│ FhirToRestTransformer                   │
│ .transformServiceRequest(fhirResource) │
└────────────────────────────────────────┘
         │
         ▼ Step A: Decide OrderType
┌────────────────────────────────────────┐
│ determineOrderType(fhir)                │
│  • Check requisition.value=="medical-  │
│    Review"  → "order" (Referral)       │
│  • Check identifier patient-status=    │
│    "Referred" → "order"                │
│  • Check category[] for "referral"     │
│  • Check code text/SNOMED 3457005      │
│  • Otherwise → "testorder"             │
└────────────────────────────────────────┘
         │
         ▼ Step B: If "order", get Referral OrderType UUID
┌────────────────────────────────────────┐
│ discoverReferralOrderTypeUuid()         │
│  GET /ordertype?v=default              │
│  Find name="Referral" → cache UUID     │
│  If missing → POST /ordertype to create│
└────────────────────────────────────────┘
         │
         ▼ Step C: Map each field
┌────────────────────────────────────────┐
│ Build REST JSON payload                 │
└────────────────────────────────────────┘
         │
         ▼
  POST /openmrs/ws/rest/v1/order
```

### Step 3.2 — Field-by-Field Mapping for ORD-342

| OpenMRS REST field | Adaptor's source line | FHIR source | Type of mapping |
|---|---|---|---|
| `type` | `rest.put("type", determineOrderType(fhir))` | Derived from 4 SPICE signals | **SPICE-specific inference** |
| `orderType` | `rest.put("orderType", discoverReferralOrderTypeUuid())` | NONE — synthesized | **OpenMRS-required** (only when type="order") |
| `patient` | `rest.put("patient", resolvePatientUuid(...))` | `subject.reference` | **Standard FHIR** |
| `encounter` | `rest.put("encounter", resolveEncounterUuid(...))` | `encounter.reference` (or auto-created) | **Standard FHIR** + auto-creation fallback |
| `concept` | `rest.put("concept", conceptResolver.resolveConceptUuid(code))` | `code.coding[0].code` | **Standard FHIR** |
| `orderer` | `rest.put("orderer", resolveOrderer(fhir))` | `requester.reference` (with Org fallback) | **Standard FHIR** + SPICE workaround |
| `urgency` | `rest.put("urgency", switch(priority){...})` | `priority` | **Standard FHIR** |
| `dateActivated` | `rest.put("dateActivated", authoredOn)` | `authoredOn` | **Standard FHIR** |
| `careSetting` | `rest.put("careSetting", "OUTPATIENT")` | NONE — hardcoded | **OpenMRS-required default** |
| `instructions` | `rest.put("instructions", patientInstruction \|\| note[0].text)` | `patientInstruction` | **Standard FHIR** |
| `accessionNumber` | `rest.put("accessionNumber", extractAccessionNumber(fhir))` | `id` (or `identifier[0].value`) | **Pragmatic adaptor choice** |

### Step 3.3 — Tracing One Field End-to-End

Picking **"Referral reference number = 2332"** and tracing it through every step:

```
Step 1 (SPICE source):
  ServiceRequest payload arrives:
  { "id": "2332", "resourceType": "ServiceRequest", ... }

Step 2 (Adaptor transformer):
  Java code in FhirToRestTransformer.transformServiceRequest():
    String accessionNumber = extractAccessionNumber(fhir);
    if (accessionNumber != null && !accessionNumber.isBlank()) {
        rest.put("accessionNumber", accessionNumber);
    }

  extractAccessionNumber() returns "2332" because:
    String id = fhir.path("id").asText("");        // "2332"
    if (!id.isBlank() && !isUuid(id)) return id;   // "2332" is not a UUID, so return it

Step 3 (REST POST):
  POST http://localhost:9096/openmrs/ws/rest/v1/order
  Body: { ..., "accessionNumber": "2332", ... }

Step 4 (OpenMRS service layer):
  OrderResource1_8.create() in webservices.rest module:
    - Validates payload
    - Instantiates org.openmrs.Order (because type="order")
    - Calls order.setAccessionNumber("2332")
    - Calls OrderService.saveOrder(order)
    - Hibernate INSERT INTO orders (..., accession_number)
                              VALUES (..., '2332')

Step 5 (Database):
  orders row written:
    order_id = 342, order_number = 'ORD-342', accession_number = '2332', ...

Step 6 (O3 frontend GET):
  GET /ws/rest/v1/order?patient=...&v=full
  Returns: { ..., "orderNumber": "ORD-342", "accessionNumber": "2332",
             "orderType": { "display": "Referral" }, ... }

Step 7 (O3 Orders widget):
  Receives the order. Looks at orderType.display = "Referral"
  Selects the Referral expansion schema:
    columns: [
      { label: "Referral",                  field: "concept.display" },
      { label: "Instructions",              field: "instructions" },
      { label: "Referral reference number", field: "accessionNumber" }  ← Match
    ]
  Renders the cell with label "Referral reference number" and value "2332"
```

### Step 3.4 — Why the Adaptor's Mapping Looks the Way It Does

For each field decision, the rationale:

| Field | Why this mapping? |
|---|---|
| `type="order"` for referrals | Generic Order is the only path until OpenMRS Core ships ReferralOrder (TRUNK-6029). Test/Drug have hardcoded subclasses; Referral doesn't. |
| `orderType=<Referral UUID>` | When `type="order"`, OpenMRS can't infer which OrderType you mean (could be any custom one). Required to disambiguate. |
| `concept` from `code.coding[].code` | Direct standard FHIR ↔ OpenMRS mapping; what every FHIR adapter does. |
| `instructions` from `patientInstruction` | `ServiceRequest.patientInstruction` is the FHIR field semantically equivalent to `Order.instructions`. |
| `accessionNumber` from `ServiceRequest.id` | OpenMRS's `accessionNumber` is a free-text cross-system reference field. Putting the SPICE source ID here gives end-users a traceable link back. Not a formal FHIR mapping; pragmatic choice. |
| `careSetting="OUTPATIENT"` hardcoded | OpenMRS requires it on every Order. SPICE doesn't send it. Outpatient is the safe default for a primary-care setting. |
| `urgency` from `priority` | Standard FHIR-to-OpenMRS enum mapping with synonym handling (urgent/asap → STAT). |

---

## Summary — The Three Layers Working Together

```
┌──────────────────────────────────────────────────────────────────┐
│ LAYER 1: ADAPTOR (this repository)                                │
│  Reads FHIR → Picks OpenMRS OrderType → Maps fields → POSTs JSON │
│  Decision: WHICH order type? WHICH base fields to populate?      │
└──────────────────────────────────────────────────────────────────┘
                          ↓ POST /order
┌──────────────────────────────────────────────────────────────────┐
│ LAYER 2: OPENMRS BACKEND                                          │
│  Generic data model: ONE orders table for ALL OrderTypes          │
│  OrderType row only stores: name, javaClass, allowed concept      │
│  classes. NO display metadata.                                    │
│  Each Order row has same base columns regardless of OrderType.    │
└──────────────────────────────────────────────────────────────────┘
                          ↓ GET /order?patient=...
┌──────────────────────────────────────────────────────────────────┐
│ LAYER 3: O3 FRONTEND                                              │
│  Receives generic JSON. Looks up OrderType.display in its         │
│  schema map. Renders columns/labels accordingly.                  │
│  THIS is where "Referral reference number" vs "Specimen ID" vs    │
│  "Drug" differentiation happens — purely a presentation decision. │
└──────────────────────────────────────────────────────────────────┘
```

**Three crucial takeaways:**

1. **Creating an OrderType doesn't define its UI.** It only declares: a name, a Java class, and what concept classes it accepts. The visual columns are picked by O3's frontend based on the OrderType's **name** as a lookup key.

2. **The base Order data model is identical across OrderTypes.** A Referral and a Test Order share the exact same `orders` table columns. Test Order has *additional* columns in `test_order` (subclass table); Drug Order has them in `drug_order`. Referral has none — only base.

3. **The adaptor populates base fields directly mappable from FHIR.** It doesn't need to know about UI rendering. It just makes sure `concept`, `instructions`, `accessionNumber` are populated — and trusts O3 to display them correctly under the right column headers because the OrderType name "Referral" matches O3's known schema.

If you wanted to add a **truly new field to a referral** (like "Destination Facility"), you'd need:
- (Step A) Create an OrderAttributeType in OpenMRS
- (Step B) Modify the adaptor to extract `performer[0]` and post it as an attribute
- (Step C) Configure O3 to display that attribute as a column for Referral OrderType

The adaptor already handles Steps A and B's pattern for things like `accessionNumber`; the OrderAttribute path would be a small extension. Step C is an O3-config concern outside the adaptor.

---

## Important Caveat About the O3 Display Layer

The exact mechanism by which O3 produces the labels "Referral", "Instructions", and "Referral reference number" for a generic Order with `orderType.display = "Referral"` was not verified against the O3 source while writing this document. It is one of the following (or a combination):

1. **Hardcoded schema** in `@openmrs/esm-patient-orders-app` switching on `orderType.display === "Referral"`
2. **i18n-keyed labels** where the key includes the OrderType name (e.g., `Referral_accessionNumber`)
3. **Distro-config override** in this O3 instance that maps the Referral OrderType UUID to custom column labels
4. **A dedicated referrals microfrontend** that filters Order results and renders its own table

To confirm which mechanism is in play, search the O3 frontend source or running bundle for the string "Referral reference number":

```bash
# Against the O3 source
git clone https://github.com/openmrs/openmrs-esm-patient-chart.git
grep -r "Referral reference" openmrs-esm-patient-chart/

# Or against the running bundle
docker exec -it o3-frontend-1 sh -c \
  'grep -r "Referral reference" /usr/share/nginx/html/openmrs/spa/ 2>/dev/null | head'
```

The file the match lives in (`.json` translation, `.js` bundle, or `config.json`) reveals which mechanism is producing the label. Regardless of which it is, the adaptor's responsibilities described in this document remain unchanged — the adaptor populates base fields; O3 decides how to render them.
