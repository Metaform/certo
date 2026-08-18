# TC-CCM-N03 — Negative: Certificate Request Non-Existent (P2 – Optional)

**Roles:** Consumer + Provider  ·  **Mechanism:** v2 **Notification API** (`request` → REJECTED)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Request a certificate type not available at the Provider (e.g. ISO27001) | Consumer |
| 2 | Check the Provider's response in the Consumer application | Consumer |
| 3 | Check in the Provider application how the request was processed | Provider |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/request`** | Consumer → Provider | `content = { certifiedBpn, certificateType: iso27001, locationBpns }` — a type the provider cannot supply. |
| 2 | *(reply)* | Provider → Consumer | `requestStatus = REJECTED` (**HTTP 200**) carrying `requestErrors` (and/or `locationErrors`) with the reason. |
| 3 | *(receipt of step 1)* | Provider | Provider shows the request was received and declined. |

## Notes

- The negative outcome is expressed **in the `/request` reply body** (`REJECTED` + `requestErrors`),
  not as a separate endpoint. No `/push`, `/available`, or asset pull occurs.

## Certo code references

- **Receive request + reject:** `Ccm240ProviderController#request`; a declined/failed provider outcome
  maps via `Ccm240Translation.toReplyStatus` → `Ccm240RequestReply.rejected(...)` with
  `toRejectionReplyErrors(...)` populating `requestErrors`.
- **Send request (Certo as consumer):** `Ccm240Requester` (`POST /companycertificate/request`), selected by
  `DispatchingCertificateRequester` for `protocolVersion: 2.4.0`; Management trigger
  `POST /consumer/certificate-requests`. The REJECTED reply maps to `DECLINED` with the errors.

## Certo status — ✅ Implemented

Both roles: provider receives + rejects (`Ccm240ProviderController#request` → REJECTED with `requestErrors`);
consumer opens the request and maps the REJECTED reply → `DECLINED` (`Ccm240Requester`). The consumer request
adapter was added this session. Covered by the request-mapping tests in `Ccm240ConsumerControllerTest`.
