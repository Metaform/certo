# TC-CCM-07 — Available Notification + PULL (P2 – Optional)

**Roles:** Provider + Consumer  ·  **Mechanism:** v2 **Notification API** (`available`) + **Asset-based Pull**

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Provider makes Set 1 available and sends an availability notification | Provider |
| 2 | Consumer receives the notification in the application | Consumer |
| 3 | Consumer actively retrieves the certificate using the notification | Consumer |
| 4 | Consumer reviews the certificate against Set 1 | Consumer |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/available`** | Provider → Consumer | `content = { documentId, certificateType, locationBpns }`, `header.context = ...-Available:1.0.0`. **By reference only** — no inline content. |
| 2 | *(receipt of step 1)* | Consumer | Consumer records the availability notice. |
| 3 | **EDC asset-based Pull** | Consumer → Provider EDC | Using the notice's `documentId` as correlation handle, discover + contract + pull the certificate over the dataspace. See [README §2](README.md#2-asset-based-pull-edc-dataspace). |
| 4 | — | Consumer | Review pulled `BusinessPartnerCertificate` against Set 1. |

## Notes

- This is the canonical **notify-then-pull** flow: `/available` is the trigger (step 1), the EDC pull
  is the retrieval (step 3). Contrast TC-CCM-03, where `/push` carries the content inline and no pull
  occurs.

## Certo status — ✅ Implemented

The canonical notify-then-pull flow works end to end: `/available` opens a by-reference exchange and emits
it; a client drives `POST /consumer/exchanges/{id}/retrieve?flowId=…` to pull the content.

## Certo code references

- **Send available:** `Ccm240Notifier` (by-reference lifecycle, or `notifyFulfillment` for a later
  `FULFILLED`) → `POST /companycertificate/available`.
- **Receive available (step 1–2):** `Ccm240ConsumerController#available` — validates the notice, mints a
  surrogate `exchangeId`, records a `CCM_2_4_0` / `PROVIDER` `ExchangeBinding`, and opens a by-reference
  exchange via `ConsumerExchangeService#receiveAvailableCertificate` (idempotent on `messageId`).
- **Pull (step 3):** `ConsumerManagementController` `retrieve` → `ConsumerExchangeService#retrieve` →
  `DispatchingCertificateRetriever` (routes the v2 binding to) `Ccm240Retriever`, reading over the flow.
