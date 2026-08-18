# TC-CCM-05 — PUSH – Rejected (Location Errors)

**Roles:** Provider + Consumer  ·  **Mechanism:** v2 **Notification API** (`push` + `status`)  ·  *Mandatory*

## Steps

| # | Step | Role | Mandatory |
|---|------|------|-----------|
| 1 | Send Set 3 (BPNS + BPNA) to Consumer | Provider | ✅ |
| 2 | Check receipt in the Consumer application | Consumer | ✅ |
| 3 | Mark the certificate as **rejected** | Consumer | ✅ |
| 4 | Check whether `locationErrors` with a location-specific rejection reason are included | Consumer | Optional |
| 5 | Check whether status "Rejected" has been received | Provider | ✅ |
| 6 | Check whether location-specific rejection reason is displayed | Provider | Optional |

## v2 API calls invoked

| Step | v2 API call | Direction | Detail |
|------|-------------|-----------|--------|
| 1 | **`POST /companycertificate/push`** | Provider → Consumer | Set 3 inline (mixed sites: 1× BPNS + BPNA). |
| 2 | *(receipt of step 1)* | Consumer | Consumer ingests the pushed record. |
| 3–4 | **`POST /companycertificate/status`** | Consumer → Provider | `certificateStatus = REJECTED` with a **`locationErrors`** array — each entry `{ bpn, locationErrors: [{ message }] }` scoping the rejection to a specific site. |
| 5–6 | *(receipt of step 3)* | Provider | Provider records `REJECTED` with the per-location reason(s). |

`locationErrors` (per-site, carries a `bpn` specifier) is the distinguishing field of this test vs.
TC-CCM-04 (`certificateErrors`).

## Certo code references

- **Send:** `Ccm240Notifier#notifyLifecycle` (`POST .../push`).
- **Reject:** `Ccm240Reporter#report`; a `StatusError` with a `specifier` (site BPN) serializes into a
  `locationErrors` entry. Management trigger `POST /consumer/exchanges/{id}/accept`.
- **Receive:** `Ccm240ProviderController#status` → `toStatusErrors` flattens each
  `locationErrors[].locationErrors[]` into `StatusError(message, bpn)`.

## Certo status — ✅ Implemented *(location-error split added this session)*

`Ccm240Reporter` now groups a REJECTED error whose `specifier` is a site BPN into `locationErrors`
(previously every error was flattened into `certificateErrors` — the optional steps 4/6 were not faithful).
The provider splits them back on receipt. Covered by `v240Reject_splitsCertificateAndLocationErrors`.
