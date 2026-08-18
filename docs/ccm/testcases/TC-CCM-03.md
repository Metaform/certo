# TC-CCM-03 — PUSH – Accepted

**Roles:** Provider + Consumer  ·  **Mechanism:** v2 **Notification API** (`push` + `status`)  ·  *Mandatory*

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select test partner and actively send Set 1 | Provider |
| 2 | Confirm successful transmission | Provider |
| 3 | Check receipt in the Consumer application | Consumer |
| 4 | Review certificate content against Set 1 | Consumer |
| 5 | Mark the certificate as **accepted** | Consumer |
| 6 | Check whether status "Accepted" has been received | Provider |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/push`** | Provider → Consumer | Full Set 1 (ISO 9001) inline as `BusinessPartnerCertificate` 3.1.0, `header.context = ...-Push:1.0.0`. `header.senderFeedbackUrl` tells the consumer where to send status. |
| 2 | — | — | Transmission confirmed by the push's **HTTP 200** response. |
| 3–4 | *(receipt of step 1)* | Consumer | Consumer ingests and displays the pushed record. |
| 5 | **`POST /companycertificate/status`** | Consumer → Provider | `certificateStatus = ACCEPTED`, `documentId` = the certificate's id (UUID). `header.context = ...-Status:1.0.0`. |
| 6 | *(receipt of step 5)* | Provider | Provider records the `ACCEPTED` feedback. |

An optional intermediate `POST /companycertificate/status` with `certificateStatus = RECEIVED` MAY be
sent on receipt (step 3) before the terminal `ACCEPTED`.

## Certo code references

- **Provider side (send):** `Ccm240Notifier#notifyLifecycle` → embedded cert → `POST .../push`
  (triggered by Management `POST /certificates/{id}/publish` with `protocolVersion = 2.4.0`, `embedded = true`).
- **Consumer side (receive + accept):** `Ccm240ConsumerController#push` ingests as a `CREATED` and
  accepts inline; `Ccm240Reporter#report` emits `POST .../status` with `ACCEPTED`
  (Management trigger: `POST /consumer/exchanges/{id}/accept`).
- **Provider side (receive status):** `Ccm240ProviderController#status` → `ProviderExchangeService.recordAcceptance`.

## Certo status — ✅ Implemented

End to end on both roles: provider push (`Ccm240Notifier`), consumer receive → accept (`Ccm240ConsumerController#push`
→ `POST /consumer/exchanges/{id}/accept` → `Ccm240Reporter` ACCEPTED), and provider records the acceptance
(`Ccm240ProviderController#status`). Covered by `v240Push_ingestsUpConvertsAcceptsAndReportsStatusBack`.
