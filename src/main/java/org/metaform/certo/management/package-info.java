/**
 * The <b>management</b> control surface — the operator/admin API, deliberately kept separate from the
 * role packages it drives ({@code provider}, {@code consumer}) and from the {@code common.pc} tenant model.
 *
 * <p>This is an intentional seam, not a stray grouping. The app exposes HTTP under two distinct
 * contracts, and each has its own home:
 * <ul>
 *   <li><b>Management API</b> (here) — local triggers and inspection views used to drive and observe the
 *       app: publish/modify/withdraw certificates, open and inspect exchanges, register participant
 *       contexts. None of it is part of any CX wire protocol; in a real deployment a certificate-management
 *       backend would drive these actions. Centralizing the three controllers
 *       ({@code ProviderManagementController}, {@code ConsumerManagementController},
 *       {@code ParticipantContextController}) keeps the operator surface in one place.</li>
 *   <li><b>Wire protocol</b> — the CX-0135 §3.3/§4.x endpoints a counterparty calls. Those controllers
 *       live <em>with their protocol</em> under {@code protocol.ccm240.*} / {@code protocol.ccm300.*}, so
 *       each protocol adapter contains only its own spec endpoints.</li>
 * </ul>
 *
 * <p>So the placement rule is by <em>contract</em>, not by role: management (operator-facing) is grouped
 * here; protocol (counterparty-facing) is grouped under {@code protocol}. Every management operation is
 * scoped to a tenant named in the path
 * ({@code /management/v1/participant-contexts/{participantContextId}/…}, the EDC Management API scheme).
 *
 * <p>The two contracts also have two trust domains: protocol endpoints are verified against the siglet
 * STS (see {@code common.security}), while this surface is an OAuth2 resource server
 * ({@code ManagementApiSecurityConfig}) — bearer JWTs from the issuer configured under
 * {@code spring.security.oauth2.resourceserver.jwt}, authorized per endpoint by
 * {@code certo-mgmt-api:<resource>:<action>} scopes ({@code ManagementScopeAuthorization}), with
 * {@code certo-mgmt-api:admin} superseding all of them.
 */
package org.metaform.certo.management;
