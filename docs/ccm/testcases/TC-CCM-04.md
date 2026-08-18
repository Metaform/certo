# TC-CCM-04 — PUSH – Rejected (Certificate Errors)

**Roles:** Provider + Consumer  ·  **Mechanism:** v2 **Notification API** (`push` + `status`)  ·  *Mandatory*

## Steps

| # | Step | Role | Mandatory |
|---|------|------|-----------|
| 1 | Send Set 4 (expired certificate) to Consumer | Provider | ✅ |
| 2 | Check receipt in the Consumer application | Consumer | ✅ |
| 3 | Mark the certificate as **rejected** | Consumer | ✅ |
| 4 | Check whether `certificateErrors` with a rejection reason are included | Consumer | Optional |
| 5 | Check whether status "Rejected" has been received | Provider | ✅ |
| 6 | Check whether rejection reason is displayed | Provider | Optional |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/push`** | Provider → Consumer | Set 4 (an **expired** certificate) inline. |
| 2 | *(receipt of step 1)* | Consumer | Consumer ingests the pushed record. |
| 3–4 | **`POST /companycertificate/status`** | Consumer → Provider | `certificateStatus = REJECTED` with a **`certificateErrors`** array (`[{ message: "Certificate has expired" }]`) — a **certificate-level** rejection. |
| 5–6 | *(receipt of step 3)* | Provider | Provider records `REJECTED` and the certificate-level reason(s). |

`certificateErrors` (certificate-scoped) is the distinguishing field of this test vs. TC-CCM-05
(`locationErrors`).

## Certo code references

- **Send:** `Ccm240Notifier#notifyLifecycle` (`POST .../push`).
- **Reject:** `Ccm240Reporter#report` maps status → `REJECTED` and `errors` (no `specifier`) →
  `certificateErrors`; Management trigger `POST /consumer/exchanges/{id}/accept` with a REJECTED body.
- **Receive:** `Ccm240ProviderController#status` → `toStatusErrors` folds `certificateErrors` into
  `StatusError(message)`; if a rejection carries no detail, a default reason is synthesized
  (v2 makes error detail optional).

## Certo status — ✅ Implemented

Certificate-level rejection round-trips: a consumer REJECTED error with **no** `specifier` is emitted in
`certificateErrors` (`Ccm240Reporter.certificateErrors`) and folded back by the provider
(`Ccm240ProviderController#status`).
