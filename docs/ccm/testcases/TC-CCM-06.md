# TC-CCM-06 — Certificate Request (P2 – Optional)

**Roles:** Consumer + Provider  ·  **Mechanism:** v2 **Notification API** (`request`) + **Pull** / `available`

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Submit a certificate request for ISO9001 to the Provider | Consumer |
| 2 | Check the incoming request in the Provider application | Provider |
| 3 | Provider makes Set 1 available for the Consumer | Provider |
| 4 | Check whether the requested certificate appears and matches Set 1 | Consumer |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/request`** | Consumer → Provider | `content = { certifiedBpn, certificateType: iso9001, locationBpns }`, `header.context = ...-Request:1.0.0`. |
| 1 | *(reply)* | Provider → Consumer | `requestStatus = IN_PROGRESS` (**HTTP 202**) while the provider has not yet issued the certificate. |
| 2 | *(receipt of step 1)* | Provider | Provider sees the pending request in its backlog. |
| 3 | **Make available** — one of: | Provider → Consumer | (a) the provider issues Set 1 and, on the **next** `POST /companycertificate/request`, replies `requestStatus = COMPLETED` (**HTTP 200**) carrying the `documentId`; **or** (b) the provider sends `POST /companycertificate/available` (`documentId`, `certificateType`, `locationBpns`). |
| 4 | **EDC asset-based Pull** | Consumer → Provider EDC | Using the `documentId` as correlation handle, discover + contract + pull the certificate over the dataspace and compare against Set 1. See [README §2](README.md#2-asset-based-pull-edc-dataspace). |

## Notes

- v2 `/request` is not a streaming callback: fulfillment is learned either by **re-requesting**
  (poll → `COMPLETED` + `documentId`) or by receiving an **`/available`** notice; either way the
  content itself is fetched via the asset pull.

## Certo code references

- **Request (receive):** `Ccm240ProviderController#request` → `ProviderExchangeService.requestCertificate`;
  reply mapped by `Ccm240Translation.toReplyStatus` → `Ccm240RequestReply.inProgress()/completed()/rejected()`.
- **Request (send, Certo as consumer):** `Ccm240Requester` (`POST /companycertificate/request`), selected by
  `DispatchingCertificateRequester` for `protocolVersion: 2.4.0`; Management trigger
  `POST /consumer/certificate-requests`.
- **Make available (send):** later `FULFILLED` is surfaced by `Ccm240Notifier#notifyFulfillment` as a
  `POST /companycertificate/available` notice (Management trigger `POST /certificate-requests/{id}/fulfill`).

## Certo status — ✅ Implemented

Both halves work: the `/request` reply flow (`Ccm240ProviderController#request` / `Ccm240Requester`) and
the step-4 retrieval — the `/available` notice opens a by-reference exchange the client retrieves over a flow
(`Ccm240Retriever`), the same pull as [TC-CCM-07](TC-CCM-07.md).
