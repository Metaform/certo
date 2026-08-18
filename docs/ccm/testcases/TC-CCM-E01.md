# TC-CCM-E01 — Edge Case: Expired Certificate

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Retrieve the partner's certificates | Consumer |
| 3 | Check how the expired certificate is displayed | Consumer |

## v2 API calls invoked

Identical mechanism to [TC-CCM-01](TC-CCM-01.md) — the **asset-based pull**. The edge is purely in the
**payload content**, not the call: the pulled `BusinessPartnerCertificate` has a `validUntil` in the
past (Set 4 = expired).

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog + contract + data-plane pull** | Pull the (expired) `BusinessPartnerCertificate`. |
| 3 | — | Validity check on `validUntil` and display treatment are local. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace).

## Notes

- The v2 API does not filter out expired certificates at the wire level; expiry is a property
  (`validUntil`) evaluated by the consuming application.

## Certo code references

- Validity is a separate dimension from lifecycle status; see `BusinessPartnerCertificate31` fields
  and `Ccm240Translation` up-conversion.

## Certo status — ✅ Implemented

Same pull path as [TC-CCM-01](TC-CCM-01.md) (`POST /consumer/certificates/pull` or the `/available`→retrieve
flow). The expired `validUntil` is carried through `Ccm240Retriever` → `Ccm240Translation#upConvert`
unchanged; expiry evaluation/display is on the certificate record.
