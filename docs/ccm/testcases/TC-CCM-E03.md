# TC-CCM-E03 — Edge Case: No Expiry Date

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Retrieve the partner's certificates | Consumer |
| 3 | Check how the value `9999-12-31` is displayed | Consumer |

## v2 API calls invoked

Same mechanism as [TC-CCM-01](TC-CCM-01.md) — the **asset-based pull**. The edge is the sentinel
`validUntil = 9999-12-31` in the pulled payload (a certificate with no real expiry).

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog + contract + data-plane pull** | Pull the `BusinessPartnerCertificate` with `validUntil = 9999-12-31`. |
| 3 | — | Display treatment of the "never expires" sentinel is local. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace).

## Notes

- `9999-12-31` is a data convention, not a distinct API path; it flows through the same pull as any
  other `validUntil` value.

## Certo status — ✅ Implemented

Same pull path as [TC-CCM-01](TC-CCM-01.md); `9999-12-31` is parsed by `Ccm240Translation` like any date and
carried onto the record.
