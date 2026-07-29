# Problem Notes

Known problems, gaps, deliberate divergences, and caveats in this implementation — the things a reader should
not mistake for "done" just because the build is green. Grouped by kind. This lists only **live** items;
things that were resolved have been dropped. See also the migration analysis under `certo-spec/migration/`
(`certo-conformance-gaps.md`, `adapter-architecture.md`), the [README](../README.md), and [FLOWS](FLOWS.md)
for the implemented behavior.

Status legend: **Blocked** (waits on something external) · **Parked** (deliberately deferred) ·
**By design** (intentional) · **Open** (should be fixed) · **Deferred** (not built yet).

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

### 1.3 Lifecycle `certificate` oneOf deferred — **Parked**
The alignment made `data.certificate` a `oneOf(full BPC-4.0.0 record, {certificateId})`; certo still
sends the older light-triage subset on `CREATED`/`MODIFIED`. It still validates against the loose
`{certificateId}` branch, so it is not a hard break, but it is not the exact shape the examples show.

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
project decision and simplifies the 2.4.0 bridge (which has no revision concept). A withdrawn certificate
returns a minimal `{certificateId, status: WITHDRAWN}` body (rather than the §4.1 submodel or a `404`/`410`)
so a holder can observe the withdrawal — an intentional non-spec extension.

---

## 3. Out-of-scope boundaries & deferrals

### 3.1 No DSP control plane — **By design (out of scope)**
No catalog, no contract negotiation. Flows are established by an external control plane; Certo consumes
them. The counterparty URL and token always come from the siglet cache (keyed by the ephemeral `flowId`),
but the *flow* that provisions them is the control plane's job — in particular, an unsolicited provider push
has no consumer→provider return flow unless the control plane creates one. *Impact: Certo is a data plane;
the dataspace catalog/negotiation/transfer layer is external by design.*

### 3.2 Postgres schema management not built — **Deferred**
Storage is Spring Data JPA — embedded H2 by default (dev/test), Postgres under the `prod` profile. **Postgres
is not yet exercised** and there is **no migration tool**: `prod` runs Hibernate `validate`, so a real
Postgres deployment needs Flyway (or equivalent) to create/evolve the schema first. Concurrency control is
the database's (PK/unique constraints for create-once, `@Version` optimistic locking for read-modify-write,
one `@Transactional` per multi-store operation), which is correct across a cluster — but that guarantee is
only meaningful once Postgres is actually in use.

### 3.3 Transport & token lifecycle — **Deferred**
Security is always-on and siglet-based, and multi-tenancy is enforced everywhere (see README/FLOWS for the
implemented behavior). Remaining gaps: **TLS is not enforced** — the siglet base URL and the endpoints the
siglet cache returns are used as given; and **token refresh** / deep revocation semantics are siglet's
concern and are not re-implemented here (inbound verification is a per-request `POST /tokens/verify` call).

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
§4.5). So a v2.4.0 consumer can request and get a `documentId`, but the actual content retrieval is not
performed in this build. To deliver a certificate to a v2.4.0 consumer without the dataspace, use the
provider-initiated push (§4.5): an **embedded** publish emits `/companycertificate/push` with the full
certificate inline, which the consumer ingests directly (fully operational, no pull); a by-reference publish
emits `/companycertificate/available`, which still relies on the out-of-scope asset-pull.

### 4.3 Feedback identity is per-certificate, not per-interaction — **By design (protocol limit)**
v2.4.0 `/status` references only a `documentId` (= `certificateId`), so feedback is correlated by
`(documentId, peerDid) → exchangeId` (the peer's **verified DID**, not a self-declared BPN). If one consumer
has two concurrent exchanges for the same certificate, the index points at the latest — the earlier one's
feedback cannot be distinguished. This is inherent to v2.4.0 (no per-interaction id) and cannot be fixed in
the adapter.

### 4.5 Non-spec management trigger for the push — **By design**
`POST /management/v1/participant-contexts/{pc}/certificates/{id}/publish` (provider-initiated push) is our own
trigger, not part of v2.4.0. Its body selects the `protocolVersion` and, for a non-native (`2.4.0`) target,
names the consumer by `consumerBpn` + `consumerDid` — this is how a v2.4.0 consumer is identified (there is no
inbound request to derive it from); the outbound endpoint comes from the siglet cache. Recording that as a
v2.4.0 `ExchangeBinding` is what routes the notification to the v2.4.0 adapter.

---

## 5. Architectural caveats

### 5.1 Consumer service parses v3 CloudEvents inline — **Open (residual)**
The version-neutral certificate model is fully in place — every certificate wire boundary crosses
`Ccm300CertificateCodec`, and both protocol versions are symmetric adapter packages over the neutral core.
The one remaining core→adapter edge: `ConsumerExchangeService` still imports the ccm300 codec + wire type
(`Ccm300LifecycleStatus`) to parse inbound v3 CloudEvents directly. The clean form extracts CloudEvent
parsing/dispatch into a ccm300 consumer adapter, leaving the service with domain-level methods. *Not required
for the data-model decoupling; a further refactor.*

### 5.2 `certificateTypeVersion` is inert passenger data — **By design**
The edition of the certificate type (e.g. ISO 9001:2015) is carried and translated (3.1.0
`type.certificateVersion` ⟷ canonical `certificateTypeVersion`) but is **not** a selector: requests are
type-only, and matching/search ignore the version. Thin v2.4.0 messages (`/request`, `/available`) have
no version slot, so it is dropped there (not mangled into the type name). Version-aware request/matching
would require adding it to the request DTO and the match predicates.

### 5.3 Push-in surrogate `exchangeId` — **By design**
On a v2.4.0 `/push` (Certo is the consumer) the provider assigns no `exchangeId`, so the adapter mints a
consumer-local surrogate per delivery — honestly a correlation handle, not a provider-assigned id (a real one
would come from the out-of-scope dataspace layer). The `certificateId`, by contrast, is derived
deterministically (a name-based UUID of `issuerBpn|registrationNumber`), so a re-push of the same certificate
keeps its identity and accrues **revisions** rather than duplicating on every push.
