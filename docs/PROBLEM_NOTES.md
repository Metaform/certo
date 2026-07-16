# Problem Notes

Known problems, gaps, deliberate divergences, and caveats in this implementation — the things a reader should
not mistake for "done" just because the build is green. Grouped by kind. See also the migration
analysis under `certo-spec/migration/` (`certo-conformance-gaps.md`, `adapter-architecture.md`).

Status legend: **Blocked** (waits on something external) · **Parked** (deliberately deferred) ·
**By design** (intentional) · **Open** (should be fixed) · **Mitigated** (partial fix in place).

---

## 1. v3 (CX-0135 3.0.0) conformance gaps

### 1.1 The certificate data model is not real 4.0.0 — **Blocked**
`CertificateRecord` (and `CertifiedLocation`, `CertificateIssuer`, `CertificateDocument`, …) is an
*assumed* BusinessPartnerCertificate 4.0.0. It diverges from the 26.09 alignment examples: flat
`certificateType` vs nested `type{}`, `issuerBpn` vs `issuerBpnl`, no `uploader`, `documents` vs
`document`, `createdDate` vs `createdAt`, `mediaType` vs `contentType`, cert-level `areaOfApplication`
vs per-location, and an enum `locationRole` vs a free-string. **And 4.0.0 is not published** in
`eclipse-tractusx/sldt-semantic-models` (latest is 3.1.0), so it cannot be aligned yet. Every push/pull
therefore carries a non-conformant certificate body. *Impact: largest single v3 non-compliance.*

### 1.2 Search / discovery is not the AAS profile — **Parked**
The spec rebased `POST /certificates/search` on the CX-0002 AAS Registry (SSP-004 query returning shell
descriptors with CX-0018 hrefs). certo keeps a homegrown `$field/$eq` grammar with an RFC 8288 `Link`
header and a `501` for unsupported fields. Parked because the AAS query endpoint is slated for
replacement. *Impact: no spec-conformant "discover by criteria then pull"; a consumer can only retrieve
a known `certificateId` or receive a push.*

### 1.3 Lifecycle `certificate` oneOf deferred (A3) — **Parked**
The alignment made `data.certificate` a `oneOf(full BPC-4.0.0 record, {certificateId})`; certo still
sends the older light-triage subset on `CREATED`/`MODIFIED`. It still validates against the loose
`{certificateId}` branch, so it is not a hard break, but it is not the exact shape the examples show.

### 1.4 `sourcebpn` required; withdrawn-retrieval is a documented extension — **Resolved**
**`sourcebpn`:** CX-0000 §2.1.2 lists it as a **Required Extension Attribute**, so certo is correct to
require it. The alignment CX-0135 CloudEvent schemas omit it only because CX-0000 governs it. No change.
**Withdrawn retrieval:** the alignment §3.2.2 says `GET /certificates/{id}` returns the §4.1 submodel and
is **silent** on withdrawn certificates — it dropped the earlier `WithdrawnCertificate` body + `404`.
certo still returns a minimal `{certificateId, status: WITHDRAWN}` body so a holder can observe the
withdrawal; this is now treated as an **intentional non-spec extension** (a withdrawn certificate is "no
longer available," so a strict reading would instead return the §4.1 submodel or a `404`/`410`).

---

## 2. Deliberate divergences (decisions)

### 2.1 Acceptance stays keyed on `exchangeId` — **By design**
The 26.09 alignment re-keyed the acceptance notification to `(certificateId, revision)`; we kept
`exchangeId` (the key every other exchange message uses), because the re-key breaks exchange symmetry
and is ambiguous when several exchanges share a `(certificateId, revision)`. Documented in
`certo-spec/migration/certo-conformance-gaps.md`. *Impact: intentional non-compliance with 26.09 on this
one field; reconcile upstream or record as a permanent deviation.*

### 2.2 Always latest revision — **By design**
`GET /certificates/{id}` has no `?revision=`; retrieval is always the latest revision. Matches the
project decision and simplifies the 2.4.0 bridge (which has no revision concept).

---

## 3. Out-of-scope boundaries (affect both APIs)

### 3.1 No DSP control plane — **By design (out of scope)**
No catalog, no contract negotiation. Flows are established by an external control plane; Certo consumes
them. The counterparty URL and token always come from the siglet cache (keyed by the ephemeral `flowId`),
but the *flow* that provisions them is the control plane's job — in particular, an unsolicited provider push
has no consumer→provider return flow unless the control plane creates one. *Impact: Certo is a data plane;
the dataspace catalog/negotiation/transfer layer is external by design.*

### 3.3 Security tokens + multi-tenancy — **Implemented, always on**
Security tokens on the CCM protocol layer (both v3.0.0 and v2.4.0) are **always on** and always come from a
**siglet** STS (`certo.security.siglet-base-url` is required; dev/test point at a mock siglet). Inbound
protocol calls are verified by calling siglet's **revocation-aware** verification endpoint `POST /tokens/verify`,
which checks signature, expiry, and `jti` revocation — a per-request network call, so token **refresh** stays
siglet's concern. Outbound calls are made on behalf of the sender's participant context, resolving token +
counterparty endpoint from the siglet cache keyed by an ephemeral, per-call `flowId` (no configured-URL
fallback). The verified caller replaces any previously-trusted inbound BPN, and the token **audience**
resolves to the receiving tenant. **Multi-tenancy is enforced everywhere,
no exceptions:** certificates + exchanges belong to a `ParticipantContext` created via the management API
(no config default); certificate lookup/search/request are tenant-scoped. The management API is never
token-secured (it carries `flowId` + `participantContextId` as data). *Deferred: server-side
revocation-aware verify, token refresh, TLS.*

### 3.2 Persistence: Spring Data JPA (H2 dev/test, Postgres prod) — **By design**
Storage is **Spring Data JPA**. One datasource: an embedded **H2** database by default (dev/test — a
uniquely-named in-memory instance per Spring context, so tests are isolated), swapped for **Postgres** under
the `prod` profile. The aggregates are JPA `@Entity`s. Value-object collections and single value objects that
are only ever read whole with their root (status-error lists, certificate revisions, issuer/validator,
embedded content) are stored as JSON text (varchar) columns via hand-written `AttributeConverter`s (Jackson
3), keeping the aggregate one row. Collections that are <b>queried</b> — a certificate's/known-certificate's
`certifiedLocations` and an exchange's `requestedLocations` — are instead normalized into `@ElementCollection`
child tables, so coverage, search, and overlap/subset run as real SQL (`JpaSpecificationExecutor` +
`CertificateSpecifications`/`ExchangeSpecifications`), not by loading every row and filtering in memory.

Most stores (`ProviderCertificateStore`, `ProviderDocumentStore`, `ProviderCertificateExchangeStore`,
`ConsumerCertificateStore`, `ConsumerCertificateExchangeStore`, `ExchangeBindingStore`) are **Spring Data
repository interfaces directly** — they extend `JpaRepository` and expose domain-named operations
(`find`/`all`/`record`/`resolve`) as thin `default` methods. Two keep a hand-written adapter because they
carry logic beyond CRUD: `ProcessedEventStore` (its `claim` needs an injected `Clock`) and
`ParticipantContextStore` (a plain port, since a small in-memory fake also implements it for a pure unit
test, and `MockSiglet` depends on the abstraction).

**Concurrency control is the database's, not the JVM's.** There are no application locks or `synchronized`
model methods. Create-once uses primary-key / unique (`did`) constraints; read-modify-write uses `@Version`
optimistic locking; multi-store operations (`publish`, `recordAcceptance`, `handleNotifications`, the seeder,
tenant create) run under one `@Transactional` so they commit or roll back atomically. Event idempotency
(`ProcessedEventStore.claim`) inserts a marker row in the same transaction as the side effect — a rollback
undoes it, so a failed apply is retried (the old two-phase claim/release compensation is gone). This is
correct across a cluster; the former in-memory `ConcurrentHashMap` design was not.
*Deferred: prod Postgres schema management (Flyway).*

---

## 4. v2.4.0 adapter limitations

### 4.1 `/companycertificate/available` is ack-only — **By design (out of scope)**
A real v2.4.0 provider using `/available` expects the consumer to then pull the certificate via the
v2.4.0 per-asset EDC mechanism, which is out of scope. We only log and `200`. Old providers should use
`/push` (which delivers the full certificate inline).

### 4.2 v2.4.0 pull retrieval is out of scope — **By design (out of scope)**
The `/companycertificate/request` message and its reply are v2.4.0-**compliant**: a `COMPLETED` reply
returns only the `documentId`. In v2.4.0 the consumer then retrieves the certificate by that `documentId`
via the **dataspace / EDC per-asset pull**, which is out of scope here (the same DSP boundary as §3.1 and
§4.1). So a v2.4.0 consumer can request and get a `documentId`, but the actual content retrieval is not
performed in this build. *(An earlier build returned the certificate inline in the reply / pushed it to a
request-supplied URL; both were dropped as non-spec deviations in favor of compliance.)* To deliver a
certificate to a v2.4.0 consumer without the dataspace, use the provider-initiated push (§4.5): an
**embedded** publish emits `/companycertificate/push` with the full certificate inline, which the consumer
ingests directly (fully operational, no pull); a by-reference publish emits `/companycertificate/available`,
which still relies on the out-of-scope asset-pull.

### 4.3 Feedback identity is per-certificate, not per-interaction — **By design (protocol limit)**
v2.4.0 `/status` references only a `documentId` (= `certificateId`), so feedback is correlated by
`(documentId, peerBpn) → exchangeId`. If one consumer has two concurrent exchanges for the same
certificate, the index overwrites and points at the latest — the earlier one's feedback cannot be
distinguished. This is inherent to v2.4.0 (no per-interaction id) and cannot be fixed in the adapter.

### 4.4 v2.4.0 envelope + content validation — **Resolved**
Inbound v2.4.0 messages are validated by `Ccm240Envelope`. **Header:** the required fields (`context`,
`messageId`, `senderBpn`, `receiverBpn`, `sentDateTime`, `version`), their patterns (BPNL, UUID, SemVer,
ISO-8601 date-time), and the `context` expected for the endpoint. **Content:** `certifiedBpn` (BPNL,
required on a request), `documentId` (UUID, on status/available), and `locationBpns` (each a site/address
`BPN[AS]…`). A malformed header or content is rejected with `400`. (`@JsonIgnoreProperties(ignoreUnknown =
true)` still tolerates *extra* fields, which is lenient-but-safe.)

### 4.5 Non-spec management triggers — **By design**
`POST /management/v1/certificates/{id}/publish` (provider-initiated push) is our own trigger, not part of
v2.4.0. Its body selects the `protocolVersion` and, for a non-native (`2.4.0`) target, the consumer's BPN +
base URL — this is how a v2.4.0 consumer is named (there is no inbound request to derive it from). Recording
that as a v2.4.0 `ExchangeBinding` is what routes the notification to the v2.4.0 adapter.

### 4.7 `documentId` **is** the `certificateId` (both UUIDs) — **Resolved**
v2.4.0 `documentId` is "the UUID of the asset under which the certificate is available." certo generates
`certificateId`s as bare **UUIDs** (and the seeded sample certs use fixed UUIDs), so the v2.4.0 `documentId`
is simply the `certificateId` — no translation table. (An earlier `Ccm240DocumentIds` derived a UUID via
`nameUUIDFromBytes` and kept a session-only reverse map; it was **deleted** once ids became UUIDs.)

### 4.6 Outbound routing bindings are keyed by `exchangeId` — **Resolved**
`publish()` now mints the `exchangeId` first and records the (non-native) `ExchangeBinding` against it
before notifying, so the dispatcher resolves it directly by `exchangeId` — no `certificateId`-fallback
timing window (the earlier two-step `publish-to-consumer` needed one because it recorded the binding
before the exchangeId existed). The push-in path likewise mints a consumer-local surrogate exchangeId up
front. Binding *resolution* is now purely by `exchangeId` (the `certificateId`-keyed binding map was
removed); the inbound v2.4.0 `/status` path still correlates a `documentId` (= `certificateId`) + peer back
to an exchangeId through a separate index. Lifecycle notifications no longer resolve a binding at all — the
`publish` caller names the target explicitly (§4.5).

---

## 5. Architectural caveats

### 5.1 Version-neutral certificate model — **In progress (Phases 1–2 done)**
Making the certificate data-model axis as pluggable as the protocol axis, so v3/4.0.0 is an adapter (not
the hub) over a neutral core. **Phase 1 (done):** `CertificateRecord` is the version-neutral domain model;
a v3 wire type (`Ccm300Certificate`) + `Ccm300CertificateCodec` were introduced and applied at the
**retrieval/search/pull** surface (`GET /certificates/{id}`, `POST /certificates/search`, the consumer's
pull). **Phase 2 (done):** the **lifecycle-event** certificate now also goes through the codec — the v3
notify adapter (`Ccm300Notifier`) renders a wire `Ccm300LifecycleStatus` (cert via
`toWire`) on send, and the consumer decodes it and maps back via `toDomain` on receive (covers the
embedded-document push too). **Net:** every certificate wire boundary now crosses the codec, so a BPC
4.0.0 reshape lands in `Ccm300Certificate`/`Ccm300LifecycleStatus` + the codec, not the core (today the
shapes coincide, so the codec is near-identity — the seam is in place). **Residual coupling:** the consumer
service still imports the ccm300 codec/wire type inline (it parses v3 CloudEvents directly). The fully
clean form extracts CloudEvent parsing/dispatch into a ccm300 consumer adapter, leaving the service with
domain-level methods — a further refactor, not required for the data-model decoupling. **Phase 3 (done):**
both protocol versions are now symmetric adapter packages over a version-neutral core.
- `protocol/ccm240` split by role — `ccm240/provider` (`Ccm240ProviderController`, `Ccm240Notifier`),
  `ccm240/consumer` (`Ccm240ConsumerController`, `Ccm240Reporter`), `ccm240/model`, and shared
  translation/envelope/outbound/documentIds at the root.
- `protocol/ccm300` now holds the v3 HTTP surface — `ccm300/provider` (`Ccm300ProviderController`,
  `Ccm300Notifier`), `ccm300/consumer` (`Ccm300ConsumerController`, `Ccm300Retriever`,
  `Ccm300Requester`, `Ccm300Reporter`), and the wire types/codec in `ccm300` / `ccm300/model`.
- The **core** (`provider`/`consumer`) keeps only services, domain `model`, `store`, the application `dto`
  (formerly `api/dto`), and the outbound **ports** in `spi` — `provider/spi/ConsumerNotifier`,
  `consumer/spi/{AcceptanceReporter, CertificateRetriever, CertificateRequester}` plus their value types
  (`RetrievedCertificate`, `RetrievedDocument`, `ProviderRequestResult`). Two new ports were introduced so
  the consumer service depends on interfaces, not the concrete HTTP clients (which now live in `ccm300`).

Dependency direction is inward: the ccm300/ccm240 adapters depend on the core ports/DTOs; the **only**
core→adapter edge is the documented Phase-2 residual (`ConsumerCertificateService` importing the ccm300
codec/wire type to parse v3 CloudEvents inline). DTOs stay in core rather than being duplicated into a
`ccm300/model` wire set + identity codecs: v3's wire shape equals the domain shape today, so a wire/domain
split is only worth adding per-DTO **when** a real divergence appears (as with the certificate for BPC 4.0.0)
— the same lazy-split discipline applied consistently.

### 5.2 `certificateTypeVersion` is inert passenger data — **By design**
The edition of the certificate type (e.g. ISO 9001:2015) is carried and translated (3.1.0
`type.certificateVersion` ⟷ canonical `certificateTypeVersion`) but is **not** a selector: requests are
type-only, and matching/search ignore the version. Thin v2.4.0 messages (`/request`, `/available`) have
no version slot, so it is dropped there (not mangled into the type name). Version-aware request/matching
would require adding it to the request DTO and the match predicates.

### 5.3 Push-in surrogate `exchangeId`; derived stable `certificateId` — **By design / Resolved**
On a v2.4.0 `/push` (we are the consumer) the provider assigns no `exchangeId`, so the adapter mints a
local surrogate per delivery — honestly a correlation handle, not a provider-assigned id (a real one would
come from the out-of-scope dataspace layer). The **`certificateId`, by contrast, is now derived
deterministically** — a name-based UUID of `issuerBpn|registrationNumber` — so a re-push of the same
certificate keeps its identity and accrues **revisions** (`ConsumerCertificateService.nextPushedRevision`)
instead of creating a duplicate record on every push. (Earlier it was a fresh random id at `revision=1`.)
