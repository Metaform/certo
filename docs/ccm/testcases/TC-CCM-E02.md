# TC-CCM-E02 — Edge Case: Mixed Sites (BPNS + BPNA)

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Retrieve the partner's certificates | Consumer |
| 3 | Check whether all sites from Set 3 are correctly displayed (1× BPNS, 2× BPNA) | Consumer |

## v2 API calls invoked

Same mechanism as [TC-CCM-01](TC-CCM-01.md) — the **asset-based pull**. The edge is in the pulled
payload's `enclosedSites` list (Set 3 = 1 BPNS + 2 BPNA).

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog + contract + data-plane pull** | Pull the `BusinessPartnerCertificate` whose `enclosedSites` mixes site (BPNS) and address (BPNA) locations. |
| 3 | — | Enumerate and display all sites locally. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace). `dct:enclosedSites` on the asset lets the catalog filter target specific sites.

## Notes

- `enclosedSites` / `locationBpns` in v2 may hold both BPNS and BPNA identifiers; both map to the v3
  `certifiedLocations` on up-conversion.

## Certo code references

- Site handling: `Ccm240Notifier#locationBpns` / `Ccm240Translation` (BPNS preferred, BPNA fallback).

## Certo status — ✅ Implemented

Same pull path as [TC-CCM-01](TC-CCM-01.md). `Ccm240Translation#upConvert` maps the 3.1.0
`businessPartnerNumber` + `enclosedSites` (1 BPNS + 2 BPNA) into the record's `certifiedLocations`
(MAIN + enclosed), so all sites are present for display.
