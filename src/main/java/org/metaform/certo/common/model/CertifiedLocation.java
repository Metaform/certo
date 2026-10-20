package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One certified location as stated on a certificate. Exactly one entry in {@code certifiedLocations} MUST
 * have {@code locationRole == MAIN_LOCATION}; its {@code legalEntityId} is the certificate holder. The
 * identifiers are dataspace-scoped; their format is defined by the deployment context. Only the
 * {@code legalEntityId} is required — {@code siteId} and {@code addressId} apply when the certification is
 * scoped to a site or an address.
 *
 * <p>An {@code @Embeddable}: it is persisted as child rows of an {@code @ElementCollection} on the
 * certificate/known-certificate (a normalized {@code certified_location} table) so location-based queries —
 * coverage and search — run in the database rather than in memory.
 *
 * @param legalEntityId     legal entity the location belongs to (also the holder for MAIN_LOCATION)
 * @param siteId            the certified site, when applicable
 * @param addressId         the certified address, when applicable
 * @param locationRole      the role of the location on the certificate
 * @param areaOfApplication verbatim location-specific scope, if printed on the certificate
 */
@Embeddable
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertifiedLocation(
        String legalEntityId,
        String siteId,
        String addressId,
        @Enumerated(EnumType.STRING) LocationRole locationRole,
        String areaOfApplication) {

    public CertifiedLocation(String legalEntityId, String siteId, String addressId, LocationRole locationRole) {
        this(legalEntityId, siteId, addressId, locationRole, null);
    }
}
