# TC-CCM-N04 — Certificate with Invalid Data

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)  ·  *(second-to-last test)*

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Retrieve the partner's certificates | Consumer |
| 3 | Check how the Consumer application responds to the invalid data | Consumer |

## v2 API calls invoked

Same mechanism as [TC-CCM-01](TC-CCM-01.md) — the **asset-based pull**. The negative aspect is that the
pulled payload is **malformed / schema-invalid**; the test checks the consumer's validation and error
handling, not a distinct API.

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog + contract + data-plane pull** | Pull a `BusinessPartnerCertificate` that violates the 3.1.0 schema / business rules. |
| 3 | — | Consumer rejects/flags the record; no acceptance feedback is required by this test. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace). The dataspace transfer succeeds (transport-valid); the invalid data surfaces only when the consumer validates the pulled payload.

## Notes

- Validation happens on ingest of the pulled record. In Certo's push-equivalent path, the same checks
  run in `Ccm240ConsumerController#push` / `Ccm240Envelope.validate(...)`, which reject a malformed
  message with **HTTP 400** (`ApiException.badRequest`).

## Certo code references

- `protocol/ccm240/Ccm240Envelope.java` (envelope + BPN/UUID validation), `Ccm240ConsumerController#push`.

## Certo status — ✅ Implemented

Pulled over the same path as [TC-CCM-01](TC-CCM-01.md). The EDC transfer succeeds; invalid data surfaces when
`Ccm240Retriever` deserializes the `BusinessPartnerCertificate` (malformed JSON → the retriever fails, mapped
to **HTTP 502** on the management pull) or when a downstream constraint rejects the up-converted record.
