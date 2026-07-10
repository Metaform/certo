# Problem Notes

Known problems, gaps, deliberate divergences, and caveats in this demo — the things a reader should
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
No catalog, no contract negotiation, no CX-0000 token-refresh authorization. `provider-base-url` /
`consumer-base-url` are hardcoded and the "pull" is a direct HTTP `GET`, not a dataspace transfer.
*Impact: neither API is production/certification-compliant; the pull's transport/authorization layer is
absent.*

### 3.2 In-memory storage — **By design (abstracted for Postgres)**
Every store is now a **port** (interface) with a swappable adapter. The default adapters are in-memory
(`InMemory*`), active via `certo.persistence=memory` (the default) and reset on restart. A JDBC/Postgres
adapter can be added per store — a new `Postgres*` class registered under `certo.persistence=postgres` —
with **no change to the services**, which depend only on the port. Ports: `ProviderCertificateStore`,
`ProviderDocumentStore`, `ProviderCertificateExchangeStore`, `ConsumerCertificateStore`,
`ConsumerCertificateExchangeStore`, `ProcessedEventStore`, `ExchangeBindingStore`, `Ccm240DocumentIds`.

---

## 4. v2.4.0 adapter limitations

### 4.1 `/companycertificate/available` is ack-only — **By design (out of scope)**
A real v2.4.0 provider using `/available` expects the consumer to then pull the certificate via the
legacy per-asset EDC mechanism, which is out of scope. We only log and `200`. Old providers should use
`/push` (which delivers the full certificate inline).

### 4.2 v2.4.0 pull retrieval is out of scope — **By design (out of scope)**
The `/companycertificate/request` message and its reply are v2.4.0-**compliant**: a `COMPLETED` reply
returns only the `documentId`. In v2.4.0 the consumer then retrieves the certificate by that `documentId`
via the **dataspace / EDC per-asset pull**, which is out of scope here (the same DSP boundary as §3.1 and
§4.1). So a v2.4.0 consumer can request and get a `documentId`, but the actual content retrieval is not
performed in this demo. *(An earlier build returned the certificate inline in the reply / pushed it to a
request-supplied URL; both were dropped as non-spec deviations in favor of compliance.)* To deliver a
certificate to a legacy consumer without the dataspace, use the provider-initiated push (§4.5), which
emits a compliant `/companycertificate/available` (or `/push`) to a caller-specified endpoint.

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

### 4.5 Non-spec demo triggers — **By design**
`POST /legacy/certificates/{id}/publish` (provider-initiated v2.4.0 push) is our own trigger, not part
of v2.4.0. It is how the caller specifies a legacy consumer target (there is no inbound request to derive
it from).

### 4.7 `documentId` is a UUID mapped from `certificateId` — **Resolved**
v2.4.0 `documentId` is "the UUID of the asset under which the certificate is available." v3 certificate
ids are opaque non-UUID strings, so `Ccm240DocumentIds` derives a stable UUID `documentId` per certificate
(deterministic `nameUUIDFromBytes`) and resolves it back on inbound `/status`. Every `documentId` the
adapter emits (`COMPLETED` reply, outbound `/available`, outbound `/status`) is now a valid UUID; the
internal `certificateId` never appears on the wire.

### 4.6 `certificateId` fallback remains for the legacy-publish trigger — **By design**
The provider-initiated `publish()` mints the `exchangeId` mid-call, before the exchangeId-keyed binding
can be recorded, so the notifier resolves the binding by `certificateId` for that window. This is a
timing bridge, not a role error. (The push-in path no longer needs it — it mints a consumer-local
surrogate exchangeId up front.)

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

### 5.3 Push-in synthesizes a consumer-local surrogate `exchangeId` — **By design**
On a v2.4.0 `/push` (we are the consumer) the provider assigns no `exchangeId`, so the adapter mints a
local surrogate. It is honestly a correlation handle, not a provider-assigned id — a real
provider-assigned id would come from the dataspace layer that is out of scope.
