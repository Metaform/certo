# Certo

A Spring Boot demonstration of the **Catena-X CX-0135 Company Certificate Management (CCM)**
data-plane wire protocol (**v3.0.0**). A certificate is **JSON metadata** that references one or more
**document binaries** (e.g. a PDF), exchanged between a **Certificate Provider** and a **Certificate
Consumer**.

The app hosts **two REST APIs in a single runtime** (a demo convenience — in production each role is a
separate participant):

| API | Spec | Role |
|-----|------|------|
| [Certificate Provider API](docs/ccm/certificate-provider-api.yaml) | CX-0135 §3.3 | Accepts requests, serves certificate metadata + document binaries, answers searches, receives acceptance feedback |
| [Certificate Consumer API](docs/ccm/certificate-consumer-api.yaml) | CX-0135 §3.2 | Receives lifecycle / fulfillment notifications, exposes acceptance status |

> **Scope.** Only the **data plane** is implemented. The Dataspace Protocol (DSP) control plane —
> catalog, contract negotiation and the token-refresh authorization of CX-0000 §4 — is **out of
> scope**. Storage is in-memory and resets on restart.
>
> **v3 exception.** Certo follows the **push-pull** mechanism (v2→v3 migration Option 2): a push
> notification carries only the certificate id/light-triage subset and the consumer pulls the rest. The
> **embedded-document** push option (`…ConsumerEmbeddedDocumentApi`, `documents[].contentBase64` inline)
> is intentionally **not** implemented.

The spec this implements is vendored under [`docs/ccm/`](docs/ccm). For a walkthrough of every
supported interaction with sequence diagrams, see [`docs/FLOWS.md`](docs/FLOWS.md).

## Stack

- **Java 25** (LTS) — the Gradle toolchain targets Java 25 and auto-provisions it (via the Foojay
  resolver) if it isn't installed locally
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

On startup the provider seeds demo certificates (each revision references one PDF document):

| certificateId | type | revisions | validUntil (latest) |
|---------------|------|-----------|---------------------|
| `cert-iso9001-0001` | ISO9001 | 1, 2 (CREATED → MODIFIED) | 2027-01-24 |
| `cert-iso14001-0001` | ISO14001 | 1 | 2027-05-31 |
| `cert-expired-0001` | IATF16949 | 1 | 2020-01-01 (expired) |

The provider offers the types `ISO9001`, `ISO14001`, `IATF16949`; any other requested type is
`DECLINED`.

## Domain model (CX-0135 §2)

- A **Certificate Exchange** (`exchangeId`) is one end-to-end delivery interaction. It runs through a
  provider-owned **Fulfillment** phase (`ACKNOWLEDGED → CERTIFICATION_REQUESTED → FULFILLED`, or
  `DECLINED`/`FAILED`) and then a consumer-owned **Acceptance** phase (a terminal
  `ACCEPTED`/`REJECTED`/`ERRORED` reached directly from `FULFILLED`, or via the optional non-terminal
  `RETRIEVED` receipt).
- A **Certificate Lifecycle** tracks the artifact itself (`CREATED → MODIFIED* → WITHDRAWN`), keyed by
  `(certificateId, revision)`, independently of any exchange.
- A **certificate** is metadata (`certifiedLocations[]`, validity, `trustLevel`, issuer/validator, …)
  plus a `documents[]` array of references. Each **document** is opaque and **revision-independent**
  (one document may be shared across revisions) and is retrieved separately by id.

The state machine is enforced: only legal transitions are allowed, terminal states are immutable, and
acceptance can only be reported once an exchange is `FULFILLED` (illegal attempts → `409 Conflict`).
A request for a certificate the provider **already holds** (covering the requested
`certifiedLocationBpns`) is fulfilled immediately (`FULFILLED`); otherwise the request is
`ACKNOWLEDGED` and fulfilled asynchronously — `certificateId`/`revision` are allocated up front, but
the certificate is published (and becomes retrievable) only when the exchange reaches `FULFILLED`. Each
request opens a **new** exchange, so a re-attempt after a terminal outcome is a distinct exchange.

For the demo, the provider's asynchronous fulfillment backend is driven by an explicit
`POST /certificate-requests/{id}/advance` trigger (one step per call) rather than a timer.

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
  -d '{"certificateType":"ISO9001","certifiedLocationBpns":["BPNS00000003AYRE"]}'

# Request a not-yet-held certificate -> ACKNOWLEDGED; advance asynchronous fulfillment (demo trigger)
curl -s -X POST $B/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","certifiedLocationBpns":["BPNS-NEW-PLANT"]}'   # -> ACKNOWLEDGED
curl -s -X POST $B/certificate-requests/<exchangeId>/advance              # -> CERTIFICATION_REQUESTED
curl -s -X POST $B/certificate-requests/<exchangeId>/advance              # -> FULFILLED (cert published)

# Poll fulfillment status
curl -s $B/certificate-requests/<exchangeId>

# Retrieve certificate metadata as JSON (latest revision, or a specific ?revision=)
curl -s $B/certificates/cert-iso9001-0001                # full metadata + documents[] references
curl -s "$B/certificates/cert-iso9001-0001?revision=1"   # a specific revision

# Retrieve a document binary by its opaque id (served with Content-Type = its mediaType)
curl -s "$B/documents/<documentId>" -o certificate.pdf

# Report acceptance outcome as a CloudEvent (404 if the exchangeId is unknown)
curl -s -X POST $B/certificate-acceptance-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateAcceptanceStatus.v1",
    "source":"urn:bpn:BPNL0000000002CD","sourcebpn":"BPNL0000000002CD","id":"evt-1","time":"2025-05-04T08:00:00Z",
    "data":{"exchangeId":"<exchangeId>","certificateId":"cert-iso9001-0001","status":"ACCEPTED"}}'
```

Retrieval is **two-step**: `GET /certificates/{id}` returns metadata listing the documents by
reference; the consumer then follows each `documents[].documentId` to `GET /documents/{id}` for the
binary. A **withdrawn** certificate need not stay retrievable — `GET /certificates/{id}` returns
`200` with the minimal `{certificateId, status: WITHDRAWN}` body (CX-0135 §3.3.2).

### Provider-initiated push — the full loop in one call (demo)

`POST /certificates/{id}/publish` opens an exchange and pushes a lifecycle `CREATED` event to the
consumer, which pulls the certificate + its documents, evaluates them, and posts its acceptance **back**
to the provider — all in this one runtime. `GET /certificate-exchanges/{id}` is a demo/inspection
endpoint (not in CX-0135) showing the provider's recorded view of both phases.

```bash
B=http://localhost:8080

PUB=$(curl -s -X POST $B/certificates/cert-iso9001-0001/publish)   # 202: {exchangeId, revision, consumerNotified:true}
EXCH=$(echo "$PUB" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

curl -s $B/certificate-acceptance-status/$EXCH   # consumer side  -> status ACCEPTED
curl -s $B/certificate-exchanges/$EXCH           # provider side  -> fulfillmentStatus FULFILLED, acceptanceStatus ACCEPTED
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
    "data":{"status":"CREATED","exchangeId":"exch-demo-1",
            "certificate":{"certificateId":"cert-iso9001-0001","revision":2,"certificateType":"ISO9001",
                           "validFrom":"2023-01-25","validUntil":"2027-01-24"}}}'

# A provider queries the consumer's acceptance decision (404 if unknown)
curl -s $B/certificate-acceptance-status/exch-demo-1

# Fulfillment status push (counterpart of polling GET /certificate-requests/{id})
curl -s -X POST $B/certificate-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateFulfillmentStatus.v1",
    "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","id":"evt-2","time":"2025-05-04T07:30:00Z",
    "data":{"exchangeId":"exch-demo-1","certificateId":"cert-iso9001-0001","status":"FULFILLED"}}'
```

Both notification endpoints accept a **single CloudEvent or a batch** (a JSON array), per CX-0000 §4.

### Consumer-initiated pull + fulfillment push (the full loop)

The consumer can open its **own** request on the provider and then be **pushed** the fulfillment status
when the certificate is ready — at which point it pulls and accepts automatically.
`POST /consumer/certificate-requests` is a demo trigger; the request/poll it performs are CX-0135 §3.3.

```bash
B=http://localhost:8080

# Consumer opens a request for a not-yet-held certificate -> ACKNOWLEDGED
OPEN=$(curl -s -X POST $B/consumer/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","certifiedLocationBpns":["BPNS-PLANT-9"]}')
EX=$(echo "$OPEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

curl -s -X POST $B/certificate-requests/$EX/advance     # -> CERTIFICATION_REQUESTED
curl -s -X POST $B/certificate-requests/$EX/advance     # -> FULFILLED  (push fires)

curl -s $B/consumer/certificate-requests/$EX            # consumer's tracked fulfillment -> FULFILLED
curl -s $B/certificate-acceptance-status/$EX            # consumer's decision -> ACCEPTED
curl -s $B/certificate-exchanges/$EX                    # provider's view  -> FULFILLED / ACCEPTED

# (Equivalent to the push: the consumer can poll instead)
curl -s -X POST $B/consumer/certificate-requests/$EX/poll
```

### Certificate lifecycle — modify / withdraw (the consumer reacts)

`POST /certificates/{id}/modify` publishes a new revision (lifecycle `CREATED → MODIFIED`);
`POST /certificates/{id}/withdraw` revokes it (`WITHDRAWN`). Both push a lifecycle event to the
consumer, which keeps its `GET /consumer/certificates/{id}` view in sync (demo triggers; the lifecycle
model is CX-0135 §2.2).

```bash
B=http://localhost:8080; C=cert-iso14001-0001

curl -s -X POST $B/certificates/$C/publish              # consumer learns CREATED r1
curl -s $B/consumer/certificates/$C                     # -> CREATED, revision 1

curl -s -X POST $B/certificates/$C/modify               # -> MODIFIED, revision 2 (pushed)
curl -s $B/consumer/certificates/$C                     # -> MODIFIED, revision 2

curl -s -X POST $B/certificates/$C/withdraw             # -> WITHDRAWN (pushed)
curl -s $B/certificates/$C                              # -> 200 {certificateId, status:"WITHDRAWN"}
curl -s $B/consumer/certificates/$C                     # -> WITHDRAWN
```

**CloudEvents conformance.** Inbound events are validated against CX-0000 §2.1 — required
`specversion` (`"1.0"`), `type`, `source`, `id`, and the required `sourcebpn` extension; a malformed
envelope is rejected with `400`. Delivery is **idempotent**: a repeat of the same `source`+`id` is
ignored. A **batch is atomic** — every event is validated before any is applied, so one bad event in a
batch leaves the rest unapplied (`400`). Search rejects an unsupported field/operator with `501` and
paginates via the RFC 8288 `Link` header.

**Acceptance evaluation:** on a `CREATED` lifecycle event (or a `FULFILLED` fulfillment status for its
own request) the consumer **pulls the certificate from the provider's data plane** — an OkHttp
`GET /certificates/{id}?revision=` for the metadata, then `GET /documents/{id}` for each referenced
document, against the configured `certo.provider-base-url` (default `http://localhost:8080`, i.e. this
same runtime; no DSP catalog/negotiation) — and concludes directly: `ACCEPTED` if a document is present
and the certificate is within its validity window, `REJECTED` ("Certificate has expired") if past
`validUntil`, or `ERRORED` if it can't be retrieved or has no document. Reporting the non-terminal
`RETRIEVED` status is **optional** (CX-0135 §2.1.3), so the consumer transitions straight from
`FULFILLED` to the terminal verdict — a single best-effort OkHttp
`POST /certificate-acceptance-notifications` carrying a `CertificateAcceptanceStatus` CloudEvent —
closing the exchange loop. Error entries MAY carry a per-site `specifier` (e.g. a BPNS).

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
│   ├── client/             # ConsumerNotificationClient (OkHttp push to the consumer)
│   ├── model/ store/       # Certificate, CertificateRevision, Document, ProviderCertificateExchange,
│   │                       #   ProviderCertificateStore, ProviderDocumentStore, ProviderCertificateExchangeStore
│   ├── ProviderCertificateService.java
│   └── ProviderCertificateSeeder.java
└── consumer/               # Certificate Consumer API (§3.2)
    ├── api/                # controller + DTOs
    ├── client/             # OkHttp clients: ProviderRequestClient (pull), ProviderCertificateClient
    │                       #   (metadata + documents), ProviderAcceptanceClient (callback)
    ├── model/ store/       # ConsumerCertificateExchange (both phases), KnownCertificate (lifecycle view)
    └── ConsumerCertificateService.java
```

Naming: side-specific stateful classes carry a `Provider*` / `Consumer*` prefix
(`ProviderCertificateExchange` ↔ `ConsumerCertificateExchange`, `ProviderCertificateStore` ↔
`ConsumerCertificateStore`, …). Classes that work on both sides keep neutral names (the `common`
package, the CloudEvents types, the status enums, the shared certificate records). `Certificate` /
`CertificateRevision` / `Document` keep bare names — they're the provider's domain entities (the
consumer's lifecycle analog is the differently-named `KnownCertificate`). The OkHttp client names are
**directional** (a consumer's `ProviderCertificateClient` calls the provider), so they're left as-is.

## Tests

```bash
./gradlew test
```

`ProviderCertificateApiTest` and `ConsumerCertificateApiTest` drive both APIs through MockMvc and a
real running server, covering request/decline, polling, JSON metadata retrieval (incl. a specific
revision), the separate document API, the search grammar (incl. unsupported-field `501` and
pagination), withdrawn-status retrieval, acceptance recording (incl. per-site `specifier` errors),
lifecycle/fulfillment notifications (single and batch), and the 404/400 paths.
```
