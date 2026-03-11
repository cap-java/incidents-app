# CAP Java: OData V4 Batch Request Handling

## Overview

This document explains OData V4 Batch requests and how SAP Cloud Application Programming Model (CAP) for Java handles them. Batch requests enable clients to bundle multiple operations into a single HTTP request, reducing network overhead and improving performance in distributed systems.

---

## 1. What are OData V4 Batch Requests?

### 1.1 Definition

OData V4 Batch requests allow clients to group multiple individual requests (queries, creates, updates, deletes) into a single HTTP POST request. The server processes these requests and returns all responses bundled together in a single HTTP response.

### 1.2 Key Characteristics

- **Single HTTP Request**: Multiple operations sent in one HTTP POST to `$batch` endpoint
- **Multipart Format**: Uses multipart/mixed content type with boundary delimiters
- **Change Sets**: Operations can be grouped into atomic transactions (changesets)
- **Independent Requests**: Queries outside changesets are processed independently
- **Reduced Latency**: Fewer HTTP roundtrips improve performance
- **Transaction Control**: Changesets provide all-or-nothing execution semantics

### 1.3 Structure

```
POST /odata/v4/IncidentService/$batch HTTP/1.1
Content-Type: multipart/mixed; boundary=batch_boundary

--batch_boundary
Content-Type: multipart/mixed; boundary=changeset_boundary

--changeset_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

POST Incidents HTTP/1.1
Content-Type: application/json

{
  "title": "Network outage",
  "urgency": "high"
}

--changeset_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

PATCH Incidents(ID=f60f5c43-3c6e-4c79-bb18-23cf314c55e4) HTTP/1.1
Content-Type: application/json

{
  "status": "closed"
}

--changeset_boundary--

--batch_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

GET Incidents HTTP/1.1

--batch_boundary--
```

---

## 2. OData V4 Batch Concepts

### 2.1 Batch Boundary

- Separates individual requests within the batch
- Defined in the `Content-Type` header
- Format: `multipart/mixed; boundary=<boundary_string>`
- Each part starts with `--<boundary_string>`
- Batch ends with `--<boundary_string>--`

### 2.2 ChangeSet

A changeset is a group of operations that must be executed atomically:

- **Atomic Transaction**: All operations succeed or all fail
- **Supported Operations**: CREATE, UPDATE (PUT/PATCH), DELETE
- **Not Allowed**: GET requests cannot be in changesets
- **Own Boundary**: Uses nested multipart with changeset boundary
- **Rollback**: On any failure, all changes in the changeset are rolled back

### 2.3 Request Types

**Within ChangeSet (Transactional)**:
- POST (Create)
- PATCH (Update)
- PUT (Replace)
- DELETE (Delete)

**Outside ChangeSet (Independent)**:
- GET (Query)
- Can also include modification operations that should run independently

### 2.4 Content References

Batch requests support `Content-ID` headers to reference created entities within the same batch:

```
--changeset_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary
Content-ID: 1

POST Incidents HTTP/1.1
Content-Type: application/json

{
  "title": "Server down"
}

--changeset_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

POST $1/conversations HTTP/1.1
Content-Type: application/json

{
  "message": "Investigating the issue"
}
```

Here `$1` references the Incident created in the first request.

---

## 3. CAP Java Batch Request Processing

### 3.1 Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CAP Java Batch Processing                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   ┌──────────────────┐    ┌────────────────┐    ┌───────────────────────┐  │
│   │  OData Adapter   │    │ Batch Executor │    │  Transaction Manager  │  │
│   │                  │───▶│                │───▶│                       │  │
│   │  - Parse batch   │    │ - Process each │    │  - Begin transaction  │  │
│   │  - Extract parts │    │   request      │    │  - Commit/rollback    │  │
│   │  - Build response│    │ - Handle refs  │    │  - Savepoint support  │  │
│   └──────────────────┘    └────────────────┘    └───────────────────────┘  │
│            │                      │                          │              │
│            │                      ▼                          ▼              │
│            │              ┌────────────────┐      ┌─────────────────────┐  │
│            │              │  CQN Executor  │      │  Database (HANA,    │  │
│            │              │                │─────▶│  H2, PostgreSQL,    │  │
│            │              │  - Build CQN   │      │  SQLite)            │  │
│            │              │  - Execute ops │      └─────────────────────┘  │
│            │              └────────────────┘                               │
│            │                                                               │
│            └──────────────────▶ Serialize Batch Response                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Request Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │
       │ POST /$batch
       │ (multipart/mixed)
       ▼
┌─────────────────────────────────────────────────────────────┐
│ 1. ODataServlet / Request Handler                           │
│    - Receives HTTP request                                  │
│    - Routes to batch endpoint                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Batch Request Parser                                     │
│    - Parse multipart/mixed content                          │
│    - Extract individual request parts                       │
│    - Identify changesets vs independent requests            │
│    - Parse Content-ID headers                               │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. Batch Executor                                           │
│    ┌───────────────────────────────────────────────────┐   │
│    │ For each ChangeSet:                               │   │
│    │   - Begin transaction                             │   │
│    │   - Execute all operations in order               │   │
│    │   - Track Content-IDs for references              │   │
│    │   - If any fails: Rollback entire changeset       │   │
│    │   - If all succeed: Commit changeset              │   │
│    └───────────────────────────────────────────────────┘   │
│    ┌───────────────────────────────────────────────────┐   │
│    │ For each Independent Request:                     │   │
│    │   - Execute immediately                           │   │
│    │   - Failure doesn't affect other requests         │   │
│    └───────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. Individual Request Processing                            │
│    - Parse OData request (GET, POST, PATCH, DELETE)         │
│    - Convert to CQN query/statement                         │
│    - Trigger event handlers (@Before/@On/@After)            │
│    - Execute against persistence service                    │
│    - Collect response                                       │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. Response Builder                                         │
│    - Collect all individual responses                       │
│    - Build multipart/mixed response                         │
│    - Include status codes for each operation                │
│    - Map Content-IDs to results                             │
└────────────────────────┬────────────────────────────────────┘
                         │
                         ▼
                    ┌────────┐
                    │ Client │
                    └────────┘
```

### 3.3 Transaction Management

CAP Java handles transactions for batch requests as follows:

**ChangeSet Processing**:
```java
// Pseudo-code representation
for (ChangeSet changeSet : batch.getChangeSets()) {
    try {
        transaction.begin();

        for (Request request : changeSet.getRequests()) {
            Result result = processRequest(request);
            results.add(result);

            // Track Content-ID for references
            if (request.hasContentId()) {
                contentIdMap.put(request.getContentId(), result.getEntity());
            }
        }

        transaction.commit();
    } catch (Exception e) {
        transaction.rollback();
        // Add error response for entire changeset
        results.addChangeSetError(e);
    }
}
```

**Independent Request Processing**:
```java
// Each request runs in its own transaction
for (Request request : batch.getIndependentRequests()) {
    try {
        Result result = processRequest(request);
        results.add(result);
    } catch (Exception e) {
        // Error only affects this request
        results.addError(request, e);
    }
}
```

### 3.4 Event Handler Execution

Event handlers are executed for each operation within the batch:

```java
@Component
@ServiceName("IncidentService")
public class IncidentServiceHandler implements EventHandler {

    @Before(event = CqnService.EVENT_CREATE, entity = "Incidents")
    public void beforeCreateIncident(List<Incidents> incidents) {
        // Called for each CREATE in the batch
        for (Incidents incident : incidents) {
            incident.setCreatedAt(Instant.now());
        }
    }

    @On(event = CqnService.EVENT_UPDATE, entity = "Incidents")
    public void onUpdateIncident(CdsUpdateEventContext context) {
        // Called for each UPDATE in the batch
        // Full access to context and transaction
    }

    @After(event = CqnService.EVENT_READ, entity = "Incidents")
    public void afterReadIncidents(List<Incidents> incidents) {
        // Called for each READ in the batch
        // Can modify results before sending back
    }
}
```

---

## 4. Batch Response Format

### 4.1 Successful Response

```
HTTP/1.1 200 OK
Content-Type: multipart/mixed; boundary=batch_response_boundary

--batch_response_boundary
Content-Type: multipart/mixed; boundary=changeset_response_boundary

--changeset_response_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 201 Created
Content-Type: application/json

{
  "ID": "f60f5c43-3c6e-4c79-bb18-23cf314c55e4",
  "title": "Network outage",
  "urgency": "high"
}

--changeset_response_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 200 OK
Content-Type: application/json

{
  "ID": "f60f5c43-3c6e-4c79-bb18-23cf314c55e4",
  "status": "closed"
}

--changeset_response_boundary--

--batch_response_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 200 OK
Content-Type: application/json

{
  "value": [
    {
      "ID": "f60f5c43-3c6e-4c79-bb18-23cf314c55e4",
      "title": "Network outage"
    }
  ]
}

--batch_response_boundary--
```

### 4.2 Error Response (ChangeSet Failure)

When any operation in a changeset fails, the entire changeset is rolled back:

```
--batch_response_boundary
Content-Type: multipart/mixed; boundary=changeset_response_boundary

--changeset_response_boundary
Content-Type: application/http
Content-Transfer-Encoding: binary

HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "error": {
    "code": "400",
    "message": "Validation failed for field 'urgency'",
    "target": "urgency"
  }
}

--changeset_response_boundary--
```

All operations in the changeset are rolled back, and none of the changes are persisted.

---

## 5. Common Use Cases

### 5.1 Bulk Create

Create multiple entities in a single atomic transaction:

```
POST /$batch

--batch
Content-Type: multipart/mixed; boundary=changeset

--changeset
POST Incidents
{ "title": "Issue 1" }

--changeset
POST Incidents
{ "title": "Issue 2" }

--changeset
POST Incidents
{ "title": "Issue 3" }

--changeset--
--batch--
```

All incidents are created, or none if any validation fails.

### 5.2 Create with Related Entities

Create an entity and related entities using Content-ID references:

```
--changeset
Content-ID: new-incident

POST Incidents
{
  "title": "Database performance issue",
  "urgency": "high"
}

--changeset
POST $new-incident/conversations
{
  "message": "Initial investigation started"
}

--changeset--
```

### 5.3 Mixed Operations

Combine queries with modifications:

```
--batch
GET Incidents?$filter=status eq 'open'

--batch
Content-Type: multipart/mixed; boundary=changeset

--changeset
PATCH Incidents(ID=<guid>)
{ "status": "closed" }

--changeset
POST Incidents
{ "title": "New incident" }

--changeset--

--batch
GET Customers

--batch--
```

The GET requests execute independently, while modifications are atomic.

---

## 6. CAP Java Configuration

### 6.1 Default Behavior

CAP Java handles OData V4 batch requests automatically with built-in defaults:

- Batch requests are **enabled by default** on the `$batch` endpoint
- Transaction management is automatic for changesets
- No special configuration required for basic batch support

### 6.2 Transaction Management

CAP Java automatically manages transactions for batch requests:

**ChangeSet Transactions**:
- Each changeset runs in a single database transaction
- Automatic commit on success, rollback on any failure
- Transaction boundaries are managed by the CAP runtime

**Independent Requests**:
- Each request outside a changeset may run in its own transaction
- Failure of one request doesn't affect others

### 6.3 Spring Boot Configuration

Standard Spring Boot settings apply to batch request processing:

```yaml
spring:
  datasource:
    # Connection pool settings affect batch performance
    hikari:
      maximum-pool-size: 10
      connection-timeout: 30000
```

### 6.4 HTTP Request Limits

Configure HTTP request size limits (applies to batch requests):

```yaml
server:
  # Maximum HTTP request body size
  max-http-request-header-size: 10MB
  tomcat:
    max-swallow-size: 10MB
```

---

## 7. Best Practices

### 7.1 Use ChangeSets for Related Operations

Group related operations in changesets to ensure data consistency:

```
✓ GOOD: Create order and line items in one changeset
✗ BAD: Create order in one changeset, line items in another
```

### 7.2 Optimize Request Order

Order requests to minimize database roundtrips:

```
✓ GOOD: Create parent first, then children using Content-ID
✗ BAD: Random order requiring multiple passes
```

### 7.3 Limit Batch Size

Don't create overly large batches:

```
✓ GOOD: 10-50 operations per batch
✗ BAD: 1000+ operations (consider pagination or background jobs)
```

### 7.4 Handle Partial Failures

Independent requests outside changesets can fail without affecting others:

```
✓ GOOD: Critical operations in changesets, optional operations outside
✗ BAD: All operations in one changeset when partial success is acceptable
```

### 7.5 Use Content-IDs Wisely

Only use Content-IDs when you need to reference created entities:

```
✓ GOOD: Content-ID for parent when creating children
✗ BAD: Content-ID on every operation (adds overhead)
```

---

## 8. Error Handling

### 8.1 Validation Errors

If a validation error occurs in a changeset, the entire changeset fails:

```java
@Before(event = CqnService.EVENT_CREATE, entity = "Incidents")
public void validateIncident(List<Incidents> incidents) {
    for (Incidents incident : incidents) {
        if (incident.getUrgency() == null) {
            throw new ServiceException(ErrorStatuses.BAD_REQUEST,
                "Urgency is required");
        }
    }
}
```

Response:
```json
{
  "error": {
    "code": "400",
    "message": "Urgency is required"
  }
}
```

### 8.2 Database Constraint Violations

Unique constraint violations or foreign key errors roll back the changeset:

```
HTTP/1.1 400 Bad Request

{
  "error": {
    "code": "400",
    "message": "Entity already exists with this key"
  }
}
```

### 8.3 Authorization Errors

If user lacks permission for any operation in a changeset:

```
HTTP/1.1 403 Forbidden

{
  "error": {
    "code": "403",
    "message": "User not authorized to perform this operation"
  }
}
```

---

## 9. Performance Considerations

### 9.1 Benefits

- **Reduced Network Latency**: Single HTTP request vs multiple roundtrips
- **Connection Pooling**: Better utilization of database connections
- **Transaction Overhead**: Fewer transaction begin/commit cycles
- **Client Efficiency**: Simplified client code for bulk operations

### 9.2 Trade-offs

- **Memory Usage**: Large batches consume more memory
- **Response Time**: Client waits for all operations to complete
- **Error Recovery**: Partial failures require re-sending entire changeset
- **Debugging**: Harder to troubleshoot than individual requests

### 9.3 Optimization Tips

1. **Batch Similar Operations**: Group creates together, updates together
2. **Use Streaming**: For very large batches, process in chunks
3. **Monitor Transaction Time**: Keep changesets small enough to avoid timeouts
4. **Index Properly**: Ensure database indexes support batch operations
5. **Profile Handlers**: Ensure event handlers are efficient

---

## 10. Testing Batch Requests

### 10.1 Using cURL

```bash
curl -X POST http://localhost:8080/odata/v4/IncidentService/$batch \
  -H "Content-Type: multipart/mixed; boundary=batch" \
  --data-binary @batch-request.txt
```

### 10.2 Using Postman

1. Create a POST request to `/$batch`
2. Set Content-Type to `multipart/mixed; boundary=batch`
3. Add batch content in raw body
4. Send and inspect multipart response

### 10.3 Integration Tests

```java
@SpringBootTest
@AutoConfigureMockMvc
class BatchRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testBatchCreateIncidents() throws Exception {
        String batchContent = """
            --batch
            Content-Type: multipart/mixed; boundary=changeset

            --changeset
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            POST Incidents HTTP/1.1
            Content-Type: application/json

            {"title": "Test Incident 1"}

            --changeset
            Content-Type: application/http
            Content-Transfer-Encoding: binary

            POST Incidents HTTP/1.1
            Content-Type: application/json

            {"title": "Test Incident 2"}

            --changeset--
            --batch--
            """;

        mockMvc.perform(post("/odata/v4/IncidentService/$batch")
                .contentType("multipart/mixed; boundary=batch")
                .content(batchContent))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("multipart/mixed"));
    }
}
```

---

## 11. Troubleshooting

### 11.1 Common Issues

**Issue**: Batch request returns 400 Bad Request
**Solution**: Check boundary delimiters match exactly between Content-Type and body

**Issue**: ChangeSet operations not atomic
**Solution**: Verify all operations are within changeset boundaries

**Issue**: Content-ID references not working
**Solution**: Ensure Content-ID is defined before usage, check format `$<id>`

**Issue**: Performance degradation with large batches
**Solution**: Reduce batch size, check database connection pool settings

### 11.2 Debugging

Enable detailed logging in `application.yaml`:

```yaml
logging:
  level:
    com.sap.cds: DEBUG
    com.sap.cds.odata: TRACE
    # See detailed batch processing
    com.sap.cds.odata.v4.batch: TRACE
```

### 11.3 Monitoring

Key metrics to monitor:

- Batch request size distribution
- Average processing time per batch
- Changeset rollback rate
- Individual operation success/failure rates
- Memory consumption during batch processing

---

## 12. Comparison with OData V2 Batch

### 12.1 Key Differences

| Aspect | OData V2 | OData V4 |
|--------|----------|----------|
| Content Type | `multipart/mixed` | `multipart/mixed` (same) |
| ChangeSet Format | Similar | Simplified boundaries |
| Content-ID | Limited support | Full support with `$id` |
| Error Handling | Vendor-specific | Standardized |
| Specification | Less detailed | More comprehensive |

### 12.2 Migration Notes

When migrating from OData V2 to V4:

1. Update batch endpoint URLs (`/v2/` → `/v4/`)
2. Review Content-ID reference syntax
3. Update error response parsing
4. Test changeset rollback behavior
5. Verify boundary delimiter handling

---

## Summary

OData V4 Batch requests provide a powerful mechanism for bundling multiple operations into a single HTTP request. CAP Java handles batch processing automatically, providing:

- Atomic transactions via changesets
- Content-ID references for related entities
- Automatic transaction management
- Event handler integration
- Standard OData V4 compliance

By understanding batch request structure and CAP Java's processing model, developers can build efficient applications that minimize network overhead while maintaining data consistency and leveraging CAP's full event-driven architecture.
