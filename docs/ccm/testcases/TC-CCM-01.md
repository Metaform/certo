# TC-CCM-01 — Retrieve and Display a Partner's Certificate

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Execute the function to retrieve partner certificates | Consumer |
| 3 | Review the displayed certificate against Set 1 | Consumer |

## v2 API calls invoked

There is **no** `POST /companycertificate/*` message in this flow — a pure "retrieve & display" is the
v2 **asset-based pull** against the partner's EDC.

| Step | v2 API call | Detail |
|------|-------------|--------|
| 1 | — | Partner (BPNL) selection is local app state; no wire call. |
| 2 | **EDC catalog + contract** | Discover the offer with `dct:subject = cx-taxo:CompanyCertificate` (+ `dct:certificateType = iso9001`) and agree a contract for it. |
| 2 | **EDC data-plane pull** | Pull the `BusinessPartnerCertificate` (`urn:samm:io.catenax.business_partner_certificate:3.1.0`). |
| 3 | — | Rendering / comparison against **Set 1** (ISO 9001) is local. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace).

## Payload of interest

The pulled `BusinessPartnerCertificate` 3.1.0 record carries: `type`, `businessPartnerNumber`,
`enclosedSites`, `validFrom`/`validUntil`, `trustLevel`, `issuer`, `validator`, and an inline
`document` (`documentID`, `contentBase64`).

## Certo status — ✅ Implemented

Proactive pull with no prior notification: `POST /management/v1/participant-contexts/{pctx}/consumer/certificates/pull`
(`partnerBpn`, `partnerDid`, `flowId`, `protocolVersion: "2.4.0"`) → reads the certificate over the
client-supplied flow, up-converts, records it in the known-certificate view, returns it for display.

## Certo code references

- Management endpoint: `ConsumerManagementController#pull` → `ConsumerExchangeService#pullCertificate`.
- Data-plane read + up-convert: `protocol/ccm240/consumer/Ccm240Retriever` (v2 retriever, selected by
  `protocol/DispatchingCertificateRetriever`), using `protocol/ccm240/model/BusinessPartnerCertificate31`
  + `protocol/ccm240/Ccm240Translation#upConvert`.
- siglet resolves the `flowId` to the provider's data-plane endpoint + token (EDC catalog/negotiation/transfer
  are upstream of Certo).
