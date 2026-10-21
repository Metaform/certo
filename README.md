![certo](/assets/certo-logo-tagline-dark.svg)

A Spring Boot implementation of **[Supply Chain Certificate Exchange (SCE)](docs/sce/certificate-exchange.md)**:
an interoperable protocol for exchanging company certificates between supply chain participants. A
certificate is **JSON metadata** that references one or more **document binaries** (e.g. a PDF), exchanged
between a **Certificate Provider** and a **Certificate Consumer**.

The app hosts **both roles' REST APIs in a single runtime** — one instance can act as a Certificate
Provider, a Certificate Consumer, or both at once:

| API                                                                    | Role                                                                                                               |
|------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| [Certificate Provider API](docs/sce/certificate.provider.api.yaml)     | Accepts certificate requests (exchanges), serves certificate metadata + document binaries, answers searches, receives acceptance feedback |
| [Certificate Consumer API](docs/sce/certificate.consumer.api.yaml)     | Receives lifecycle / fulfillment notifications, exposes acceptance status                                           |

> **Scope.** Only the **data plane** is implemented; how a flow (token + counterparty endpoint) is
> established is a control-plane concern outside this runtime. Persistence is Spring Data JPA — an
> embedded H2 database by default (resets on restart), Postgres under the `prod` profile. The `prod`
> profile **validates** the schema and creates nothing: a DBA applies
> [`db/postgres/schema.sql`](db/postgres/schema.sql) to a fresh database first. That script is generated
> from the entities and kept in sync by `PostgresSchemaTest`, which fails the build on drift — the diff it
> reports is what a DBA translates into an `ALTER` for live databases.
>
> **Pluggable protocols.** The core model is protocol-neutral; SCE is implemented as a protocol adapter
> under `protocol/sce`, registered under the open protocol id `sce-1.0`. Every exchange records the
> protocol its counterparty speaks, and outbound routing selects that protocol's adapter — adding a
> protocol is a new adapter package, not a change to the core.

The spec this implements is vendored under [`docs/sce/`](docs/sce). For a walkthrough of every supported
interaction with sequence diagrams, see [`docs/FLOWS.md`](docs/FLOWS.md).

## Stack

- **Java 25** (LTS) — the Gradle toolchain targets Java 25 and auto-provisions it (via the Foojay resolver)
  if it isn't installed locally
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
`./gradlew bootRun --args='--certo.seed-sample-data=true'`) to populate the search/retrieval endpoints with
sample data; the test suite enables it automatically. When enabled, the provider seeds these sample
certificates on startup (each revision references one generated PDF document):

| certificateId (fixed UUID)             | type      | revisions                 | validUntil (latest)  |
|----------------------------------------|-----------|---------------------------|----------------------|
| `00000000-0000-0000-0000-000000009001` | iso9001   | 0, 1 (AVAILABLE)          | 2027-01-24           |
| `00000000-0000-0000-0000-000000014001` | iso14001  | 0                         | 2027-05-31           |
| `00000000-0000-0000-0000-000000000001` | iatf16949 | 0                         | 2020-01-01 (expired) |

The set of certificate types is open, but type codes MUST be lowercase Latin letters and digits only
(`iso9001`) — a non-conformant spelling is rejected with `400` wherever a type enters the system. A
request for a type the provider doesn't yet hold waits for the backend, which may issue it or `decline`
it (the grounds travel in the `errors` array).

## Domain model

- A **Certificate Exchange** (`exchangeId`) is one end-to-end delivery interaction. It runs through a
  provider-owned **Fulfillment** phase (`RECEIVED → FULFILLED`, or `DECLINED`)
  and then a consumer-owned **Acceptance** phase (a terminal `ACCEPTED`/`REJECTED` reached directly from
  `FULFILLED`). Acceptance feedback is optional — an exchange without it rests at `FULFILLED`.
- A **Certificate Lifecycle** tracks the artifact itself (`AVAILABLE → WITHDRAWN`; revisions are not
  lifecycle transitions), keyed by
  `(certificateId, revision)`, independently of any exchange. Lifecycle events are purely informational —
  they never open, alter, or close an exchange.
- A **certificate** is metadata (`certifiedLocations[]` with `legalEntityId`/`siteId`/`addressId`,
  validity, issuer/validator, …) plus a `documents[]` array of references. Each **document** is opaque and
  **revision-independent** and is retrieved separately by id. Certificate records never embed document
  content.

The state machine is enforced: only legal transitions are allowed, terminal states are immutable, and
acceptance can only be reported once an exchange is `FULFILLED` (illegal attempts → `409 Conflict`; a
re-report of the current state is a duplicate → `204`). A request for a certificate the provider **already
holds** (covering every requested location filter) is fulfilled immediately (`FULFILLED`); otherwise the
exchange waits in `RECEIVED` for the certification-authority backend. Exchange creation is
**idempotent on (counterparty, `messageId`)**: a retried request repeats the pair and gets the
already-created exchange back — whatever state it is in — and a genuinely new request carries a new
`messageId`.

The certification-authority backend is driven through the management API: it uploads the issued document(s)
with `POST /management/v1/participant-contexts/{pc}/documents`, then `POST …/{pc}/certificates`
(referencing them by `documentIds`) issues the certificate; `…/certificate-requests/{id}/fulfill` fulfills
a waiting exchange, `…/decline` ends it in `DECLINED`.

## Certificate Provider API

```bash
B=http://localhost:8080

# Search with the SCE filter object (AND across supplied properties; {} matches all).
# Filters: certificateId, certificateType, certifiedLocations[{legalEntityId,siteId,addressId}].
# Response: { "result": [...], "cursor": "..." } — cursor present only when there is a next page.
curl -s -X POST "$B/search" -H 'Content-Type: application/json' \
  -d '{"certificateType":"iso9001"}'

# Create an exchange for a held certificate -> 201 Created, Location: /exchanges/{id},
# status FULFILLED with the full certificate record embedded (no separate retrieval needed)
curl -s -X POST $B/exchanges -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'","certificateType":"iso9001","certifiedLocations":[{"siteId":"SITE00000003AYRE"}]}'

# Request a not-yet-held certificate -> RECEIVED (waits for the backend)
curl -s -X POST $B/exchanges -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'","certificateType":"iso14001","certifiedLocations":[{"siteId":"SITE-NEW-PLANT"}]}'

# Backend issues the certificate: upload its document(s), then add the certificate referencing them.
# Adding the certificate is a STATE change only (it notifies no one). Every management call is scoped
# to the tenant in the path: PC=<participantContextId>.
M=$B/management/v1/participant-contexts/$PC
curl -s -X POST $M/documents -H 'Content-Type: application/json' \
  -d '{"mediaType":"application/pdf","contentBase64":"<base64-pdf>"}'          # -> 201 {documentId}
curl -s -X POST $M/certificates -H 'Content-Type: application/json' -d '{
    "certificateType":"iso14001", "certificateTypeVersion":"2015", "registrationNumber":"DE-14001-12345",
    "validFrom":"2026-01-01", "validUntil":"2029-01-01",
    "certifiedLocations":[{"legalEntityId":"LE00000000HOLDER","siteId":"SITE-NEW-PLANT","locationRole":"MAIN_LOCATION"}],
    "issuer":{"issuerName":"Acme Certification","issuerId":"LE00000000ISSUER"},
    "documentIds":["<documentId>"]
  }'   # -> 201 {certificateId, revision}
# Discover which waiting exchanges the new certificate covers, then fulfill each per-exchange (carrying
# that consumer's flowId, which resolves the token + endpoint).
curl -s $M/certificates/<certificateId>/fulfillable-requests   # -> {items:[{exchangeId, counterpartyId, ...}]}
curl -s -X POST "$M/certificate-requests/<exchangeId>/fulfill?flowId=<flowId>"
# ...or the backend cannot issue it -> decline (grounds in the errors array)
curl -s -X POST "$M/certificate-requests/<exchangeId>/decline?flowId=<flowId>&reason=..."

# The exchange's current state, covering BOTH phases (404 when unknown)
curl -s $B/exchanges/<exchangeId>

# Retrieve certificate metadata (latest revision, or pin one with ?revision=)
curl -s $B/certificates/00000000-0000-0000-0000-000000009001
curl -s "$B/certificates/00000000-0000-0000-0000-000000009001?revision=0"

# Retrieve a document binary by its opaque id (served with Content-Type = its mediaType)
curl -s "$B/documents/<documentId>" -o certificate.pdf

# Report acceptance as a CloudEvent (404 unknown exchange, 204 duplicate re-report, 409 invalid transition)
curl -s -X POST $B/notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.dsaf.sce.CertificateAcceptanceStatus.v1",
    "source":"did:web:consumer","id":"'$(uuidgen)'","time":"2026-05-04T08:00:00Z",
    "data":{"exchangeId":"<exchangeId>","status":"ACCEPTED"}}'
```

Retrieval is **two-step**: `GET /certificates/{id}` returns metadata listing the documents by reference;
the consumer then follows each `documents[].documentId` to `GET /documents/{id}` for the binary. A
**withdrawn** certificate need not stay retrievable — `GET /certificates/{id}` returns `200` with the
minimal `{certificateId, status: WITHDRAWN}` body.

## Certificate Consumer API

```bash
B=http://localhost:8080

# Lifecycle events are purely informational: they update the consumer's known-certificate view and are
# emitted to listeners; a consumer that wants the certificate opens an exchange by request.
curl -s -X POST $B/notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.dsaf.sce.CertificateLifecycleStatus.v1",
    "source":"did:web:provider","subject":"00000000-0000-0000-0000-000000009001","id":"'$(uuidgen)'",
    "time":"2026-05-04T07:00:00Z",
    "data":{"status":"AVAILABLE",
            "certificate":{"certificateId":"00000000-0000-0000-0000-000000009001","revision":1,"certificateType":"iso9001",
                           "validFrom":"2023-01-25","validUntil":"2027-01-24"}}}'

# A provider queries the consumer's acceptance decision (404 until one is reported)
curl -s $B/acceptance-status/<exchangeId>

# Fulfillment status push (counterpart of polling GET /exchanges/{id})
curl -s -X POST $B/notifications \
  -H 'Content-Type: application/cloudevents+json' -d '{
    "specversion":"1.0","type":"org.dsaf.sce.CertificateFulfillmentStatus.v1",
    "source":"did:web:provider","subject":"<exchangeId>","id":"'$(uuidgen)'","time":"2026-05-04T07:30:00Z",
    "data":{"exchangeId":"<exchangeId>","status":"FULFILLED",
            "certificate":{"certificateId":"00000000-0000-0000-0000-000000009001","revision":1,"certificateType":"iso9001",
                           "validFrom":"2024-01-24","validUntil":"2027-01-24"}}}'
```

`POST /notifications` is one endpoint serving both roles (the base URL is shared): lifecycle and
fulfillment events route to the consumer side, acceptance events to the provider side. It accepts a
**single CloudEvent or a batch** (a JSON array); a batch is accepted or rejected as a unit and must not mix
the two directions.

### Consumer-initiated request + fulfillment push (the full loop)

```bash
B=http://localhost:8080
CM=$B/management/v1/participant-contexts/$CPC/consumer   # CPC = the consumer tenant's participantContextId
PM=$B/management/v1/participant-contexts/$PPC            # PPC = the provider tenant's participantContextId

# Consumer opens a request for a not-yet-held certificate -> RECEIVED
OPEN=$(curl -s -X POST $CM/certificate-requests -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'","protocol":"sce-1.0","providerId":"did:web:provider",
       "certificateType":"iso14001","certifiedLocations":[{"siteId":"SITE-PLANT-9"}],"flowId":"<flowId>"}')
EX=$(echo "$OPEN" | python3 -c 'import sys,json;print(json.load(sys.stdin)["exchangeId"])')

# Backend issues the certificate (upload document, add the cert), then fulfills the waiting exchange —
# which fires the FULFILLED push to the consumer.
curl -s -X POST "$PM/certificate-requests/$EX/fulfill?flowId=<flowId>"

curl -s $CM/certificate-requests/$EX            # consumer's tracked fulfillment -> FULFILLED
curl -s $B/acceptance-status/$EX                # consumer's decision -> ACCEPTED (once driven, below)
curl -s $PM/certificate-exchanges/$EX           # provider's view  -> FULFILLED / ACCEPTED

# (Equivalent to the push: the consumer can poll instead)
curl -s -X POST "$CM/certificate-requests/$EX/poll?flowId=<flowId>"
```

### Certificate lifecycle — revise / withdraw + notify (single-target)

**State** and **notification** are separate. A state change updates the artifact but tells no one:
`POST …/{pc}/certificates/{id}/revisions` creates a **new version** (the lifecycle stays `AVAILABLE`);
`POST …/{pc}/certificates/{id}/withdraw` revokes it (`WITHDRAWN`). To inform a consumer you then `notify`
**one named target** — the event's status and revision are **derived from the certificate's current
state**, and the caller's `messageId` becomes the pushed event id verbatim (a retry reuses it; the consumer
dedups). Certo keeps no counterparty registry: reaching several consumers is several notifies, each with
its own synchronous outcome.

```bash
PM=$B/management/v1/participant-contexts/$PPC
C=00000000-0000-0000-0000-000000014001
TARGET='"protocol":"sce-1.0","consumerId":"did:web:consumer","flowId":"<flowId>"'

curl -s -X POST $PM/certificates/$C/notify -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'",'"$TARGET"'}'          # notify AVAILABLE r0
# state: new revision (no notification), then notify again -> AVAILABLE r1 is derived
curl -s -X POST $PM/certificates/$C/revisions -H 'Content-Type: application/json' -d '{...}'
curl -s -X POST $PM/certificates/$C/notify -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'",'"$TARGET"'}'
# state: withdraw, then notify -> WITHDRAWN (id-only payload) is derived
curl -s -X POST $PM/certificates/$C/withdraw
curl -s -X POST $PM/certificates/$C/notify -H 'Content-Type: application/json' \
  -d '{"messageId":"'$(uuidgen)'",'"$TARGET"'}'
```

**CloudEvents conformance.** Inbound events are validated against core messaging — required `specversion`
(`"1.0"`), `type`, `source`, `id`; a malformed envelope is rejected with `400`. The event `source` must
match the **authenticated sender** (the verified token subject), or the event is rejected. Delivery is
**idempotent**: a repeat of the same `source`+`id` is ignored. A **batch is atomic** — every event is
validated before any is applied, so one bad event leaves the rest unapplied (`400`). Search rejects an
unsupported filter property with `400` and paginates via the opaque body `cursor`.

## Multi-tenancy

Every certificate and every exchange belongs to a **participant context** (a tenant: its `did` — the
participant identity, used as token audience and event source — plus an optional `dataspaceIdentifier`
where the deployment's dataspace assigns one). There is **no config-derived default** — a deployment
creates its tenants through the management API, and the id never appears on the wire:

```bash
curl -s -X POST $B/management/v1/participant-contexts -H 'Content-Type: application/json' \
  -d '{"dataspaceIdentifier":"PRT000000000AAA","did":"did:web:tenant-a"}'
# -> {"participantContextId":"<uuid>","dataspaceIdentifier":…,"did":…}

# Or choose the id (idempotent, externally-owned key):
curl -s -X POST $B/management/v1/participant-contexts -H 'Content-Type: application/json' \
  -d '{"participantContextId":"tenant-a","dataspaceIdentifier":"PRT000000000AAA","did":"did:web:tenant-a"}'
```

Everything is tenant-scoped, no exceptions. A management call names the tenant it acts for; an inbound
protocol call is scoped to the tenant its **token audience** (`aud` = a tenant DID) resolves to: a request
fulfils only from that tenant's holdings, a retrieval or search never crosses the tenant boundary.

## Security & the consumer extension point

Security tokens on the protocol layer are **always on** and always come from a
**[Siglet](https://github.com/eclipse-dataplane-core/dsdk-facet-rs)** STS; the
management API is OAuth2-protected, never Siglet-secured. A deployment must point at a Siglet —
`certo.security.siglet-base-url` is required (dev/test point at a mock Siglet).

- **Inbound** protocol calls must present a `Bearer` token; it is verified by calling Siglet's
  revocation-aware verification endpoint `POST /tokens/verify`. The subject (`sub`, the caller's **DID**)
  is the one identity used for all correlation — it becomes the exchange's counterparty. An optional
  `dataspaceidentifier` claim carries the dataspace-assigned id where one exists. The token **audience**
  (a tenant DID) resolves to the receiving tenant's participant context.
- **Outbound** calls are made on behalf of the sender's participant context, addressed to the counterparty
  (its DID). The token **and the counterparty endpoint** come from the Siglet cache
  (`GET /tokens/{participantContextId}/{flowId}`) — there is no configured-URL fallback. Outbound HTTP runs
  through a **retrying client** (Failsafe) that retries a transient `IOException`/`5xx` with backoff; every
  outbound call is idempotent, so retries are safe.
- `flowId` is **ephemeral**: supplied fresh on each management request that triggers an outbound call,
  never persisted. The **wire protocol** of a counterparty is explicit everywhere: management requests name
  it (`protocol`, e.g. `sce-1.0`), inbound adapters stamp it on the exchanges they open, and there is no
  default.

Inbound consumer notifications are **recorded, then emitted** to `InboundNotificationListener` beans (a
neutral `InboundNotification`, fire-and-forget). The consumer never decides acceptance itself: a client
drives the consumer management API on its own timeline, supplying its live `flowId`:

```bash
CM=$B/management/v1/participant-contexts/$CPC/consumer
curl -s -X POST "$CM/exchanges/$EX/retrieve?flowId=<flowId>"   # -> {certificate, documents[]} (pinned to the exchange's revision)
curl -s -X POST $CM/exchanges/$EX/accept -H 'Content-Type: application/json' \
  -d '{"status":"ACCEPTED","flowId":"<flowId>"}'
# Reconcile after a dropped callback or a lost acceptance report:
curl -s -X POST $CM/exchanges/query -H 'Content-Type: application/json' -d '{"awaitingAcceptanceOnly":true}'
```

## Operational endpoints

Public, token-free endpoints (not protocol paths, so the Siglet interceptor never applies) for container
probes and inspection — they expose no tenant data:

| Endpoint         | Purpose                                                                                                                         |
|------------------|---------------------------------------------------------------------------------------------------------------------------------|
| `GET /health`    | **Liveness** — the process is up. Dependency-free by design; wire the k8s `livenessProbe` here.                                 |
| `GET /readiness` | **Readiness** — up **and** the database is reachable; `200 {status:UP}` or `503 {status:DOWN}`. Wire the `readinessProbe` here. |
| `GET /info`      | Static service descriptor (`{name, description}`).                                                                              |

## Tests

```bash
./gradlew test
```

`ProviderCertificateApiTest` and `ConsumerCertificateApiTest` drive both APIs through MockMvc and a real
running server.
