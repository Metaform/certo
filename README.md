# Certo

A Spring Boot implementation of the **Catena-X CX-0135 Company Certificate Management (CCM)**
data-plane wire protocol (**v3.0.0**). A certificate is **JSON metadata** that references one or more **document
binaries** (e.g. a PDF), exchanged between a **Certificate Provider** and a **Certificate Consumer**.

The app hosts **both roles' REST APIs in a single runtime** — one instance can act as a Certificate
Provider, a Certificate Consumer, or both at once:

| API                                                                | Spec         | Role                                                                                                              |
|--------------------------------------------------------------------|--------------|-------------------------------------------------------------------------------------------------------------------|
| [Certificate Provider API](docs/ccm/certificate-provider-api.yaml) | CX-0135 §3.3 | Accepts requests, serves certificate metadata + document binaries, answers searches, receives acceptance feedback |
| [Certificate Consumer API](docs/ccm/certificate-consumer-api.yaml) | CX-0135 §3.2 | Receives lifecycle / fulfillment notifications, exposes acceptance status                                         |

> **Scope.** Only the **data plane** is implemented. The Dataspace Protocol (DSP) control plane —
> catalog, contract negotiation and the token-refresh authorization of CX-0000 §4 — is **out of
> scope**. Storage is in-memory and resets on restart.
>
> **Push variants.** Certo supports **both** push mechanisms. By default a lifecycle `CREATED`
> notification carries only the certificate id/light-triage subset and the consumer **pulls** the rest
> (push-pull). With **embedded-document** push (`…ConsumerEmbeddedDocumentApi`,
> `documents[].contentBase64` inline) the notification carries the full certificate and its document
> content, and the consumer accepts **without a pull** — trigger it with
> `POST /management/v1/certificates/{id}/publish` and body `{"embedded":true}`. Metadata retrieval
> (`GET /certificates/{id}`) never includes `contentBase64` (CX-0135 §3.3.2).

The spec this implements is vendored under [`docs/ccm/`](docs/ccm). For a walkthrough of every supported interaction
with sequence diagrams, see [`docs/FLOWS.md`](docs/FLOWS.md).

## Stack

- **Java 25** (LTS) — the Gradle toolchain targets Java 25 and auto-provisions it (via the Foojay resolver) if it isn't
  installed locally
- **Spring Boot 4.0** / Spring Framework 7 (uses **Jackson 3**, `tools.jackson`)
- **Gradle 9.5.1** via the wrapper — no local Gradle needed

## Build & run

```bash
./gradlew build          # compile + test (provisions JDK 25 on first run)
./gradlew bootRun        # run on http://localhost:8080 using the JDK 25 toolchain
```

To run the jar directly, use a Java 25 runtime:

```bash
java -jar build/libs/certo-0.1.0.jar     # requires java 25+ on PATH
```

Certificate seeding is **off by default**. Enable it with `certo.seed-sample-data=true` (e.g.
`./gradlew bootRun --args='--certo.seed-sample-data=true'`) to populate the search/retrieval endpoints
with sample data; the test suite enables it automatically. When enabled, the provider seeds these sample
certificates on startup (each revision references one generated PDF document):

| certificateId        | type      | revisions                 | validUntil (latest)  |
|----------------------|-----------|---------------------------|----------------------|
| `cert-iso9001-0001`  | ISO9001   | 1, 2 (CREATED → MODIFIED) | 2027-01-24           |
| `cert-iso14001-0001` | ISO14001  | 1                         | 2027-05-31           |
| `cert-expired-0001`  | IATF16949 | 1                         | 2020-01-01 (expired) |

Any certificate type is accepted (the seeded sample certificates happen to be `ISO9001`, `ISO14001`,
`IATF16949`). A request for a type the provider doesn't yet hold waits for the backend, which may issue it,
`fail`, or `decline` it (a business decision → `DECLINED`).

## Domain model (CX-0135 §2)

- A **Certificate Exchange** (`exchangeId`) is one end-to-end delivery interaction. It runs through a provider-owned
  **Fulfillment** phase (`CERTIFICATION_REQUESTED → FULFILLED`, or
  `DECLINED`/`FAILED`) and then a consumer-owned **Acceptance** phase (a terminal
  `ACCEPTED`/`REJECTED`/`ERRORED` reached directly from `FULFILLED`, or via the optional non-terminal
  `RETRIEVED` receipt).
- A **Certificate Lifecycle** tracks the artifact itself (`CREATED → MODIFIED* → WITHDRAWN`), keyed by
  `(certificateId, revision)`, independently of any exchange.
- A **certificate** is metadata (`certifiedLocations[]`, validity, `trustLevel`, issuer/validator, …)
  plus a `documents[]` array of references. Each **document** is opaque and **revision-independent**
  (one document may be shared across revisions) and is retrieved separately by id.

The state machine is enforced: only legal transitions are allowed, terminal states are immutable, and acceptance can
only be reported once an exchange is `FULFILLED` (illegal attempts → `409 Conflict`). A request for a certificate the
provider **already holds** (covering the requested
`certifiedLocations`) is fulfilled immediately (`FULFILLED`); otherwise the request is
`CERTIFICATION_REQUESTED` and the exchange waits for the certification-authority backend to issue the certificate — the
`certificateId`/`revision` are assigned only then. Each request opens a **new** exchange, so a re-attempt after a
terminal outcome is a distinct exchange.

The certification-authority backend is driven through the management API: it uploads the
issued document(s) with `POST /management/v1/documents`, then `POST /management/v1/certificates` (referencing
them by `documentIds`) issues the certificate and fulfils every waiting exchange it covers;
`POST /management/v1/certificate-requests/{id}/fail` ends a waiting exchange in `FAILED`.

## Certificate Provider API (CX-0135 §3.3)

```bash
B=http://localhost:8080

# Search certificates with the §3.3.4 query grammar ($condition.$match of $field/$eq, AND-combined).
# Supported fields: certificateType, certifiedLocations.{bpnl,bpns,bpna}; anything else -> 501.
# Pagination (when limit is set) is carried in the RFC 8288 Link header (next/prev).
curl -s -X POST "$B/certificates/search" -H 'Content-Type: application/json' \
  -d '{"$condition":{"$match":[{"$field":"certificateType","$eq":"ISO9001"}]}}'

# Request a held certificate -> FULFILLED immediately (HTTP 202)
curl -s -X POST $B/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO9001","certifiedLocations":["BPNS00000003AYRE"]}'

# Request a not-yet-held certificate -> CERTIFICATION_REQUESTED (waits for the backend)
curl -s -X POST $B/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","certifiedLocations":["BPNS-NEW-PLANT"]}'   # -> CERTIFICATION_REQUESTED
# Backend issues the certificate: first upload its document(s), then add the certificate referencing them.
# Adding the certificate FULFILLS every waiting exchange it covers (and notifies each consumer).
curl -s -X POST $B/management/v1/documents -H 'Content-Type: application/json' \
  -d '{"mediaType":"application/pdf","contentBase64":"<base64-pdf>"}'          # -> 201 {documentId}
curl -s -X POST $B/management/v1/certificates -H 'Content-Type: application/json' -d '{
    "certificateType":"ISO14001", "certificateTypeVersion":"2015", "registrationNumber":"DE-14001-12345",
    "validFrom":"2026-01-01", "validUntil":"2029-01-01", "trustLevel":"high",
    "certifiedLocations":[{"bpnl":"BPNL00000000HOLDER","bpna":"BPNA00000000MAIN0","bpns":"BPNS-NEW-PLANT","locationRole":"MAIN_LOCATION"}],
    "issuer":{"issuerName":"TÜV","issuerBpn":"BPNL00000000ISSUER"},
    "documentIds":["<documentId>"]
  }'   # -> 201 {certificateId, revision, fulfilledExchanges}
# ...or the backend cannot issue it -> the waiting exchange ends FAILED
curl -s -X POST $B/management/v1/certificate-requests/<exchangeId>/fail

# Poll fulfillment status
curl -s $B/certificate-requests/<exchangeId>

# Retrieve certificate metadata as JSON (always the latest revision, CX-0135 §3.3.2)
curl -s $B/certificates/cert-iso9001-0001                # full metadata + documents[] references

# Retrieve a document binary by its opaque id (served with Content-Type = its mediaType)
curl -s "$B/documents/<documentId>" -o certificate.pdf

# Report acceptance outcome as a CloudEvent (404 if the exchangeId is unknown)
curl -s -X POST $B/certificate-acceptance-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateAcceptanceStatus.v1",
    "source":"urn:bpn:BPNL0000000002CD","sourcebpn":"BPNL0000000002CD","id":"evt-1","time":"2025-05-04T08:00:00Z",
    "data":{"exchangeId":"<exchangeId>","certificateId":"cert-iso9001-0001","status":"ACCEPTED"}}'
```

Retrieval is **two-step**: `GET /certificates/{id}` returns metadata listing the documents by reference; the consumer
then follows each `documents[].documentId` to `GET /documents/{id}` for the binary. A **withdrawn** certificate need not
stay retrievable — `GET /certificates/{id}` returns
`200` with the minimal `{certificateId, status: WITHDRAWN}` body (CX-0135 §3.3.2).

### Provider-initiated push — the full loop in one call

`POST /management/v1/certificates/{id}/publish` opens an exchange and pushes a lifecycle `CREATED` event to the consumer, which pulls
the certificate + its documents, evaluates them, and posts its acceptance **back**
to the provider — all in this one runtime. `GET /management/v1/certificate-exchanges/{id}` is a management/inspection endpoint (not in
CX-0135) showing the provider's recorded view of both phases.

The `publish` body selects the target: `protocolVersion` (`3.0.0`, the configured native consumer, or `2.4.0`, a
caller-named one), `embedded` (full content inline vs by-reference), and `revision`. An empty body publishes
the latest revision to the native consumer, by reference.

```bash
B=http://localhost:8080

# Native (v3) push to the configured consumer; empty body = latest revision, by reference
PUB=$(curl -s -X POST $B/management/v1/certificates/cert-iso9001-0001/publish)   # 202: {exchangeId, revision, consumerNotified:true}
EXCH=$(echo "$PUB" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

curl -s $B/certificate-acceptance-status/$EXCH   # consumer side  -> status ACCEPTED
curl -s $B/management/v1/certificate-exchanges/$EXCH           # provider side  -> fulfillmentStatus FULFILLED, acceptanceStatus ACCEPTED

# Push to a v2.4.0 consumer instead: name it, and embed the content so it is delivered as /companycertificate/push
curl -s -X POST $B/management/v1/certificates/cert-iso9001-0001/publish -H 'Content-Type: application/json' \
  -d '{"protocolVersion":"2.4.0","embedded":true,"consumerBpn":"BPNL...","consumerUrl":"http://legacy-consumer:8080"}'
```

## Certificate Consumer API (CX-0135 §3.2)

```bash
B=http://localhost:8080

# Lifecycle CREATED event -> opens a (provider-initiated) exchange; the consumer pulls + evaluates.
# v3 nests the certificate under data.certificate; CREATED carries the light-triage subset.
curl -s -X POST $B/certificate-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateLifecycleStatus.v1",
    "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","subject":"BPNL0000000002CD","id":"evt-1",
    "time":"2025-05-04T07:00:00Z",
    "data":{"status":"CREATED","exchangeId":"exch-1",
            "certificate":{"certificateId":"cert-iso9001-0001","revision":2,"certificateType":"ISO9001",
                           "validFrom":"2023-01-25","validUntil":"2027-01-24"}}}'

# A provider queries the consumer's acceptance decision (404 if unknown)
curl -s $B/certificate-acceptance-status/exch-1

# Fulfillment status push (counterpart of polling GET /certificate-requests/{id})
curl -s -X POST $B/certificate-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateFulfillmentStatus.v1",
    "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","id":"evt-2","time":"2025-05-04T07:30:00Z",
    "data":{"exchangeId":"exch-1","certificateId":"cert-iso9001-0001","status":"FULFILLED"}}'
```

Both notification endpoints accept a **single CloudEvent or a batch** (a JSON array), per CX-0000 §4.

### Consumer-initiated pull + fulfillment push (the full loop)

The consumer can open its **own** request on the provider and then be **pushed** the fulfillment status when the
certificate is ready — at which point it pulls and accepts automatically.
`POST /management/v1/consumer/certificate-requests` is a management trigger; the request/poll it performs are CX-0135 §3.3.

```bash
B=http://localhost:8080

# Consumer opens a request for a not-yet-held certificate -> CERTIFICATION_REQUESTED
OPEN=$(curl -s -X POST $B/management/v1/consumer/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","certifiedLocations":["BPNS-PLANT-9"]}')
EX=$(echo "$OPEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

# Backend issues the certificate (upload document, then add the cert referencing it) -> the waiting
# exchange is FULFILLED and the push fires to the consumer
DOC=$(curl -s -X POST $B/management/v1/documents -H 'Content-Type: application/json' \
  -d '{"mediaType":"application/pdf","contentBase64":"<base64-pdf>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["documentId"])')
curl -s -X POST $B/management/v1/certificates -H 'Content-Type: application/json' -d "{
    \"certificateType\":\"ISO14001\", \"certificateTypeVersion\":\"2015\", \"registrationNumber\":\"DE-14001-99\",
    \"validFrom\":\"2026-01-01\", \"validUntil\":\"2029-01-01\", \"trustLevel\":\"high\",
    \"certifiedLocations\":[{\"bpnl\":\"BPNL00000000HOLDER\",\"bpna\":\"BPNA00000000MAIN0\",\"bpns\":\"BPNS-PLANT-9\",\"locationRole\":\"MAIN_LOCATION\"}],
    \"documentIds\":[\"$DOC\"]
  }"

curl -s $B/management/v1/consumer/certificate-requests/$EX            # consumer's tracked fulfillment -> FULFILLED
curl -s $B/certificate-acceptance-status/$EX            # consumer's decision -> ACCEPTED
curl -s $B/management/v1/certificate-exchanges/$EX                    # provider's view  -> FULFILLED / ACCEPTED

# (Equivalent to the push: the consumer can poll instead)
curl -s -X POST $B/management/v1/consumer/certificate-requests/$EX/poll
```

### Certificate lifecycle — revise / withdraw + publish (the consumer reacts)

**State** and **notification** are separate. A state change updates the artifact but tells no one:
`POST /management/v1/certificates/{id}/revisions` creates a **new version** (a revision carrying the
caller's issued validity + documents, lifecycle `CREATED → MODIFIED`); `POST /management/v1/certificates/{id}/withdraw`
revokes it (`WITHDRAWN`). To inform a consumer you then `publish` that lifecycle status to **one named target**
(`{"lifecycleStatus":"MODIFIED"|"WITHDRAWN"}`) — the client determines its own interest, so reaching several
consumers is several publishes.

```bash
B=http://localhost:8080; C=cert-iso14001-0001

curl -s -X POST $B/management/v1/certificates/$C/publish              # notify CREATED r1 (native consumer)
curl -s $B/management/v1/consumer/certificates/$C                     # -> CREATED, revision 1

# state: create a new version -> upload its document, then add the revision (no notification)
DOC=$(curl -s -X POST $B/management/v1/documents -H 'Content-Type: application/json' \
  -d '{"mediaType":"application/pdf","contentBase64":"<base64-pdf>"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["documentId"])')
curl -s -X POST $B/management/v1/certificates/$C/revisions -H 'Content-Type: application/json' \
  -d "{\"validFrom\":\"2026-01-01\",\"validUntil\":\"2029-01-01\",\"documentIds\":[\"$DOC\"]}"
curl -s -X POST $B/management/v1/certificates/$C/publish -H 'Content-Type: application/json' \
  -d '{"lifecycleStatus":"MODIFIED"}'                                 # notify MODIFIED to the native consumer
curl -s $B/management/v1/consumer/certificates/$C                     # -> MODIFIED, revision 2

curl -s -X POST $B/management/v1/certificates/$C/withdraw             # state: withdraw (no notification)
curl -s -X POST $B/management/v1/certificates/$C/publish -H 'Content-Type: application/json' \
  -d '{"lifecycleStatus":"WITHDRAWN"}'                                # notify WITHDRAWN
curl -s $B/certificates/$C                              # -> 200 {certificateId, status:"WITHDRAWN"}
curl -s $B/management/v1/consumer/certificates/$C                     # -> WITHDRAWN
```

**CloudEvents conformance.** Inbound events are validated against CX-0000 §2.1 — required
`specversion` (`"1.0"`), `type`, `source`, `id`, and the required `sourcebpn` extension; a malformed envelope is
rejected with `400`. Delivery is **idempotent**: a repeat of the same `source`+`id` is ignored. A **batch is atomic** —
every event is validated before any is applied, so one bad event in a batch leaves the rest unapplied (`400`). Search
rejects an unsupported field/operator with `501` and paginates via the RFC 8288 `Link` header.

**Acceptance evaluation:** on a `CREATED` lifecycle event (or a `FULFILLED` fulfillment status for its own request) the
consumer **pulls the certificate from the provider's data plane** — an OkHttp
`GET /certificates/{id}` (latest revision) for the metadata, then `GET /documents/{id}` for each referenced document,
against the configured `certo.provider-base-url` (default `http://localhost:8080`, i.e. this same runtime; no DSP
catalog/negotiation) — and concludes directly: `ACCEPTED` if a document is present and the certificate is within its
validity window, `REJECTED` ("Certificate has expired") if past
`validUntil`, or `ERRORED` if it can't be retrieved or has no document. Reporting the non-terminal
`RETRIEVED` status is **optional** (CX-0135 §2.1.3), so the consumer transitions straight from
`FULFILLED` to the terminal verdict — a single best-effort OkHttp
`POST /certificate-acceptance-notifications` carrying a `CertificateAcceptanceStatus` CloudEvent — closing the exchange
loop. Error entries MAY carry a per-site `specifier` (e.g. a BPNS).

## Project layout

```
src/main/java/org/metaform/certo
├── CertoApplication.java
├── common/                 # shared across both roles
│   ├── cloudevent/         # CloudEvent envelope, event-type constants, codec (single/batch)
│   ├── model/              # status enums, StatusError, event payloads, shared certificate records
│   │                       #   (CertificateRecord, CertifiedLocation, CertificateDocument, LocationRole, …)
│   ├── pdf/                # minimal PDF generator for document binaries
│   └── web/                # error handling, application/cloudevents+json media type
├── provider/               # Certificate Provider API (§3.3)
│   ├── api/                # controller + DTOs (CertificateQuery grammar, WithdrawnCertificate, …)
│   ├── client/             # Ccm300Notifier (OkHttp push to the consumer)
│   ├── model/ store/       # Certificate, CertificateRevision, Document, ProviderCertificateExchange,
│   │                       #   ProviderCertificateStore, ProviderDocumentStore, ProviderCertificateExchangeStore
│   ├── ProviderCertificateService.java
│   └── ProviderCertificateSeeder.java
└── consumer/               # Certificate Consumer API (§3.2)
    ├── api/                # controller + DTOs
    ├── client/             # OkHttp clients: Ccm300Requester (pull), Ccm300Retriever
    │                       #   (metadata + documents), Ccm300Reporter (callback)
    ├── model/ store/       # ConsumerCertificateExchange (both phases), KnownCertificate (lifecycle view)
    └── ConsumerCertificateService.java
```

Naming: side-specific stateful classes carry a `Provider*` / `Consumer*` prefix (`ProviderCertificateExchange` ↔
`ConsumerCertificateExchange`, `ProviderCertificateStore` ↔
`ConsumerCertificateStore`, …). Classes that work on both sides keep neutral names (the `common`
package, the CloudEvents types, the status enums, the shared certificate records). `Certificate` /
`CertificateRevision` / `Document` keep bare names — they're the provider's domain entities (the consumer's lifecycle
analog is the differently-named `KnownCertificate`). The OkHttp client names are **directional** (a consumer's
`Ccm300Retriever` calls the provider), so they're left as-is.

## Tests

```bash
./gradlew test
```

`ProviderCertificateApiTest` and `ConsumerCertificateApiTest` drive both APIs through MockMvc and a real running server,
covering request/decline, polling, JSON metadata retrieval (incl. a specific revision), the separate document API, the
search grammar (incl. unsupported-field `501` and pagination), withdrawn-status retrieval, acceptance recording (incl.
per-site `specifier` errors), lifecycle/fulfillment notifications (single and batch), and the 404/400 paths.

```
