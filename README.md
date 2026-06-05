# Certo

A Spring Boot demonstration of the **Catena-X CX-0135 Company Certificate Management (CCM)**
data-plane wire protocol. Certificates are PDF documents exchanged between a **Certificate Provider**
and a **Certificate Consumer**.

The app hosts **two REST APIs in a single runtime** (a demo convenience — in production each role is a
separate participant):

| API | Spec | Role |
|-----|------|------|
| [Certificate Provider API](docs/ccm/certificate-provider-api.yaml) | CX-0135 §4.4 | Accepts requests, serves certificate data, answers queries, receives acceptance feedback |
| [Certificate Consumer Notification API](docs/ccm/certificate-consumer-notification-api.yaml) | CX-0135 §4.3 | Receives lifecycle / fulfillment notifications, exposes acceptance status |

> **Scope.** Only the **data plane** is implemented. The Dataspace Protocol (DSP) control plane —
> catalog, contract negotiation and the token-refresh authorization of CX-0000 §4 — is **out of
> scope**. Storage is in-memory and resets on restart.

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

On startup the provider seeds demo certificates:

| certificateId | type | versions | validUntil (latest) |
|---------------|------|----------|---------------------|
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
  `(certificateId, version)`, independently of any exchange.

The state machine is enforced: only legal transitions are allowed, terminal states are immutable, and
acceptance can only be reported once an exchange is `FULFILLED` (illegal attempts → `409 Conflict`).
A request for a certificate the provider **already holds** (covering the requested `locationBpns`) is
fulfilled immediately (`FULFILLED`); otherwise the request is `ACKNOWLEDGED` and fulfilled
asynchronously — `certificateId`/`version` are allocated up front, but the certificate is published
(and becomes retrievable) only when the exchange reaches `FULFILLED`. Each request opens a **new**
exchange, so a re-attempt after a terminal outcome is a distinct exchange.

For the demo, the provider's asynchronous fulfillment backend is driven by an explicit
`POST /certificate-requests/{id}/advance` trigger (one step per call) rather than a timer.

## Certificate Provider API (CX-0135 §4.4)

```bash
B=http://localhost:8080

# Query certificates by type (cursor pagination via the RFC 8288 Link header when paginated)
curl -s -X POST $B/certificates/query -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO9001"}'

# Request a held certificate -> FULFILLED immediately (HTTP 202)
curl -s -X POST $B/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO9001","locationBpns":["BPNS00000003AYRE"]}'

# Request a not-yet-held certificate -> ACKNOWLEDGED; advance asynchronous fulfillment (demo trigger)
curl -s -X POST $B/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","locationBpns":["BPNS-NEW-PLANT"]}'   # -> status ACKNOWLEDGED
curl -s -X POST $B/certificate-requests/<exchangeId>/advance              # -> CERTIFICATION_REQUESTED
curl -s -X POST $B/certificate-requests/<exchangeId>/advance              # -> FULFILLED (cert published)

# Poll fulfillment status
curl -s $B/certificate-requests/<exchangeId>

# Retrieve the certificate: multipart/related (application/json metadata + application/pdf binary)
curl -s $B/certificates/cert-iso9001-0001            # latest version
curl -s "$B/certificates/cert-iso9001-0001?version=1"  # a specific version

# Report acceptance outcome as a CloudEvent (404 if the exchangeId is unknown)
curl -s -X POST $B/certificate-acceptance-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateAcceptanceStatus.v1",
    "source":"urn:bpn:BPNL0000000002CD","sourcebpn":"BPNL0000000002CD","id":"evt-1","time":"2025-05-04T08:00:00Z",
    "data":{"exchangeId":"<exchangeId>","certificateId":"cert-iso9001-0001","status":"ACCEPTED"}}'
```

### Provider-initiated push — the full loop in one call (demo)

`POST /certificates/{id}/publish` opens an exchange and pushes a lifecycle `CREATED` event to the
consumer, which retrieves the certificate, evaluates it, and posts its acceptance **back** to the
provider — all in this one runtime. `GET /certificate-exchanges/{id}` is a demo/inspection endpoint
(not in CX-0135) showing the provider's recorded view of both phases.

```bash
B=http://localhost:8080

# Provider publishes a held certificate; the whole exchange runs synchronously
PUB=$(curl -s -X POST $B/certificates/cert-iso9001-0001/publish)   # 202: {exchangeId, version, consumerNotified:true}
EXCH=$(echo "$PUB" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

curl -s $B/certificate-acceptance-status/$EXCH   # consumer side  -> status ACCEPTED
curl -s $B/certificate-exchanges/$EXCH           # provider side  -> fulfillmentStatus FULFILLED, acceptanceStatus ACCEPTED
```

## Certificate Consumer Notification API (CX-0135 §4.3)

```bash
B=http://localhost:8080

# Lifecycle CREATED event -> opens a (provider-initiated) exchange; the consumer evaluates it
curl -s -X POST $B/certificate-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateLifecycleStatus.v1",
    "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","subject":"BPNL0000000002CD","id":"evt-1",
    "time":"2025-05-04T07:00:00Z",
    "data":{"exchangeId":"exch-demo-1","certificateId":"cert-550e8400","version":1,
            "status":"CREATED","datasetId":"dataset-ccm-cert-abc123","certificateType":"ISO9001",
            "validFrom":"2023-01-25","validUntil":"2099-01-24"}}'

# A provider queries the consumer's acceptance decision (404 if unknown)
curl -s $B/certificate-acceptance-status/exch-demo-1

# Fulfillment status push (counterpart of polling GET /certificate-requests/{id})
curl -s -X POST $B/certificate-notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.catena-x.ccm.CertificateFulfillmentStatus.v1",
    "source":"urn:bpn:BPNL0000000001AB","sourcebpn":"BPNL0000000001AB","id":"evt-2","time":"2025-05-04T07:30:00Z",
    "data":{"exchangeId":"exch-demo-1","certificateId":"cert-550e8400","status":"FULFILLED"}}'
```

Both notification endpoints accept a **single CloudEvent or a batch** (a JSON array), per CX-0000 §4.

### Consumer-initiated pull + fulfillment push (the full loop)

The consumer can open its **own** request on the provider and then be **pushed** the fulfillment status
when the certificate is ready — at which point it retrieves and accepts automatically.
`POST /consumer/certificate-requests` is a demo trigger; the request/poll it performs are CX-0135
§4.4.1/§4.4.2.

```bash
B=http://localhost:8080

# Consumer opens a request for a not-yet-held certificate -> ACKNOWLEDGED
OPEN=$(curl -s -X POST $B/consumer/certificate-requests -H 'Content-Type: application/json' \
  -d '{"certificateType":"ISO14001","locationBpns":["BPNS-PLANT-9"]}')
EX=$(echo "$OPEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

# Provider fulfils; reaching FULFILLED pushes a CertificateFulfillmentStatus to the consumer,
# which then retrieves + accepts automatically
curl -s -X POST $B/certificate-requests/$EX/advance     # -> CERTIFICATION_REQUESTED
curl -s -X POST $B/certificate-requests/$EX/advance     # -> FULFILLED  (push fires)

curl -s $B/consumer/certificate-requests/$EX            # consumer's tracked fulfillment -> FULFILLED
curl -s $B/certificate-acceptance-status/$EX            # consumer's decision -> ACCEPTED
curl -s $B/certificate-exchanges/$EX                    # provider's view  -> FULFILLED / ACCEPTED

# (Equivalent to the push: the consumer can poll instead)
curl -s -X POST $B/consumer/certificate-requests/$EX/poll
```

### Certificate lifecycle — modify / withdraw (the consumer reacts)

`POST /certificates/{id}/modify` publishes a new version (lifecycle `CREATED → MODIFIED`);
`POST /certificates/{id}/withdraw` removes it (`WITHDRAWN`, now unretrievable). Both push a lifecycle
event to the consumer, which keeps its `GET /consumer/certificates/{id}` view in sync (demo triggers;
the lifecycle model is CX-0135 §2.2).

```bash
B=http://localhost:8080; C=cert-iso14001-0001

curl -s -X POST $B/certificates/$C/publish              # consumer learns CREATED v1
curl -s $B/consumer/certificates/$C                     # -> CREATED, version 1

curl -s -X POST $B/certificates/$C/modify               # -> MODIFIED, version 2 (pushed)
curl -s $B/consumer/certificates/$C                     # -> MODIFIED, version 2

curl -s -X POST $B/certificates/$C/withdraw             # -> WITHDRAWN (pushed)
curl -s -o /dev/null -w '%{http_code}\n' $B/certificates/$C   # -> 404 (withdrawn)
curl -s $B/consumer/certificates/$C                     # -> WITHDRAWN
```

**CloudEvents conformance.** Inbound events are validated against CX-0000 §2.1 — required
`specversion` (`"1.0"`), `type`, `source`, `id`, and the required `sourcebpn` extension; a malformed
envelope is rejected with `400`. Delivery is **idempotent**: a repeat of the same `source`+`id` is
ignored. A **batch is atomic** — every event is validated before any is applied, so one bad event in a
batch leaves the rest unapplied (`400`). Certificate retrieval honours the `Accept` header (returns
`406` if `multipart/related` isn't acceptable) and labels its parts with RFC 2387 `Content-ID` +
`start`.

**Acceptance evaluation:** on a `CREATED` lifecycle event the consumer **retrieves the certificate
from the provider's data plane** — an OkHttp `GET /certificates/{id}?version=` against the configured
`certo.provider-base-url` (default `http://localhost:8080`, i.e. this same runtime; no DSP
catalog/negotiation) — parses the `multipart/related` metadata + PDF and concludes directly:
`ACCEPTED` if within its validity window, `REJECTED` ("Certificate has expired") if past `validUntil`,
or `ERRORED` if it can't be retrieved or has no document. Reporting the non-terminal `RETRIEVED` status
is **optional** (CX-0135 §2.1.3), so the consumer transitions straight from `FULFILLED` to the terminal
verdict — a single best-effort OkHttp `POST /certificate-acceptance-notifications` carrying a
`CertificateAcceptanceStatus` CloudEvent — closing the exchange loop. A fuller implementation might run
the validation asynchronously and emit an interim `RETRIEVED` receipt first.

## Project layout

```
src/main/java/org/metaform/certo
├── CertoApplication.java
├── common/                 # shared across both roles
│   ├── cloudevent/         # CloudEvent envelope, event-type constants, codec (single/batch)
│   ├── model/              # status enums, StatusError, event data payloads
│   ├── pdf/                # minimal PDF generator for the certificate binary
│   └── web/                # error handling, application/cloudevents+json media type
├── provider/               # Certificate Provider API (§4.4)
│   ├── api/                # controller + DTOs
│   ├── client/             # ConsumerNotificationClient (OkHttp push to the consumer)
│   ├── model/ store/       # Certificate(Version), ProviderCertificateExchange, ProviderCertificate*Store
│   ├── ProviderCertificateService.java
│   └── ProviderCertificateSeeder.java
└── consumer/               # Certificate Consumer Notification API (§4.3)
    ├── api/                # controller + DTO
    ├── client/             # OkHttp clients: ProviderRequestClient (pull), ProviderCertificateClient (retrieve), ProviderAcceptanceClient (callback)
    ├── model/ store/       # ConsumerCertificateExchange (both phases, like the provider), KnownCertificate (lifecycle)
    └── ConsumerCertificateService.java
```

Naming: side-specific stateful classes carry a `Provider*` / `Consumer*` prefix
(`ProviderCertificateExchange` ↔ `ConsumerCertificateExchange`, `ProviderCertificateStore` ↔
`ConsumerCertificateStore`, …). Classes that work on both sides keep neutral names (the `common`
package, the CloudEvents types, the status enums). `Certificate`/`CertificateVersion` keep their bare
names — they're the provider's domain entity (the consumer's analog is the differently-named
`KnownCertificate`), and "Certificate" is pervasive CX-0135 terminology. The OkHttp client names are
**directional** (a consumer's `ProviderCertificateClient` calls the provider), so they're left as-is.

## Tests

```bash
./gradlew test
```

`CertificateProviderApiTest` and `CertificateConsumerApiTest` drive both APIs through MockMvc,
covering request/decline, polling, multipart retrieval (incl. a specific version), queries,
acceptance recording, lifecycle/fulfillment notifications (single and batch), and the 404/400 paths.
