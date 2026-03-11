# CAP Java: OData Request/Response Cycle

## Overview

This application demonstrates SAP Cloud Application Programming Model (CAP) for Java. CAP provides an opinionated framework that handles OData protocol, database persistence, and business logic orchestration through a well-defined event-driven architecture.

---

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              CAP Java Runtime                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────────┐ │
│   │   OData V4   │    │   Service    │    │      Persistence Layer       │ │
│   │   Adapter    │───▶│    Layer     │───▶│    (PersistenceService)      │ │
│   │              │    │              │    │                              │ │
│   │  - Parse     │    │ - Processor  │    │  - CQN to SQL translation   │ │
│   │  - Serialize │    │   Service    │    │  - Transaction management   │ │
│   │  - $metadata │    │ - Admin      │    │  - Database execution       │ │
│   │              │    │   Service    │    │                              │ │
│   └──────────────┘    └──────────────┘    └──────────────────────────────┘ │
│          │                   │                          │                   │
│          │                   ▼                          │                   │
│          │           ┌──────────────┐                   │                   │
│          │           │    Event     │                   │                   │
│          │           │   Handlers   │                   │                   │
│          │           │              │                   │                   │
│          │           │  @Before     │                   │                   │
│          │           │  @On         │                   │                   │
│          │           │  @After      │                   │                   │
│          │           └──────────────┘                   │                   │
│          │                                              │                   │
└──────────┼──────────────────────────────────────────────┼───────────────────┘
           │                                              │
           ▼                                              ▼
    ┌─────────────┐                              ┌─────────────────┐
    │   HTTP      │                              │    Database     │
    │   Client    │                              │   (H2/HANA)     │
    └─────────────┘                              └─────────────────┘
```

### Runtime Components (Maven Dependencies)

| Component | Maven Artifact | Purpose |
|-----------|----------------|---------|
| OData V4 Adapter | `cds-adapter-odata-v4` | HTTP/OData protocol handling |
| Spring Boot Integration | `cds-starter-spring-boot` | Auto-configuration, DI |
| Core Runtime | `cds4j-runtime` | Event handling, persistence |

---

## 2. The CDS Model Layer

CAP uses **CDS (Core Data Services)** to define both the domain model and service exposure:

### Domain Model

**File:** [`db/schema.cds`](db/schema.cds)

```
┌─────────────────────────────────────────────────────────────────┐
│                         Domain Model                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   ┌─────────────┐         ┌─────────────┐                       │
│   │  Incidents  │────────▶│  Customers  │                       │
│   │             │   N:1   │             │                       │
│   │  - title    │         │  - name     │                       │
│   │  - urgency ─┼────┐    │  - email    │                       │
│   │  - status ──┼──┐ │    │  - phone    │                       │
│   │             │  │ │    │             │                       │
│   └──────┬──────┘  │ │    └──────┬──────┘                       │
│          │         │ │           │                              │
│          │ 1:N     │ │           │ 1:N                          │
│          ▼         │ │           ▼                              │
│   ┌─────────────┐  │ │    ┌─────────────┐                       │
│   │Conversation │  │ │    │  Addresses  │                       │
│   │  - author   │  │ │    │  - city     │                       │
│   │  - message  │  │ │    │  - postCode │                       │
│   └─────────────┘  │ │    └─────────────┘                       │
│                    │ │                                          │
│          ┌─────────┘ └─────────┐                                │
│          ▼                     ▼                                │
│   ┌─────────────┐       ┌─────────────┐                         │
│   │   Status    │       │   Urgency   │   (CodeList entities)   │
│   │  N,A,I,H,   │       │   H, M, L   │                         │
│   │  R,C        │       │             │                         │
│   └─────────────┘       └─────────────┘                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Code Reference:** [`db/schema.cds:7-18`](db/schema.cds) - Incidents entity definition

```cds
entity Incidents : cuid, managed {
  customer     : Association to Customers;
  title        : String  @title : 'Title';
  urgency      : Association to Urgency default 'M';
  status       : Association to Status default 'N';
  conversation : Composition of many { ... };
}
```

### Service Projections

**File:** [`srv/services.cds`](srv/services.cds)

```
┌────────────────────────────────────┐    ┌────────────────────────────────────┐
│       ProcessorService             │    │         AdminService               │
│       @requires: 'support'         │    │       @requires: 'admin'           │
├────────────────────────────────────┤    ├────────────────────────────────────┤
│                                    │    │                                    │
│   Incidents                        │    │   Incidents                        │
│   ├── @odata.draft.enabled         │    │   └── full CRUD                    │
│   └── full CRUD                    │    │                                    │
│                                    │    │   Customers                        │
│   Customers                        │    │   └── full CRUD                    │
│   └── @readonly                    │    │                                    │
│                                    │    │                                    │
└────────────────────────────────────┘    └────────────────────────────────────┘
         │                                          │
         │  OData Endpoint                          │  OData Endpoint
         ▼                                          ▼
  /odata/v4/ProcessorService                /odata/v4/AdminService
```

**Code Reference:** [`srv/services.cds:6-9`](srv/services.cds) - ProcessorService definition

```cds
service ProcessorService {
  entity Incidents as projection on my.Incidents;
  entity Customers @readonly as projection on my.Customers;
}
```

**Code Reference:** [`srv/services.cds:19-21`](srv/services.cds) - Annotations

```cds
annotate ProcessorService.Incidents with @odata.draft.enabled;
annotate ProcessorService with @(requires: 'support');
annotate AdminService with @(requires: 'admin');
```

---

## 3. Request/Response Flow: CREATE Example

Let's trace a `POST /odata/v4/ProcessorService/Incidents` request through the actual code:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                           CREATE Incident Flow                                   │
└──────────────────────────────────────────────────────────────────────────────────┘

  Client                                                                    Database
    │                                                                          │
    │  POST /odata/v4/ProcessorService/Incidents                              │
    │  { "title": "Urgent: Server down", "customer_ID": "1001" }              │
    │                                                                          │
    ▼                                                                          │
```

### Step 1: HTTP Entry Point (OData Servlet)

**Class:** `com.sap.cds.adapter.odata.v4.AbstractCdsODataServlet`
**Artifact:** `cds-adapter-odata-v4`

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  1. OData V4 Adapter Entry Point                                                │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  AbstractCdsODataServlet.service(HttpServletRequest, HttpServletResponse)││
│     │                                                                         │ │
│     │  • Establishes request context with runtime.requestContext()            │ │
│     │  • Extracts service name from URL: "ProcessorService"                   │ │
│     │  • Validates service definition exists                                  │ │
│     │  • Initializes Olingo OData infrastructure (EDM, metadata)              │ │
│     │  • Registers OlingoProcessor as the request processor                   │ │
│     │  • Calls odataHandler.process(req, resp)                                │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
```

### Step 2: Olingo Processing Bridge

**Class:** `com.sap.cds.adapter.odata.v4.processors.OlingoProcessor`
**Implements:** `EntityProcessor`, `ActionEntityProcessor`, `BatchProcessor`, etc.

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  2. Olingo Processor Bridge                                                     │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  OlingoProcessor implements all Olingo processor interfaces             │ │
│     │                                                                         │ │
│     │  • Receives parsed OData request from Olingo                            │ │
│     │  • Delegates to CdsProcessor for CAP-specific handling                  │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
```

### Step 3: CQN Conversion & Service Dispatch

**Class:** `com.sap.cds.adapter.odata.v4.processors.CdsProcessor`
**Key Methods:**
- `processRequest()` - Main entry point
- `post()` - Handles POST/CREATE requests
- `delegateRequest()` - Routes by HTTP method

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  3. CQN Conversion (CdsProcessor)                                               │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  CdsProcessor.post(CdsODataRequest request)                             │ │
│     │                                                                         │ │
│     │  // Get the target service                                              │ │
│     │  ApplicationService applicationService = globals.getApplicationService();││
│     │                                                                         │ │
│     │  // Convert OData payload to CQN Insert statement                       │ │
│     │  Insert insert = Insert.into(ref).entry(entityData);                    │ │
│     │                                                                         │ │
│     │  // Dispatch to service layer (triggers event handlers)                 │ │
│     │  result = applicationService.run(insert);  // ◄── SERVICE DISPATCH      │ │
│     │                                                                         │ │
│     │  return new CdsODataResponse(SC_CREATED, remapResult(...));             │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │  applicationService.run(insert)
                                    ▼
```

### Step 4: Event Handler Chain

**Interface:** `com.sap.cds.services.cds.ApplicationService`
**Interface:** `com.sap.cds.services.handler.EventHandler`

The `applicationService.run()` call triggers the event handler chain:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  4. Service Layer - Event Dispatch                                              │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  ApplicationService.run(CqnInsert)                                      │ │
│     │                                                                         │ │
│     │  • Authorization check: user has 'support' role? (from @requires)       │ │
│     │  • Emit event: CqnService.EVENT_CREATE ("CREATE")                       │ │
│     │  • Find registered handlers via @ServiceName annotation                 │ │
│     │  • Execute handler chain: @Before → @On → @After                        │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
```

### Step 5: @Before Handler Execution

**File:** [`srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java`](srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  5. @Before Handler Phase                                                       │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  ProcessorServiceHandler.java:35-45                                     │ │
│     │  ─────────────────────────────────────────────────────────────────────  │ │
│     │                                                                         │ │
│     │  @Before(event = CqnService.EVENT_CREATE)                               │ │
│     │  public void ensureHighUrgencyForIncidentsWithUrgentInTitle(            │ │
│     │          List<Incidents> incidents) {                                   │ │
│     │      for (Incidents incident : incidents) {                             │ │
│     │          if (incident.getTitle()                                        │ │
│     │                  .toLowerCase(Locale.ENGLISH)                           │ │
│     │                  .contains("urgent") &&                                 │ │
│     │              incident.getUrgencyCode() == null ||                       │ │
│     │              !incident.getUrgencyCode().equals("H")) {                  │ │
│     │                                                                         │ │
│     │              incident.setUrgencyCode("H");  // ◄── MUTATE INPUT         │ │
│     │              logger.info("Adjusted Urgency...");                        │ │
│     │          }                                                              │ │
│     │      }                                                                  │ │
│     │  }                                                                      │ │
│     │                                                                         │ │
│     │  Input:  { title: "Urgent: Server down", urgency_code: null }          │  │
│     │  Output: { title: "Urgent: Server down", urgency_code: "H" }           │  │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
```

### Step 6: @On Handler (Default Persistence)

**Interface:** `com.sap.cds.services.persistence.PersistenceService`

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  6. @On Handler Phase (Default: GenericHandler → PersistenceService)            │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  No custom @On handler in this app                                      │ │
│     │                                                                         │ │
│     │  CAP's built-in GenericHandler delegates to:                            │ │
│     │  PersistenceService.run(CqnInsert)                                      │ │
│     │                                                                         │ │
│     │  The PersistenceService:                                                │ │
│     │  • Translates CQN to SQL (database-specific)                            │ │
│     │  • Manages transaction boundaries                                       │ │
│     │  • Executes the SQL statement                                           │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
```

### Step 7: SQL Generation & Execution

**Package:** `com.sap.cds.impl.sql.*` (in `cds4j-runtime`)
**Generated Schema:** [`srv/src/main/resources/schema-h2.sql`](srv/src/main/resources/schema-h2.sql) (generated at build time)

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  7. Persistence Layer - SQL Execution                                           │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  CQN to SQL Translation (com.sap.cds.impl.sql.*)                        │ │
│     │                                                                         │ │
│     │  INSERT INTO sap_capire_incidents_Incidents                             │ │
│     │    (ID, title, urgency_code, status_code, customer_ID,                  │ │
│     │     createdAt, createdBy, modifiedAt, modifiedBy)                       │ │
│     │  VALUES                                                                 │ │
│     │    (UUID, 'Urgent: Server down', 'H', 'N', '1001',                      │ │
│     │     NOW(), 'alice', NOW(), 'alice')                                     │ │
│     │                                                                         │ │
│     │  Target table defined in: schema-h2.sql (generated from schema.cds)     │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                              ┌───────────┐
                              │    H2     │
                              │ Database  │
                              └─────┬─────┘
                                    │
                                    │ (Result: 1 row inserted)
                                    ▼
```

### Step 8: @After Handler & Response

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│  8. @After Handler Phase                                                        │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  No custom @After handler in this app                                   │ │
│     │  Could be used for: audit logging, notifications, enrichment            │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│  9. Response Serialization                                                      │
│     ┌─────────────────────────────────────────────────────────────────────────┐ │
│     │                                                                         │ │
│     │  CdsProcessor.post() returns:                                           │ │
│     │  new CdsODataResponse(SC_CREATED, remapResult(result, entity, null))    │ │
│     │                                                                         │ │
│     │  OlingoProcessor serializes to OData JSON format                        │ │
│     │                                                                         │ │
│     └─────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
                               Client receives:
                               HTTP/1.1 201 Created
                               {
                                 "ID": "...",
                                 "title": "Urgent: Server down",
                                 "urgency_code": "H",
                                 "status_code": "N",
                                 ...
                               }
```

---

## 4. Event Handler Phases

CAP Java uses three handler phases, executed in order:

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                          Event Handler Lifecycle                               │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                         @Before Phase                                   │  │
│   │                                                                         │  │
│   │   Annotation: com.sap.cds.services.handler.annotations.Before           │  │
│   │                                                                         │  │
│   │   Purpose: Validation, input mutation, authorization checks             │  │
│   │                                                                         │  │
│   │   • Runs BEFORE the main operation                                      │  │
│   │   • Can modify input data                                               │  │
│   │   • Can reject request by throwing ServiceException                     │  │
│   │   • Multiple @Before handlers execute in registration order             │  │
│   │                                                                         │  │
│   │   This app's handlers:                                                  │  │
│   │   ┌───────────────────────────────────────────────────────────────────┐ │  │
│   │   │ ProcessorServiceHandler.java:35-45                                │ │  │
│   │   │ @Before(event = CqnService.EVENT_CREATE)                          │ │  │
│   │   │ ensureHighUrgencyForIncidentsWithUrgentInTitle(List<Incidents>)   │ │  │
│   │   │   → Mutates urgency_code if title contains "urgent"               │ │  │
│   │   │                                                                   │ │  │
│   │   │ ProcessorServiceHandler.java:50-57                                │ │  │
│   │   │ @Before(event = CqnService.EVENT_UPDATE)                          │ │  │
│   │   │ ensureNoUpdateOnClosedIncidents(Incidents)                        │ │  │
│   │   │   → Throws ServiceException(CONFLICT) if status = 'C'             │ │  │
│   │   └───────────────────────────────────────────────────────────────────┘ │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
│                                     │                                          │
│                                     ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                           @On Phase                                     │  │
│   │                                                                         │  │
│   │   Annotation: com.sap.cds.services.handler.annotations.On               │  │
│   │                                                                         │  │
│   │   Purpose: Main operation execution                                     │  │
│   │                                                                         │  │
│   │   • The actual CRUD operation happens here                              │  │
│   │   • Default: CAP's GenericHandler delegates to PersistenceService       │  │
│   │   • Custom @On handler REPLACES default behavior                        │  │
│   │   • Only ONE @On handler executes (first registered wins)               │  │
│   │                                                                         │  │
│   │   Use cases for custom @On:                                             │  │
│   │   • Call external APIs instead of/alongside database                    │  │
│   │   • Complex multi-step operations                                       │  │
│   │   • Custom actions/functions                                            │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
│                                     │                                          │
│                                     ▼                                          │
│   ┌─────────────────────────────────────────────────────────────────────────┐  │
│   │                          @After Phase                                   │  │
│   │                                                                         │  │
│   │   Annotation: com.sap.cds.services.handler.annotations.After            │  │
│   │                                                                         │  │
│   │   Purpose: Post-processing, side effects                                │  │
│   │                                                                         │  │
│   │   • Runs AFTER the main operation completes successfully                │  │
│   │   • Can modify response data                                            │  │
│   │   • Can trigger side effects (notifications, audit logs, etc.)          │  │
│   │   • Multiple @After handlers execute in registration order              │  │
│   │                                                                         │  │
│   │   Common uses:                                                          │  │
│   │   • Enrich response with computed fields                                │  │
│   │   • Send notifications                                                  │  │
│   │   • Log audit trails                                                    │  │
│   │   • Trigger downstream workflows                                        │  │
│   └─────────────────────────────────────────────────────────────────────────┘  │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. UPDATE Flow with Validation Rejection

The `@Before(EVENT_UPDATE)` handler demonstrates request rejection.

**File:** [`srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java:50-57`](srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java)

```
  Client                                                                    Database
    │                                                                          │
    │  PATCH /odata/v4/ProcessorService/Incidents(ID='...')                    │
    │  { "title": "Updated title" }                                            │
    │                                                                          │
    ▼                                                                          │
┌─────────────────────────────────────────────────────────────────────────────────┐
│  @Before(EVENT_UPDATE) - ProcessorServiceHandler.java:50-57                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐│
│  │                                                                             ││
│  │  @Before(event = CqnService.EVENT_UPDATE)                                   ││
│  │  public void ensureNoUpdateOnClosedIncidents(Incidents incident) {          ││
│  │                                                                             ││
│  │      // Line 52: Query current state using PersistenceService               ││
│  │      Incidents in = db.run(                                                 ││
│  │          Select.from(Incidents_.class)                                      ││
│  │                .where(i -> i.ID().eq(incident.getId()))                     ││
│  │      ).single(Incidents.class);                                             ││
│  │                                                                             ││
│  │      // Line 53-55: Check status and reject if closed                       ││
│  │      if (in.getStatusCode().equals("C")) {                                  ││
│  │          throw new ServiceException(                                        |│
│  │              ErrorStatuses.CONFLICT,           ◄─── HTTP 409                |│
│  │              "Can't modify a closed incident"                               |│
│  │          );                                                                 |│
│  │      }                                                                      |│
│  │  }                                                                          ││
│  │                                                                             ││
│  └─────────────────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────────────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            │                                               │
            ▼                                               ▼
   Status = 'C' (Closed)                         Status != 'C'
            │                                               │
            ▼                                               ▼
   ┌─────────────────────┐                      ┌─────────────────────┐
   │   ServiceException  │                      │   Continue to @On   │
   │   thrown            │                      │   phase (UPDATE     │
   │                     │                      │   proceeds)         │
   └──────────┬──────────┘                      └─────────────────────┘
              │
              ▼
   Client receives:
   HTTP/1.1 409 Conflict
   {
     "error": {
       "code": "409",
       "message": "Can't modify a closed incident"
     }
   }
```

**Key Classes Used:**

| Import | Purpose |
|--------|---------|
| `com.sap.cds.services.ErrorStatuses` | HTTP status code mappings |
| `com.sap.cds.services.ServiceException` | Exception that maps to HTTP error response |
| `com.sap.cds.services.persistence.PersistenceService` | Direct database access |
| `com.sap.cds.ql.Select` | CQN query builder |

---

## 6. CQN (CDS Query Notation)

CQN is CAP's internal query representation, independent of the database.

**Package:** `com.sap.cds.ql.*`

```
┌────────────────────────────────────────────────────────────────────────────────┐
│                    CQN: Abstraction Layer for Queries                          │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                │
│   OData Request                        CQN Representation                      │
│   ─────────────                        ──────────────────                      │
│                                                                                │
│   GET /Incidents?$filter=urgency_code eq 'H'                                   │
│   ────────────────────────────────────────────                                 │
│                                                                                │
│   // com.sap.cds.ql.Select                                                     │
│   Select.from(Incidents_.class)                                                │
│         .where(i -> i.urgency().code().eq("H"))                                │
│                                                                                │
│   CdsProcessor.get() builds this from OData $filter                            │
│                                                                                │
│   ═══════════════════════════════════════════════════════════════════════════  │
│                                                                                │
│   POST /Incidents { "title": "New issue" }                                     │
│   ────────────────────────────────────────                                     │
│                                                                                │
│   // com.sap.cds.ql.Insert                                                     │
│   Insert.into(Incidents_.class)                                                │
│         .entry(incident)                                                       │
│                                                                                │
│   CdsProcessor.post(): Insert insert = Insert.into(ref).entry(entityData);     │
│                                                                                │
│   ═══════════════════════════════════════════════════════════════════════════  │
│                                                                                │
│   PATCH /Incidents(ID='xxx') { "status_code": "R" }                            │
│   ─────────────────────────────────────────────────                            │
│                                                                                │
│   // com.sap.cds.ql.Update                                                     │
│   Update.entity(Incidents_.class)                                              │
│         .data(incident)                                                        │
│         .where(i -> i.ID().eq("xxx"))                                          │
│                                                                                │
│   CdsProcessor.patch(): CqnUpdate update = Update.entity(ref).data(entityData);│
│                                                                                │
│   ═══════════════════════════════════════════════════════════════════════════  │
│                                                                                │
│   DELETE /Incidents(ID='xxx')                                                  │
│   ───────────────────────────                                                  │
│                                                                                │
│   // com.sap.cds.ql.Delete                                                     │
│   Delete.from(Incidents_.class)                                                │
│         .where(i -> i.ID().eq("xxx"))                                          │
│                                                                                │
│   CdsProcessor.delete(): CqnDelete delete = Delete.from(toPathExpression(...));│
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘

                                    │
                                    │  PersistenceService.run() translates
                                    │  via com.sap.cds.impl.sql.*
                                    ▼

┌────────────────────────────────────────────────────────────────────────────────┐
│                              Generated SQL                                     │
├────────────────────────────────────────────────────────────────────────────────┤
│                                                                                │
│   SELECT * FROM sap_capire_incidents_Incidents WHERE urgency_code = 'H'        │
│                                                                                │
│   INSERT INTO sap_capire_incidents_Incidents (...) VALUES (...)                │
│                                                                                │
│   UPDATE sap_capire_incidents_Incidents SET status_code = 'R' WHERE ID = ...   │
│                                                                                │
│   DELETE FROM sap_capire_incidents_Incidents WHERE ID = ...                    │
│                                                                                │
│   Table names from: srv/src/main/resources/schema-h2.sql (generated)           │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```

---

## 7. Handler Registration & Spring Integration

**File:** [`srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java`](srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java)

```java
// Line 16: Spring component annotation
@Component                                          // Spring-managed bean

// Line 21: Binds this handler to ProcessorService
@ServiceName(ProcessorService_.CDS_NAME)            // "ProcessorService"

// Line 22: Implements CAP's EventHandler marker interface
public class ProcessorServiceHandler implements EventHandler {

    // Line 26: PersistenceService for direct DB access
    private final PersistenceService db;            // Injected by Spring

    // Line 28-30: Constructor injection
    public ProcessorServiceHandler(PersistenceService db) {
        this.db = db;
    }

    // Line 35: Register for CREATE events on Incidents
    @Before(event = CqnService.EVENT_CREATE)
    public void ensureHighUrgencyForIncidentsWithUrgentInTitle(
            List<Incidents> incidents) {            // CAP deserializes request body
        // Handler logic...
    }
}
```

### Key Annotations & Interfaces

| Annotation/Interface | Package | Purpose |
|---------------------|---------|---------|
| `@Component` | `org.springframework.stereotype` | Spring bean registration |
| `@ServiceName` | `com.sap.cds.services.handler.annotations` | Binds handler to CDS service |
| `EventHandler` | `com.sap.cds.services.handler` | Marker interface for CAP handlers |
| `@Before` | `com.sap.cds.services.handler.annotations` | Pre-operation handler |
| `@On` | `com.sap.cds.services.handler.annotations` | Main operation handler |
| `@After` | `com.sap.cds.services.handler.annotations` | Post-operation handler |

### Event Constants

**Class:** `com.sap.cds.services.cds.CqnService`

| Constant | Value | Triggered By |
|----------|-------|--------------|
| `EVENT_CREATE` | `"CREATE"` | POST (new entity) |
| `EVENT_READ` | `"READ"` | GET |
| `EVENT_UPDATE` | `"UPDATE"` | PATCH/PUT |
| `EVENT_DELETE` | `"DELETE"` | DELETE |

### Generated POJOs

**Location:** `srv/src/gen/java/cds/gen/`
**Generated By:** `cds-maven-plugin` during build

| Generated Class | Source |
|-----------------|--------|
| `cds.gen.processorservice.Incidents` | `srv/services.cds` projection |
| `cds.gen.processorservice.ProcessorService_` | Service metadata |
| `cds.gen.sap.capire.incidents.Incidents_` | Entity metadata (for CQN queries) |

---

## 8. Draft-Enabled Entities

**Annotation:** [`srv/services.cds:19`](srv/services.cds)

```cds
annotate ProcessorService.Incidents with @odata.draft.enabled;
```

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                         Draft Entity Lifecycle                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│   1. CREATE (New Draft)                                                         │
│      POST /ProcessorService/Incidents                                           │
│      ─────────────────────────────────────────────────────────────────────      │
│      → Creates draft record (IsActiveEntity = false)                            │
│      → Stored in: ProcessorService_Incidents_drafts table                       │
│                                                                                 │
│   2. EDIT (Modify Draft)                                                        │
│      PATCH /ProcessorService/Incidents(ID='...',IsActiveEntity=false)           │
│      ─────────────────────────────────────────────────────────────────────      │
│      → Updates draft without touching active entity                             │
│      → Multiple users can see draft state                                       │
│                                                                                 │
│   3. ACTIVATE (Promote to Active)                                               │
│      POST .../Incidents(...,IsActiveEntity=false)/ProcessorService.draftActivate│
│      ─────────────────────────────────────────────────────────────────────      │
│      → Validates draft                                                          │
│      → Moves to active table (IsActiveEntity = true)                            │
│      → Deletes draft record                                                     │
│                                                                                 │
│   4. DISCARD                                                                    │
│      DELETE /ProcessorService/Incidents(ID='...',IsActiveEntity=false)          │
│      ─────────────────────────────────────────────────────────────────────      │
│      → Removes draft without affecting active entity                            │
│                                                                                 │
│                                                                                 │
│   ┌─────────────────────┐                    ┌─────────────────────┐            │
│   │                     │                    │                     │            │
│   │   Draft Tables      │    draftActivate   │   Active Tables     │            │
│   │   ───────────────   │   ─────────────►   │   ─────────────     │            │
│   │   IsActiveEntity    │                    │   IsActiveEntity    │            │
│   │   = false           │                    │   = true            │            │
│   │                     │                    │                     │            │
│   └─────────────────────┘                    └─────────────────────┘            │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 9. Complete Request Flow Summary

```
HTTP Request (POST /odata/v4/ProcessorService/Incidents)
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ AbstractCdsODataServlet.service()                                           │
│   Package: com.sap.cds.adapter.odata.v4                                     │
│   • Establishes request context                                             │
│   • Extracts service name from URL                                          │
│   • Initializes Olingo OData infrastructure                                 │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ OlingoProcessor                                                             │
│   Package: com.sap.cds.adapter.odata.v4.processors                          │
│   • Implements Olingo processor interfaces                                  │
│   • Delegates to CdsProcessor                                               │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ CdsProcessor.post()                                                         │
│   Package: com.sap.cds.adapter.odata.v4.processors                          │
│   • Converts OData request to CQN: Insert.into(ref).entry(entityData)       │
│   • Calls applicationService.run(insert)                                    │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ ApplicationService.run(CqnInsert)                                           │
│   Interface: com.sap.cds.services.cds.ApplicationService                    │
│   • Authorization check (@requires annotation)                              │
│   • Emits EVENT_CREATE event                                                │
│   • Triggers handler chain                                                  │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ @Before Handler                                                             │
│   File: ProcessorServiceHandler.java:35-45                                  │
│   Method: ensureHighUrgencyForIncidentsWithUrgentInTitle()                  │
│   • Validates/mutates input data                                            │
│   • Can throw ServiceException to reject request                            │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ @On Handler (Default: GenericHandler)                                       │
│   • Delegates to PersistenceService.run(CqnInsert)                          │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ PersistenceService.run()                                                    │
│   Interface: com.sap.cds.services.persistence.PersistenceService            │
│   Implementation: com.sap.cds.impl.sql.* (in cds4j-runtime)                 │
│   • Translates CQN to SQL                                                   │
│   • Executes within transaction                                             │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Database (H2)                                                               │
│   Schema: srv/src/main/resources/schema-h2.sql                              │
│   Table: sap_capire_incidents_Incidents                                     │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ @After Handler (none in this app)                                           │
│   • Post-processing, side effects                                           │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Response Serialization                                                      │
│   CdsProcessor: return new CdsODataResponse(SC_CREATED, remapResult(...))   │
│   OlingoProcessor: Serializes to OData JSON                                 │
└─────────────────────────────────────────────────────────────────────────────┘
    │
    ▼
HTTP Response (201 Created + JSON body)
```

---

## 10. Key Files Reference

| File | Purpose |
|------|---------|
| [`db/schema.cds`](db/schema.cds) | Domain model (entities, associations) |
| [`srv/services.cds`](srv/services.cds) | Service definitions, projections, annotations |
| [`srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java`](srv/src/main/java/customer/incident_management/handler/ProcessorServiceHandler.java) | Custom event handlers |
| [`srv/src/main/java/customer/incident_management/Application.java`](srv/src/main/java/customer/incident_management/Application.java) | Spring Boot entry point |
| [`srv/src/main/resources/application.yaml`](srv/src/main/resources/application.yaml) | Runtime configuration |
| `srv/src/main/resources/schema-h2.sql` | Generated database schema |
| `srv/src/gen/java/cds/gen/` | Generated POJOs from CDS |

---

## 11. Key Takeaways

| Concept | Description |
|---------|-------------|
| **CDS-First** | Define models in CDS; CAP generates SQL, POJOs, and OData metadata |
| **Event-Driven** | All operations emit events; handlers intercept at `@Before`/`@On`/`@After` |
| **CQN** | Database-agnostic query notation; same code works on H2, HANA, PostgreSQL |
| **Service Projections** | Multiple services can expose different views of the same entities |
| **Type-Safe POJOs** | Generated from CDS; handlers receive/return typed objects |
| **Spring Integration** | Handlers are Spring beans with full DI support |
| **Draft Support** | Built-in collaborative editing via `@odata.draft.enabled` |
| **Declarative Security** | `@requires` annotation on services enforces role-based access |

The beauty of CAP Java is that most of the boilerplate (OData parsing, SQL generation, transaction management) is handled automatically, allowing developers to focus on business logic in event handlers.
