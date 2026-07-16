package org.metaform.certo.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * One certified location as stated on a certificate (CX-0135 &sect;4.2.4). Replaces the v2
 * {@code enclosedSites}/location-BPN list. Exactly one entry in {@code certifiedLocations} MUST have
 * {@code locationRole == MAIN_LOCATION}; its {@code bpnl} is the certificate holder.
 *
 * <p>An {@code @Embeddable}: it is persisted as child rows of an {@code @ElementCollection} on the
 * certificate/known-certificate (a normalized {@code certified_location} table) so location-based queries —
 * coverage and search — run in the database rather than in memory.
 *
 * @param bpnl              legal entity the location belongs to (also the holder for MAIN_LOCATION)
 * @param bpna             Golden Record address anchor (mandatory for every entry)
 * @param bpns             site the BPNA is assigned to, if any
 * @param locationRole     the role of the location on the certificate
 * @param areaOfApplication verbatim location-specific scope, if printed on the certificate
 */
@Embeddable
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CertifiedLocation(
        String bpnl,
        String bpna,
        String bpns,
        @Enumerated(EnumType.STRING) LocationRole locationRole,
        String areaOfApplication) {

    public CertifiedLocation(String bpnl, String bpna, String bpns, LocationRole locationRole) {
        this(bpnl, bpna, bpns, locationRole, null);
    }

    /** Whether this location is identified by the given BPN under any of its BPNL/BPNS/BPNA anchors. */
    public boolean matchesBpn(String bpn) {
        return bpn != null && (bpn.equals(bpnl) || bpn.equals(bpns) || bpn.equals(bpna));
    }
}
