# TC-CCM-N02 — No Certificates Stored

**Role:** Consumer  ·  **Mechanism:** v2 **Asset-based Pull** (EDC)  ·  *(last test)*

## Steps

| # | Step | Role |
|---|------|------|
| 1 | Select the test partner in the application | Consumer |
| 2 | Execute the function to retrieve partner certificates | Consumer |
| 3 | Check the application's response | Consumer |

## v2 API calls invoked

Same mechanism as [TC-CCM-01](TC-CCM-01.md) — the **asset-based pull** — but the partner exposes **no**
certificate assets, so the flow stops at the catalog step.

| Step | v2 API call | Detail |
|------|-------------|--------|
| 2 | **EDC catalog** | Returns an **empty** catalog — no offers with `dct:subject = cx-taxo:CompanyCertificate`. |
| 2 | *(no contract / pull)* | Nothing to contract or pull; the flow stops at the catalog step. |
| 3 | — | Consumer displays a "no certificates" / empty-result response. |

> The pull mechanism is described in [README §2](README.md#2-asset-based-pull-edc-dataspace). This test exercises only the catalog step.

## Notes

- This is the empty-result path of the pull mechanism: a successful catalog request with zero matching
  offers, distinct from an error. No `/companycertificate/*` message is exchanged.

## Certo status — ✅ Implemented

The empty catalog is observed upstream (siglet/client): with no offers there is nothing to pull, so no
`POST /consumer/certificates/pull` is issued and the known-certificate view stays empty. Certo's role is the
empty-view response; the "zero offers" determination is the catalog browse, outside Certo.
