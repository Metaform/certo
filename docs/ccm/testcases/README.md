# CCM Test Cases → CX-0135 v2 (2.4.0) API Mapping

This folder maps each test case in [`../TestCases_CCM.pdf`](../TestCases_CCM.pdf) to the **CX-0135 v2 (version 2.4.0)**
API calls it invokes. One document per test case.

> Scope: **v2 only.** The v3 (3.0.0) CloudEvents notification API
> (`POST /certificate-notifications`, `GET /certificates/{id}`, `GET /documents/{id}`,
> `POST /certificates/search`, `POST /certificate-acceptance-notifications`,
> `GET /certificate-acceptance-status/{id}`) is intentionally **out of scope** here. Where the
> Certo codebase implements a step, the relevant v2 adapter class is cited.

## The CX-0135 v2 (2.4.0) API surface

v2 has two parts: a JSON **Notification API** ("CCMAPI") for the four `POST /companycertificate/*`
messages, and an EDC **asset-based Pull** for retrieving certificate content.

### 1. Notification API (CCMAPI) — HTTPS JSON messages between partners

Every message is a `{ header, content }` envelope. `header.context` carries the message-type discriminator; delivery is
a `POST` to the counterparty's base URL + the path below.

| # | Method + Path                        | Direction           | `header.context`                                      | Purpose                                                                                                          |
|---|--------------------------------------|---------------------|-------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| 1 | `POST /companycertificate/request`   | Consumer → Provider | `CompanyCertificateManagement-CCMAPI-Request:1.0.0`   | Request a certificate (`certifiedBpn`, `certificateType`, `locationBpns`).                                       |
| 2 | `POST /companycertificate/status`    | Consumer → Provider | `CompanyCertificateManagement-CCMAPI-Status:1.0.0`    | Feedback on a consumed certificate (`documentId`, `certificateStatus`, `certificateErrors`, `locationErrors`).   |
| 3 | `POST /companycertificate/push`      | Provider → Consumer | `CompanyCertificateManagement-CCMAPI-Push:1.0.0`      | Push the full certificate inline (`BusinessPartnerCertificate` 3.1.0, `document.contentBase64`).                 |
| 4 | `POST /companycertificate/available` | Provider → Consumer | `CompanyCertificateManagement-CCMAPI-Available:1.0.0` | Notify (by reference) that a certificate is available to pull (`documentId`, `certificateType`, `locationBpns`). |

**`/request` reply (`requestStatus`)** — `IN_PROGRESS` (HTTP 202), `COMPLETED` (HTTP 200, carries
`documentId`), `REJECTED` (HTTP 200, carries `requestErrors` / `locationErrors`).

**`/status` values (`certificateStatus`)** — `RECEIVED`, `ACCEPTED`, `REJECTED`.

### 2. Asset-based Pull (EDC dataspace)

The `/available` notice (message 4) points at an asset by `documentId`; the Consumer uses it as the correlation handle
to select and pull that asset (v2 does not specify the `documentId`→asset-id mapping, so it is provider convention).

### Roles legend

- **Certo as Provider** emits messages 3 & 4 via `Ccm240Notifier`, receives 1 & 2 via `Ccm240ProviderController`.
- **Certo as Consumer** emits message 1 via `Ccm240Requester` and message 2 via `Ccm240Reporter`, receives 3 & 4 via
  `Ccm240ConsumerController`.
- App-facing **Management API** (`/management/v1/participant-contexts/...`) is Certo's own control surface, **not** part
  of the v2 wire protocol; it is listed only to show what triggers each wire call.

> ✅ **Implementation note (Certo).** The v2 consumer-side pull is implemented. `Ccm240ConsumerController#available`
> opens a by-reference exchange and emits it; a client then drives `POST /consumer/exchanges/{id}/retrieve?flowId=…`,
> which routes to `Ccm240Retriever` (selected per exchange by `DispatchingCertificateRetriever`) to read the
> certificate over the flow. A proactive pull with no prior notice is available at
> `POST /consumer/certificates/pull`. In both cases siglet performs the EDC catalog/negotiation/transfer and
> hands Certo the resolved data-plane endpoint + token per `flowId`; Certo performs the data-plane read and
> up-conversion. See the per-test **Status** lines below.

## Index

| Test case                   | Title                                           | Primary v2 call(s)                               | Certo status   |
|-----------------------------|-------------------------------------------------|--------------------------------------------------|----------------|
| [TC-CCM-01](TC-CCM-01.md)   | Retrieve and Display a Partner's Certificate    | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-02](TC-CCM-02.md)   | Retrieve Multiple Certificates from a Partner   | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-03](TC-CCM-03.md)   | PUSH – Accepted                                 | `push` + `status`(ACCEPTED)                      | ✅ Implemented |
| [TC-CCM-04](TC-CCM-04.md)   | PUSH – Rejected (Certificate Errors)            | `push` + `status`(REJECTED, `certificateErrors`) | ✅ Implemented |
| [TC-CCM-05](TC-CCM-05.md)   | PUSH – Rejected (Location Errors)               | `push` + `status`(REJECTED, `locationErrors`)    | ✅ Implemented |
| [TC-CCM-E01](TC-CCM-E01.md) | Edge Case: Expired Certificate                  | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-E02](TC-CCM-E02.md) | Edge Case: Mixed Sites (BPNS + BPNA)            | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-E03](TC-CCM-E03.md) | Edge Case: No Expiry Date                       | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-06](TC-CCM-06.md)   | Certificate Request (P2)                        | `request` (+ Pull / `available`)                 | ✅ Implemented |
| [TC-CCM-07](TC-CCM-07.md)   | Available Notification + PULL (P2)              | `available` + Asset-based Pull                   | ✅ Implemented |
| [TC-CCM-N03](TC-CCM-N03.md) | Negative: Certificate Request Non-Existent (P2) | `request` → REJECTED                             | ✅ Implemented |
| [TC-CCM-N04](TC-CCM-N04.md) | Certificate with Invalid Data                   | Asset-based Pull                                 | ✅ Implemented |
| [TC-CCM-N02](TC-CCM-N02.md) | No Certificates Stored                          | Asset-based Pull (empty catalog)                 | ✅ Implemented |

> "Implemented" = Certo performs its side of the v2 API for the test case. For pull-based cases the EDC
> catalog/negotiation/transfer is performed by siglet (which hands Certo the resolved flow); Certo performs
> the data-plane read, up-conversion, and storage.
