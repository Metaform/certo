# TC-CCM-02 — Retrieve Multiple Certificates from a Partner

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Retrieve all certificates of the partner | Consumer |
| 3 | Check that both certificates (ISO 9001 + IATF 16949) are displayed | Consumer |
| 4 | Verify contents against Set 2a and Set 2b | Consumer |

## v2 API calls invoked

Same mechanism as [TC-CCM-01](TC-CCM-01.md), but the catalog returns **multiple** certificate assets,
each pulled independently.

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog** | Discover **two** offers with `dct:subject = cx-taxo:CompanyCertificate` (Set 2a = `iso9001`, Set 2b = `iatf16949`). |
| 2 | **EDC contract + data-plane pull** ×2 | One contract and one pull per certificate asset (2a and 2b). |
| 3–4 | — | Display both; compare against Set 2a / Set 2b locally. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace). Each certificate is a distinct asset (filterable by `dct:certificateType`), so the contract + pull run once per certificate.

## Notes

- The number of pull round-trips equals the number of certificate assets exposed by the partner
  (here 2). Each asset is filterable by `dct:certificateType` (`iso9001`, `iatf16949`).

## Certo status — ✅ Implemented

Each certificate is pulled with its own `POST /consumer/certificates/pull` over its own flow (the client
runs the catalog browse via siglet and drives one pull per offer). ISO 9001 and IATF 16949 each land in the
known-certificate view.

## Certo code references

- `ConsumerManagementController#pull` → `ConsumerExchangeService#pullCertificate` → `Ccm240Retriever`
  (per-call, one per certificate); `protocol/ccm240/model/BusinessPartnerCertificate31` +
  `Ccm240Translation#upConvert`.
